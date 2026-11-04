@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.ui.components.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import dev.swipe.platform.VideoItemCache
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerLooper
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.setVolume
import platform.AVFoundation.volume
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIView

/**
 * The iOS `actual` of [VideoPlayer] (WP-4.1; FR-16, FR-17, R2) — see the expect KDoc
 * for the contract. Hosting follows the proven `spike-video-interop` pattern: a plain
 * `UIView` with an `AVPlayerLayer` sublayer whose frame tracks the bounds in
 * `layoutSubviews` (overriding `layerClass` is not expressible through Kotlin/Native
 * ObjC interop; the sublayer is what the spike verified end to end).
 *
 * The item comes from the shared [VideoItemCache] (the `VideoPreloader` binding), so a
 * card whose video entered the deck's prefetch window mounts warm (§6.5 step 4).
 * The cache is resolved from the running Koin graph because the spec-fixed signature
 * carries no loader parameter — part of what makes this composable the sanctioned
 * expect/actual exception (AGENTS.md rule 5).
 */
@Composable
actual fun VideoPlayer(
    id: String,
    muted: Boolean,
    playing: Boolean,
    onProgress: (Float) -> Unit,
    modifier: Modifier,
    livePhoto: Boolean,
) {
    val cache = remember { KoinPlatform.getKoin().get<VideoItemCache>() }
    val currentOnProgress by rememberUpdatedState(onProgress)
    // One view per media id: an id change is a different video, not an update.
    key(id, livePhoto) {
        val view = remember { VideoPlayerSurface(cache = cache, id = id, livePhoto = livePhoto) }
        SideEffect { view.onProgress = { fraction -> currentOnProgress(fraction) } }
        LaunchedEffect(muted) { view.applyMuted(muted) }
        LaunchedEffect(playing) { view.applyPlaying(playing) }
        DisposableEffect(view) {
            onDispose { view.release() }
        }
        UIKitView(factory = { view }, modifier = modifier)
    }
}

/**
 * The hosted view: loads the item off the main thread, then attaches an
 * [AVQueuePlayer] (+ `AVPlayerLooper` unless [livePhoto]) to an [AVPlayerLayer]
 * (FR-16: loops; FR-17: the paired video plays once, no loop).
 *
 * Muting uses `volume = 0` — `AVPlayer` has no mute flag — and playing maps to
 * `play()`/`pause()`: with `automaticallyWaitsToMinimizeStalling` (the default) a
 * card mounted with `playing = true` starts the moment the item is ready without
 * any polling (NFR-1; iOS 26 removed `playWhenReady`, `play()` carries its
 * semantics). A periodic time observer (every
 * ~100 ms) reports [videoProgressFraction] through [onProgress]; it is removed and
 * the player paused in [release], which also retires the Live Photo temp file.
 */
private class VideoPlayerSurface(
    private val cache: VideoItemCache,
    private val id: String,
    private val livePhoto: Boolean,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    /** Progress sink, installed by the composable right after this view is created. */
    var onProgress: (Float) -> Unit = {}

    private val playerLayer = AVPlayerLayer()
    private var player: AVQueuePlayer? = null

    /** Must stay strongly held or the loop stops (Apple docs on `AVPlayerLooper`). */
    private var looper: AVPlayerLooper? = null
    private var timeObserver: Any? = null
    private var released = false
    private var muted = false
    private var playing = false

    init {
        // The deck is full-bleed (FR-11); aspect-fit keeps whole video frames visible,
        // mirroring the card image's Fit scaling (DeckMotion). WP-4.2 can revisit.
        playerLayer.videoGravity = AVLayerVideoGravityResizeAspect
        layer.addSublayer(playerLayer)
        NSOperationQueue.mainQueue().addOperationWithBlock { load() }
    }

    fun applyMuted(muted: Boolean) {
        this.muted = muted
        player?.volume = volumeFor(muted)
    }

    fun applyPlaying(playing: Boolean) {
        this.playing = playing
        player?.let { p -> if (playing) p.play() else p.pause() }
    }

    /** Pause, drop the observer and layer, and delete a Live Photo temp file (FR-17). */
    fun release() {
        if (released) return
        released = true
        timeObserver?.let { observer -> player?.removeTimeObserver(observer) }
        timeObserver = null
        player?.pause()
        playerLayer.player = null
        player = null
        looper = null
        if (livePhoto) cache.releaseLivePhotoVideo(id)
    }

    private fun load() {
        if (released) return
        // The cache performs PhotoKit on its own Dispatchers.Default (AGENTS.md); the
        // completion hops back to main before any UIKit/AVPlayerLayer touch.
        cache.loadScope.launch {
            val item = produceItem()
            NSOperationQueue.mainQueue().addOperationWithBlock {
                if (!released) attach(item)
            }
        }
    }

    private suspend fun produceItem(): AVPlayerItem? =
        if (livePhoto) {
            cache.livePhotoVideo(id)?.let { AVPlayerItem(uRL = it) }
        } else {
            cache.item(id)
        }

    private fun attach(item: AVPlayerItem?) {
        if (released || item == null) return
        val queuePlayer = AVQueuePlayer(items = listOf(item))
        queuePlayer.volume = volumeFor(muted)
        // FR-16 looping; the looper must stay strongly held or the loop stops. It
        // adds its own queued copy of the item, hence AVQueuePlayer. Full range:
        // `playerLooperWithPlayer:templateItem:` is not in this SDK's interop.
        looper =
            if (livePhoto) {
                null
            } else {
                AVPlayerLooper(
                    player = queuePlayer,
                    templateItem = item,
                    // kCMTimeZero .. kCMTimePositiveInfinity (their Make forms; the
                    // constants arrive as raw struct values in this interop).
                    timeRange =
                        CMTimeRangeMake(
                            // start =
                            CMTimeMake(value = 0, timescale = 1),
                            // duration =
                            CMTimeMake(value = Long.MAX_VALUE, timescale = Int.MAX_VALUE),
                        ),
                )
            }
        // iOS 26 dropped `playWhenReady`; `play()` carries its semantics — with
        // automaticallyWaitsToMinimizeStalling (default) it starts at ReadyForPlaying,
        // so no status polling is needed here (NFR-1).
        if (playing) queuePlayer.play()
        playerLayer.player = queuePlayer
        player = queuePlayer
        timeObserver =
            queuePlayer.addPeriodicTimeObserverForInterval(
                // interval =
                CMTimeMake(value = 1, timescale = 10),
                // queue =
                null, // nil → main queue delivery
            ) { _ ->
                // The block's CMTime arrives as a raw struct value; reading
                // `currentTime()` (a CValue) is equivalent and simpler to type.
                val position = CMTimeGetSeconds(queuePlayer.currentTime())
                val duration = CMTimeGetSeconds(item.duration)
                onProgress(videoProgressFraction(position, duration))
            }
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        // The layer must track the view's bounds by hand (spike-verified hosting).
        playerLayer.frame = bounds
    }

    private fun volumeFor(muted: Boolean): Float = if (muted) MUTED_VOLUME else AUDIBLE_VOLUME
}

/** `AVPlayer` has no mute flag (FR-16): muting is volume zero. */
private const val MUTED_VOLUME = 0f

private const val AUDIBLE_VOLUME = 1f

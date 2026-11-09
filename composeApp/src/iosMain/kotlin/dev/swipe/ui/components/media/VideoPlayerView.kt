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
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setVolume
import platform.AVFoundation.volume
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSNotificationCenter
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
    seekTarget: SeekTarget?,
) {
    val cache = remember { KoinPlatform.getKoin().get<VideoItemCache>() }
    val currentOnProgress by rememberUpdatedState(onProgress)
    // One view per media id: an id change is a different video, not an update.
    key(id, livePhoto) {
        val view = remember { VideoPlayerSurface(cache = cache, id = id, livePhoto = livePhoto) }
        SideEffect { view.onProgress = { fraction -> currentOnProgress(fraction) } }
        LaunchedEffect(muted) { view.applyMuted(muted) }
        LaunchedEffect(playing) { view.applyPlaying(playing) }
        // FR-16 scrubber: each new token seeks once; null (never scrubbed yet)
        // does nothing. The token, not the fraction, keys the effect so a drag
        // back to the same fraction still re-seeks.
        LaunchedEffect(seekTarget) { seekTarget?.let { view.seekTo(it.fraction) } }
        DisposableEffect(view) {
            onDispose { view.release() }
        }
        UIKitView(factory = { view }, modifier = modifier)
    }
}

/**
 * The hosted view: loads the item off the main thread, then attaches an
 * [AVPlayer] to an [AVPlayerLayer]; looping is done with the item's
 * end-of-playback notification + seek to zero unless [livePhoto]
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
    private var player: AVPlayer? = null

    /** Strong reference for the end-of-item loop observer (FR-16). */
    private var endObserver: Any? = null
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
        player?.let { p ->
            if (playing) {
                // WP-4.1 carry-over: a pause racing the end-of-item notification
                // can leave the item parked at its end, where `play()` does
                // nothing — resume from zero instead of freezing.
                if (!livePhoto && isAtItemEnd(p)) {
                    p.seekToTime(CMTimeMake(value = 0, timescale = 1))
                }
                p.play()
            } else {
                p.pause()
            }
        }
    }

    /**
     * FR-16 scrubber seek: jump to [fraction] of the item (milliseconds;
     * [seekPositionSeconds] clamps the fraction and guards unknown durations).
     * Playback resumes only when [playing] — a paused card stays paused.
     */
    fun seekTo(fraction: Float) {
        val p = player ?: return
        val item = p.currentItem ?: return
        val seconds = seekPositionSeconds(fraction, CMTimeGetSeconds(item.duration))
        p.seekToTime(CMTimeMake(value = (seconds * 1000).toLong(), timescale = 1000))
        if (playing) p.play()
    }

    private fun isAtItemEnd(p: AVPlayer): Boolean {
        val item = p.currentItem ?: return false
        val duration = CMTimeGetSeconds(item.duration)
        val position = CMTimeGetSeconds(p.currentTime())
        return duration.isFinite() && duration > 0.0 && position >= duration - RESUME_AT_END_EPSILON_S
    }

    /** Pause, drop the observer and layer, and delete a Live Photo temp file (FR-17). */
    fun release() {
        if (released) return
        released = true
        timeObserver?.let { observer -> player?.removeTimeObserver(observer) }
        timeObserver = null
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
        player?.pause()
        // The item is cached and reused by the next mount of this card, and AVPlayer
        // throws if an item still belongs to another player. Dropping our reference
        // is not enough: the old player only lets go when it is deallocated (FR-16).
        player?.replaceCurrentItemWithPlayerItem(null)
        playerLayer.player = null
        player = null
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
        // Plain `AVPlayer`, not `AVQueuePlayer`: on iOS 26 the queue drains a played
        // item at item end, so the loop's seek-to-zero restart raced the removal and
        // died (verified: item vanishes, `items()` empty, playback stops).
        val avPlayer = AVPlayer(playerItem = item)
        avPlayer.volume = volumeFor(muted)
        // FR-16 looping. `AVPlayerLooper` is unusable through this SDK's Kotlin/Native
        // interop: neither the 3-arg nor the 4-arg (`existingItemsOrdering`)
        // initialiser ever inserted a copy — verified on the iOS 26.5 simulator with
        // both a huge finite and the real `kCMTimePositiveInfinity` duration (queue
        // stayed empty, status never ReadyToPlay). The classic end-of-item
        // notification + seek-to-zero loop replaces it; the restart hitch is a
        // frame, acceptable for a muted autoplay card.
        if (!livePhoto) {
            endObserver =
                NSNotificationCenter.defaultCenter.addObserverForName(
                    name = AVPlayerItemDidPlayToEndTimeNotification,
                    `object` = item,
                    queue = NSOperationQueue.mainQueue(),
                ) {
                    if (!released && playing) {
                        avPlayer.seekToTime(CMTimeMake(value = 0, timescale = 1))
                        avPlayer.play()
                    }
                }
        }
        // iOS 26 dropped `playWhenReady`; `play()` carries its semantics — with
        // automaticallyWaitsToMinimizeStalling (default) it starts at ReadyForPlaying,
        // so no status polling is needed here (NFR-1).
        if (playing) avPlayer.play()
        playerLayer.player = avPlayer
        player = avPlayer
        timeObserver =
            avPlayer.addPeriodicTimeObserverForInterval(
                // interval =
                CMTimeMake(value = 1, timescale = 10),
                // queue =
                null, // nil → main queue delivery
            ) { _ ->
                // The block's CMTime arrives as a raw struct value; reading the
                // player's `currentTime()` (a CValue) is equivalent and simpler to
                // type. The modulo against the item duration wraps the reported
                // position with every loop pass, keeping the fraction cycling 0..1
                // (FR-16) even if the queue clock ever runs ahead of the item.
                val position = CMTimeGetSeconds(avPlayer.currentTime())
                val duration = CMTimeGetSeconds(item.duration)
                val wrapped =
                    if (duration.isFinite() && duration > 0.0) {
                        position % duration
                    } else {
                        position
                    }
                onProgress(videoProgressFraction(wrapped, duration))
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

/** How close to the item's end (seconds) counts as "at end" for the resume guard. */
private const val RESUME_AT_END_EPSILON_S = 0.05

package dev.swipe.ui.screens.swipe

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.toSize
import dev.swipe.data.Haptics
import dev.swipe.data.LoadedImage
import dev.swipe.data.MediaLoader
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.MediaType
import dev.swipe.presentation.swipe.CardState
import dev.swipe.presentation.swipe.SwipeEvent
import dev.swipe.presentation.swipe.SwipeIntent
import dev.swipe.ui.components.SwipeIcon
import dev.swipe.ui.components.SwipeIcons
import dev.swipe.ui.components.media.DefaultVideoPlayerSlot
import dev.swipe.ui.components.media.SeekTarget
import dev.swipe.ui.components.media.VideoPlayerSlot
import dev.swipe.ui.components.media.rememberMediaImage
import dev.swipe.ui.components.swipe.CardAccessibilityAction
import dev.swipe.ui.components.swipe.SwipeCard
import dev.swipe.ui.components.swipe.SwipeCardState
import dev.swipe.ui.components.swipe.SwipeVerdict
import dev.swipe.ui.components.swipe.underCardAlpha
import dev.swipe.ui.components.swipe.underCardScale
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.flow.Flow

/**
 * The card animations driven by state, not gestures: one-shot returns (undo,
 * FR-15; favorite refusal, FR-22), the NFR-7 revert on a failed write, and
 * the at-rest reset for a new top card. The pending-return flag marks "this
 * state change is a return, not an advance" while the state emission racing
 * the channel event may re-key the reset effect.
 */
@Composable
internal fun CardMotionEffects(
    cardState: SwipeCardState,
    events: Flow<SwipeEvent>,
    revertToken: Long,
    currentId: String?,
    reduceMotion: Boolean,
) {
    var pendingReturn by remember { mutableStateOf(false) }
    // One-shot returns (undo FR-15; favorite refusal FR-22; hide undo FR-38).
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is SwipeEvent.ReturnCard -> {
                    val verdict = event.decision.toSwipeVerdict()
                    pendingReturn = true
                    cardState.returnFrom(verdict, reduceMotion)
                    pendingReturn = false
                }

                SwipeEvent.ReturnCardInPlace -> {
                    // FR-38: fade + scale in place (§5.6 "Hide undo return").
                    pendingReturn = true
                    cardState.returnInPlace(reduceMotion)
                    pendingReturn = false
                }
            }
        }
    }
    // NFR-7: a failed decision write bumps the token; the card goes back.
    // The committed verdict is always the one that just failed, because the
    // UI animates before sending the intent.
    LaunchedEffect(revertToken) {
        if (revertToken > 0L) {
            cardState.returnFrom(cardState.committedVerdict ?: SwipeVerdict.KEEP, reduceMotion)
        }
    }
    // A new top card sits the slot at rest — unless the return animation
    // above is what brought it, which owns the slot until it lands.
    LaunchedEffect(currentId) {
        if (!pendingReturn) {
            cardState.resetForNext()
        }
    }
}

/**
 * The §5.6 display toggles of the deck, grouped into one [CardStack]
 * parameter (WP-9.2): [reduceMotion] honors the OS motion setting,
 * [chromeVisible] the photo-tap chrome (FR-16), [fillMode] the double-tap
 * fill, and [autoplayVideos] the FR-16 autoplay setting.
 */
internal data class DeckDisplay(
    val reduceMotion: Boolean,
    val chromeVisible: Boolean,
    val fillMode: Boolean,
    val autoplayVideos: Boolean,
)

/**
 * The top card's video state (FR-16, FR-17) as a value snapshot of the
 * stage's per-card state (WP-9.2): the autoplay-off start, the mute flag,
 * the FR-17 live-press, and the pending scrub seek.
 */
internal data class VideoUiState(
    val started: Boolean,
    val muted: Boolean,
    val livePress: Boolean,
    val seekTarget: SeekTarget?,
)

/**
 * Every way the top card talks back, grouped into one [CardStack] parameter
 * (WP-9.2): the gesture [DeckCallbacks.onDismiss] and the button
 * [DeckCallbacks.onButtonCommit] verdict paths (§5.6), Undo (FR-15), the
 * chrome, mute, and fill toggles, the threshold-crossed haptic, the video
 * start and progress and Live Photo press callbacks (FR-16, FR-17), and the
 * U3 (WP-8.9) drag-start dismissal — inert by default so a caller that has
 * no FR-22 confirm to cancel stays untouched by it.
 */
internal class DeckCallbacks(
    val onDismiss: (SwipeVerdict) -> Unit,
    val onButtonCommit: (SwipeVerdict) -> Unit,
    val onUndo: () -> Unit,
    val onToggleChrome: () -> Unit,
    val onToggleMute: () -> Unit,
    val onToggleFill: () -> Unit,
    val onThresholdCrossed: () -> Unit,
    val onVideoStart: () -> Unit,
    val onVideoProgress: (Float) -> Unit,
    val onLivePressStart: () -> Unit,
    val onPressUp: () -> Unit,
    val onDragStart: () -> Unit = {},
    /** FR-38: the Hide custom action, the same intent the TopBar button sends. */
    val onHide: () -> Unit = {},
)

/**
 * The card stack (FR-11): [UnderDeckCard] under the top [SwipeCard], growing
 * 0.94 → 1.0 and 0.6 → 1.0 alpha with the drag progress (§5.6); the one §5.5
 * shadow belongs to the top card only. The §5.6 taps: a video tap toggles
 * mute (or, with autoplay off, starts playback first), a photo tap the chrome,
 * a double-tap the fill mode, and a Live Photo long-press plays through
 * [videoSlot] while the finger is down (FR-16, FR-17).
 *
 * R2 (see [dev.swipe.ui.components.media.VideoPlayer]): the hosted player
 * ignores card transforms, so it is mounted only while the top card is at
 * rest — poster image up for every animated moment.
 *
 * NFR-8: the card node carries [cardAccessibilityDescription] plus one
 * custom action per decision — Keep/Delete/Skip run the same
 * [DeckCallbacks.onButtonCommit] the ActionBar buttons take, and Undo (only
 * while [canUndo]) the same [DeckCallbacks.onUndo] — so VoiceOver users
 * decide without gestures and with identical behavior.
 */
@Composable
internal fun ColumnScope.CardStack(
    card: CardState,
    next: CardState?,
    cardState: SwipeCardState,
    mediaLoader: MediaLoader,
    latency: CommitLatencyTrace,
    display: DeckDisplay,
    video: VideoUiState,
    canUndo: Boolean,
    callbacks: DeckCallbacks,
    videoSlot: VideoPlayerSlot = DefaultVideoPlayerSlot,
) {
    var deckSize by remember { mutableStateOf(Size.Zero) }
    Box(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = SwipeTheme.componentSizes.cardInset, vertical = SwipeTheme.spacing.xs)
                .onSizeChanged { px -> deckSize = px.toSize() },
    ) {
        next?.let { under ->
            UnderDeckCard(
                card = under,
                loader = mediaLoader,
                offset = cardState.offset,
                deckSize = deckSize,
            )
        }
        SwipeCard(
            onDismiss = callbacks.onDismiss,
            state = cardState,
            reduceMotion = display.reduceMotion,
            accessibilityDescription = cardAccessibilityDescription(card),
            accessibilityActions = decisionAccessibilityActions(canUndo, callbacks),
            onTap = cardTapAction(card, display, video, callbacks),
            onDoubleTap = callbacks.onToggleFill,
            onLongPress = {
                // FR-17: hold a Live Photo to play its paired video.
                if (card.type == MediaType.LIVE_PHOTO) callbacks.onLivePressStart()
            },
            onPressUp = callbacks.onPressUp,
            onThresholdCrossed = callbacks.onThresholdCrossed,
            onDragStart = callbacks.onDragStart,
            modifier = Modifier.matchParentSize().testTag(SWIPE_CARD_TEST_TAG),
        ) {
            val loaded = cardMedia(card = card, loader = mediaLoader, fillMode = display.fillMode, latency = latency)
            TopCardPlayback(
                card = card,
                cardState = cardState,
                autoplayVideos = display.autoplayVideos,
                videoStarted = video.started,
                muted = video.muted,
                livePress = video.livePress,
                seekTarget = video.seekTarget,
                onVideoProgress = callbacks.onVideoProgress,
                videoSlot = videoSlot,
            )
            if (display.chromeVisible) {
                CardMetadata(
                    card = card,
                    // R6: either signal lights the badge — the deck-time flag
                    // or the pixels having come from iCloud.
                    isCloudOnly = card.isCloudOnly || loaded?.isCloud == true,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
    }
}

/**
 * The NFR-8 custom actions for the top card: Keep/Delete/Skip run the same
 * [DeckCallbacks.onButtonCommit] the ActionBar buttons take, Hide (FR-38) the
 * same [DeckCallbacks.onHide] the TopBar button takes, and Undo (only while
 * [canUndo], mirroring the disabled bar button — FR-15) the same
 * [DeckCallbacks.onUndo], so a VoiceOver decision is a button decision.
 */
private fun decisionAccessibilityActions(
    canUndo: Boolean,
    callbacks: DeckCallbacks,
): List<CardAccessibilityAction> =
    buildList {
        add(CardAccessibilityAction("Keep") { callbacks.onButtonCommit(SwipeVerdict.KEEP) })
        add(CardAccessibilityAction("Delete") { callbacks.onButtonCommit(SwipeVerdict.DELETE) })
        add(CardAccessibilityAction("Skip") { callbacks.onButtonCommit(SwipeVerdict.SKIP) })
        add(CardAccessibilityAction("Hide", callbacks.onHide))
        if (canUndo) add(CardAccessibilityAction("Undo", callbacks.onUndo))
    }

/**
 * The §5.6 tap behavior on the top card: a video tap toggles mute; a photo
 * tap the chrome. With autoplay off the first tap starts playback instead
 * (the poster + affordance state), later taps toggle mute.
 */
private fun cardTapAction(
    card: CardState,
    display: DeckDisplay,
    video: VideoUiState,
    callbacks: DeckCallbacks,
): () -> Unit =
    {
        when {
            card.type != MediaType.VIDEO -> callbacks.onToggleChrome()
            !display.autoplayVideos && !video.started -> callbacks.onVideoStart()
            else -> callbacks.onToggleMute()
        }
    }

/**
 * The top card's live playback layers (FR-16, FR-17) — everything between
 * the poster and the metadata scrim. R2 governs the mounts: the hosted
 * player ignores `graphicsLayer` transforms, so it is composed only while
 * the top card is at rest, with the poster up for every animated moment.
 * With autoplay off the card shows a play affordance until the first tap.
 */
@Composable
private fun BoxScope.TopCardPlayback(
    card: CardState,
    cardState: SwipeCardState,
    autoplayVideos: Boolean,
    videoStarted: Boolean,
    muted: Boolean,
    livePress: Boolean,
    seekTarget: SeekTarget?,
    onVideoProgress: (Float) -> Unit,
    videoSlot: VideoPlayerSlot,
) {
    if (card.type != MediaType.VIDEO && !(livePress && card.type == MediaType.LIVE_PHOTO)) {
        return
    }
    if (card.type == MediaType.VIDEO) {
        if (!autoplayVideos && !videoStarted) {
            // R2 wiring: poster + play affordance until the first tap.
            SwipeIcon(
                SwipeIcons.PLAY_ARROW,
                contentDescription = null,
                size = SwipeTheme.componentSizes.videoPlayAffordance,
                modifier = Modifier.align(Alignment.Center).testTag(VIDEO_PLAY_AFFORDANCE_TEST_TAG),
            )
        } else if (cardState.isIdle) {
            // Positional: the slot is a function type (named args are
            // prohibited on function-type invocations).
            videoSlot(card.id, muted, true, onVideoProgress, Modifier.matchParentSize(), false, seekTarget)
        }
    } else if (cardState.isIdle) {
        // FR-17: over the still, muted, once; release unmounts (stops).
        // Positional for the same function-type reason as the video branch above.
        videoSlot(card.id, true, true, {}, Modifier.matchParentSize(), true, null)
    }
}

/**
 * The top card's media: aspect-fit by default, fill after a double-tap (§5.6),
 * always mounted as the poster under any live playback (R2). Returns the
 * loaded image so the caller can read its R6 cloud signal.
 */
@Composable
private fun BoxScope.cardMedia(
    card: CardState,
    loader: MediaLoader,
    fillMode: Boolean,
    latency: CommitLatencyTrace,
): LoadedImage? {
    val loaded =
        rememberMediaImage(
            id = card.id,
            targetPx = MediaLoader.FULL_TARGET_PX,
            loader = loader,
        )
    loaded?.let { image ->
        Image(
            bitmap = image.bitmap,
            contentDescription = null,
            contentScale = if (fillMode) ContentScale.FillBounds else ContentScale.Fit,
            modifier = Modifier.matchParentSize(),
        )
        // NFR-1 span ends ride the emissions, after the frame they draw in
        // (CommitLatencyTrace; the committed card's own emissions are ignored).
        LaunchedEffect(image) { latency.onImage(card.id, image.isDegraded) }
    }
    return loaded
}

/** The card underneath (FR-11): the next item's media on the shared surface. */
@Composable
private fun BoxScope.UnderDeckCard(
    card: CardState,
    loader: MediaLoader,
    offset: Offset,
    deckSize: Size,
) {
    val loaded =
        rememberMediaImage(
            id = card.id,
            targetPx = MediaLoader.FULL_TARGET_PX,
            loader = loader,
        )
    Box(
        modifier =
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val scale = underCardScale(offset, deckSize)
                    scaleX = scale
                    scaleY = scale
                    alpha = underCardAlpha(offset, deckSize)
                }.clip(RoundedCornerShape(SwipeTheme.radii.card))
                .background(SwipeTheme.colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        loaded?.let { image ->
            Image(
                bitmap = image.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** The §5.6 haptic for a verdict: light for Keep/Skip, medium for Delete. */
internal fun SwipeVerdict.fireHaptic(haptics: Haptics) {
    when (this) {
        SwipeVerdict.KEEP, SwipeVerdict.SKIP -> haptics.impactLight()
        SwipeVerdict.DELETE -> haptics.impactMedium()
    }
}

/**
 * The intent a committed verdict sends for the card [id] (persist-before-advance,
 * NFR-7; the id binds it — FR-13).
 */
internal fun SwipeVerdict.toIntent(id: String): SwipeIntent = SwipeIntent.Decide(id, toDecision())

/** The domain [Decision] a committed verdict records (the layering map, §6.1). */
internal fun SwipeVerdict.toDecision(): Decision =
    when (this) {
        SwipeVerdict.KEEP -> Decision.KEEP
        SwipeVerdict.DELETE -> Decision.DELETE
        SwipeVerdict.SKIP -> Decision.SKIP
    }

/** The UI verdict matching a domain [Decision] (the layering map, §6.1). */
private fun Decision.toSwipeVerdict(): SwipeVerdict =
    when (this) {
        Decision.KEEP -> SwipeVerdict.KEEP

        Decision.DELETE -> SwipeVerdict.DELETE

        Decision.SKIP -> SwipeVerdict.SKIP

        // A confirmed library delete (FR-26) can never sit on the deck a
        // return animation replays — the card is already gone — but the
        // mapping has to be exhaustive, and DELETE is its verdict's shape.
        Decision.DELETED -> SwipeVerdict.DELETE
    }

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
import dev.swipe.data.MediaLoader
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.MediaType
import dev.swipe.presentation.swipe.CardState
import dev.swipe.presentation.swipe.SwipeEvent
import dev.swipe.presentation.swipe.SwipeIntent
import dev.swipe.ui.components.media.rememberMediaImage
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
    var pendingReturn by remember { mutableStateOf<SwipeVerdict?>(null) }
    // One-shot returns (undo FR-15; favorite refusal FR-22).
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is SwipeEvent.ReturnCard -> {
                    val verdict = event.decision.toSwipeVerdict()
                    pendingReturn = verdict
                    cardState.returnFrom(verdict, reduceMotion)
                    pendingReturn = null
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
        if (pendingReturn == null) {
            cardState.resetForNext()
        }
    }
}

/**
 * The card stack (FR-11): [UnderDeckCard] under the top [SwipeCard], growing
 * 0.94 → 1.0 and 0.6 → 1.0 alpha with the drag progress (§5.6); the one §5.5
 * shadow belongs to the top card only. The §5.6 taps: a video tap toggles
 * mute, a photo tap the chrome, a double-tap the fill mode.
 */
@Composable
internal fun ColumnScope.CardStack(
    card: CardState,
    next: CardState?,
    cardState: SwipeCardState,
    mediaLoader: MediaLoader,
    reduceMotion: Boolean,
    chromeVisible: Boolean,
    fillMode: Boolean,
    onDismiss: (SwipeVerdict) -> Unit,
    onToggleChrome: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleFill: () -> Unit,
    onThresholdCrossed: () -> Unit,
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
            onDismiss = onDismiss,
            state = cardState,
            reduceMotion = reduceMotion,
            onTap = {
                // §5.6: a video tap toggles mute; a photo tap the chrome.
                if (card.type == MediaType.VIDEO) onToggleMute() else onToggleChrome()
            },
            onDoubleTap = onToggleFill,
            onLongPress = { /* Live Photo playback: WP-4.2 */ },
            onThresholdCrossed = onThresholdCrossed,
            modifier = Modifier.matchParentSize().testTag(SWIPE_CARD_TEST_TAG),
        ) {
            CardMedia(card = card, loader = mediaLoader, fillMode = fillMode)
            if (chromeVisible) {
                CardMetadata(
                    card = card,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
    }
}

/** The top card's media: aspect-fit by default, fill after a double-tap (§5.6). */
@Composable
private fun BoxScope.CardMedia(
    card: CardState,
    loader: MediaLoader,
    fillMode: Boolean,
) {
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
    }
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

/** The intent a committed verdict sends (persist-before-advance, NFR-7). */
internal fun SwipeVerdict.toIntent(): SwipeIntent =
    when (this) {
        SwipeVerdict.KEEP -> SwipeIntent.Keep
        SwipeVerdict.DELETE -> SwipeIntent.Delete
        SwipeVerdict.SKIP -> SwipeIntent.Skip
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

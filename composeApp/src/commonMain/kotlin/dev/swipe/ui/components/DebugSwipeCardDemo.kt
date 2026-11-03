package dev.swipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import dev.swipe.resources.Res
import dev.swipe.resources.ic_arrow_upward
import dev.swipe.resources.ic_check
import dev.swipe.resources.ic_close
import dev.swipe.resources.ic_undo
import dev.swipe.ui.components.swipe.SwipeCard
import dev.swipe.ui.components.swipe.SwipeCardState
import dev.swipe.ui.components.swipe.SwipeVerdict
import dev.swipe.ui.components.swipe.rememberSwipeCardState
import dev.swipe.ui.components.swipe.underCardAlpha
import dev.swipe.ui.components.swipe.underCardScale
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.launch

// Demo sizing only; not a REQUIREMENTS §5 value.
private const val DEMO_CARD_ASPECT = 0.72f
private const val DEMO_DECK_SIZE = 4

/**
 * WP-2.1 demo section of the debug gallery: a small deck of placeholder
 * cards driven by the real [SwipeCard] gesture — drag right/left/up, fling,
 * snap back, the underneath card tracking the drag with its VerdictStamp
 * overlay, the action buttons committing through `flyOff` (30 dp nudge +
 * fly-off, §5.6), and undo returning the last card from its edge. The deck
 * wraps so the demo never runs out of cards.
 */
@Composable
internal fun GallerySwipeCardSection() {
    val scope = rememberCoroutineScope()
    val cardState = rememberSwipeCardState()
    var topIndex by remember { mutableIntStateOf(0) }
    var lastVerdict by remember { mutableStateOf<SwipeVerdict?>(null) }
    var history by remember { mutableStateOf(listOf<SwipeVerdict>()) }
    var fillMode by remember { mutableStateOf(false) }

    // One dismiss path for gestures and buttons alike: record, advance the
    // deck (wrapping so the demo card reappears), reset the slot at rest.
    val dismiss: (SwipeVerdict) -> Unit = { verdict ->
        lastVerdict = verdict
        history += verdict
        topIndex = (topIndex + 1) % DEMO_DECK_SIZE
        scope.launch { cardState.resetForNext() }
    }
    // §5.6 button-triggered action: nudge + fly-off, then the same commit.
    val commitFromButton: (SwipeVerdict) -> Unit = { verdict ->
        scope.launch {
            if (cardState.dragging || cardState.committedVerdict != null) return@launch
            cardState.flyOff(verdict)
            dismiss(verdict)
        }
    }
    // Undo: fly the last card back in from the edge it left (§5.6).
    val undo: () -> Unit = {
        val verdict = history.lastOrNull()
        if (verdict != null) {
            history = history.dropLast(1)
            topIndex = (topIndex - 1 + DEMO_DECK_SIZE) % DEMO_DECK_SIZE
            scope.launch { cardState.returnFrom(verdict) }
        }
    }

    Column {
        GalleryHeading("8 · SwipeCard")
        GallerySwipeDeck(
            cardState = cardState,
            topIndex = topIndex,
            fillMode = fillMode,
            onDoubleTap = { fillMode = !fillMode },
            onDismiss = dismiss,
        )
        GallerySwipeActionRow(onCommit = commitFromButton, onUndo = undo)
        BasicText(
            text = "last verdict: ${lastVerdict?.name ?: "—"}",
            style = SwipeTheme.typography.caption.copy(color = SwipeTheme.colors.textSecondary),
        )
    }
}

/** Delete / Undo / Keep / Skip row; the commit buttons take the §5.6 button path. */
@Composable
private fun GallerySwipeActionRow(
    onCommit: (SwipeVerdict) -> Unit,
    onUndo: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.l)) {
        CircleIconButton(
            Res.drawable.ic_close,
            label = "Delete",
            onClick = { onCommit(SwipeVerdict.DELETE) },
            contentColor = SwipeTheme.colors.delete,
        )
        CircleIconButton(
            Res.drawable.ic_undo,
            label = "Undo",
            onClick = onUndo,
        )
        CircleIconButton(
            Res.drawable.ic_check,
            label = "Keep",
            onClick = { onCommit(SwipeVerdict.KEEP) },
            contentColor = SwipeTheme.colors.keep,
        )
        CircleIconButton(
            Res.drawable.ic_arrow_upward,
            label = "Skip",
            onClick = { onCommit(SwipeVerdict.SKIP) },
            contentColor = SwipeTheme.colors.skip,
        )
    }
}

/** The deck area: the card underneath tracks the top card's drag progress (§5.6). */
@Composable
private fun GallerySwipeDeck(
    cardState: SwipeCardState,
    topIndex: Int,
    fillMode: Boolean,
    onDoubleTap: () -> Unit,
    onDismiss: (SwipeVerdict) -> Unit,
) {
    var deckSize by remember { mutableStateOf(Size.Zero) }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(DEMO_CARD_ASPECT)
                .onSizeChanged { px: IntSize -> deckSize = Size(px.width.toFloat(), px.height.toFloat()) },
    ) {
        // The next item sits underneath, growing 0.94 → 1.0 with the
        // top card's drag progress (§5.6).
        if (topIndex + 1 < DEMO_DECK_SIZE) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val scale = underCardScale(cardState.offset, deckSize)
                            scaleX = scale
                            scaleY = scale
                            alpha = underCardAlpha(cardState.offset, deckSize)
                        }.background(
                            SwipeTheme.colors.surfaceRaised,
                            RoundedCornerShape(SwipeTheme.radii.card),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "next item",
                    style = SwipeTheme.typography.caption.copy(color = SwipeTheme.colors.textTertiary),
                )
            }
        }
        SwipeCard(
            onDismiss = onDismiss,
            state = cardState,
            onDoubleTap = onDoubleTap,
            modifier = Modifier.matchParentSize(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.matchParentSize(),
            ) {
                BasicText(
                    text = "Item ${topIndex + 1} of $DEMO_DECK_SIZE",
                    style = SwipeTheme.typography.title.copy(color = SwipeTheme.colors.textPrimary),
                )
                BasicText(
                    text = if (fillMode) "double-tap: fill" else "double-tap: fit",
                    style = SwipeTheme.typography.caption.copy(color = SwipeTheme.colors.textSecondary),
                )
            }
        }
    }
}

package dev.swipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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
import dev.swipe.resources.ic_cloud
import dev.swipe.resources.ic_delete
import dev.swipe.resources.ic_favorite
import dev.swipe.resources.ic_undo
import dev.swipe.ui.components.swipe.SwipeCard
import dev.swipe.ui.components.swipe.SwipeCardState
import dev.swipe.ui.components.swipe.SwipeVerdict
import dev.swipe.ui.components.swipe.rememberSwipeCardState
import dev.swipe.ui.components.swipe.underCardAlpha
import dev.swipe.ui.components.swipe.underCardScale
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.launch

private enum class GalleryFilter { All, Photos, Videos }

// Debug-gallery sizing only; not a REQUIREMENTS §5 value.
private const val DEMO_CARD_ASPECT = 0.72f
private const val DEMO_DECK_SIZE = 4

/**
 * Debug-only catalogue of the WP-1.2 base components, for the human visual
 * checkpoint. Contains no placement logic — each section stacks vertically.
 * Compiled and shown only where [dev.swipe.App] enables the debug gallery;
 * release navigation never reaches it (WP-1.3 owns the debug entry point).
 */
@Composable
fun ComponentGallery(modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.l),
        modifier =
            modifier
                .fillMaxSize()
                .background(SwipeTheme.colors.bg)
                .verticalScroll(rememberScrollState())
                .padding(SwipeTheme.spacing.m),
    ) {
        BasicText(
            text = "WP-1.2 gallery",
            style = SwipeTheme.typography.title.copy(color = SwipeTheme.colors.textPrimary),
        )
        GalleryIconSection()
        GalleryButtonSection()
        GalleryBannerSection()
        GalleryToastSection()
        GalleryBadgeSection()
        GalleryIconButtonSection()
        GallerySegmentedSection()
        GallerySwipeCardSection()
    }
}

@Composable
private fun GalleryIconSection() {
    Column {
        GalleryHeading("1 · SwipeIcon")
        Row(horizontalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.l)) {
            SwipeIcon(Res.drawable.ic_check, tint = SwipeTheme.colors.keep, contentDescription = "Keep")
            SwipeIcon(Res.drawable.ic_close, tint = SwipeTheme.colors.delete, contentDescription = "Delete")
            SwipeIcon(Res.drawable.ic_arrow_upward, tint = SwipeTheme.colors.skip, contentDescription = "Skip")
            SwipeIcon(Res.drawable.ic_undo, tint = SwipeTheme.colors.accent, contentDescription = "Undo")
            SwipeIcon(Res.drawable.ic_cloud, tint = SwipeTheme.colors.warning, contentDescription = "Wait")
        }
    }
}

@Composable
private fun GalleryButtonSection() {
    Column {
        GalleryHeading("2 · PrimaryButton")
        PrimaryButton("Review 128", onClick = {}, modifier = Modifier.fillMaxWidth())
        PrimaryButton("Delete all", onClick = {}, destructive = true, modifier = Modifier.fillMaxWidth())
        PrimaryButton("Disabled", onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun GalleryBannerSection() {
    Column {
        GalleryHeading("3 · Banner")
        Column(verticalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.xs)) {
            Banner(
                text = "18 items are waiting to delete.",
                tone = BannerTone.Info,
                actionLabel = "Review",
                onAction = {},
            )
            Banner(
                text = "You've given access to 42 items.",
                tone = BannerTone.Warning,
                actionLabel = "Manage",
                onAction = {},
            )
        }
    }
}

@Composable
private fun GalleryToastSection() {
    Column {
        GalleryHeading("4 · Toast")
        Toast(text = "Undone — card returned", icon = Res.drawable.ic_undo, autoDismissDelay = NEVER_AUTO_DISMISS)
        Toast(
            text = "This is a favorite — delete anyway?",
            icon = Res.drawable.ic_favorite,
            iconTint = SwipeTheme.colors.warning,
            actionLabel = "Delete",
            onAction = {},
            autoDismissDelay = NEVER_AUTO_DISMISS,
        )
    }
}

@Composable
private fun GalleryBadgeSection() {
    Column {
        GalleryHeading("5 · QueueBadge")
        Row(horizontalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.l)) {
            QueueBadge(count = 1)
            QueueBadge(count = 42)
            QueueBadge(count = 100)
            QueueBadge(count = 1000)
        }
    }
}

@Composable
private fun GalleryIconButtonSection() {
    val tokens = SwipeTheme.componentSizes
    Column {
        GalleryHeading("6 · CircleIconButton")
        Row(horizontalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.l)) {
            CircleIconButton(Res.drawable.ic_close, label = "Close", onClick = {})
            CircleIconButton(
                Res.drawable.ic_delete,
                label = "Delete",
                onClick = {},
                container = SwipeTheme.colors.delete.copy(alpha = tokens.tintedFillAlpha),
                contentColor = SwipeTheme.colors.delete,
            )
            CircleIconButton(
                Res.drawable.ic_check,
                label = "Keep",
                onClick = {},
                container = SwipeTheme.colors.keep.copy(alpha = tokens.tintedFillAlpha),
                contentColor = SwipeTheme.colors.keep,
            )
            CircleIconButton(
                Res.drawable.ic_arrow_upward,
                label = "Skip",
                onClick = {},
                contentColor = SwipeTheme.colors.skip,
            )
        }
    }
}

@Composable
private fun GallerySegmentedSection() {
    var filter by remember { mutableStateOf<GalleryFilter>(GalleryFilter.All) }
    Column {
        GalleryHeading("7 · SegmentedControl")
        SegmentedControl(
            options = GalleryFilter.entries,
            selected = filter,
            labelOf = { it.name },
            onSelect = { tapped -> filter = tapped },
        )
        BasicText(
            text = "Segmented selection: ${filter.name}",
            style = SwipeTheme.typography.caption.copy(color = SwipeTheme.colors.textSecondary),
        )
    }
}

@Composable
private fun GalleryHeading(text: String) {
    BasicText(
        text = text,
        style = SwipeTheme.typography.headline.copy(color = SwipeTheme.colors.textSecondary),
    )
}

/**
 * WP-2.1 demo: a small deck of placeholder cards driven by the real
 * [SwipeCard] gesture — drag right/left/up, fling, snap back, the
 * underneath card tracking the drag with its VerdictStamp overlay, the
 * action buttons committing through `flyOff` (30 dp nudge + fly-off,
 * §5.6), and undo returning the last card from its edge. The deck wraps
 * so the demo never runs out of cards.
 */
@Composable
private fun GallerySwipeCardSection() {
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

    Column {
        GalleryHeading("8 · SwipeCard")
        GallerySwipeDeck(
            cardState = cardState,
            topIndex = topIndex,
            fillMode = fillMode,
            onDoubleTap = { fillMode = !fillMode },
            onDismiss = dismiss,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.l)) {
            CircleIconButton(
                Res.drawable.ic_close,
                label = "Delete",
                onClick = { commitFromButton(SwipeVerdict.DELETE) },
                contentColor = SwipeTheme.colors.delete,
            )
            CircleIconButton(
                Res.drawable.ic_undo,
                label = "Undo",
                onClick = {
                    val verdict = history.lastOrNull()
                    if (verdict != null) {
                        history = history.dropLast(1)
                        topIndex = (topIndex - 1 + DEMO_DECK_SIZE) % DEMO_DECK_SIZE
                        scope.launch { cardState.returnFrom(verdict) }
                    }
                },
            )
            CircleIconButton(
                Res.drawable.ic_check,
                label = "Keep",
                onClick = { commitFromButton(SwipeVerdict.KEEP) },
                contentColor = SwipeTheme.colors.keep,
            )
            CircleIconButton(
                Res.drawable.ic_arrow_upward,
                label = "Skip",
                onClick = { commitFromButton(SwipeVerdict.SKIP) },
                contentColor = SwipeTheme.colors.skip,
            )
        }
        BasicText(
            text = "last verdict: ${lastVerdict?.name ?: "—"}",
            style = SwipeTheme.typography.caption.copy(color = SwipeTheme.colors.textSecondary),
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

package dev.swipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.swipe.ui.theme.SwipeTheme

private enum class GalleryFilter { All, Photos, Videos }

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
                // Safe area (WP-6.4): the first heading clears the status bar.
                .statusBarsPadding()
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
            SwipeIcon(SwipeIcons.CHECK, tint = SwipeTheme.colors.keep, contentDescription = "Keep")
            SwipeIcon(SwipeIcons.CLOSE, tint = SwipeTheme.colors.delete, contentDescription = "Delete")
            SwipeIcon(SwipeIcons.ARROW_UPWARD, tint = SwipeTheme.colors.skip, contentDescription = "Skip")
            SwipeIcon(SwipeIcons.UNDO, tint = SwipeTheme.colors.accent, contentDescription = "Undo")
            SwipeIcon(SwipeIcons.CLOUD, tint = SwipeTheme.colors.warning, contentDescription = "Wait")
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
        Toast(text = "Undone — card returned", icon = SwipeIcons.UNDO, autoDismissDelay = NEVER_AUTO_DISMISS)
        Toast(
            text = "This is a favorite — delete anyway?",
            icon = SwipeIcons.FAVORITE,
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
            CircleIconButton(SwipeIcons.CLOSE, label = "Close", onClick = {})
            CircleIconButton(
                SwipeIcons.DELETE,
                label = "Delete",
                onClick = {},
                container = SwipeTheme.colors.delete.copy(alpha = tokens.tintedFillAlpha),
                contentColor = SwipeTheme.colors.delete,
            )
            CircleIconButton(
                SwipeIcons.CHECK,
                label = "Keep",
                onClick = {},
                container = SwipeTheme.colors.keep.copy(alpha = tokens.tintedFillAlpha),
                contentColor = SwipeTheme.colors.keep,
            )
            CircleIconButton(
                SwipeIcons.ARROW_UPWARD,
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
internal fun GalleryHeading(text: String) {
    BasicText(
        text = text,
        style = SwipeTheme.typography.headline.copy(color = SwipeTheme.colors.textSecondary),
    )
}

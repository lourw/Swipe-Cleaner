package dev.swipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.swipe.resources.Res
import dev.swipe.resources.ic_arrow_upward
import dev.swipe.resources.ic_check
import dev.swipe.resources.ic_close
import dev.swipe.resources.ic_cloud
import dev.swipe.resources.ic_delete
import dev.swipe.resources.ic_favorite
import dev.swipe.resources.ic_undo
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
    }
}

@Composable
private fun GalleryIconSection() {
    Column {
        GalleryHeading("1 · SwipeIcon + SystemDefaultIcon")
        Row(horizontalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.l)) {
            SwipeIcon(Res.drawable.ic_check, tint = SwipeTheme.colors.keep, contentDescription = "Keep")
            SwipeIcon(Res.drawable.ic_close, tint = SwipeTheme.colors.delete, contentDescription = "Delete")
            SwipeIcon(Res.drawable.ic_arrow_upward, tint = SwipeTheme.colors.skip, contentDescription = "Skip")
            SwipeIcon(Res.drawable.ic_undo, tint = SwipeTheme.colors.accent, contentDescription = "Undo")
            SwipeIcon(Res.drawable.ic_cloud, tint = SwipeTheme.colors.warning, contentDescription = "Wait")
            SystemDefaultIcon(glyph = "✓", contentDescription = null)
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

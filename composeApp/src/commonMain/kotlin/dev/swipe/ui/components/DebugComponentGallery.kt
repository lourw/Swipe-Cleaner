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
import dev.swipe.resources.R
import dev.swipe.ui.theme.SwipeTheme

private enum class GalleryFilter { All, Photos, Videos }

/**
 * Debug-only catalogue of the WP-1.2 base components, for the human visual
 * checkpoint. Compiled and shown only where [dev.swipe.App] is given a
 * [gallery] slot; production navigation never reaches it (WP-1.3 owns the
 * debug entry point).
 */
@Composable
fun ComponentGallery() {
    val tokens = SwipeTheme.componentSizes
    var filter by remember { mutableStateOf<GalleryFilter>(GalleryFilter.All) }
    Column(
        verticalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.l),
        modifier =
            Modifier
                .fillMaxSize()
                .background(SwipeTheme.colors.bg)
                .verticalScroll(rememberScrollState())
                .padding(SwipeTheme.spacing.m),
    ) {
        BasicText(
            text = "WP-1.2 gallery",
            style = SwipeTheme.typography.title.copy(color = SwipeTheme.colors.textPrimary),
        )
        GalleryHeading("1 · SwipeIcon + SystemDefaultIcon")
        Row(horizontalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.l)) {
            SwipeIcon(R.drawable.ic_check, tint = SwipeTheme.colors.keep, contentDescription = "Keep")
            SwipeIcon(R.drawable.ic_close, tint = SwipeTheme.colors.delete, contentDescription = "Delete")
            SwipeIcon(R.drawable.ic_arrow_upward, tint = SwipeTheme.colors.skip, contentDescription = "Skip")
            SwipeIcon(R.drawable.ic_undo, tint = SwipeTheme.colors.accent, contentDescription = "Undo")
            SwipeIcon(R.drawable.ic_cloud, tint = SwipeTheme.colors.warning, contentDescription = "Wait")
            SystemDefaultIcon(glyph = "✓", contentDescription = null)
        }
        GalleryHeading("2 · PrimaryButton")
        PrimaryButton("Review 128", onClick = {}, modifier = Modifier.fillMaxWidth())
        PrimaryButton("Delete all", onClick = {}, container = SwipeTheme.colors.delete, modifier = Modifier.fillMaxWidth())
        PrimaryButton("Disabled", onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
        GalleryHeading("3 · Banner")
        Column(verticalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.xs)) {
            Banner(
                text = "Some photos are still downloading from iCloud.",
                tone = BannerTone.Info,
            )
            Banner(
                text = "Some photos are still downloading from iCloud.",
                tone = BannerTone.Warning,
                actionLabel = "Retry",
                onAction = {},
            )
            Banner(text = "iCloud library unreachable.", tone = BannerTone.Error)
        }
        GalleryHeading("4 · Toast")
        Toast(text = "Undone — card returned", icon = R.drawable.ic_undo)
        GalleryHeading("5 · QueueBadge")
        Row(horizontalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.l)) {
            QueueBadge(count = 1)
            QueueBadge(count = 42)
            QueueBadge(count = 100)
            QueueBadge(count = 1000)
        }
        GalleryHeading("6 · CircleIconButton")
        Row(horizontalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.l)) {
            CircleIconButton(R.drawable.ic_close, label = "Close", onClick = {})
            CircleIconButton(
                R.drawable.ic_delete,
                label = "Delete",
                onClick = {},
                container = SwipeTheme.colors.delete.copy(alpha = tokens.tintedFillAlpha),
                contentColor = SwipeTheme.colors.delete,
            )
            CircleIconButton(
                R.drawable.ic_check,
                label = "Keep",
                onClick = {},
                container = SwipeTheme.colors.keep.copy(alpha = tokens.tintedFillAlpha),
                contentColor = SwipeTheme.colors.keep,
            )
            CircleIconButton(R.drawable.ic_arrow_upward, label = "Skip", onClick = {}, contentColor = SwipeTheme.colors.skip)
        }
        GalleryHeading("7 · SegmentedControl")
        SegmentedControl(
            options = GalleryFilter.entries,
            selected = filter,
            labelOf = { it.name },
            onSelect = { selected -> filter = selected },
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

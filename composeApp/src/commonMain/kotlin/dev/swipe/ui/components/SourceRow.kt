package dev.swipe.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import dev.swipe.data.MediaLoader
import dev.swipe.ui.components.media.rememberMediaImage
import dev.swipe.ui.screens.swipe.groupDigits
import dev.swipe.ui.theme.SwipeTheme
import dev.swipe.ui.theme.TABULAR_FIGURES

/**
 * The §5.7 `SourceRow`: a 64 dp row on `surface` with a 40 dp leading slot —
 * the album [coverAssetId] thumbnail (radius `radii.tile`) when one is given
 * and [mediaLoader] with it, otherwise the decorative [icon] in `accent` —
 * the title in `headline`, an optional trailing [count] in `callout
 * textSecondary`, and a chevron. Used by Home (FR-4) and the Albums screen
 * (FR-8); the tap opens the row's destination.
 *
 * The cover is a *small-target* request (the 40 dp slot's pixels, never the
 * full-screen target), so row lists can't evict card bitmaps from the
 * loader's cache (NFR-3, §6.8), and leaving the composition cancels the
 * request (§6.8).
 */
@Composable
fun SourceRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: String? = null,
    coverAssetId: String? = null,
    mediaLoader: MediaLoader? = null,
    icon: String? = null,
) {
    val colors = SwipeTheme.colors
    val tokens = SwipeTheme.componentSizes
    val description = if (count != null) "$title, $count" else title
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .height(tokens.sourceRowHeight)
                .background(colors.surface)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description }
                .padding(horizontal = SwipeTheme.spacing.gutter),
    ) {
        val loader = mediaLoader
        val cover = coverAssetId
        if (cover != null && loader != null) {
            SourceRowCover(cover, loader)
        } else if (icon != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(tokens.sourceRowThumbnail),
            ) {
                SwipeIcon(
                    symbol = icon,
                    tint = colors.accent,
                    modifier = Modifier.size(SwipeTheme.sizes.icon),
                )
            }
        }
        BasicText(
            text = title,
            style = SwipeTheme.typography.headline.copy(color = colors.textPrimary),
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = SwipeTheme.spacing.s),
        )
        if (count != null) {
            BasicText(
                text = count,
                style =
                    SwipeTheme.typography.callout.copy(
                        color = colors.textSecondary,
                        fontFeatureSettings = TABULAR_FIGURES,
                    ),
            )
        }
        SwipeIcon(
            symbol = SwipeIcons.CHEVRON_RIGHT,
            tint = colors.textSecondary,
            modifier = Modifier.size(SwipeTheme.sizes.icon).padding(start = SwipeTheme.spacing.xs),
        )
    }
}

/**
 * The 40 dp album cover slot: a small-target [MediaLoader.image] request for
 * the row's own pixels (never the full-screen target, NFR-3), clipped to the
 * tile radius; leaving the composition cancels the request (§6.8).
 */
@Composable
private fun SourceRowCover(
    coverAssetId: String,
    mediaLoader: MediaLoader,
) {
    val sidePx = with(LocalDensity.current) { SwipeTheme.componentSizes.sourceRowThumbnail.roundToPx() }
    val loaded = rememberMediaImage(coverAssetId, IntSize(sidePx, sidePx), mediaLoader)
    Box(
        modifier =
            Modifier
                .size(SwipeTheme.componentSizes.sourceRowThumbnail)
                .clip(RoundedCornerShape(SwipeTheme.radii.tile))
                .background(SwipeTheme.colors.surfaceRaised),
    ) {
        loaded?.bitmap?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null, // the row-level description covers it
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * NFR-4 placeholder for a count that has not arrived yet: the em dash (§6.5
 * *Home counts* — the row is on screen before its number is).
 */
const val COUNT_PLACEHOLDER = "—"

/** A count for [value] with §5.7 grouping separators, or [COUNT_PLACEHOLDER] while loading. */
fun formatCount(value: Int?): String = value?.let(::groupDigits) ?: COUNT_PLACEHOLDER

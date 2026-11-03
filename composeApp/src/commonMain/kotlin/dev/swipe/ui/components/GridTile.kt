package dev.swipe.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import dev.swipe.data.MediaLoader
import dev.swipe.domain.model.MediaType
import dev.swipe.presentation.queue.QueueTile
import dev.swipe.ui.components.media.rememberMediaImage
import dev.swipe.ui.screens.swipe.formatCardDate
import dev.swipe.ui.screens.swipe.formatDuration
import dev.swipe.ui.theme.SwipeTheme

/**
 * The §5.7 `GridTile`: a square thumbnail (radius `radii.tile`) for one queued
 * item in the Review Queue grid — the gaps between tiles belong to the grid
 * (`componentSizes.gridGap`). A video shows its duration at the bottom-right
 * in `caption`, and an item selected for restore wears a 3 dp `keep` ring.
 *
 * The thumbnail is a *small-target* request — the tile's own pixel side, never
 * the full-screen target — so bulk grids can't evict card bitmaps from the
 * loader's full-screen cache (NFR-3, §6.8), and leaving the composition
 * cancels the underlying `PHImageRequestID` (§6.8, NFR-4). The tile's
 * accessibility line is the NFR-8 [tileDescription]: date, type, duration.
 */
@Composable
fun GridTile(
    tile: QueueTile,
    loader: MediaLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val colors = SwipeTheme.colors
    val tokens = SwipeTheme.componentSizes
    val shape = RoundedCornerShape(SwipeTheme.radii.tile)
    val description = tileDescription(tile)
    BoxWithConstraints(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(shape)
                .background(colors.surface)
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.BottomEnd,
    ) {
        // The grid bounds each cell's width; that is the small decode target.
        val sidePx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val loaded = rememberMediaImage(tile.id, IntSize(sidePx, sidePx), loader)
        loaded?.bitmap?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null, // the tile-level description covers it
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        tile.durationMs?.let { durationMs ->
            BasicText(
                text = formatDuration(durationMs),
                style = SwipeTheme.typography.caption.copy(color = colors.textPrimary),
                modifier = Modifier.padding(SwipeTheme.spacing.xxs),
            )
        }
        if (selected) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .border(tokens.tileRingWidth, colors.keep, shape),
            )
        }
    }
}

/**
 * NFR-8 VoiceOver line: `Mar 14, 2021, video, 0:32` — date, type and duration
 * as far as they are known; a metadata-less tile (failed fetch) is just
 * "Queued item".
 */
fun tileDescription(tile: QueueTile): String {
    val parts =
        listOfNotNull(
            tile.createdAt?.let(::formatCardDate),
            tile.type?.let(::tileTypeLabel),
            tile.durationMs?.let(::formatDuration),
        )
    return parts.joinToString(", ").ifEmpty { "Queued item" }
}

private fun tileTypeLabel(type: MediaType): String =
    when (type) {
        MediaType.PHOTO -> "photo"
        MediaType.VIDEO -> "video"
        MediaType.LIVE_PHOTO -> "Live Photo"
    }

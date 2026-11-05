package dev.swipe.ui.screens.queue

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import dev.swipe.data.MediaLoader
import dev.swipe.presentation.queue.QueueTile
import dev.swipe.ui.components.CircleIconButton
import dev.swipe.ui.components.SwipeIcons
import dev.swipe.ui.components.media.rememberMediaImage
import dev.swipe.ui.components.tileDescription
import dev.swipe.ui.theme.SwipeTheme
import dev.swipe.ui.theme.VERDICT_TINT_ALPHA

/**
 * The FR-24 full-screen viewer for one queued item, raised by a tile tap.
 * The image decodes at [MediaLoader.FULL_TARGET_PX] — this *is* the
 * full-screen context the LRU is for (NFR-3) — and leaving the composition
 * cancels the request (§6.8).
 *
 * Two actions: **Restore** (the `keep`-tinted pill, mirroring the action
 * bar's Keep) records KEEP and drops the item from the queue, and **Close**
 * dismisses the viewer. Both leave as intents through the caller; the
 * viewer holds no state of its own.
 */
@Composable
fun QueueItemViewer(
    tile: QueueTile,
    loader: MediaLoader,
    onRestore: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SwipeTheme.colors
    val sizes = SwipeTheme.componentSizes
    val loaded = rememberMediaImage(tile.id, MediaLoader.FULL_TARGET_PX, loader)
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.bg),
    ) {
        loaded?.bitmap?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = tileDescription(tile),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        CircleIconButton(
            symbol = SwipeIcons.CLOSE,
            label = "Close",
            onClick = onClose,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    // Safe areas (WP-6.4): Close clears the status bar,
                    // Restore the Home Indicator; the image stays full-bleed.
                    .statusBarsPadding()
                    .padding(SwipeTheme.spacing.gutter),
        )
        CircleIconButton(
            symbol = SwipeIcons.CHECK,
            label = "Restore",
            onClick = onRestore,
            size = sizes.actionButtonLarge,
            container = colors.keep.copy(alpha = VERDICT_TINT_ALPHA),
            contentColor = colors.keep,
            iconSize = SwipeTheme.sizes.actionBarIcon,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(SwipeTheme.spacing.gutter),
        )
    }
}

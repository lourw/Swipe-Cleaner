package dev.swipe.ui.screens.queue

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.swipe.data.MediaLoader
import dev.swipe.domain.model.MediaType
import dev.swipe.presentation.queue.QueueTile
import dev.swipe.ui.components.CircleIconButton
import dev.swipe.ui.components.SwipeIcons
import dev.swipe.ui.components.media.DefaultVideoPlayerSlot
import dev.swipe.ui.components.media.VideoPlayerSlot
import dev.swipe.ui.components.media.rememberMediaImage
import dev.swipe.ui.components.tileDescription
import dev.swipe.ui.theme.SwipeTheme
import dev.swipe.ui.theme.VERDICT_TINT_ALPHA

/**
 * The FR-24 full-screen viewer for one queued item, raised by a tile tap.
 * Photos and Live Photos decode at [MediaLoader.FULL_TARGET_PX] — this *is*
 * the full-screen context the LRU is for (NFR-3) — and leaving the composition
 * cancels the request (§6.8). **Videos play** (§5.8, WP-9.5): a queued video
 * mounts the deck's [VideoPlayerSlot] (the sanctioned layering exception,
 * AGENTS.md rule 5) muted and looping like FR-16, so an item can be checked
 * before it is deleted; a video skips the still decode entirely (NFR-3).
 *
 * Two actions: **Restore** (the `keep`-tinted pill, mirroring the action
 * bar's Keep) records KEEP and drops the item from the queue, and **Close**
 * dismisses the viewer. Close rides the `arrow_back` glyph, never `close` —
 * × means Delete in this app and must not sit beside the Restore check
 * (§5.5, WP-9.5). Both leave as intents through the caller; the viewer holds
 * no state of its own.
 *
 * **Modal (NFR-5, WP-9.5).** The root consumes every tap that is not a
 * button: [pointerInput] with a no-op [detectTapGestures] takes the down
 * event, so nothing behind the viewer — the grid in particular — can be
 * tapped or scrolled through it. The caller pairs this with hiding the
 * background's semantics while the viewer is up, so VoiceOver cannot reach
 * the grid or the delete CTA behind it either.
 */
@Composable
fun QueueItemViewer(
    tile: QueueTile,
    loader: MediaLoader,
    onRestore: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    player: VideoPlayerSlot = DefaultVideoPlayerSlot,
) {
    val colors = SwipeTheme.colors
    val sizes = SwipeTheme.componentSizes
    val isVideo = tile.type == MediaType.VIDEO
    // Videos play through the player instead of decoding a full-screen still
    // the player would only cover (NFR-3).
    val loaded = if (isVideo) null else rememberMediaImage(tile.id, MediaLoader.FULL_TARGET_PX, loader)
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.bg)
                // Consume taps anywhere on the body so they cannot fall
                // through to the grid underneath (WP-9.5). The buttons are
                // children, so their own click handlers run first.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { })
                },
    ) {
        if (isVideo) {
            // The hosted player carries no semantics of its own, so the
            // NFR-8 description rides the wrapper (mirrors the deck).
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = tileDescription(tile) },
                contentAlignment = Alignment.Center,
            ) {
                // Positional: the slot is a function type (named args are
                // prohibited on function-type invocations) — mirrors the deck.
                player(tile.id, true, true, {}, Modifier.fillMaxSize(), false, null)
            }
        } else {
            loaded?.bitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = tileDescription(tile),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        CircleIconButton(
            symbol = SwipeIcons.ARROW_BACK,
            label = "Close",
            onClick = onClose,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    // Safe areas (WP-6.4): Close clears the status bar,
                    // Restore the Home Indicator; the media stays full-bleed.
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

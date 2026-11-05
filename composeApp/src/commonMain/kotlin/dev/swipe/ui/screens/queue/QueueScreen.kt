package dev.swipe.ui.screens.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.swipe.data.MediaLoader
import dev.swipe.presentation.queue.QueueEvent
import dev.swipe.presentation.queue.QueueIntent
import dev.swipe.presentation.queue.QueueTile
import dev.swipe.presentation.queue.QueueUiState
import dev.swipe.resources.Res
import dev.swipe.resources.ic_arrow_back
import dev.swipe.ui.components.CircleIconButton
import dev.swipe.ui.components.ConfirmDialog
import dev.swipe.ui.components.GridTile
import dev.swipe.ui.components.PrimaryButton
import dev.swipe.ui.components.Toast
import dev.swipe.ui.screens.swipe.EmptyState
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.flow.Flow

/**
 * The §5.8 Review queue (FR-23–27, NFR-5, NFR-8): a header ("‹ Back   To
 * delete (N)"), the restore hint, a 3-column [GridTile] grid newest-first,
 * and the pinned destructive CTA "Delete N items" over the Recently-Deleted
 * caption. An empty queue shows the §5.8 empty state instead.
 *
 * Everything is [state]-driven (the [QueueUiState] invariant keeps `tiles`
 * aligned with `items`); user actions leave only as [QueueIntent]s. The two
 * overlays ride the state too: `confirming` raises the [ConfirmDialog] — the
 * NFR-5 gate, whose Delete tap is the only path to `ConfirmDelete` — and
 * `selected` raises the full-screen [QueueItemViewer] (FR-24).
 *
 * [events] carries the one-shots: [QueueEvent.ShowDeleteSuccess] navigates
 * through [onShowDeleteSuccess] (the ViewModel never navigates), and the two
 * error events render as a local [Toast] (§6.7). Thumbnails request their
 * tile-sized target through [mediaLoader], never the full-screen LRU
 * (NFR-3, §6.8).
 */
@Composable
fun QueueScreen(
    state: QueueUiState,
    events: Flow<QueueEvent>,
    onIntent: (QueueIntent) -> Unit,
    mediaLoader: MediaLoader,
    onBack: () -> Unit,
    onBackHome: () -> Unit,
    onShowDeleteSuccess: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var toast by remember { mutableStateOf<String?>(null) }
    // The collector restarts only with the events flow; the latest
    // navigation callback still receives it (Compose-rule fix).
    val navigate by rememberUpdatedState(onShowDeleteSuccess)
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is QueueEvent.ShowDeleteSuccess -> navigate(event.count)
                is QueueEvent.ShowDeleteError -> toast = event.message
                QueueEvent.RestoreFailed -> toast = RESTORE_FAILED_COPY
            }
        }
    }

    if (state.items.isEmpty()) {
        // §5.8 empty state; also the zero-count commit's feedback (§6.7) —
        // the queue re-emit that empties `items` lands here.
        EmptyState(
            onTryAnotherSource = onBackHome,
            ctaLabel = "Back home",
            modifier = modifier,
        )
        return
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(SwipeTheme.colors.bg),
    ) {
        // Safe areas (WP-6.4): the bg above paints edge-to-edge; the header
        // clears the status bar and the pinned CTA the Home Indicator.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
        ) {
            QueueHeader(count = state.items.size, onBack = onBack)
            QueueGrid(
                tiles = state.tiles,
                selectedId = state.selected,
                loader = mediaLoader,
                onOpen = { onIntent(QueueIntent.Open(it)) },
            )
            QueueFooter(
                count = state.items.size,
                deleting = state.deleting,
                onRequestDelete = { onIntent(QueueIntent.RequestDelete) },
            )
        }
        QueueOverlays(state = state, mediaLoader = mediaLoader, onIntent = onIntent, toast = toast, onDismissToast = { toast = null })
    }
}

/**
 * The state-raised overlays: the §6.7 toast, the NFR-5 [ConfirmDialog] gate,
 * and the FR-24 [QueueItemViewer]. All three render above the grid, in the
 * order toast < dialog < viewer.
 */
@Composable
private fun BoxScope.QueueOverlays(
    state: QueueUiState,
    mediaLoader: MediaLoader,
    onIntent: (QueueIntent) -> Unit,
    toast: String?,
    onDismissToast: () -> Unit,
) {
    toast?.let { message ->
        Toast(
            text = message,
            onDismiss = onDismissToast,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = SwipeTheme.spacing.xl),
        )
    }

    if (state.confirming) {
        // NFR-5: this dialog is the gate. Cancel (or dismissing it) only
        // closes it; the Delete tap is the sole ConfirmDelete path.
        ConfirmDialog(
            title = "Delete ${state.items.size} items?",
            message = "They'll move to Recently Deleted for 30 days.",
            confirmLabel = "Delete",
            onConfirm = { onIntent(QueueIntent.ConfirmDelete) },
            onCancel = { onIntent(QueueIntent.CancelConfirm) },
        )
    }

    val selected = state.selected
    if (selected != null) {
        QueueItemViewer(
            tile = state.tiles.firstOrNull { it.id == selected } ?: metadataLessTile(selected),
            loader = mediaLoader,
            onRestore = { onIntent(QueueIntent.Restore(selected)) },
            onClose = { onIntent(QueueIntent.Close) },
        )
    }
}

/** The §5.8 header row: "‹ Back   To delete (N)" plus the restore hint. */
@Composable
private fun QueueHeader(
    count: Int,
    onBack: () -> Unit,
) {
    val colors = SwipeTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SwipeTheme.spacing.gutter - SwipeTheme.spacing.xs, vertical = SwipeTheme.spacing.xs),
        ) {
            CircleIconButton(
                resource = Res.drawable.ic_arrow_back,
                label = "Back",
                onClick = onBack,
            )
            BasicText(
                text = "To delete ($count)",
                style = SwipeTheme.typography.title.copy(color = colors.textPrimary),
                modifier = Modifier.padding(start = SwipeTheme.spacing.xs),
            )
        }
        BasicText(
            text = "Tap an item to restore it.",
            style = SwipeTheme.typography.callout.copy(color = colors.textSecondary),
            modifier = Modifier.padding(horizontal = SwipeTheme.spacing.gutter),
        )
    }
}

/**
 * The 3-column grid (§5.8, FR-23): one [GridTile] per queued item, newest first.
 * The tile open in the viewer carries §5.7's keep ring (`selectedId`).
 */
@Composable
private fun ColumnScope.QueueGrid(
    tiles: List<QueueTile>,
    selectedId: String?,
    loader: MediaLoader,
    onOpen: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        horizontalArrangement = Arrangement.spacedBy(SwipeTheme.componentSizes.gridGap),
        verticalArrangement = Arrangement.spacedBy(SwipeTheme.componentSizes.gridGap),
        contentPadding = PaddingValues(horizontal = SwipeTheme.spacing.gutter, vertical = SwipeTheme.spacing.s),
        modifier =
            Modifier
                .weight(1f)
                .fillMaxWidth(),
    ) {
        items(tiles, key = { it.id }) { tile ->
            GridTile(
                tile = tile,
                loader = loader,
                selected = tile.id == selectedId,
                onClick = { onOpen(tile.id) },
            )
        }
    }
}

/** The pinned CTA block: "Delete N items" (NFR-5: it only *asks*) and the caption. */
@Composable
private fun QueueFooter(
    count: Int,
    deleting: Boolean,
    onRequestDelete: () -> Unit,
) {
    val colors = SwipeTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SwipeTheme.spacing.gutter, vertical = SwipeTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.xs),
    ) {
        PrimaryButton(
            label = "Delete $count items",
            onClick = onRequestDelete,
            destructive = true,
            enabled = !deleting,
            modifier = Modifier.fillMaxWidth(),
        )
        BasicText(
            text = "They'll move to Recently Deleted for 30 days.",
            style =
                SwipeTheme.typography.caption.copy(
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A tile whose metadata vanished with its queue row; the viewer still shows the media. */
private fun metadataLessTile(id: String) = QueueTile(id = id, type = null, createdAt = null, durationMs = null)

/** §5.8: the Review queue is a 3-column grid. */
private const val GRID_COLUMNS = 3

/** Toast copy for a restore whose KEEP write failed (NFR-7's convention). */
private const val RESTORE_FAILED_COPY = "Couldn't restore — try again"

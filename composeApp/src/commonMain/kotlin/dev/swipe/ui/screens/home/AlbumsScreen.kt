package dev.swipe.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.swipe.data.MediaLoader
import dev.swipe.presentation.albums.AlbumsIntent
import dev.swipe.presentation.albums.AlbumsUiState
import dev.swipe.ui.components.SourceRow
import dev.swipe.ui.components.formatCount
import dev.swipe.ui.theme.SwipeTheme

/**
 * The Albums screen (§5.8, FR-8): a back header and one [SourceRow] per
 * album — user albums first, then the main smart albums — each with its
 * cover thumbnail (40 dp, `MediaLoader.image` at the row's own pixel size,
 * never the full-screen target, NFR-3) and its converging count (FR-9). The
 * empty list renders just the header until `albums()` answers (NFR-4).
 *
 * Taps leave as [AlbumsIntent]s; the App host collects the ViewModel's
 * [dev.swipe.presentation.albums.AlbumsEvent]s and navigates to the deck
 * with the screen's media-type filter (§6.2, FR-5).
 */
@Composable
fun AlbumsScreen(
    state: AlbumsUiState,
    onIntent: (AlbumsIntent) -> Unit,
    mediaLoader: MediaLoader,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(SwipeTheme.colors.bg),
    ) {
        ScreenHeader(title = "Albums", onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.rows, key = { it.id }) { row ->
                SourceRow(
                    title = row.title,
                    count = formatCount(row.count),
                    coverAssetId = row.coverAssetId,
                    mediaLoader = mediaLoader,
                    onClick = { onIntent(AlbumsIntent.OpenAlbum(row)) },
                )
            }
        }
    }
}

package dev.swipe.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.swipe.data.MediaLoader
import dev.swipe.presentation.ListStatus
import dev.swipe.presentation.albums.AlbumsIntent
import dev.swipe.presentation.albums.AlbumsUiState
import dev.swipe.ui.components.SourceRow
import dev.swipe.ui.components.formatCount
import dev.swipe.ui.theme.SwipeTheme

/** The explainer under the header, shown every time (§5.8, WP-8.14). */
internal const val SHUFFLE_EXPLAINER =
    "Turn an album on to leave its photos out of Shuffle everything."

/** The [SourceRow] subtitle of an excluded album (FR-37, WP-8.14). */
internal const val EXCLUDED_SUBTITLE = "Not in Shuffle"

/** The empty-state message: the scan answered and there are no albums (WP-9.6, FR-8). */
internal const val ALBUMS_EMPTY_MESSAGE = "No albums"

/** The error-state message: the scan failed with nothing on screen (WP-9.6, FR-8). */
internal const val ALBUMS_ERROR_MESSAGE = "Couldn't load albums"

/**
 * The Albums screen (§5.8, FR-8, FR-37): a back header, a one-line explainer of
 * the switch (WP-8.14), and one [SourceRow] per album — user albums first, then
 * the main smart albums — each with its cover thumbnail (40 dp, `MediaLoader.image`
 * at the row's own pixel size, never the full-screen target, NFR-3), its
 * converging count (FR-9), and an **Exclude from Shuffle** switch (FR-37).
 * An excluded row also shows a "Not in Shuffle" subtitle, so the state reads
 * without interpreting the switch. The scan's states (WP-9.6, §5.8): while it
 * runs the screen shows its loading message, an answered empty list and a
 * failed scan each say so under the explainer, and a failed *refresh* keeps
 * the rows shown (§6.8).
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
                .background(SwipeTheme.colors.bg)
                // Safe areas (WP-6.4): header below the status bar, last row
                // above the Home Indicator; bg paints edge-to-edge.
                .statusBarsPadding(),
    ) {
        ScreenHeader(title = "Albums", onBack = onBack)
        // WP-8.14: reads as normal text before the first row (NFR-8). §5.4's
        // type scale has no footnote style, so the explainer uses the smallest
        // token, caption (see the WP report).
        BasicText(
            text = SHUFFLE_EXPLAINER,
            style =
                SwipeTheme.typography.caption.copy(
                    color = SwipeTheme.colors.textSecondary,
                ),
            modifier =
                Modifier.padding(
                    horizontal = SwipeTheme.spacing.gutter,
                    vertical = SwipeTheme.spacing.xs,
                ),
        )
        // WP-9.6 (FR-8, §5.8): the scan's four states each render their own
        // body under the header and explainer.
        when (state.status) {
            ListStatus.Loading -> {
                ListStateMessage(LIST_LOADING_MESSAGE)
            }

            ListStatus.Empty -> {
                ListStateMessage(ALBUMS_EMPTY_MESSAGE)
            }

            ListStatus.Error -> {
                ListStateMessage(
                    message = ALBUMS_ERROR_MESSAGE,
                    onRetry = { onIntent(AlbumsIntent.Retry) },
                )
            }

            ListStatus.Ready -> {
                LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                    items(state.rows, key = { it.id }) { row ->
                        val excluded = row.id in state.excludedAlbumIds
                        SourceRow(
                            title = row.title,
                            count = formatCount(row.count),
                            subtitle = if (excluded) EXCLUDED_SUBTITLE else null,
                            coverAssetId = row.coverAssetId,
                            mediaLoader = mediaLoader,
                            onClick = { onIntent(AlbumsIntent.OpenAlbum(row)) },
                            switchChecked = excluded,
                            onSwitchToggle = { onIntent(AlbumsIntent.ToggleShuffleExclusion(row)) },
                            switchContentDescription = "Exclude ${row.title} from Shuffle",
                        )
                    }
                }
            }
        }
    }
}

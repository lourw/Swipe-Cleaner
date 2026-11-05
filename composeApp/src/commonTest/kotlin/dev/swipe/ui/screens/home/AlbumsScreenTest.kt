// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.home

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.data.MediaLoader
import dev.swipe.fakes.FakeMediaLoader
import dev.swipe.presentation.albums.AlbumsIntent
import dev.swipe.presentation.albums.AlbumsUiState
import dev.swipe.presentation.home.HomeAlbumRow
import dev.swipe.ui.assertAllClickablesLabeled
import dev.swipe.ui.theme.SwipeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Albums screen (WP-5.3, §5.8, FR-8): one row per album with its cover
 * thumbnail (requested at the row's own 40 dp size, never the full-screen
 * target — NFR-3) and its count, the back header, and taps leaving as
 * [AlbumsIntent]s. Fixed states; the converging-count ladder is tested in
 * [dev.swipe.presentation.albums.AlbumsViewModelTest].
 */
class AlbumsScreenTest {
    private val rows =
        listOf(
            HomeAlbumRow(id = "a1", title = "Trip", coverAssetId = "asset-3", count = 1204),
            HomeAlbumRow(id = "a2", title = "Family", coverAssetId = null, count = null),
        )

    @Test
    fun `FR-8 album rows show cover and count`() =
        runComposeUiTest {
            val loader = FakeMediaLoader()
            setContent {
                SwipeTheme {
                    AlbumsScreen(
                        state = AlbumsUiState(rows = rows),
                        onIntent = {},
                        mediaLoader = loader,
                        onBack = {},
                    )
                }
            }
            waitForIdle()
            onNodeWithText("Trip").assertExists()
            onNodeWithText("1,204").assertExists() // grouping separators (§5.7)
            onNodeWithText("Family").assertExists()

            // The cover rides the row: one small-target request for the album
            // with a cover, none for the album without one (NFR-3).
            val covers = loader.imageRequests.map { it.first }
            assertEquals(listOf("asset-3"), covers)
            val target = loader.imageRequests.single().second
            assertTrue(
                target.width < MediaLoader.FULL_TARGET_PX.width && target.height < MediaLoader.FULL_TARGET_PX.height,
                "covers must never ask for the full-screen target: $target",
            )
        }

    @Test
    fun `FR-8 tapping an album row sends the OpenAlbum intent`() =
        runComposeUiTest {
            val intents = mutableListOf<AlbumsIntent>()
            setContent {
                SwipeTheme {
                    AlbumsScreen(
                        state = AlbumsUiState(rows = rows),
                        onIntent = { intents.add(it) },
                        mediaLoader = FakeMediaLoader(),
                        onBack = {},
                    )
                }
            }
            onNodeWithText("Trip").performClick()
            waitForIdle()
            assertEquals(listOf<AlbumsIntent>(AlbumsIntent.OpenAlbum(rows.first())), intents)
        }

    @Test
    fun `5-8 header names the screen and back leaves it`() =
        runComposeUiTest {
            var backTaps = 0
            setContent {
                SwipeTheme {
                    AlbumsScreen(
                        state = AlbumsUiState(),
                        onIntent = {},
                        mediaLoader = FakeMediaLoader(),
                        onBack = { backTaps++ },
                    )
                }
            }
            onNodeWithText("Albums").assertExists()
            onNodeWithContentDescription("Back").performClick()
            waitForIdle()
            assertEquals(1, backTaps)
        }

    @Test
    fun `NFR-8 every screen's clickable nodes have content descriptions`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    AlbumsScreen(state = AlbumsUiState(rows = rows), onIntent = {}, mediaLoader = FakeMediaLoader(), onBack = {})
                }
            }
            waitForIdle()
            assertAllClickablesLabeled()
        }
}

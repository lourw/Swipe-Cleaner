// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.swipe.data.MediaLoader
import dev.swipe.fakes.FakeMediaLoader
import dev.swipe.presentation.albums.AlbumsIntent
import dev.swipe.presentation.albums.AlbumsUiState
import dev.swipe.presentation.home.HomeAlbumRow
import dev.swipe.ui.assertAllClickablesLabeled
import dev.swipe.ui.theme.SwipeComponentSizes
import dev.swipe.ui.theme.SwipeSizes
import dev.swipe.ui.theme.SwipeSpacing
import dev.swipe.ui.theme.SwipeTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Albums screen (WP-5.3, §5.8, FR-8): one row per album with its cover
 * thumbnail (requested at the row's own 40 dp size, never the full-screen
 * target — NFR-3) and its count, the back header, and taps leaving as
 * [AlbumsIntent]s. Fixed states; the converging-count ladder is tested in
 * [dev.swipe.presentation.albums.AlbumsViewModelTest]. WP-8.14 adds the FR-37
 * switch spacing, the explainer line, and the excluded-row subtitle.
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
    fun `FR-37 tapping the switch toggles the exclusion without opening the album`() =
        runComposeUiTest {
            val intents = mutableListOf<AlbumsIntent>()
            setContent {
                SwipeTheme {
                    AlbumsScreen(
                        state = AlbumsUiState(rows = rows, excludedAlbumIds = setOf("a1")),
                        onIntent = { intents.add(it) },
                        mediaLoader = FakeMediaLoader(),
                        onBack = {},
                    )
                }
            }
            waitForIdle()
            onNodeWithContentDescription("Exclude Trip from Shuffle").performClick()
            waitForIdle()
            // The switch owns its tap: a toggle, never an OpenAlbum.
            assertEquals(listOf<AlbumsIntent>(AlbumsIntent.ToggleShuffleExclusion(rows.first())), intents)
        }

    @Test
    fun `FR-37 an excluded album's row still opens`() =
        runComposeUiTest {
            val intents = mutableListOf<AlbumsIntent>()
            setContent {
                SwipeTheme {
                    AlbumsScreen(
                        state = AlbumsUiState(rows = rows, excludedAlbumIds = setOf("a1")),
                        onIntent = { intents.add(it) },
                        mediaLoader = FakeMediaLoader(),
                        onBack = {},
                    )
                }
            }
            waitForIdle()
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

    /**
     * WP-8.14 (FR-37): the switch's touch box is as wide as the 51 dp track,
     * so the track can no longer spill toward the count, and at least an `s`
     * gap separates the count's right edge from the switch's left edge — with
     * a 5-digit count and a long album title pressing in from both sides.
     */
    @Test
    fun `FR-37 the count and the switch keep the s gap and the track fits the touch box`() =
        runComposeUiTest {
            val longTitle = "Golden Gate Bridge sunset originals"
            setContent {
                SwipeTheme {
                    AlbumsScreen(
                        state =
                            AlbumsUiState(
                                rows = listOf(HomeAlbumRow(id = "a1", title = longTitle, coverAssetId = null, count = 12345)),
                                excludedAlbumIds = setOf("a1"),
                            ),
                        onIntent = {},
                        mediaLoader = FakeMediaLoader(),
                        onBack = {},
                    )
                }
            }
            waitForIdle()
            // The row is clickable and merges its descendants' semantics, so
            // the text leaves (count, subtitle) are only addressable in the
            // unmerged tree — in the merged tree their text rides on the row.
            val count = onNodeWithText("12,345", useUnmergedTree = true).fetchSemanticsNode()
            val switch =
                onNodeWithContentDescription("Exclude $longTitle from Shuffle", useUnmergedTree = true)
                    .fetchSemanticsNode()
            val gap = switch.boundsInWindow.left - count.boundsInWindow.right
            val minGap = with(density) { SwipeSpacing().s.toPx() }
            assertTrue(
                gap >= minGap - 0.5f,
                "the count and the switch need at least the s gap ($minGap px), got $gap px; " +
                    "count=${count.boundsInWindow} switch=${switch.boundsInWindow}",
            )
            val boxWidth = with(density) { switch.size.width.toDp() }
            val boxHeight = with(density) { switch.size.height.toDp() }
            val shared = SwipeSizes()
            val components = SwipeComponentSizes()
            // 1 dp slack for px rounding (the 44-vs-51 regression this pins is 7 dp).
            assertTrue(
                boxWidth >= shared.minTouchTarget - 1.dp && boxHeight >= shared.minTouchTarget - 1.dp,
                "the switch box keeps the §5.5 44 dp target, got $boxWidth × $boxHeight",
            )
            assertTrue(
                boxWidth >= components.switchTrackWidth - 1.dp,
                "the ${components.switchTrackWidth} track fits the touch box, got $boxWidth",
            )
        }

    /**
     * WP-8.14 (FR-37, NFR-8): "Not in Shuffle" appears under the title of an
     * excluded album only, joins the row's VoiceOver description, and the
     * two-line layout fits the fixed 64 dp row height.
     */
    @Test
    fun `FR-37 the Not in Shuffle subtitle appears only on excluded rows`() =
        runComposeUiTest {
            var excluded by mutableStateOf(setOf("a1"))
            setContent {
                SwipeTheme {
                    AlbumsScreen(
                        state = AlbumsUiState(rows = rows, excludedAlbumIds = excluded),
                        onIntent = {},
                        mediaLoader = FakeMediaLoader(),
                        onBack = {},
                    )
                }
            }
            waitForIdle()
            // Trip is excluded: one subtitle, on its row; Family is not and
            // shows none. The subtitle joins the row description (NFR-8).
            // Text leaves live in the unmerged tree (the row merges them).
            assertEquals(
                1,
                onAllNodesWithText(EXCLUDED_SUBTITLE, useUnmergedTree = true).fetchSemanticsNodes().size,
            )
            val row = onNodeWithContentDescription("Trip, 1,204, $EXCLUDED_SUBTITLE").fetchSemanticsNode()
            onNodeWithContentDescription("Family, —").assertExists()
            // The two lines fit the fixed row height (§5.7): the subtitle sits
            // inside the row's bounds and the row is still sourceRowHeight.
            val rowBounds = row.boundsInWindow
            val subtitleBounds =
                onNodeWithText(EXCLUDED_SUBTITLE, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInWindow
            val height = with(density) { row.size.height.toDp() }
            val expected = SwipeComponentSizes().sourceRowHeight
            assertTrue(abs((height - expected).value) <= 0.5f, "the row stays $expected, got $height")
            assertTrue(
                subtitleBounds.top >= rowBounds.top - 0.5f && subtitleBounds.bottom <= rowBounds.bottom + 0.5f,
                "the subtitle fits inside the row: $subtitleBounds vs $rowBounds",
            )
            // Excluded off: the subtitle and its place in the description go.
            excluded = emptySet()
            waitForIdle()
            assertEquals(
                0,
                onAllNodesWithText(EXCLUDED_SUBTITLE, useUnmergedTree = true).fetchSemanticsNodes().size,
            )
            onNodeWithContentDescription("Trip, 1,204").assertExists()
        }

    /** WP-8.14 (FR-37, NFR-8): the explainer reads as normal text before the first row. */
    @Test
    fun `FR-37 the explainer sits above the first row and shows every time`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    AlbumsScreen(state = AlbumsUiState(rows = rows), onIntent = {}, mediaLoader = FakeMediaLoader(), onBack = {})
                }
            }
            waitForIdle()
            val explainer = onNodeWithText(SHUFFLE_EXPLAINER).fetchSemanticsNode().boundsInWindow
            val firstRow = onNodeWithContentDescription("Trip, 1,204").fetchSemanticsNode().boundsInWindow
            assertTrue(
                explainer.bottom <= firstRow.top + 0.5f,
                "the explainer reads before the first row: $explainer vs $firstRow",
            )
        }

    /** WP-8.14 (FR-37): the explainer is not first-launch-only — it shows with no albums yet. */
    @Test
    fun `FR-37 the explainer shows before the albums arrive`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    AlbumsScreen(state = AlbumsUiState(), onIntent = {}, mediaLoader = FakeMediaLoader(), onBack = {})
                }
            }
            waitForIdle()
            onNodeWithText(SHUFFLE_EXPLAINER).assertExists()
        }
}

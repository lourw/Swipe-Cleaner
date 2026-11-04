// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.home

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import dev.swipe.domain.model.Stats
import dev.swipe.presentation.home.HomeIntent
import dev.swipe.presentation.home.HomeUiState
import dev.swipe.ui.theme.SwipeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The §5.8 Home wireframe (WP-5.3, FR-4, FR-10, FR-2, NFR-4): every session
 * source on screen, the queue Banner iff the queue is non-empty, the two
 * banners stacked limited-first, em-dash placeholders while counts load, and
 * the stats line with grouping separators. Driven with fixed states; the
 * ViewModel side lives in
 * [dev.swipe.presentation.home.HomeViewModelTest].
 */
class HomeScreenTest {
    @Test
    fun `FR-4 home lists all session sources`() =
        runComposeUiTest {
            val intents = mutableListOf<HomeIntent>()
            setContent {
                SwipeTheme {
                    HomeScreen(state = HomeUiState(), onIntent = { intents.add(it) })
                }
            }
            // Title row and every source row of the §5.8 wireframe.
            onNodeWithText("Swipe Cleaner").assertExists()
            onNodeWithContentDescription("Settings").assertExists()
            onNodeWithText("Shuffle everything").assertExists()
            onNodeWithText("Albums").assertExists()
            onNodeWithText("By month").assertExists()
            onNodeWithText("SMART FILTERS").assertExists()
            onNodeWithText("Screenshots").assertExists()
            onNodeWithText("Long videos").assertExists()
            onNodeWithText("Bursts").assertExists()
            onNodeWithText("Likely duplicates").assertExists()
            onNodeWithText("Selfies").assertExists()

            // Every row leaves its intent (FR-4, FR-6); navigation is the host's.
            onNodeWithText("Shuffle everything").performClick()
            onNodeWithText("Albums").performClick()
            onNodeWithText("By month").performClick()
            onNodeWithText("Screenshots").performClick()
            onNodeWithContentDescription("Settings").performClick()
            waitForIdle()
            assertEquals(
                listOf<HomeIntent>(
                    HomeIntent.OpenSource.Shuffle,
                    HomeIntent.OpenAlbums,
                    HomeIntent.OpenMonths,
                    HomeIntent.OpenSource.Smart(SmartFilter.SCREENSHOTS),
                    HomeIntent.OpenSettings,
                ),
                intents,
            )
        }

    @Test
    fun `FR-10 queue banner hidden when queue is empty`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    HomeScreen(state = HomeUiState(queueCount = 0), onIntent = {})
                }
            }
            onNodeWithText("🗑 0 waiting to delete ›").assertDoesNotExist()
            onNodeWithText("🗑 3 waiting to delete ›").assertDoesNotExist()
        }

    @Test
    fun `FR-10 queue banner shows with the queue size and opens the queue`() =
        runComposeUiTest {
            val intents = mutableListOf<HomeIntent>()
            setContent {
                SwipeTheme {
                    HomeScreen(
                        state = HomeUiState(queueCount = 3),
                        onIntent = { intents.add(it) },
                    )
                }
            }
            onNodeWithText("🗑 3 waiting to delete ›").performClick()
            waitForIdle()
            assertEquals(listOf<HomeIntent>(HomeIntent.OpenQueue), intents)
        }

    @Test
    fun `FR-2 limited banner shows with the item count and manages access`() =
        runComposeUiTest {
            var manageTaps = 0
            setContent {
                SwipeTheme {
                    HomeScreen(
                        state = HomeUiState(access = LibraryAccess.LIMITED),
                        onIntent = {},
                        limitedItemCount = 7,
                        onManageLimitedAccess = { manageTaps++ },
                    )
                }
            }
            onNodeWithText("You've given access to 7 items.").assertExists()
            onNodeWithText("Manage").performClick()
            assertEquals(1, manageTaps)
        }

    @Test
    fun `FR-2 limited banner hidden with full access or no count`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    HomeScreen(
                        state = HomeUiState(access = LibraryAccess.FULL),
                        onIntent = {},
                        limitedItemCount = 7,
                    )
                }
            }
            onNodeWithText("You've given access to 7 items.").assertDoesNotExist()
        }

    /** §5.8 / WP-5.3 task 2: both banners show when both apply, limited first. */
    @Test
    fun `FR-2 both banners show with the limited banner first`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    HomeScreen(
                        state = HomeUiState(access = LibraryAccess.LIMITED, queueCount = 3),
                        onIntent = {},
                        limitedItemCount = 7,
                    )
                }
            }
            val limited = onNodeWithText("You've given access to 7 items.")
            val queue = onNodeWithText("🗑 3 waiting to delete ›")
            limited.assertExists()
            queue.assertExists()
            assertTrue(
                limited.fetchSemanticsNode().boundsInWindow.top <
                    queue.fetchSemanticsNode().boundsInWindow.top,
                "the limited banner must sit above the queue banner",
            )
        }

    /** NFR-4: rows render before their numbers do — every loading count is an em dash. */
    @Test
    fun `NFR-4 loading counts render as em dash placeholders`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    HomeScreen(state = HomeUiState(), onIntent = {})
                }
            }
            // Shuffle + the five smart rows, each exactly "—" (the stats line
            // carries its dashes inside the composite text, not as bare nodes).
            assertEquals(6, onAllNodesWithText("—").fetchSemanticsNodes().size)
        }

    @Test
    fun `5-8 stats line shows reviewed and deleted with grouping separators`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    HomeScreen(
                        state =
                            HomeUiState(
                                stats = Stats(reviewed = 1204, kept = 892, deleted = 312, queued = 0),
                                shuffleCount = 1204,
                            ),
                        onIntent = {},
                    )
                }
            }
            onNodeWithText("1,204 reviewed · 312 deleted").assertExists()
            onNodeWithText("1,204").assertExists() // the Shuffle row's grouped count
        }

    /** FR-5: the segmented control reflects the selection and taps leave as intents. */
    @Test
    fun `FR-5 segmented control marks the selection and sends SetTypeFilter`() =
        runComposeUiTest {
            val intents = mutableListOf<HomeIntent>()
            setContent {
                SwipeTheme {
                    HomeScreen(
                        state = HomeUiState(typeFilter = MediaTypeFilter.ALL),
                        onIntent = { intents.add(it) },
                    )
                }
            }
            onNodeWithText("Videos").performClick()
            waitForIdle()
            assertEquals(
                listOf<HomeIntent>(HomeIntent.SetTypeFilter(MediaTypeFilter.VIDEOS)),
                intents,
            )
        }
}

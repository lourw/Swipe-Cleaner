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
import dev.swipe.domain.model.MonthBucket
import dev.swipe.presentation.ListStatus
import dev.swipe.presentation.months.MonthRow
import dev.swipe.presentation.months.MonthsIntent
import dev.swipe.presentation.months.MonthsUiState
import dev.swipe.ui.assertAllClickablesLabeled
import dev.swipe.ui.components.COUNT_PLACEHOLDER
import dev.swipe.ui.theme.SwipeTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The By month screen (WP-5.3, §5.8, FR-7): the newest-first buckets grouped
 * into year sections — one header per year, months by full name — and taps
 * leaving as [MonthsIntent]s. The scan itself is the ViewModel's job
 * ([dev.swipe.presentation.months.MonthsViewModelTest]).
 */
class MonthsScreenTest {
    private val march = MonthBucket(2026, 3, 7)
    private val december = MonthBucket(2025, 12, 420)
    private val may = MonthBucket(2025, 5, 13)
    private val rows = listOf(MonthRow(march, 7), MonthRow(december, 420), MonthRow(may, 13))

    @Test
    fun `FR-7 months screen groups by year newest first`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    MonthsScreen(
                        state = MonthsUiState(status = ListStatus.Ready, rows = rows),
                        onIntent = {},
                        onBack = {},
                    )
                }
            }
            // One header per year, not per bucket: the two 2025 months share one.
            assertEquals(1, onAllNodesWithText("2025").fetchSemanticsNodes().size)
            onNodeWithText("2026").assertExists()

            // Top-to-bottom order: 2026 header, March, 2025 header, December, May.
            val tops =
                listOf("2026", "March", "2025", "December", "May").map { label ->
                    onNodeWithText(label).fetchSemanticsNode().boundsInWindow.top
                }
            assertEquals(tops.sorted(), tops)
            onNodeWithText("7").assertExists()
            onNodeWithText("420").assertExists()
        }

    @Test
    fun `FR-7 tapping a month row sends the OpenMonth intent`() =
        runComposeUiTest {
            val intents = mutableListOf<MonthsIntent>()
            setContent {
                SwipeTheme {
                    MonthsScreen(
                        state = MonthsUiState(status = ListStatus.Ready, rows = rows),
                        onIntent = { intents.add(it) },
                        onBack = {},
                    )
                }
            }
            onNodeWithText("December").performClick()
            waitForIdle()
            assertEquals(listOf<MonthsIntent>(MonthsIntent.OpenMonth(december)), intents)
        }

    @Test
    fun `FR-9 a row without its settled count shows the placeholder`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    MonthsScreen(
                        state = MonthsUiState(status = ListStatus.Ready, rows = listOf(MonthRow(march), MonthRow(december, 420))),
                        onIntent = {},
                        onBack = {},
                    )
                }
            }
            onNodeWithText(COUNT_PLACEHOLDER).assertExists()
            onNodeWithText("420").assertExists()
        }

    @Test
    fun `5-8 header names the screen and back leaves it`() =
        runComposeUiTest {
            var backTaps = 0
            setContent {
                SwipeTheme {
                    MonthsScreen(
                        state = MonthsUiState(),
                        onIntent = {},
                        onBack = { backTaps++ },
                    )
                }
            }
            onNodeWithText("By month").assertExists()
            onNodeWithContentDescription("Back").performClick()
            waitForIdle()
            assertEquals(1, backTaps)
        }

    @Test
    fun `NFR-8 every screen's clickable nodes have content descriptions`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    MonthsScreen(state = MonthsUiState(status = ListStatus.Ready, rows = rows), onIntent = {}, onBack = {})
                }
            }
            waitForIdle()
            assertAllClickablesLabeled()
        }

    @Test
    fun `FR-7 NFR-4 the loading status shows the loading message while the scan runs`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    MonthsScreen(state = MonthsUiState(), onIntent = {}, onBack = {})
                }
            }
            onNodeWithText(LIST_LOADING_MESSAGE).assertExists()
        }

    @Test
    fun `FR-7 the empty status says there is nothing to show`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    MonthsScreen(state = MonthsUiState(status = ListStatus.Empty), onIntent = {}, onBack = {})
                }
            }
            onNodeWithText(MONTHS_EMPTY_MESSAGE).assertExists()
        }

    @Test
    fun `FR-7 the error status explains itself and Try again sends Retry`() =
        runComposeUiTest {
            val intents = mutableListOf<MonthsIntent>()
            setContent {
                SwipeTheme {
                    MonthsScreen(
                        state = MonthsUiState(status = ListStatus.Error),
                        onIntent = { intents.add(it) },
                        onBack = {},
                    )
                }
            }
            onNodeWithText(MONTHS_ERROR_MESSAGE).assertExists()
            onNodeWithText(LIST_RETRY_LABEL).performClick()
            waitForIdle()
            assertEquals(listOf<MonthsIntent>(MonthsIntent.Retry), intents)
        }
}

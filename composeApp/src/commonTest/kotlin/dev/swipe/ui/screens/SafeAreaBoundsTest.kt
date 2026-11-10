// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MonthBucket
import dev.swipe.fakes.FakeHaptics
import dev.swipe.fakes.FakeMediaLoader
import dev.swipe.presentation.albums.AlbumsUiState
import dev.swipe.presentation.home.HomeAlbumRow
import dev.swipe.presentation.home.HomeUiState
import dev.swipe.presentation.months.MonthsUiState
import dev.swipe.presentation.queue.QueueTile
import dev.swipe.presentation.queue.QueueUiState
import dev.swipe.presentation.settings.SettingsUiState
import dev.swipe.presentation.swipe.CardState
import dev.swipe.presentation.swipe.SwipeUiState
import dev.swipe.ui.screens.home.AlbumsScreen
import dev.swipe.ui.screens.home.HomeScreen
import dev.swipe.ui.screens.home.MonthsScreen
import dev.swipe.ui.screens.onboarding.DeniedScreen
import dev.swipe.ui.screens.queue.QueueScreen
import dev.swipe.ui.screens.settings.SettingsScreen
import dev.swipe.ui.screens.summary.DeleteSuccessScreen
import dev.swipe.ui.screens.swipe.EmptyState
import dev.swipe.ui.screens.swipe.SummaryScreen
import dev.swipe.ui.screens.swipe.SwipeScreen
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * WP-6.4 safe-area regression bounds: on every screen the first and last
 * content nodes stay inside the window, so no inset modifier can ever push
 * content off-screen or clip it out of the viewport.
 *
 * These are *clipping* regressions only: headless CMP test windows report
 * zero status-bar/navigation-bar insets, so the real "clears the notch /
 * Home Indicator" offsets cannot be observed here — the 👤 device pass in
 * `docs/manual-test.md` ("Layout and safe areas") owns that. The production
 * layouts apply [androidx.compose.foundation.layout.statusBarsPadding] /
 * [androidx.compose.foundation.layout.navigationBarsPadding] after the
 * background modifier, i.e. backgrounds paint edge-to-edge while content
 * stays inside the safe area (§5.1.5).
 */
class SafeAreaBoundsTest {
    /** Assert [node] is fully inside the root (window) bounds. */
    private fun SemanticsNodeInteractionsProvider.assertInsideWindow(node: SemanticsNodeInteraction) {
        val root = onRoot().fetchSemanticsNode().boundsInWindow
        val b = node.fetchSemanticsNode().boundsInWindow
        assertTrue(b.left >= root.left - 0.5f, "$node left ${b.left} < window ${root.left}")
        assertTrue(b.top >= root.top - 0.5f, "$node top ${b.top} < window ${root.top}")
        assertTrue(b.right <= root.right + 0.5f, "$node right ${b.right} > window ${root.right}")
        assertTrue(b.bottom <= root.bottom + 0.5f, "$node bottom ${b.bottom} > window ${root.bottom}")
    }

    @Test
    fun `WP-6-4 home first and last content sit inside the window`() =
        runComposeUiTest {
            setContent { SwipeTheme { HomeScreen(state = HomeUiState(), onIntent = {}) } }
            waitForIdle()
            assertInsideWindow(onNodeWithText("Swipe Cleaner"))
        }

    @Test
    fun `WP-6-4 queue header and pinned CTA sit inside the window`() =
        runComposeUiTest {
            val state =
                QueueUiState(
                    items = (1..5).map { "asset-$it" },
                    tiles =
                        (1..5).map { id ->
                            QueueTile(
                                id = "asset-$id",
                                type = null,
                                createdAt = null,
                                durationMs = null,
                            )
                        },
                )
            setContent {
                SwipeTheme {
                    QueueScreen(
                        state = state,
                        events = flowOf(),
                        onIntent = {},
                        mediaLoader = FakeMediaLoader(),
                        onBack = {},
                        onBackHome = {},
                        onShowDeleteSuccess = {},
                    )
                }
            }
            waitForIdle()
            assertInsideWindow(onNodeWithText("To delete (5)"))
            assertInsideWindow(onNodeWithContentDescription("Delete 5 items"))
        }

    @Test
    fun `WP-6-4 queue viewer close and restore sit inside the window`() =
        runComposeUiTest {
            val state =
                QueueUiState(
                    items = listOf("asset-1"),
                    tiles =
                        listOf(
                            QueueTile(
                                id = "asset-1",
                                type = null,
                                createdAt = null,
                                durationMs = null,
                            ),
                        ),
                    selected = "asset-1",
                )
            setContent {
                SwipeTheme {
                    QueueScreen(
                        state = state,
                        events = flowOf(),
                        onIntent = {},
                        mediaLoader = FakeMediaLoader(),
                        onBack = {},
                        onBackHome = {},
                        onShowDeleteSuccess = {},
                    )
                }
            }
            waitForIdle()
            assertInsideWindow(onNodeWithContentDescription("Close"))
            assertInsideWindow(onNodeWithContentDescription("Restore"))
        }

    @Test
    fun `WP-6-4 settings header sits inside the window`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    SettingsScreen(state = SettingsUiState(), events = flowOf(), onIntent = {}, onBack = {})
                }
            }
            waitForIdle()
            assertInsideWindow(onNodeWithText("Settings"))
        }

    @Test
    fun `WP-6-4 albums header and last row sit inside the window`() =
        runComposeUiTest {
            val rows =
                listOf(
                    HomeAlbumRow(id = "a1", title = "Trip", coverAssetId = null, count = 12),
                    HomeAlbumRow(id = "a2", title = "Family", coverAssetId = null, count = null),
                )
            setContent {
                SwipeTheme {
                    AlbumsScreen(
                        state = AlbumsUiState(rows = rows),
                        onIntent = {},
                        mediaLoader = FakeMediaLoader(),
                        onBack = {},
                    )
                }
            }
            waitForIdle()
            assertInsideWindow(onNodeWithText("Albums"))
            assertInsideWindow(onNodeWithText("Family"))
        }

    @Test
    fun `WP-6-4 months header and last row sit inside the window`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    MonthsScreen(
                        state =
                            MonthsUiState(
                                buckets =
                                    listOf(
                                        MonthBucket(year = 2026, month = 11, count = 40),
                                        MonthBucket(year = 2026, month = 10, count = 33),
                                    ),
                            ),
                        onIntent = {},
                        onBack = {},
                    )
                }
            }
            waitForIdle()
            assertInsideWindow(onNodeWithText("By month"))
            assertInsideWindow(onNodeWithText("October"))
        }

    @Test
    fun `WP-6-4 denied screen title and CTA sit inside the window`() =
        runComposeUiTest {
            setContent { SwipeTheme { DeniedScreen(onOpenSettings = {}) } }
            waitForIdle()
            assertInsideWindow(onNodeWithText("Photos access needed"))
            assertInsideWindow(onNodeWithText("Open Settings"))
        }

    @Test
    fun `WP-6-4 summary content and CTA sit inside the window`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    SummaryScreen(kept = 92, queued = 18, skipped = 4, onBackHome = {}, onReviewQueue = {})
                }
            }
            waitForIdle()
            assertInsideWindow(onNodeWithText("Review 18 to delete"))
        }

    @Test
    fun `WP-6-4 delete-success content and CTA sit inside the window`() =
        runComposeUiTest {
            setContent { SwipeTheme { DeleteSuccessScreen(deleted = 7, onBackHome = {}) } }
            waitForIdle()
            assertInsideWindow(onNodeWithText("Deleted"))
            assertInsideWindow(onNodeWithText("Back home"))
        }

    @Test
    fun `WP-6-4 empty state copy and CTA sit inside the window`() =
        runComposeUiTest {
            setContent { SwipeTheme { EmptyState(onTryAnotherSource = {}) } }
            waitForIdle()
            assertInsideWindow(onNodeWithText("Nothing left here 🎉"))
            assertInsideWindow(onNodeWithText("Try another source"))
        }

    @Test
    fun `WP-6-4 deck top bar and action bar sit inside the window`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    SwipeScreen(
                        state =
                            SwipeUiState(
                                current =
                                    CardState(
                                        id = "current-id",
                                        type = MediaType.PHOTO,
                                        createdAt = null,
                                        durationMs = null,
                                        isFavorite = false,
                                    ),
                            ),
                        events = flowOf(),
                        onIntent = {},
                        mediaLoader = FakeMediaLoader(),
                        haptics = FakeHaptics(),
                        reduceMotion = false,
                        onClose = {},
                        onOpenQueue = {},
                    )
                }
            }
            waitForIdle()
            // TopBar close under the top inset, ActionBar Keep over the
            // bottom inset: the deck's two pinned chrome rows.
            assertInsideWindow(onNodeWithContentDescription("Close"))
            assertInsideWindow(onNodeWithContentDescription("Keep"))
        }
}

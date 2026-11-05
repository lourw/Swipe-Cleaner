// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(
    ExperimentalComposeUiApi::class,
    ExperimentalTestApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.swipe.ui.screens.queue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.data.MediaLoader
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.usecase.CommitDeletionsUseCase
import dev.swipe.domain.usecase.PruneQueueUseCase
import dev.swipe.domain.usecase.RestoreFromQueueUseCase
import dev.swipe.fakes.FakeClock
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeHaptics
import dev.swipe.fakes.FakeMediaLibrary
import dev.swipe.fakes.FakeMediaLoader
import dev.swipe.presentation.queue.QueueEvent
import dev.swipe.presentation.queue.QueueIntent
import dev.swipe.presentation.queue.QueueTile
import dev.swipe.presentation.queue.QueueUiState
import dev.swipe.presentation.queue.QueueViewModel
import dev.swipe.ui.assertAllClickablesLabeled
import dev.swipe.ui.screens.swipe.formatCardDate
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The WP-3.3 assembled Review Queue screen (§5.8, FR-23–27, NFR-3, NFR-5,
 * NFR-8): the 3-column grid and its header count, the FR-24 tile-tap viewer
 * with Restore/Close, the two-step delete — the in-app confirm as the gate
 * and nothing reaching PhotoKit without it — the §6.7 toasts and success
 * navigation, and the empty state. The NFR-5 tests wire the real
 * [QueueViewModel] to the screen over the fakes, so "does not delete" is
 * asserted on [FakeMediaLibrary.deleteCalls] and the store itself.
 */
class QueueScreenTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun tile(id: String) =
        QueueTile(
            id = id,
            type = null,
            createdAt = null,
            durationMs = null,
        )

    private fun queueState(count: Int) =
        QueueUiState(
            items = (1..count).map { "asset-$it" },
            tiles = (1..count).map { tile("asset-$it") },
        )

    @Test
    fun `FR-23 queue grid shows 3 columns`() =
        runComposeUiTest {
            setContent { TestScreen(queueState(6)) }
            // Six tiles fill exactly two viewport rows (the grid lazily
            // composes only what shows): two rows of three prove the columns.
            val bounds = onAllNodesWithContentDescription("Queued item").fetchSemanticsNodes().map { it.boundsInWindow }
            assertEquals(6, bounds.size)
            assertEquals(3, bounds.map { it.left }.distinct().size)
            assertEquals(2, bounds.map { it.top }.distinct().size)
            assertEquals(bounds[0].top, bounds[1].top)
            assertEquals(bounds[0].top, bounds[2].top)
            // The fourth tile wraps into a new row under the first.
            assertEquals(bounds[0].left, bounds[3].left)
            assertTrue(bounds[3].top > bounds[0].top)
        }

    @Test
    fun `FR-23 header counts the queue and names the screen`() =
        runComposeUiTest {
            setContent { TestScreen(queueState(5)) }
            onNodeWithText("To delete (5)").assertExists()
            onNodeWithText("Tap an item to restore it.").assertExists()
            onNodeWithContentDescription("Delete 5 items").assertExists()
            onNodeWithText("They'll move to Recently Deleted for 30 days.").assertExists()
        }

    @Test
    fun `FR-24 tile tap sends the Open intent`() =
        runComposeUiTest {
            val intents = mutableListOf<QueueIntent>()
            setContent { TestScreen(queueState(3), onIntent = { intents.add(it) }) }
            onAllNodesWithContentDescription("Queued item")[1].performClick()
            waitForIdle()
            assertEquals(listOf<QueueIntent>(QueueIntent.Open("asset-2")), intents)
        }

    @Test
    fun `FR-24 restore in viewer sends Restore intent`() =
        runComposeUiTest {
            val intents = mutableListOf<QueueIntent>()
            setContent { TestScreen(queueState(3).copy(selected = "asset-2"), onIntent = { intents.add(it) }) }
            onNodeWithContentDescription("Restore").performClick()
            waitForIdle()
            assertEquals(listOf<QueueIntent>(QueueIntent.Restore("asset-2")), intents)
        }

    @Test
    fun `FR-24 close in viewer sends Close intent`() =
        runComposeUiTest {
            val intents = mutableListOf<QueueIntent>()
            setContent { TestScreen(queueState(3).copy(selected = "asset-2"), onIntent = { intents.add(it) }) }
            onNodeWithContentDescription("Close").performClick()
            waitForIdle()
            assertEquals(listOf<QueueIntent>(QueueIntent.Close), intents)
        }

    @Test
    fun `NFR-3 viewer decodes at the full target and tiles at their own small one`() =
        runComposeUiTest {
            val loader = FakeMediaLoader()
            setContent { TestScreen(queueState(3).copy(selected = "asset-2"), mediaLoader = loader) }
            waitForIdle()
            val targets = loader.imageRequests.map { it.second }
            // The open item is the full-screen context the LRU is for…
            assertEquals(
                listOf(MediaLoader.FULL_TARGET_PX),
                loader.imageRequests.filter { it.second == MediaLoader.FULL_TARGET_PX }.map { it.second },
            )
            // …and every tile asks for a smaller, tile-sized target (never the LRU's).
            val tileTargets = targets.filter { it != MediaLoader.FULL_TARGET_PX }
            assertEquals(3, tileTargets.size)
            assertEquals(
                true,
                tileTargets.all { it.width in 1 until MediaLoader.FULL_TARGET_PX.width },
            )
        }

    @Test
    fun `NFR-5 delete button opens in-app confirm and does not delete`() =
        runComposeUiTest {
            val f = ScreenFixture(3)
            setContent { TestScreen(f.viewModel) }
            waitForIdle() // the queue collector publishes

            onNodeWithContentDescription("Delete 3 items").performClick()
            waitForIdle()

            // The dialog is up — and it is the only thing that happened.
            onNodeWithText("Delete 3 items?").assertExists()
            assertEquals(emptyList(), f.library.deleteCalls)
            assertEquals(3, f.store.entries.count { it.decision == Decision.DELETE })

            // Cancel closes it; still nothing deleted, queue unchanged (NFR-5).
            onNodeWithContentDescription("Cancel").performClick()
            waitForIdle()
            onNodeWithText("Delete 3 items?").assertDoesNotExist()
            assertEquals(emptyList(), f.library.deleteCalls)
            assertEquals(3, f.store.entries.count { it.decision == Decision.DELETE })
        }

    @Test
    fun `FR-25 dialog Delete commits the whole queue in one call`() =
        runComposeUiTest {
            val f = ScreenFixture(3)
            val successes = mutableListOf<Int>()
            setContent { TestScreen(f.viewModel, onShowDeleteSuccess = { successes.add(it) }) }
            waitForIdle()

            onNodeWithContentDescription("Delete 3 items").performClick()
            waitForIdle()
            onNodeWithContentDescription("Delete").performClick() // the dialog's confirm
            waitForIdle()

            assertEquals(1, f.library.deleteCalls.size)
            assertEquals(
                setOf("asset-1", "asset-2", "asset-3"),
                f.library.deleteCalls
                    .single()
                    .toSet(),
            )
            assertEquals(listOf(3), successes)
            assertEquals(listOf(FakeHaptics.Event.NOTIFICATION_SUCCESS), f.haptics.events)
            // The committed items left the queue: the screen is the empty state.
            onNodeWithText("Nothing left here 🎉").assertExists()
        }

    @Test
    fun `FR-26 success event navigates with the deleted count`() =
        runComposeUiTest {
            val successes = mutableListOf<Int>()
            setContent {
                TestScreen(
                    queueState(4),
                    events = flowOf(QueueEvent.ShowDeleteSuccess(4)),
                    onShowDeleteSuccess = { successes.add(it) },
                )
            }
            waitForIdle()
            assertEquals(listOf(4), successes)
        }

    @Test
    fun `6-7 delete error rides a toast and the queue stays`() =
        runComposeUiTest {
            setContent { TestScreen(queueState(2), events = flowOf(QueueEvent.ShowDeleteError("PhotoKit said no"))) }
            waitForIdle()
            onNodeWithContentDescription("PhotoKit said no").assertExists()
            onNodeWithText("To delete (2)").assertExists()
        }

    @Test
    fun `NFR-7 failed restore rides a toast`() =
        runComposeUiTest {
            setContent { TestScreen(queueState(2), events = flowOf(QueueEvent.RestoreFailed)) }
            waitForIdle()
            onNodeWithContentDescription("Couldn't restore — try again").assertExists()
        }

    @Test
    fun `5-8 empty queue shows the empty state and Back home closes through Home`() =
        runComposeUiTest {
            var homeTaps = 0
            setContent { TestScreen(QueueUiState(), onBackHome = { homeTaps += 1 }) }
            onNodeWithText("Nothing left here 🎉").assertExists()
            onNodeWithContentDescription("Delete 0 items").assertDoesNotExist()
            onNodeWithContentDescription("Back home").performClick()
            waitForIdle()
            assertEquals(1, homeTaps)
        }

    @Test
    fun `NFR-8 grid tiles have content descriptions with date and type`() =
        runComposeUiTest {
            val photoDate = Instant.fromEpochMilliseconds(1615708800_000L)
            val videoDate = Instant.fromEpochMilliseconds(1615795200_000L)
            val tiles =
                listOf(
                    QueueTile("asset-1", MediaType.PHOTO, photoDate, null),
                    QueueTile("asset-2", MediaType.VIDEO, videoDate, 32_000L),
                )
            setContent {
                TestScreen(QueueUiState(items = listOf("asset-1", "asset-2"), tiles = tiles))
            }
            onNodeWithContentDescription("${formatCardDate(photoDate)}, photo").assertExists()
            onNodeWithContentDescription("${formatCardDate(videoDate)}, video, 0:32").assertExists()
        }

    /**
     * The screen under the theme, driven by a fixed [state] with inert
     * defaults — the pattern from [dev.swipe.ui.screens.swipe.SwipeScreenTest].
     */
    @Composable
    private fun TestScreen(
        state: QueueUiState,
        events: Flow<QueueEvent> = flowOf(),
        onIntent: (QueueIntent) -> Unit = {},
        mediaLoader: MediaLoader = FakeMediaLoader(),
        onBack: () -> Unit = {},
        onBackHome: () -> Unit = {},
        onShowDeleteSuccess: (Int) -> Unit = {},
    ) {
        SwipeTheme {
            QueueScreen(
                state = state,
                events = events,
                onIntent = onIntent,
                mediaLoader = mediaLoader,
                onBack = onBack,
                onBackHome = onBackHome,
                onShowDeleteSuccess = onShowDeleteSuccess,
            )
        }
    }

    /**
     * The real [QueueViewModel] over the fakes with [count] queued assets
     * (asset-1 recorded first, so the queue reads asset-[count] … asset-1),
     * collected through the screen. Main is the unconfined test dispatcher so
     * the ViewModel's scope runs inline on the UI test thread.
     */
    private class ScreenFixture(
        count: Int,
    ) {
        val clock = FakeClock()
        val store = FakeDecisionStore(clock)
        val library = FakeMediaLibrary(photos(count))
        val haptics = FakeHaptics()
        val viewModel: QueueViewModel

        init {
            runBlocking {
                for (i in 1..count) {
                    store.record("asset-$i", Decision.DELETE, "session-1")
                }
            }
            Dispatchers.setMain(UnconfinedTestDispatcher())
            viewModel =
                QueueViewModel(
                    commitDeletions = CommitDeletionsUseCase(library, store),
                    pruneQueue = PruneQueueUseCase(library, store),
                    restoreFromQueue = RestoreFromQueueUseCase(store),
                    decisions = store,
                    mediaLibrary = library,
                    haptics = haptics,
                )
        }

        private companion object {
            fun photos(count: Int): List<MediaItem> =
                (1..count).map { i ->
                    MediaItem(
                        id = "asset-$i",
                        type = MediaType.PHOTO,
                        createdAt = Instant.fromEpochMilliseconds(i * 1000L),
                        durationMs = null,
                        widthPx = 120,
                        heightPx = 160,
                        isFavorite = false,
                        isScreenshot = false,
                        burstId = null,
                        isCloudOnly = null,
                    )
                }
        }
    }

    /** Collects the fixture VM's state, like the production container does. */
    @Composable
    private fun TestScreen(
        viewModel: QueueViewModel,
        onShowDeleteSuccess: (Int) -> Unit = {},
    ) {
        SwipeTheme {
            QueueScreen(
                state = viewModel.uiState.collectAsState().value,
                events = viewModel.events,
                onIntent = viewModel::onIntent,
                mediaLoader = FakeMediaLoader(),
                onBack = {},
                onBackHome = {},
                onShowDeleteSuccess = onShowDeleteSuccess,
            )
        }
    }

    @Test
    fun `NFR-8 every screen's clickable nodes have content descriptions`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    TestScreen(queueState(3))
                }
            }
            waitForIdle()
            assertAllClickablesLabeled()
        }
}

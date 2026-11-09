@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.swipe

import app.cash.turbine.test
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.Progress
import dev.swipe.domain.model.ShareResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-32: the share button hands the current card to [dev.swipe.data.MediaSharer]
 * (a [dev.swipe.fakes.FakeMediaSharer] here) and must never record a decision,
 * move the deck index, or touch the undo stack — whether the share sheet opens or
 * the load fails.
 */
class SwipeViewModelShareTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `FR-32 share opens the sheet for the current card and leaves the deck unchanged`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()
            val before = f.viewModel.uiState.value

            f.viewModel.onIntent(SwipeIntent.Share)
            advanceUntilIdle()

            assertEquals(listOf("asset-1"), f.sharer.shareCalls)
            val after = f.viewModel.uiState.value
            assertEquals(before, after)
            assertEquals("asset-1", after.current?.id)
            assertEquals(Progress(reviewed = 0, total = 3), after.progress)
            assertFalse(after.sharing)
            assertNull(after.toast)
            // Nothing persisted: no decision, no resume position (FR-32, NFR-7).
            assertTrue(f.store.recordCalls.isEmpty())
            assertTrue(f.store.entries.isEmpty())
            assertTrue(f.sessionStore.updatePositionCalls.isEmpty())
        }

    @Test
    fun `FR-32 sharing shows a loading state until the media is ready`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            val gate = CompletableDeferred<Unit>()
            f.sharer.gate = gate

            f.viewModel.uiState.test {
                awaitDeckReady()

                f.viewModel.onIntent(SwipeIntent.Share)
                val loading = awaitItem()
                assertTrue(loading.sharing)
                assertEquals("asset-1", loading.current?.id)

                // Single-flight: a second tap while loading starts nothing.
                f.viewModel.onIntent(SwipeIntent.Share)
                advanceUntilIdle()
                assertEquals(listOf("asset-1"), f.sharer.shareCalls)

                gate.complete(Unit)
                val done = awaitItem()
                assertFalse(done.sharing)
                assertEquals("asset-1", done.current?.id)
                assertEquals(0, done.progress.reviewed)
                assertNull(done.toast)
            }
            assertTrue(f.store.recordCalls.isEmpty())
        }

    @Test
    fun `FR-32 a share load failure shows the toast and keeps the deck`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            f.sharer.result = ShareResult.FAILED

            f.viewModel.uiState.test {
                awaitDeckReady()

                f.viewModel.onIntent(SwipeIntent.Share)
                assertTrue(awaitItem().sharing)
                val failed = awaitItem()
                assertEquals(ToastMessage.ShareFailed, failed.toast)
                val settled = if (failed.sharing) awaitItem() else failed
                assertFalse(settled.sharing)
                assertEquals(ToastMessage.ShareFailed, settled.toast)
                assertEquals("asset-1", settled.current?.id)
                assertEquals(0, settled.progress.reviewed)
            }
            assertTrue(f.store.recordCalls.isEmpty())
            assertTrue(f.sessionStore.updatePositionCalls.isEmpty())
        }

    @Test
    fun `FR-32 a sharer that throws counts as a load failure`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(2)
            f.sharer.throwOnShare = IllegalStateException("no window")
            advanceUntilIdle()

            f.viewModel.onIntent(SwipeIntent.Share)
            advanceUntilIdle()

            val state = f.viewModel.uiState.value
            assertEquals(ToastMessage.ShareFailed, state.toast)
            assertFalse(state.sharing)
            assertEquals("asset-1", state.current?.id)
            assertTrue(f.store.recordCalls.isEmpty())
        }

    @Test
    fun `FR-32 share leaves the undo stack untouched`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()
            f.viewModel.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()

            f.viewModel.onIntent(SwipeIntent.Share)
            advanceUntilIdle()
            assertEquals(listOf("asset-2"), f.sharer.shareCalls)
            assertEquals(
                "asset-2",
                f.viewModel.uiState.value.current
                    ?.id,
            )

            // Undo still reverts the Keep, not the share (FR-15).
            f.viewModel.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()
            assertEquals(
                "asset-1",
                f.viewModel.uiState.value.current
                    ?.id,
            )
            assertEquals(listOf(Triple("asset-1", Decision.KEEP, "session-1")), f.store.recordCalls)
            assertFalse(f.viewModel.uiState.value.canUndo)
        }

    @Test
    fun `FR-32 a deck move while the media loads abandons the share`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            f.sharer.gate = CompletableDeferred()
            advanceUntilIdle()

            f.viewModel.onIntent(SwipeIntent.Share)
            advanceUntilIdle()
            assertTrue(f.viewModel.uiState.value.sharing)

            f.viewModel.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()

            assertEquals(1, f.sharer.cancelledCalls)
            val state = f.viewModel.uiState.value
            assertEquals("asset-2", state.current?.id)
            assertFalse(state.sharing)
            assertNull(state.toast)
            // A new share targets the card that is up now.
            f.sharer.gate = null
            f.viewModel.onIntent(SwipeIntent.Share)
            advanceUntilIdle()
            assertEquals(listOf("asset-1", "asset-2"), f.sharer.shareCalls)
        }

    @Test
    fun `FR-32 share with no card is a no-op`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(0)
            advanceUntilIdle()

            f.viewModel.onIntent(SwipeIntent.Share)
            advanceUntilIdle()

            assertTrue(f.sharer.shareCalls.isEmpty())
            assertFalse(f.viewModel.uiState.value.sharing)
        }
}

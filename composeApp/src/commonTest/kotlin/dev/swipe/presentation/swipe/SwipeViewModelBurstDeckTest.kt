// The deck-open behaviour of WP-8.3: FR-6 deck ids whose metadata never resolves
// are dropped, and the loading flag keeps the placeholder up until the deck settles.
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.swipe

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP-8.3 "Fix the empty Bursts deck", ViewModel half (§6.5 step 2, §6.8):
 * a deck built from ids a source fetch enumerated (burst frames among them —
 * [dev.swipe.fakes.FakeMediaLibrary.hiddenItemIds] models ids that later fail to
 * resolve) must drop the unresolvable ids instead of ending on a blank card,
 * and [SwipeUiState.loading] must cover the open until the deck settles.
 *
 * Deck order is asset-1, asset-2, … as in the sibling suites; metadata pages
 * are 20 with a trigger of 5, so a 25-card deck loads its second page once the
 * index reaches 15 (15 decisions in).
 */
class SwipeViewModelBurstDeckTest {
    @Test
    fun `FR-6 loading covers the open until the first card lands`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)

            f.viewModel.uiState.test {
                // The very first emission, before start() has done anything:
                // no card, but loading — the screen shows the placeholder, not
                // the empty state.
                val initial = awaitItem()
                assertTrue(initial.loading)
                assertNull(initial.current)
                assertNull(initial.finished)

                val ready = awaitItem()
                assertFalse(ready.loading)
                assertEquals("asset-1", ready.current?.id)
            }
        }

    @Test
    fun `FR-6 wholly unresolvable deck opens as the honest empty state`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            // Every deck id fails to resolve (the whole burst deck gap): the deck
            // opens empty rather than stuck on a card that can never show.
            f.library.hiddenItemIds += setOf("asset-1", "asset-2", "asset-3")

            f.viewModel.uiState.test {
                val ready = awaitDeckReady()
                assertNull(ready.current)
                assertFalse(ready.loading)
                // Empty-with-no-summary, not finished: nothing was reviewed, so
                // there is no FR-21 summary (the WP-7.3 semantics are unchanged).
                assertNull(ready.finished)
            }
            // And with nothing to resume the row ends right away (FR-20).
            assertEquals(listOf("session-1"), f.sessionStore.endCalls)
        }

    @Test
    fun `FR-6 ids missing on a mid-session page are dropped and the deck carries on`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            // 25 cards: page 1 covers asset-1…asset-20 at open; page 2 loads after
            // the 15th decision. asset-25 was enumerated by the source fetch but
            // never resolves (burst frame outside the default view, or deleted in
            // the Photos app since the deck was built).
            val f = Fixture(25)
            f.library.hiddenItemIds += "asset-25"

            f.viewModel.uiState.test {
                awaitDeckReady()

                // Decide all 24 reviewable cards. Emission k (k = 1…23) names the
                // next card — the deck never shows a blank card and never ends
                // early on the unresolved id; emission 24 carries the summary.
                repeat(23) { k ->
                    f.viewModel.onIntent(f.viewModel.keep())
                    val after = awaitItem()
                    assertEquals("asset-${k + 2}", after.current?.id)
                    assertFalse(after.loading)
                    assertNull(after.finished)
                }
                f.viewModel.onIntent(f.viewModel.keep())
                val last = awaitItem()
                assertNotNull(last.finished)
                assertEquals(24, last.finished.kept)
                // The dropped id shrinks the deck, so the total counts only the
                // cards that could actually be reviewed.
                assertEquals(24, last.progress.total)
            }

            // The unresolvable id was really requested — the drop follows a fetch
            // that answered without it, it is not a silent skip.
            assertTrue(
                f.library.itemsCalls
                    .last()
                    .contains("asset-25"),
            )
            assertEquals(24, f.store.recordCalls.size)
            assertTrue(f.store.recordCalls.none { it.first == "asset-25" })
        }

    @Test
    fun `FR-6 undo still returns the card after an unresolved id was dropped`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(25)
            f.library.hiddenItemIds += "asset-25"

            f.viewModel.uiState.test {
                awaitDeckReady()

                // 15 keeps: the 15th lands index 15, whose ensure pass loads page 2
                // and drops asset-25.
                var latest: SwipeUiState? = null
                repeat(15) {
                    f.viewModel.onIntent(f.viewModel.keep())
                    latest = awaitItem()
                }
                assertEquals("asset-16", latest?.current?.id)

                // Undo pops the 15th keep — its recorded index sits behind the
                // head and is untouched by a drop at the deck's far end (FR-15).
                f.viewModel.onIntent(SwipeIntent.Undo)
                val undone = awaitItem()
                assertEquals("asset-15", undone.current?.id)
                assertTrue(undone.canUndo)
            }
        }

    @Test
    fun `FR-6 a failing metadata page keeps the ids for a retry instead of dropping them`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(25)

            f.viewModel.uiState.test {
                awaitDeckReady()
                repeat(14) {
                    f.viewModel.onIntent(f.viewModel.keep())
                    awaitItem()
                }
                // Arm the failure only now: page 1 (asset-1…20) loaded at open; the
                // 15th decision's page-2 load is the first items() call that fails.
                f.library.failNextItems = IllegalStateException("fetch failed")
                // The page load fails; the decision is already
                // durable, so the card still advances (NFR-7).
                f.viewModel.onIntent(f.viewModel.keep())
                assertEquals("asset-16", awaitItem().current?.id)
            }

            // The next trigger retries the page: asset-25 is requested again, and
            // this time it resolves — nothing was dropped on the failure.
            f.library.itemsCalls.clear()
            f.viewModel.onIntent(f.viewModel.keep())
            advanceUntilIdle()
            assertTrue(
                f.library.itemsCalls
                    .flatten()
                    .contains("asset-25"),
            )
        }
}

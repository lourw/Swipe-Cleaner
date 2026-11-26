@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.swipe

import app.cash.turbine.test
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.DuplicateSetMembership
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import dev.swipe.domain.usecase.FinishSessionUseCase
import dev.swipe.domain.usecase.RecordDecisionUseCase
import dev.swipe.domain.usecase.StartSessionUseCase
import dev.swipe.domain.usecase.UndoDecisionUseCase
import dev.swipe.fakes.FakeAudioSession
import dev.swipe.fakes.FakeClock
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeMediaLibrary
import dev.swipe.fakes.FakeMediaLoader
import dev.swipe.fakes.FakeMediaSharer
import dev.swipe.fakes.FakeSessionSkips
import dev.swipe.fakes.FakeSessionStore
import dev.swipe.fakes.FakeSettingsStore
import dev.swipe.fakes.FakeVideoPreloader
import dev.swipe.fakes.buildDeckUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * FR-35 on the assembled deck: every Likely Duplicates card carries its place
 * in the set (the label the scrim renders), decided members still count toward
 * the set size, and a resumed session reads the same numbers. The shared
 * [Fixture] hardwires a screenshots source, so these tests wire the ViewModel
 * over a Likely Duplicates source themselves.
 */
class SwipeViewModelDuplicateSetTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    /**
     * A 4-photo burst — all within 2 s with equal dimensions, so one set —
     * answered newest-first by the fake (§6.4): set order d, c, b, a.
     */
    private fun burst(): List<MediaItem> =
        listOf(
            photo("a", 0L),
            photo("b", 500L),
            photo("c", 1_000L),
            photo("d", 1_500L),
        )

    private fun viewModel(
        library: FakeMediaLibrary,
        store: FakeDecisionStore,
        sessionStore: FakeSessionStore,
    ) = SwipeViewModel(
        source = DUPLICATES_SOURCE,
        typeFilter = MediaTypeFilter.ALL,
        startSession = StartSessionUseCase(buildDeckUseCase(library, store), store, sessionStore),
        finishSession = FinishSessionUseCase(store, sessionStore),
        recordDecision = RecordDecisionUseCase(store),
        undoDecision = UndoDecisionUseCase(store),
        mediaLibrary = library,
        mediaLoader = FakeMediaLoader(),
        videoPreloader = FakeVideoPreloader(),
        mediaSharer = FakeMediaSharer(),
        mediaHider = library,
        audioSession = FakeAudioSession(),
        settings = FakeSettingsStore(),
        decisions = store,
        seedProvider = { SEED },
    )

    @Test
    fun `FR-35 duplicate cards carry their place in the set`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(burst())
            val store = FakeDecisionStore(FakeClock(), FakeSessionSkips())
            val viewModel = viewModel(library, store, FakeSessionStore(FakeClock(), FakeSessionSkips()))

            viewModel.uiState.test {
                awaitItem() // pre-deck
                val ready = awaitItem()
                // Deck order (FR-6, newest first): d, then c; one set of 4.
                val current = checkNotNull(ready.current)
                val next = checkNotNull(ready.next)
                assertEquals("d", current.id)
                assertEquals("c", next.id)
                assertEquals(
                    DuplicateSetMembership(listOf("d", "c", "b", "a"), position = 0),
                    current.duplicateSet,
                )
                assertEquals(
                    DuplicateSetMembership(listOf("d", "c", "b", "a"), position = 1),
                    next.duplicateSet,
                )
            }
            viewModel.onCleared()
        }

    @Test
    fun `FR-35 a decided member still counts toward the set size`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(burst())
            val store = FakeDecisionStore(FakeClock(), FakeSessionSkips())
            store.record("c", Decision.KEEP, "old-session")
            val viewModel = viewModel(library, store, FakeSessionStore(FakeClock(), FakeSessionSkips()))

            viewModel.uiState.test {
                awaitItem()
                val ready = awaitItem()
                // Deck lost `c`, the set did not: b is the THIRD of 4 (§6.5).
                val current = checkNotNull(ready.current)
                val next = checkNotNull(ready.next)
                assertEquals("d", current.id)
                assertEquals("b", next.id)
                assertEquals(
                    DuplicateSetMembership(listOf("d", "c", "b", "a"), position = 2),
                    next.duplicateSet,
                )
            }
            viewModel.onCleared()
        }

    @Test
    fun `FR-35 a resumed session reads the same set numbers`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val clock = FakeClock()
            val skips = FakeSessionSkips()
            val library = FakeMediaLibrary(burst())
            val store = FakeDecisionStore(clock, skips)
            val sessions = FakeSessionStore(clock, skips)

            // Decide the first card, then "process death": a fresh ViewModel
            // over the same stores resumes it (FR-20).
            val first = viewModel(library, store, sessions)
            advanceUntilIdle()
            first.onIntent(first.delete())
            advanceUntilIdle()
            assertEquals(
                "c",
                first.uiState.value.current
                    ?.id,
            ) // deck advanced past `d`

            val resumed = viewModel(library, store, sessions)
            advanceUntilIdle()
            val s = resumed.uiState.value
            assertEquals("c", s.current?.id)
            // Set membership survives resume: still 4 members, and `c` keeps
            // its place, not a renumbered 3-set (§6.5, FR-35).
            assertEquals(
                DuplicateSetMembership(listOf("d", "c", "b", "a"), position = 1),
                s.current?.duplicateSet,
            )
            assertEquals(1, sessions.createCalls.size)
        }

    @Test
    fun `FR-35 cards from an ordinary source carry no label`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            // Screenshots source (like the shared Fixture): the same four
            // photos, but the deck is not the duplicate pass, so no labels.
            val library =
                FakeMediaLibrary(
                    burst().map { it.copy(isScreenshot = true) },
                )
            val store = FakeDecisionStore(FakeClock(), FakeSessionSkips())
            val sessions = FakeSessionStore(FakeClock(), FakeSessionSkips())
            val viewModel =
                SwipeViewModel(
                    source = SOURCE,
                    typeFilter = MediaTypeFilter.ALL,
                    startSession = StartSessionUseCase(buildDeckUseCase(library, store), store, sessions),
                    finishSession = FinishSessionUseCase(store, sessions),
                    recordDecision = RecordDecisionUseCase(store),
                    undoDecision = UndoDecisionUseCase(store),
                    mediaLibrary = library,
                    mediaLoader = FakeMediaLoader(),
                    videoPreloader = FakeVideoPreloader(),
                    mediaSharer = FakeMediaSharer(),
                    mediaHider = library,
                    audioSession = FakeAudioSession(),
                    settings = FakeSettingsStore(),
                    decisions = store,
                    seedProvider = { SEED },
                )

            viewModel.uiState.test {
                awaitItem()
                val ready = awaitItem()
                val current = checkNotNull(ready.current) // deck is loaded
                assertEquals("d", current.id)
                assertEquals(null, current.duplicateSet)
                assertEquals(null, ready.next?.duplicateSet)
            }
            viewModel.onCleared()
        }

    private fun photo(
        id: String,
        createdAtMs: Long,
    ) = MediaItem(
        id = id,
        type = MediaType.PHOTO,
        createdAt = Instant.fromEpochMilliseconds(createdAtMs),
        durationMs = null,
        widthPx = 100,
        heightPx = 100,
        isFavorite = false,
        isScreenshot = false,
        burstId = null,
        isCloudOnly = null,
    )

    private companion object {
        val DUPLICATES_SOURCE: DeckSource = DeckSource.Smart(SmartFilter.LIKELY_DUPLICATES)
    }
}

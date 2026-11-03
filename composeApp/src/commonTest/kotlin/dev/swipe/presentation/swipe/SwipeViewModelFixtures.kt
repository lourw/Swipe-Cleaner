@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.swipe

import app.cash.turbine.ReceiveTurbine
import dev.swipe.data.DecisionStore
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import dev.swipe.domain.usecase.BuildDeckUseCase
import dev.swipe.domain.usecase.RecordDecisionUseCase
import dev.swipe.domain.usecase.UndoDecisionUseCase
import dev.swipe.fakes.FakeClock
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeMediaLibrary
import dev.swipe.fakes.FakeMediaLoader
import dev.swipe.fakes.FakeSessionSkips
import dev.swipe.fakes.FakeSessionStore
import kotlin.time.Instant

/** The fixed shuffle seed; unused by the source, stored on the session (§6.5 step 1). */
internal const val SEED = 1234L

/** A non-shuffled source so the deck order is deterministic (§6.5). */
internal val SOURCE: DeckSource = DeckSource.Smart(SmartFilter.SCREENSHOTS)

/** [count] screenshots; asset-1 newest, so deck order is asset-1, asset-2, … */
internal fun photos(
    count: Int,
    favoriteIds: Set<String> = emptySet(),
): List<MediaItem> =
    (1..count).map { i ->
        MediaItem(
            id = "asset-$i",
            type = MediaType.PHOTO,
            createdAt = Instant.fromEpochMilliseconds((count - i + 1L) * 1000L),
            durationMs = null,
            widthPx = 120,
            heightPx = 160,
            isFavorite = "asset-$i" in favoriteIds,
            isScreenshot = true,
            burstId = null,
            isCloudOnly = null,
        )
    }

/** Wires the ViewModel over the shared fakes; deck order is asset-1 … asset-[itemCount]. */
internal class Fixture(
    itemCount: Int,
    seed: Long = SEED,
    favoriteIds: Set<String> = emptySet(),
) {
    val clock = FakeClock()
    val skips = FakeSessionSkips()
    val store = FakeDecisionStore(clock, skips)
    val sessionStore = FakeSessionStore(clock, skips)
    val library = FakeMediaLibrary(photos(itemCount, favoriteIds))
    val loader = FakeMediaLoader()
    val viewModel =
        SwipeViewModel(
            source = SOURCE,
            typeFilter = MediaTypeFilter.ALL,
            buildDeck = BuildDeckUseCase(library, store),
            recordDecision = RecordDecisionUseCase(store),
            undoDecision = UndoDecisionUseCase(store),
            mediaLibrary = library,
            mediaLoader = loader,
            decisions = store,
            sessionStore = sessionStore,
            seedProvider = { seed },
        )
}

/**
 * Wraps a [DecisionStore] and records the value of [reviewedNow] at the moment
 * [record] / [remove] is called — the strict "persisted before the index changes"
 * probe (NFR-7), mirrored for undo (FR-15): if the ViewModel moved first, the
 * snapshot would show it.
 */
internal class OrderRecordingDecisionStore(
    private val delegate: DecisionStore,
    private val reviewedNow: () -> Int,
) : DecisionStore by delegate {
    /** The reviewed count seen by each [record] call, oldest first. */
    val reviewedAtRecord = mutableListOf<Int>()

    /** The reviewed count seen by each [remove] call, oldest first. */
    val reviewedAtRemove = mutableListOf<Int>()

    override suspend fun record(
        id: String,
        decision: Decision,
        sessionId: String,
    ) {
        reviewedAtRecord += reviewedNow()
        delegate.record(id, decision, sessionId)
    }

    override suspend fun remove(
        id: String,
        sessionId: String,
    ) {
        reviewedAtRemove += reviewedNow()
        delegate.remove(id, sessionId)
    }
}

/** Consume the initial (pre-deck) emission and return the first deck-built state. */
internal suspend fun ReceiveTurbine<SwipeUiState>.awaitDeckReady(): SwipeUiState {
    awaitItem()
    return awaitItem()
}

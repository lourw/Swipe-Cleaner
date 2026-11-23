package dev.swipe.fakes

import dev.swipe.data.DecisionStore
import dev.swipe.data.MediaLibrary
import dev.swipe.data.ShuffleExclusionStore
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.usecase.BuildDeckUseCase
import dev.swipe.domain.usecase.DeckMembership
import dev.swipe.domain.usecase.LoadSourcesUseCase
import dev.swipe.presentation.albums.AlbumsViewModel
import dev.swipe.presentation.home.HomeViewModel

/**
 * Test-only wiring for the FR-37 members of the graph (WP-8.10): the shared
 * [DeckMembership] path and the exclusion store, defaulted to fresh fakes so
 * tests that do not care about exclusions read like before. A test that drives
 * exclusions passes one [FakeShuffleExclusionStore] and shares it with the
 * screens under test.
 */
fun deckMembership(
    library: MediaLibrary,
    exclusions: ShuffleExclusionStore = FakeShuffleExclusionStore(),
): DeckMembership = DeckMembership(library, exclusions)

fun buildDeckUseCase(
    library: MediaLibrary,
    decisions: DecisionStore,
    exclusions: ShuffleExclusionStore = FakeShuffleExclusionStore(),
): BuildDeckUseCase = BuildDeckUseCase(deckMembership(library, exclusions), decisions)

fun loadSourcesUseCase(
    library: MediaLibrary,
    decisions: DecisionStore,
    exclusions: ShuffleExclusionStore = FakeShuffleExclusionStore(),
): LoadSourcesUseCase = LoadSourcesUseCase(library, decisions, deckMembership(library, exclusions))

fun homeViewModel(
    library: MediaLibrary,
    decisions: DecisionStore,
    exclusions: ShuffleExclusionStore = FakeShuffleExclusionStore(),
): HomeViewModel = HomeViewModel(library, decisions, loadSourcesUseCase(library, decisions, exclusions), exclusions)

fun albumsViewModel(
    typeFilter: MediaTypeFilter,
    library: MediaLibrary,
    decisions: DecisionStore,
    exclusions: ShuffleExclusionStore = FakeShuffleExclusionStore(),
): AlbumsViewModel = AlbumsViewModel(typeFilter, library, decisions, exclusions, deckMembership(library, exclusions))

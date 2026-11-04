package dev.swipe.di

import dev.swipe.data.DecisionStore
import dev.swipe.data.SessionStore
import dev.swipe.data.db.SqlDecisionStore
import dev.swipe.data.db.SqlSessionStore
import dev.swipe.domain.usecase.BuildDeckUseCase
import dev.swipe.domain.usecase.CommitDeletionsUseCase
import dev.swipe.domain.usecase.PruneQueueUseCase
import dev.swipe.domain.usecase.RecordDecisionUseCase
import dev.swipe.domain.usecase.RestoreFromQueueUseCase
import dev.swipe.domain.usecase.UndoDecisionUseCase
import dev.swipe.presentation.permission.PermissionViewModel
import dev.swipe.presentation.queue.QueueViewModel
import dev.swipe.presentation.swipe.SwipeViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Platform-independent bindings: use cases, ViewModels, the SQLDelight stores (WP-1.3).
 * The `SwipeDatabase` itself comes from the platform module — only its driver touches
 * platform APIs (AGENTS.md rule 5).
 */
val commonModule: Module =
    module {
        // `single<Clock>` explicitly: `Clock.System` alone would register the concrete
        // companion-object subtype, so `get<Clock>()` would not find it.
        single<Clock> { Clock.System }
        single<DecisionStore> { SqlDecisionStore(database = get(), clock = get()) }
        single<SessionStore> { SqlSessionStore(database = get(), clock = get()) }

        // Permission flow (WP-1.7); its data interfaces bind in the platform module.
        viewModel { PermissionViewModel(mediaLibrary = get(), settingsStore = get(), systemActions = get()) }

        // Deck use cases (WP-1.6), bound for the Swipe pipeline (WP-1.8).
        single { BuildDeckUseCase(library = get(), decisions = get()) }
        single { RecordDecisionUseCase(decisions = get()) }
        single { UndoDecisionUseCase(decisions = get()) }

        // Review-queue use cases (WP-3.1). CommitDeletionsUseCase is the only caller
        // of MediaLibrary.delete (NFR-5); its confirmation token is constructible
        // only inside QueueViewModel, after the in-app confirm (hard rule 1).
        single { CommitDeletionsUseCase(library = get(), decisions = get()) }
        single { PruneQueueUseCase(library = get(), decisions = get()) }
        single { RestoreFromQueueUseCase(decisions = get()) }

        // The Swipe deck (WP-1.8). Source and filter are route parameters: WP-1.9
        // resolves it with `koinViewModel { parameters { parametersOf(source, typeFilter) } }`.
        viewModel { params ->
            SwipeViewModel(
                source = params.get(),
                typeFilter = params.get(),
                buildDeck = get(),
                recordDecision = get(),
                undoDecision = get(),
                mediaLibrary = get(),
                mediaLoader = get(),
                videoPreloader = get(),
                decisions = get(),
                sessionStore = get(),
                seedProvider = { Random.nextLong() },
            )
        }

        // The Review Queue (WP-3.1). No route parameters: the queue is global state
        // from DecisionStore.deleteQueue, and the Queue route carries no arguments.
        viewModel {
            QueueViewModel(
                commitDeletions = get(),
                pruneQueue = get(),
                restoreFromQueue = get(),
                decisions = get(),
                mediaLibrary = get(),
                haptics = get(),
            )
        }
    }

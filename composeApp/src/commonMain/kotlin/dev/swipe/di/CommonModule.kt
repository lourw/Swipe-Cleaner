package dev.swipe.di

import dev.swipe.data.DecisionStore
import dev.swipe.data.SessionStore
import dev.swipe.data.db.SqlDecisionStore
import dev.swipe.data.db.SqlSessionStore
import dev.swipe.presentation.permission.PermissionViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * Platform-independent bindings: use cases, ViewModels, the SQLDelight stores (WP-1.3).
 * The `SwipeDatabase` itself comes from the platform module — only its driver touches
 * platform APIs (AGENTS.md rule 5).
 */
val commonModule: Module =
    module {
        single { Clock.System }
        single<DecisionStore> { SqlDecisionStore(database = get(), clock = get()) }
        single<SessionStore> { SqlSessionStore(database = get(), clock = get()) }

        // Permission flow (WP-1.7); its data interfaces bind in the platform module.
        viewModel { PermissionViewModel(mediaLibrary = get(), settingsStore = get(), systemActions = get()) }
    }

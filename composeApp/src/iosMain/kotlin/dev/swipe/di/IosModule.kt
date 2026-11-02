package dev.swipe.di

import dev.swipe.db.SwipeDatabase
import dev.swipe.platform.DatabaseDriverFactory
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOS implementations of the `data/` interfaces (REQUIREMENTS §6.1). The driver opens
 * eagerly when Koin starts (and [DatabaseDriverFactory.create] runs one query), so the
 * database file and schema exist from launch on (§7, FR-20).
 */
val iosModule: Module =
    module {
        single(createdAtStart = true) { DatabaseDriverFactory().create() }
        single { SwipeDatabase(get()) }
    }

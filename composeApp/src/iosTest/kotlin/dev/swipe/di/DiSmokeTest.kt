package dev.swipe.di

import dev.swipe.data.DecisionStore
import dev.swipe.data.MediaLibrary
import dev.swipe.data.MediaLoader
import dev.swipe.data.SessionStore
import dev.swipe.data.SettingsStore
import dev.swipe.data.SystemActions
import dev.swipe.domain.usecase.BuildDeckUseCase
import dev.swipe.domain.usecase.RecordDecisionUseCase
import dev.swipe.domain.usecase.UndoDecisionUseCase
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Clock

/**
 * Resolves every binding the running app needs. The WP-1.9 on-device check found that
 * `single { Clock.System }` registered the companion subtype instead of [Clock], which
 * no logic test could see (FR-20); this test is the regression guard for that class of
 * wiring bug — the Koin graph must resolve end to end on the real platform.
 */
class DiSmokeTest {
    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
    }

    @Test
    fun `koin graph resolves every app binding`() {
        startKoin { modules(commonModule, iosModule) }
        val koin = KoinPlatform.getKoin()
        koin.get<Clock>()
        koin.get<DecisionStore>()
        koin.get<SessionStore>()
        koin.get<MediaLibrary>()
        koin.get<MediaLoader>()
        koin.get<SettingsStore>()
        koin.get<SystemActions>()
        koin.get<BuildDeckUseCase>()
        koin.get<RecordDecisionUseCase>()
        koin.get<UndoDecisionUseCase>()
    }
}

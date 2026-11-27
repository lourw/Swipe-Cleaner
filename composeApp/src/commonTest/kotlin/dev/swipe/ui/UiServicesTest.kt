// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(
    ExperimentalComposeUiApi::class,
    ExperimentalTestApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.swipe.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.compose.rememberNavController
import dev.swipe.HomeDestination
import dev.swipe.data.NoPerfTrace
import dev.swipe.data.PerfSpan
import dev.swipe.data.PerfTrace
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.fakes.FakeHaptics
import dev.swipe.fakes.FakeMediaLoader
import dev.swipe.fakes.FakePerfTrace
import dev.swipe.presentation.permission.PermissionScreen
import dev.swipe.presentation.permission.PermissionUiState
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * T2 (AC1, §6.1): the sanctioned UI services reach composables through
 * [ProvideUiServices] rather than `koinInject()`. The cold-start test drives
 * the real [HomeDestination] with no `perfTrace` argument at all: it ends
 * [PerfSpan.COLD_START_HOME] only if the [LocalPerfTrace] provided around it
 * is what the destination reads. The ViewModel is never mounted while access
 * is NOT_DETERMINED (HomeDestinations.kt), so no Koin is needed.
 */
class UiServicesTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `NFR-4 Home ends the cold-start span through LocalPerfTrace`() =
        runComposeUiTest {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val trace = FakePerfTrace()
            val state =
                MutableStateFlow(
                    PermissionUiState(
                        startScreen = PermissionScreen.HOME,
                        access = LibraryAccess.NOT_DETERMINED,
                    ),
                )
            setContent {
                SwipeTheme {
                    ProvideUiServices(FakeMediaLoader(), FakeHaptics(), trace) {
                        HomeDestination(rememberNavController(), state, {})
                    }
                }
            }
            waitForIdle()
            assertEquals(listOf(FakePerfTrace.Call.END to PerfSpan.COLD_START_HOME), trace.calls)
        }

    @Test
    fun `AC1 LocalPerfTrace defaults to NoPerfTrace`() =
        runComposeUiTest {
            var current: PerfTrace? = null
            setContent {
                current = LocalPerfTrace.current
            }
            assertSame(NoPerfTrace, current)
        }
}

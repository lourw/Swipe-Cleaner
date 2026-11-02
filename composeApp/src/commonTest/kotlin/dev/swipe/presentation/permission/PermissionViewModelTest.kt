@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.permission

import app.cash.turbine.test
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.fakes.FakeMediaLibrary
import dev.swipe.fakes.FakeSettingsStore
import dev.swipe.fakes.FakeSystemActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The permission flow (FR-1–3, §5.8 screen map): start routing, the readWrite request,
 * the limited banner count (FR-2), the blocked screen with Open Settings (FR-3), and
 * the foreground re-check. `viewModelScope` runs on Main, so every test swaps in a
 * [StandardTestDispatcher] first.
 */
class PermissionViewModelTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `FR-1 first launch shows onboarding then requests readWrite`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(4), access = LibraryAccess.NOT_DETERMINED)
            library.nextRequestAuthorizationResult = LibraryAccess.FULL
            val settings = FakeSettingsStore()

            val viewModel = PermissionViewModel(library, settings, FakeSystemActions())

            viewModel.uiState.test {
                assertNull(awaitItem().startScreen) // splash while the first read is in flight
                assertEquals(PermissionScreen.ONBOARDING, awaitItem().startScreen)

                viewModel.onIntent(PermissionIntent.RequestAccess)

                val home = awaitItem()
                assertEquals(PermissionScreen.HOME, home.startScreen)
                assertEquals(LibraryAccess.FULL, home.access)
            }
            // The button fired one PHAccessLevel.readWrite request (FR-1) and recorded the visit.
            assertEquals(1, library.requestAuthorizationCalls.size)
            assertTrue("onboardingSeen=true" in settings.setCalls)
        }

    @Test
    fun `FR-2 limited access shows banner with item count`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(7), access = LibraryAccess.LIMITED)
            val settings = FakeSettingsStore()
            settings.setOnboardingSeen(true)

            val viewModel = PermissionViewModel(library, settings, FakeSystemActions())

            viewModel.uiState.test {
                awaitItem() // splash
                val home = awaitItem()
                assertEquals(PermissionScreen.HOME, home.startScreen)
                assertEquals(LibraryAccess.LIMITED, home.access)
                assertEquals(7, home.limitedCount)
            }
            // N is assetIds(All, ALL).size — an ids-only count, no metadata (FR-2, NFR-4).
            val expectedCalls: List<Pair<DeckSource, MediaTypeFilter>> = listOf(DeckSource.All to MediaTypeFilter.ALL)
            assertEquals(expectedCalls, library.assetIdsCalls)
        }

    @Test
    fun `FR-3 denied access routes to blocking screen and Open Settings calls system action`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(2), access = LibraryAccess.DENIED)
            val actions = FakeSystemActions()

            val viewModel = PermissionViewModel(library, FakeSettingsStore(), actions)

            viewModel.uiState.test {
                awaitItem() // splash
                val denied = awaitItem()
                assertEquals(PermissionScreen.DENIED, denied.startScreen)
                assertEquals(LibraryAccess.DENIED, denied.access)
                assertNull(denied.limitedCount)
            }

            viewModel.onIntent(PermissionIntent.OpenSettings)
            assertEquals(1, actions.openSettingsCalls)
        }

    @Test
    fun `FR-1 returning user with access skips onboarding`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(5), access = LibraryAccess.FULL)
            val settings = FakeSettingsStore()
            settings.setOnboardingSeen(true)

            val viewModel = PermissionViewModel(library, settings, FakeSystemActions())

            viewModel.uiState.test {
                awaitItem() // splash
                val home = awaitItem()
                assertEquals(PermissionScreen.HOME, home.startScreen)
                assertEquals(LibraryAccess.FULL, home.access)
                assertNull(home.limitedCount)
            }
            // A returning user is never prompted again (FR-1).
            assertEquals(0, library.requestAuthorizationCalls.size)
        }

    @Test
    fun `FR-3 foreground recheck leaves Denied for Home once access is granted`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(2), access = LibraryAccess.DENIED)
            val settings = FakeSettingsStore()
            // Reaching Denied through the app means the Allow button was tapped, which
            // records the visit; only Settings-side decisions could leave it unset.
            settings.setOnboardingSeen(true)

            val viewModel = PermissionViewModel(library, settings, FakeSystemActions())

            viewModel.uiState.test {
                awaitItem() // splash
                assertEquals(PermissionScreen.DENIED, awaitItem().startScreen)

                // The user grants access in Settings and returns (FR-3 way back).
                library.access = LibraryAccess.FULL
                viewModel.onIntent(PermissionIntent.Recheck)

                assertEquals(PermissionScreen.HOME, awaitItem().startScreen)
            }
        }

    @Test
    fun `FR-2 limited banner manage opens the limited picker`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(3), access = LibraryAccess.LIMITED)
            val settings = FakeSettingsStore()
            settings.setOnboardingSeen(true)
            val actions = FakeSystemActions()

            val viewModel = PermissionViewModel(library, settings, actions)

            viewModel.uiState.test {
                awaitItem() // splash
                assertEquals(3, awaitItem().limitedCount)

                viewModel.onIntent(PermissionIntent.ManageLimitedAccess)
            }
            assertEquals(1, actions.presentLimitedLibraryPickerCalls)
        }

    private fun photoItems(count: Int): List<MediaItem> =
        (1..count).map { index ->
            MediaItem(
                id = "asset-$index",
                type = MediaType.PHOTO,
                createdAt = null,
                durationMs = null,
                widthPx = 120,
                heightPx = 160,
                isFavorite = false,
                isScreenshot = false,
                burstId = null,
                isCloudOnly = null,
            )
        }
}

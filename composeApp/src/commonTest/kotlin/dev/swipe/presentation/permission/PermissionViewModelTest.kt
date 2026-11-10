@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.permission

import app.cash.turbine.test
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.fakes.FakeMediaLibrary
import dev.swipe.fakes.FakeSystemActions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The permission flow (FR-1–3, §5.8 screen map): every outcome routes to Home
 * (FR-1 — there is no intro screen), the launch prompt fires automatically once
 * while access is `NOT_DETERMINED` and never while one is in flight, the limited
 * banner count (FR-2), the blocked screen with Open Settings (FR-3), and the
 * foreground re-check. `viewModelScope` runs on Main, so every test swaps in a
 * [StandardTestDispatcher] first.
 */
class PermissionViewModelTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `FR-1 first launch opens Home and auto-requests readWrite once`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(4), access = LibraryAccess.NOT_DETERMINED)
            library.nextRequestAuthorizationResult = LibraryAccess.FULL

            val viewModel = PermissionViewModel(library, FakeSystemActions())

            viewModel.uiState.test {
                assertNull(awaitItem().startScreen) // splash while the first read is in flight
                // Home is on screen immediately, behind the prompt (FR-1).
                val pending = awaitItem()
                assertEquals(PermissionScreen.HOME, pending.startScreen)
                assertEquals(LibraryAccess.NOT_DETERMINED, pending.access)
                assertNull(pending.limitedCount)

                // The prompt is answered with Allow Full Access.
                val home = awaitItem()
                assertEquals(PermissionScreen.HOME, home.startScreen)
                assertEquals(LibraryAccess.FULL, home.access)
            }
            // One automatic PHAccessLevel.readWrite request per launch (FR-1).
            assertEquals(1, library.requestAuthorizationCalls.size)
        }

    @Test
    fun `FR-1 no second request while one is in flight`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(4), access = LibraryAccess.NOT_DETERMINED)
            library.nextRequestAuthorizationResult = LibraryAccess.FULL
            val gate = CompletableDeferred<Unit>()
            library.requestAuthorizationGate = gate

            val viewModel = PermissionViewModel(library, FakeSystemActions())

            viewModel.uiState.test {
                awaitItem() // splash
                val pending = awaitItem()
                assertEquals(PermissionScreen.HOME, pending.startScreen)
                assertEquals(LibraryAccess.NOT_DETERMINED, pending.access)

                // The user backgrounds the app with the prompt showing and
                // returns: re-checks must not stack a second prompt (FR-1).
                repeat(3) { viewModel.onIntent(PermissionIntent.Recheck) }
                advanceUntilIdle()
                assertEquals(1, library.requestAuthorizationCalls.size)

                // The prompt is finally answered.
                gate.complete(Unit)
                val home = awaitItem()
                assertEquals(PermissionScreen.HOME, home.startScreen)
                assertEquals(LibraryAccess.FULL, home.access)
            }
            assertEquals(1, library.requestAuthorizationCalls.size)
        }

    @Test
    fun `FR-1 Don't Allow routes to the denied screen`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(2), access = LibraryAccess.NOT_DETERMINED)
            library.nextRequestAuthorizationResult = LibraryAccess.DENIED

            val viewModel = PermissionViewModel(library, FakeSystemActions())

            viewModel.uiState.test {
                awaitItem() // splash
                assertEquals(PermissionScreen.HOME, awaitItem().startScreen) // Home behind the prompt
                val denied = awaitItem()
                assertEquals(PermissionScreen.DENIED, denied.startScreen)
                assertEquals(LibraryAccess.DENIED, denied.access)
                assertNull(denied.limitedCount)
            }
            assertEquals(1, library.requestAuthorizationCalls.size)
        }

    @Test
    fun `FR-1 Limit Access routes to Home with the banner count`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(7), access = LibraryAccess.NOT_DETERMINED)
            library.nextRequestAuthorizationResult = LibraryAccess.LIMITED

            val viewModel = PermissionViewModel(library, FakeSystemActions())

            viewModel.uiState.test {
                awaitItem() // splash
                assertEquals(PermissionScreen.HOME, awaitItem().startScreen) // Home behind the prompt
                val home = awaitItem()
                assertEquals(PermissionScreen.HOME, home.startScreen)
                assertEquals(LibraryAccess.LIMITED, home.access)
                assertEquals(7, home.limitedCount)
            }
            assertEquals(1, library.requestAuthorizationCalls.size)
            // N is assetIds(All, ALL).size — an ids-only count, no metadata (FR-2, NFR-4).
            val expectedCalls: List<Pair<DeckSource, MediaTypeFilter>> = listOf(DeckSource.All to MediaTypeFilter.ALL)
            assertEquals(expectedCalls, library.assetIdsCalls)
        }

    @Test
    fun `FR-2 limited access shows banner with item count`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(7), access = LibraryAccess.LIMITED)

            val viewModel = PermissionViewModel(library, FakeSystemActions())

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

            val viewModel = PermissionViewModel(library, actions)

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
    fun `FR-1 returning user is never prompted`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(5), access = LibraryAccess.FULL)

            val viewModel = PermissionViewModel(library, FakeSystemActions())

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

            val viewModel = PermissionViewModel(library, FakeSystemActions())

            viewModel.uiState.test {
                awaitItem() // splash
                assertEquals(PermissionScreen.DENIED, awaitItem().startScreen)

                // The user grants access in Settings and returns (FR-3 way back).
                library.access = LibraryAccess.FULL
                viewModel.onIntent(PermissionIntent.Recheck)

                assertEquals(PermissionScreen.HOME, awaitItem().startScreen)
            }
            // The re-check reads authorization; it never prompts (FR-1).
            assertEquals(0, library.requestAuthorizationCalls.size)
        }

    @Test
    fun `FR-2 limited banner manage opens the limited picker`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(3), access = LibraryAccess.LIMITED)
            val actions = FakeSystemActions()

            val viewModel = PermissionViewModel(library, actions)

            viewModel.uiState.test {
                awaitItem() // splash
                assertEquals(3, awaitItem().limitedCount)

                viewModel.onIntent(PermissionIntent.ManageLimitedAccess)
            }
            assertEquals(1, actions.presentLimitedLibraryPickerCalls)
        }

    @Test
    fun `FR-2 a library change refreshes the limited banner count`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(3), access = LibraryAccess.LIMITED)

            val viewModel = PermissionViewModel(library, FakeSystemActions())

            viewModel.uiState.test {
                awaitItem() // splash
                assertEquals(3, awaitItem().limitedCount)

                // The user adds items in the system limited-library picker: the
                // library changes and PhotoKit fires its observer (WP-3.2).
                library.items.addAll(photoItems(2).map { it.copy(id = "extra-${it.id}") })
                library.notifyChange()

                val refreshed = awaitItem()
                assertEquals(5, refreshed.limitedCount)
                assertEquals(PermissionScreen.HOME, refreshed.startScreen)
            }
            // Both counts came from ids-only fetches (NFR-4).
            val expectedCalls: List<Pair<DeckSource, MediaTypeFilter>> =
                listOf(DeckSource.All to MediaTypeFilter.ALL, DeckSource.All to MediaTypeFilter.ALL)
            assertEquals(expectedCalls, library.assetIdsCalls)
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

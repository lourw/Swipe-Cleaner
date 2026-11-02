// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.swipe

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.data.MediaLoader
import dev.swipe.domain.model.MediaType
import dev.swipe.fakes.FakeMediaLoader
import dev.swipe.presentation.swipe.CardState
import dev.swipe.presentation.swipe.SwipeIntent
import dev.swipe.presentation.swipe.SwipeUiState
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WP-1.9 deck screen: the three decision buttons drive the intents (FR-12–14),
 * every button carries its VoiceOver label (NFR-8), and each on-screen card
 * collects exactly one `image()` flow at the shared full-target size (NFR-1,
 * NFR-3, WP-1.5 carry-over).
 */
class SwipeScreenTest {
    private fun card(id: String) =
        CardState(
            id = id,
            type = MediaType.PHOTO,
            createdAt = null,
            durationMs = null,
            isFavorite = false,
        )

    private fun deckState() = SwipeUiState(current = card("current-id"), next = card("next-id"))

    @Test
    fun `FR-12 keep button sends the Keep intent`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            setContent {
                SwipeTheme {
                    SwipeScreen(state = deckState(), onIntent = { intents.add(it) }, mediaLoader = FakeMediaLoader())
                }
            }
            onNodeWithContentDescription("Keep").performClick()
            assertEquals(listOf<SwipeIntent>(SwipeIntent.Keep), intents)
        }

    @Test
    fun `FR-13 delete button sends the Delete intent`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            setContent {
                SwipeTheme {
                    SwipeScreen(state = deckState(), onIntent = { intents.add(it) }, mediaLoader = FakeMediaLoader())
                }
            }
            onNodeWithContentDescription("Delete").performClick()
            assertEquals(listOf<SwipeIntent>(SwipeIntent.Delete), intents)
        }

    @Test
    fun `FR-14 skip button sends the Skip intent`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            setContent {
                SwipeTheme {
                    SwipeScreen(state = deckState(), onIntent = { intents.add(it) }, mediaLoader = FakeMediaLoader())
                }
            }
            onNodeWithContentDescription("Skip").performClick()
            assertEquals(listOf<SwipeIntent>(SwipeIntent.Skip), intents)
        }

    @Test
    fun `NFR-8 every decision button exposes its verdict label`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    SwipeScreen(state = deckState(), onIntent = {}, mediaLoader = FakeMediaLoader())
                }
            }
            onNodeWithContentDescription("Keep").assertExists()
            onNodeWithContentDescription("Delete").assertExists()
            onNodeWithContentDescription("Skip").assertExists()
        }

    @Test
    fun `NFR-1 current and next cards collect their images at the full target`() =
        runComposeUiTest {
            val loader = FakeMediaLoader()
            setContent {
                SwipeTheme {
                    SwipeScreen(state = deckState(), onIntent = {}, mediaLoader = loader)
                }
            }
            waitForIdle()
            // One request per on-screen card — composition order, the next card
            // under the top one — at the shared cache key the ViewModel
            // prefetches with (NFR-3).
            assertEquals(listOf("next-id", "current-id"), loader.imageRequests.map { it.first })
            assertEquals(
                listOf(MediaLoader.FULL_TARGET_PX, MediaLoader.FULL_TARGET_PX),
                loader.imageRequests.map { it.second },
            )
        }

    @Test
    fun `NFR-3 recomposition does not add a second image collector per card`() =
        runComposeUiTest {
            val loader = FakeMediaLoader()
            val state = MutableStateFlow(deckState())
            setContent {
                SwipeTheme {
                    SwipeScreen(state = state.collectAsState().value, onIntent = {}, mediaLoader = loader)
                }
            }
            waitForIdle()
            // An unrelated state change recomposes the screen; the collectors
            // are keyed on (id, targetPx, loader), so no new request happens.
            state.value = state.value.copy(toast = null, queueCount = 3)
            waitForIdle()
            assertEquals(2, loader.imageRequests.size)
        }

    @Test
    fun `6-6 exhausted deck requests no images and keeps the buttons`() =
        runComposeUiTest {
            val loader = FakeMediaLoader()
            setContent {
                SwipeTheme {
                    SwipeScreen(state = SwipeUiState(), onIntent = {}, mediaLoader = loader)
                }
            }
            waitForIdle()
            assertEquals(emptyList(), loader.imageRequests)
            onNodeWithContentDescription("Keep").assertExists()
        }
}

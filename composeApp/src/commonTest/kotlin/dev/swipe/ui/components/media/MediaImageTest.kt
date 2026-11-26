// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.components.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.data.LoadedImage
import dev.swipe.data.MediaLoader
import dev.swipe.fakes.FakeMediaLoader
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * FR-38 (§5.6 Hide advance): [rememberMediaImage] must never surface a previous
 * card's bitmap. When the id changes, the produced value is null until the new id's
 * own flow emits — the deck draws nothing rather than the hidden item's picture
 * while the next card is promoted.
 */
class MediaImageTest {
    @Test
    fun `FR-38 rememberMediaImage returns null for a new id until its image arrives`() =
        runComposeUiTest {
            // "b" is still loading: its flow records the request but emits nothing.
            val loader = FakeMediaLoader().apply { silentIds = setOf("b") }
            var id by mutableStateOf("a")
            var captured: LoadedImage? = null
            setContent {
                captured = rememberMediaImage(id, MediaLoader.FULL_TARGET_PX, loader)
            }
            waitForIdle()
            // The first id loads and is returned normally.
            assertNotNull(captured, "id a's image arrives and is returned")
            // Switch to the still-loading id: its own flow has not emitted, so the
            // old (id a) bitmap must not be reused for id b.
            id = "b"
            waitForIdle()
            assertNull(captured, "a new id never draws the previous card's image")
        }
}

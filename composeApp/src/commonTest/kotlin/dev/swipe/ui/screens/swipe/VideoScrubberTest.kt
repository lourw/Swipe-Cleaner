// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.swipe

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.ui.theme.SwipeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The FR-16 scrubber: [scrubFractionAt]'s pure drag-position contract — the
 * production drag path — and its accessibility contract (§5.7, NFR-8): the
 * pointer drag stays pointer-only, but SetProgress gives Accessibility the
 * same seek — one call seeks to the requested fraction and ends the scrub —
 * and the action is absent while the duration is still unknown, exactly like
 * the drag gate.
 */
class VideoScrubberTest {
    @Test
    fun `FR-16 set progress seeks to the fraction and ends the scrub`() =
        runComposeUiTest {
            val scrubbed = mutableListOf<Float>()
            var ends = 0
            setContent {
                SwipeTheme {
                    VideoScrubber(
                        durationMs = 10_000L,
                        fraction = 0f,
                        onScrub = { scrubbed += it },
                        onScrubEnd = { ends++ },
                    )
                }
            }
            val node = onNodeWithTag(VIDEO_SCRUBBER_TEST_TAG).fetchSemanticsNode()
            val handled = checkNotNull(node.config[SemanticsActions.SetProgress].action).invoke(0.5f)
            assertTrue(handled, "with a known duration the seek is handled")
            assertEquals(listOf(0.5f), scrubbed)
            assertEquals(1, ends)
        }

    @Test
    fun `FR-16 set progress is absent until the duration is known`() =
        runComposeUiTest {
            var scrubbed = 0
            setContent {
                SwipeTheme {
                    VideoScrubber(
                        durationMs = 0L,
                        fraction = 0f,
                        onScrub = { scrubbed++ },
                        onScrubEnd = { scrubbed++ },
                    )
                }
            }
            val node = onNodeWithTag(VIDEO_SCRUBBER_TEST_TAG).fetchSemanticsNode()
            assertNull(node.config.getOrNull(SemanticsActions.SetProgress)?.action)
            assertEquals(0, scrubbed)
        }

    @Test
    fun `FR-16 scrub fraction divides drag x by track width`() {
        assertEquals(0f, scrubFractionAt(0f, 100f))
        assertEquals(0.25f, scrubFractionAt(25f, 100f))
        assertEquals(0.5f, scrubFractionAt(50f, 100f))
        assertEquals(1f, scrubFractionAt(100f, 100f))
    }

    @Test
    fun `FR-16 scrub fraction clamps outside the track`() {
        assertEquals(0f, scrubFractionAt(-30f, 100f))
        assertEquals(1f, scrubFractionAt(130f, 100f))
    }

    @Test
    fun `FR-16 scrub fraction guards zero and non-finite widths`() {
        // A pre-layout drag (width 0) must never divide by zero.
        assertEquals(0f, scrubFractionAt(10f, 0f))
        assertEquals(0f, scrubFractionAt(10f, -1f))
        assertEquals(0f, scrubFractionAt(Float.NaN, 100f))
        assertEquals(0f, scrubFractionAt(10f, Float.NaN))
        assertEquals(0f, scrubFractionAt(10f, Float.POSITIVE_INFINITY))
    }
}

// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.swipe.data.MediaLoader
import dev.swipe.domain.model.MediaType
import dev.swipe.fakes.FakeMediaLoader
import dev.swipe.presentation.queue.QueueTile
import dev.swipe.ui.screens.swipe.formatCardDate
import dev.swipe.ui.theme.SwipeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The §5.7 `GridTile` (WP-3.3): the square small-target thumbnail request
 * (NFR-3, §6.8), the video duration at the bottom-right in `caption`, and the
 * NFR-8 accessibility line (date, type, duration; "Queued item" without
 * metadata).
 */
class GridTileTest {
    private val date: Instant = Instant.parse("2021-03-14T12:00:00Z")

    @Test
    fun `NFR-8 tile description carries date and type`() =
        runComposeUiTest {
            val loader = FakeMediaLoader()
            setContent {
                TestTile(
                    QueueTile("asset-1", MediaType.PHOTO, date, null),
                    loader,
                )
            }
            onNodeWithContentDescription("${formatCardDate(date)}, photo").assertExists()
        }

    @Test
    fun `NFR-8 a video tile adds the duration to its description`() =
        runComposeUiTest {
            setContent {
                TestTile(
                    QueueTile("asset-1", MediaType.VIDEO, date, 32_000L),
                    FakeMediaLoader(),
                )
            }
            onNodeWithContentDescription("${formatCardDate(date)}, video, 0:32").assertExists()
        }

    @Test
    fun `NFR-8 a metadata-less tile falls back to Queued item`() =
        runComposeUiTest {
            setContent {
                TestTile(
                    QueueTile("asset-1", null, null, null),
                    FakeMediaLoader(),
                )
            }
            onNodeWithContentDescription("Queued item").assertExists()
        }

    // Position (bottom-right per §5.7) is visual QA — manual-test.md row.
    @Test
    fun `5-7 a video tile shows its duration`() =
        runComposeUiTest {
            setContent {
                TestTile(
                    QueueTile("asset-1", MediaType.VIDEO, date, 32_000L),
                    FakeMediaLoader(),
                )
            }
            onNodeWithText("0:32").assertExists()
        }

    @Test
    fun `NFR-3 the thumbnail is requested at a small square target not the full-screen one`() =
        runComposeUiTest {
            val loader = FakeMediaLoader()
            setContent {
                Box(modifier = Modifier.size(120.dp)) {
                    SwipeTheme {
                        GridTile(
                            tile = QueueTile("asset-1", MediaType.PHOTO, date, null),
                            loader = loader,
                            onClick = {},
                        )
                    }
                }
            }
            waitForIdle()
            val (id, target) = loader.imageRequests.single()
            assertEquals("asset-1", id)
            assertTrue(target.width > 0 && target.height > 0)
            assertEquals(target.width, target.height, "the tile target is square")
            assertTrue(
                target.width < Int.MAX_VALUE && target != MediaLoader.FULL_TARGET_PX,
                "a tile never requests the full-screen decode (NFR-3)",
            )
        }

    @Test
    fun `5-7 the tile is square`() =
        runComposeUiTest {
            setContent {
                Box(modifier = Modifier.size(120.dp)) {
                    SwipeTheme {
                        GridTile(
                            tile = QueueTile("asset-1", MediaType.PHOTO, date, null),
                            loader = FakeMediaLoader(),
                            onClick = {},
                        )
                    }
                }
            }
            val bounds =
                onNodeWithContentDescription("${formatCardDate(date)}, photo")
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertEquals(bounds.width, bounds.height, 0.5f, "a GridTile is square (§5.7)")
        }

    @Composable
    private fun TestTile(
        tile: QueueTile,
        loader: MediaLoader,
    ) {
        SwipeTheme {
            GridTile(
                tile = tile,
                loader = loader,
                onClick = {},
            )
        }
    }
}

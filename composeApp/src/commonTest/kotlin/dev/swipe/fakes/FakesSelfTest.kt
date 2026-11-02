package dev.swipe.fakes

import androidx.compose.ui.unit.IntSize
import app.cash.turbine.test
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Keeps the fakes honest (§8.2): the two fakes with real filtering/state logic get a
 * self-test each, so every later test built on them rests on working ground.
 */
class FakesSelfTest {
    @Test
    fun `FakeDecisionStore records and fails on demand`() =
        runTest {
            val store = FakeDecisionStore()
            store.record("a", Decision.KEEP, "s1")
            store.record("b", Decision.DELETE, "s1")

            assertEquals(listOf(Triple("a", Decision.KEEP, "s1"), Triple("b", Decision.DELETE, "s1")), store.recordCalls)
            assertEquals(setOf("a", "b"), store.decidedIds())
            store.deleteQueue().test { assertEquals(listOf("b"), awaitItem()) }

            // The failure switch throws once and persists nothing (NFR-7 path).
            store.failNextRecord = IllegalStateException("disk full")
            val error =
                assertFailsWith<IllegalStateException> {
                    store.record("c", Decision.KEEP, "s1")
                }
            assertEquals("disk full", error.message)
            assertEquals(setOf("a", "b"), store.decidedIds())

            // SKIP goes to session_skip, never to decidedIds (§7 comment, FR-14).
            store.record("d", Decision.SKIP, "s1")
            assertEquals(setOf("d"), store.skips.idsOf("s1"))
            assertEquals(setOf("a", "b"), store.decidedIds())
        }

    @Test
    fun `FakeMediaLibrary filters by type`() =
        runTest {
            val library =
                FakeMediaLibrary(
                    listOf(
                        item("photo-1", MediaType.PHOTO),
                        item("live-1", MediaType.LIVE_PHOTO),
                        item("video-1", MediaType.VIDEO),
                    ),
                )

            assertEquals(listOf("photo-1", "live-1", "video-1"), library.assetIds(DeckSource.All, MediaTypeFilter.ALL))
            // PHOTOS includes Live Photos; VIDEOS only videos (FR-5).
            assertEquals(setOf("photo-1", "live-1"), library.assetIds(DeckSource.All, MediaTypeFilter.PHOTOS).toSet())
            assertEquals(listOf("video-1"), library.assetIds(DeckSource.All, MediaTypeFilter.VIDEOS))

            // Every call is recorded (FR-5, §8.2).
            val expectedCalls: List<Pair<DeckSource, MediaTypeFilter>> =
                listOf(
                    DeckSource.All to MediaTypeFilter.ALL,
                    DeckSource.All to MediaTypeFilter.PHOTOS,
                    DeckSource.All to MediaTypeFilter.VIDEOS,
                )
            assertEquals(expectedCalls, library.assetIdsCalls)
            assertEquals(LibraryAccess.FULL, library.authorization())
        }

    @Test
    fun `FakeMediaLoader emits degraded then full`() =
        runTest {
            val loader = FakeMediaLoader()
            loader.image("a", IntSize(1024, 1024)).test {
                val degraded = awaitItem()
                assertTrue(degraded.isDegraded)
                assertEquals(loader.degradedBitmap, degraded.bitmap)
                val full = awaitItem()
                assertFalse(full.isDegraded)
                assertEquals(loader.fullBitmap, full.bitmap)
                awaitComplete()
            }
            assertEquals(listOf("a" to IntSize(1024, 1024)), loader.imageRequests)
        }

    private fun item(
        id: String,
        type: MediaType,
    ) = MediaItem(
        id = id,
        type = type,
        createdAt = Instant.fromEpochMilliseconds(0),
        durationMs = if (type == MediaType.VIDEO) 5_000L else null,
        widthPx = 100,
        heightPx = 100,
        isFavorite = false,
        isScreenshot = false,
        burstId = null,
        isCloudOnly = null,
    )
}

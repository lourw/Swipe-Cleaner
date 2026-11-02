package dev.swipe.domain.usecase

import dev.swipe.domain.model.DuplicateKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * FR-6 Likely Duplicates grouping: neighbours within 2 s with identical pixel
 * dimensions, runs chained through their predecessor, groups of >= 2 only (§6.5).
 * Pure function, so no fakes and no clock needed; dates are fixed epoch millis.
 */
class DuplicateGrouperTest {
    @Test
    fun `FR-6 duplicates group items within 2 s with equal dimensions`() {
        val candidates =
            listOf(
                key("burst-a", 0L, 4032, 3024),
                key("burst-b", 1_500L, 4032, 3024),
                // Same dimensions but 8.5 s after its predecessor: its own singleton (FR-6).
                key("later", 10_000L, 4032, 3024),
            )

        assertEquals(listOf("burst-a", "burst-b"), DuplicateGrouper.group(candidates))
    }

    @Test
    fun `FR-6 duplicates ignore same-time items with different dimensions`() {
        val candidates =
            listOf(
                key("photo", 0L, 4032, 3024),
                key("crop", 0L, 2016, 1512),
            )

        assertEquals(emptyList(), DuplicateGrouper.group(candidates))
    }

    @Test
    fun `FR-6 duplicates chain a run of 3`() {
        // Each neighbour is 1.5 s from its predecessor, so one run even though the
        // whole burst spans 3 s (> the 2 s window).
        val candidates =
            listOf(
                key("shot-1", 0L, 4032, 3024),
                key("shot-2", 1_500L, 4032, 3024),
                key("shot-3", 3_000L, 4032, 3024),
            )

        assertEquals(listOf("shot-1", "shot-2", "shot-3"), DuplicateGrouper.group(candidates))
    }

    private fun key(
        id: String,
        createdAtEpochMs: Long,
        widthPx: Int,
        heightPx: Int,
    ) = DuplicateKey(
        id = id,
        createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
        widthPx = widthPx,
        heightPx = heightPx,
    )
}

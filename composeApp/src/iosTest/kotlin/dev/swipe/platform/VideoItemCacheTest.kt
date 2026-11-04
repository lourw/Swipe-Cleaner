package dev.swipe.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * FR-17: the paired-video temp-file name must be deterministic (the same asset id
 * always maps to one file) and injective (distinct ids never share a file), because
 * [VideoItemCache] deletes by name and a collision would show one Live Photo's
 * motion inside another.
 */
class VideoItemCacheTest {
    @Test
    fun `FR-17 live photo temp file name sanitizes a realistic asset id`() {
        // PHAsset local identifiers really look like this (slashes included);
        // each non-alphanumeric (except `-`) becomes a fixed-width hex escape.
        assertEquals(
            "swipe-live-2AC1A3B4-5678_002fL0_002f001.mov",
            livePhotoTempFileName("2AC1A3B4-5678/L0/001"),
        )
    }

    @Test
    fun `FR-17 live photo temp file name is deterministic`() {
        val id = "ABC/123_456"
        assertEquals(livePhotoTempFileName(id), livePhotoTempFileName(id))
    }

    @Test
    fun `FR-17 underscore ids cannot collide with slash ids`() {
        // Both would flatten to `a_b` without escaping; the hex scheme separates them.
        assertNotEquals(livePhotoTempFileName("a/b"), livePhotoTempFileName("a_b"))
        // Consecutive specials are the case underscore-doubling got wrong:
        // `a//b` and `a_b` both encoded to `a__b` under the old scheme.
        assertNotEquals(livePhotoTempFileName("a//b"), livePhotoTempFileName("a_b"))
        assertNotEquals(livePhotoTempFileName("a_-b"), livePhotoTempFileName("a__b"))
    }
}

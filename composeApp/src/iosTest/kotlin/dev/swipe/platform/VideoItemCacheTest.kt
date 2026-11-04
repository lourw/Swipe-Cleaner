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
    fun `live photo temp file name sanitizes a realistic asset id`() {
        // PHAsset local identifiers really look like this (slashes included).
        assertEquals(
            "swipe-live-2AC1A3B4-5678_L0_001.mov",
            livePhotoTempFileName("2AC1A3B4-5678/L0/001"),
        )
    }

    @Test
    fun `live photo temp file name is deterministic`() {
        val id = "ABC/123_456"
        assertEquals(livePhotoTempFileName(id), livePhotoTempFileName(id))
    }

    @Test
    fun `underscore ids cannot collide with slash ids`() {
        // Both would flatten to `a_b` without the underscore-doubling escape.
        assertNotEquals(livePhotoTempFileName("a/b"), livePhotoTempFileName("a_b"))
    }
}

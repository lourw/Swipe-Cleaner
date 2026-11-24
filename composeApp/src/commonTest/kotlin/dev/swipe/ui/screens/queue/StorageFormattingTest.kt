package dev.swipe.ui.screens.queue

import dev.swipe.domain.model.StorageEstimate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * FR-34: the byte formatting and dialog copy behind the confirm dialog's
 * estimate line. Pure functions, so the rounding and the honest wording are
 * pinned without a Compose host or a ViewModel.
 */
class StorageFormattingTest {
    @Test
    fun `formatStorageBytes counts decimal units the way iOS labels storage`() {
        assertEquals("0 B", formatStorageBytes(0L))
        assertEquals("999 B", formatStorageBytes(999L))
        assertEquals("1 KB", formatStorageBytes(1_000L))
        assertEquals("1.5 KB", formatStorageBytes(1_500L))
        assertEquals("9.5 MB", formatStorageBytes(9_500_000L))
        assertEquals("850 MB", formatStorageBytes(850_000_000L))
        assertEquals("1.2 GB", formatStorageBytes(1_200_000_000L))
    }

    @Test
    fun `formatStorageBytes drops the fraction above ten units`() {
        assertEquals("10 GB", formatStorageBytes(10_000_000_000L))
        assertEquals("25 GB", formatStorageBytes(25_400_000_000L))
        assertEquals("512 GB", formatStorageBytes(512_345_678_901L))
        assertEquals("1 TB", formatStorageBytes(1_000_000_000_000L))
    }

    @Test
    fun `formatStorageBytes carries tenth-place rounding into the next unit`() {
        // 999.96 MB rounds to a full 1000 MB: it must read "1 GB", never "1000 MB".
        assertEquals("1 GB", formatStorageBytes(999_960_000L))
        assertEquals("1 TB", formatStorageBytes(999_960_000_000L))
    }

    @Test
    fun `deleteSizeLabel omits the line for nothing to say`() {
        // Loading (null) and nothing readable (R3) render no line at all…
        assertNull(deleteSizeLabel(null))
        assertNull(deleteSizeLabel(StorageEstimate.Unavailable))
        // …and so does a zero total, which only an empty queue produces —
        // and an empty queue cannot raise the dialog (FR-25).
        assertNull(deleteSizeLabel(StorageEstimate.Complete(totalBytes = 0L)))
        assertNull(deleteSizeLabel(StorageEstimate.Partial(totalBytes = 0L)))
    }

    @Test
    fun `deleteSizeLabel hedges a complete total and states a partial one`() {
        assertEquals(
            "About 1.2 GB. Space is freed after Recently Deleted clears.",
            deleteSizeLabel(StorageEstimate.Complete(totalBytes = 1_200_000_000L)),
        )
        assertEquals(
            "At least 850 MB. Space is freed after Recently Deleted clears.",
            deleteSizeLabel(StorageEstimate.Partial(totalBytes = 850_000_000L)),
        )
    }
}

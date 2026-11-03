package dev.swipe.data

import dev.swipe.domain.model.DeleteResult
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure mapping from PhotoKit raw values (WP-1.4): the bitmask → [MediaType] logic the
 * iOS implementation relies on, covered without PhotoKit (§8.2). Raw values come from
 * `PHAsset.h`: mediaType 1 = image, 2 = video; subtype bit 0 = Live Photo, bit 2 =
 * screenshot.
 */
class PhotoKitMappingTest {
    @Test
    fun `FR-18 media type mapping recognises live photos`() {
        // Image + Live Photo bit → LIVE_PHOTO; the bit alone never misfires on videos.
        assertEquals(MediaType.LIVE_PHOTO, photoKitMediaType(mediaTypeId = 1, subtypes = 1 shl 0))
        assertEquals(MediaType.PHOTO, photoKitMediaType(mediaTypeId = 1, subtypes = 0))
        assertEquals(MediaType.VIDEO, photoKitMediaType(mediaTypeId = 2, subtypes = 0))
        assertEquals(MediaType.VIDEO, photoKitMediaType(mediaTypeId = 2, subtypes = 1 shl 0))
        // Other subtype bits (HDR = 1 shl 1, screenshot = 1 shl 2) keep photos photos.
        assertEquals(MediaType.PHOTO, photoKitMediaType(mediaTypeId = 1, subtypes = (1 shl 1) or (1 shl 2)))
        // Unknown media types (0 = unknown, 3 = audio) are skipped by the caller.
        assertNull(photoKitMediaType(mediaTypeId = 0, subtypes = 0))
        assertNull(photoKitMediaType(mediaTypeId = 3, subtypes = 0))
    }

    @Test
    fun `FR-6 screenshot subtype maps to isScreenshot`() {
        assertTrue(photoKitIsScreenshot(1 shl 2))
        assertTrue(photoKitIsScreenshot((1 shl 2) or (1 shl 0))) // screenshot taken as Live Photo
        assertFalse(photoKitIsScreenshot(1 shl 0)) // Live Photo alone is not a screenshot
        assertFalse(photoKitIsScreenshot(0))
    }

    @Test
    fun `FR-27 the iOS cancel error code maps to Cancelled`() {
        assertEquals(
            DeleteResult.Cancelled,
            photoKitDeleteResult(
                success = false,
                errorDomain = "PHPhotosErrorDomain",
                errorCode = 3072L,
                errorMessage = "User cancelled",
            ),
        )
        // The message never leaks into the cancelled branch: FR-27 shows no error.
        assertEquals(
            DeleteResult.Cancelled,
            photoKitDeleteResult(
                success = false,
                errorDomain = "PHPhotosErrorDomain",
                errorCode = 3072L,
                errorMessage = null,
            ),
        )
    }

    @Test
    fun `FR-26 a successful change request maps to Deleted`() {
        assertEquals(DeleteResult.Deleted, photoKitDeleteResult(true, null, null, null))
    }

    @Test
    fun `6-7 any other failure maps to Failed with the localized message`() {
        assertEquals(
            DeleteResult.Failed("iCloud sync failed"),
            photoKitDeleteResult(
                success = false,
                errorDomain = "PHPhotosErrorDomain",
                errorCode = 2001L, // some other PHPhotosError code, not 3072
                errorMessage = "iCloud sync failed",
            ),
        )
        // A same-code error from a different domain is a failure, not a cancel.
        assertEquals(
            DeleteResult.Failed("disk full"),
            photoKitDeleteResult(false, "NSPOSIXErrorDomain", 3072L, "disk full"),
        )
        // No error at all besides the flag: the fallback message keeps the toast non-empty.
        assertEquals(DeleteResult.Failed("Delete failed"), photoKitDeleteResult(false, null, null, null))
    }

    @Test
    fun `FR-1 authorization status maps to library access`() {
        // PHAuthorizationStatus: 0 notDetermined, 1 restricted, 2 denied, 3 authorized, 4 limited.
        assertEquals(LibraryAccess.FULL, photoKitLibraryAccess(3))
        assertEquals(LibraryAccess.LIMITED, photoKitLibraryAccess(4))
        assertEquals(LibraryAccess.DENIED, photoKitLibraryAccess(2))
        assertEquals(LibraryAccess.DENIED, photoKitLibraryAccess(1))
        assertEquals(LibraryAccess.NOT_DETERMINED, photoKitLibraryAccess(0))
        // Unknown future statuses fall back to NOT_DETERMINED rather than locking the user out.
        assertEquals(LibraryAccess.NOT_DETERMINED, photoKitLibraryAccess(99))
    }
}

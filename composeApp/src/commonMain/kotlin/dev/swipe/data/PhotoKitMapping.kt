package dev.swipe.data

import dev.swipe.domain.model.DeleteResult
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaType

/*
 * Pure mappings from PhotoKit raw values to domain models (WP-1.4). The `iosMain`
 * implementation passes these numbers straight from `PHAsset` / `PHPhotoLibrary`;
 * keeping the non-trivial ones here — the media-type bitmask and the authorization
 * switch — lets commonTest cover them without PhotoKit (§8.2, §8.3).
 */

// Raw PhotoKit values from `PHAsset.h` and `PHPhotoLibrary.h`, written out for the
// mappers below.
private const val PH_MEDIA_TYPE_IMAGE = 1 // PHAssetMediaType.image
private const val PH_MEDIA_TYPE_VIDEO = 2 // PHAssetMediaType.video

private const val PH_SUBTYPE_PHOTO_LIVE = 1 shl 0 // PHAssetMediaSubtype.photoLive
private const val PH_SUBTYPE_SCREENSHOT = 1 shl 2 // PHAssetMediaSubtype.photoScreenshot

/** The `NSError.domain` of every `PHPhotosError` (§6.8). */
private const val PH_ERROR_DOMAIN = "PHPhotosErrorDomain"

/** `PHPhotosErrorUserCancelled`: the iOS delete dialog was dismissed (3072, §6.8). */
private const val PH_ERROR_USER_CANCELLED = 3072L

private const val PH_AUTH_RESTRICTED = 1 // PHAuthorizationStatus.restricted
private const val PH_AUTH_DENIED = 2 // PHAuthorizationStatus.denied
private const val PH_AUTH_AUTHORIZED = 3 // PHAuthorizationStatus.authorized
private const val PH_AUTH_LIMITED = 4 // PHAuthorizationStatus.limited

/**
 * `PHAsset.mediaType` + `PHAsset.mediaSubtypes` → [MediaType] (FR-18): videos stay
 * videos, images carrying the Live Photo subtype bit are Live Photos, everything
 * else image-shaped is a plain photo. Anything PhotoKit reports as unknown (or the
 * Live Photo bit on a video) maps to null so the caller skips the asset.
 */
fun photoKitMediaType(
    mediaTypeId: Int,
    subtypes: Int,
): MediaType? =
    when (mediaTypeId) {
        PH_MEDIA_TYPE_VIDEO -> {
            MediaType.VIDEO
        }

        PH_MEDIA_TYPE_IMAGE -> {
            if (subtypes and PH_SUBTYPE_PHOTO_LIVE != 0) MediaType.LIVE_PHOTO else MediaType.PHOTO
        }

        else -> {
            null
        }
    }

/** The `PHAssetMediaSubtypeScreenshot` bit of `PHAsset.mediaSubtypes` (FR-6). */
fun photoKitIsScreenshot(subtypes: Int): Boolean = subtypes and PH_SUBTYPE_SCREENSHOT != 0

/**
 * `PHAuthorizationStatus` → [LibraryAccess] (§6.4): `authorized` → FULL, `limited` →
 * LIMITED, `denied`/`restricted` → DENIED, `notDetermined` → NOT_DETERMINED. Any
 * status this app doesn't know yet is treated as NOT_DETERMINED — the conservative
 * choice: it re-runs the onboarding path instead of locking the user out (FR-1).
 */
fun photoKitLibraryAccess(status: Int): LibraryAccess =
    when (status) {
        PH_AUTH_AUTHORIZED -> LibraryAccess.FULL
        PH_AUTH_LIMITED -> LibraryAccess.LIMITED
        PH_AUTH_DENIED, PH_AUTH_RESTRICTED -> LibraryAccess.DENIED
        else -> LibraryAccess.NOT_DETERMINED
    }

/**
 * The PhotoKit change-request completion handler's outcome → [DeleteResult]
 * (FR-25–27, §6.7):
 * success → [DeleteResult.Deleted]; the error PhotoKit raises when the user dismisses
 * the iOS delete dialog — domain [PH_ERROR_DOMAIN] with code 3072,
 * `PHPhotosErrorUserCancelled` — → [DeleteResult.Cancelled], silent per FR-27; anything
 * else → [DeleteResult.Failed] carrying the error's `localizedDescription` so the toast
 * can show why. The iOS implementation passes the `NSError`'s fields as plain values,
 * which keeps this mapping testable without PhotoKit (§8.2).
 */
fun photoKitDeleteResult(
    success: Boolean,
    errorDomain: String?,
    errorCode: Long?,
    errorMessage: String?,
): DeleteResult =
    when {
        success -> DeleteResult.Deleted
        errorDomain == PH_ERROR_DOMAIN && errorCode == PH_ERROR_USER_CANCELLED -> DeleteResult.Cancelled
        else -> DeleteResult.Failed(errorMessage ?: DELETE_FAILED_FALLBACK)
    }

/** Stand-in message when a PhotoKit failure carries no description at all. */
private const val DELETE_FAILED_FALLBACK = "Delete failed"

// Raw `PHAssetResourceType` values from `PHAssetResource.h` (FR-32).
private const val PH_RESOURCE_PHOTO = 1
private const val PH_RESOURCE_VIDEO = 2
private const val PH_RESOURCE_FULL_SIZE_PHOTO = 5
private const val PH_RESOURCE_FULL_SIZE_VIDEO = 6
private const val PH_RESOURCE_PAIRED_VIDEO = 9
private const val PH_RESOURCE_FULL_SIZE_PAIRED_VIDEO = 10

/**
 * Which of an asset's `PHAssetResource`s to share (FR-32), as raw resource types in
 * share order. The *current* rendition wins, as in the Photos app: an edited asset's
 * `fullSize…` resource, else the original. A photo shares its photo resource; a
 * video its video resource; a Live Photo its photo plus its paired video, so the
 * receiver gets the motion too (a Live Photo whose paired video is missing still
 * shares its photo). An unknown media type, or an asset with none of the needed
 * resources, yields an empty list — the caller reports a failure.
 */
fun photoKitShareResourceTypes(
    mediaTypeId: Int,
    subtypes: Int,
    availableTypes: Collection<Int>,
): List<Int> {
    fun pick(
        current: Int,
        original: Int,
    ): Int? =
        when {
            current in availableTypes -> current
            original in availableTypes -> original
            else -> null
        }
    val photo = pick(PH_RESOURCE_FULL_SIZE_PHOTO, PH_RESOURCE_PHOTO)
    return when (photoKitMediaType(mediaTypeId, subtypes)) {
        MediaType.PHOTO -> {
            listOfNotNull(photo)
        }

        MediaType.LIVE_PHOTO -> {
            if (photo == null) {
                emptyList()
            } else {
                listOfNotNull(photo, pick(PH_RESOURCE_FULL_SIZE_PAIRED_VIDEO, PH_RESOURCE_PAIRED_VIDEO))
            }
        }

        MediaType.VIDEO -> {
            listOfNotNull(pick(PH_RESOURCE_FULL_SIZE_VIDEO, PH_RESOURCE_VIDEO))
        }

        null -> {
            emptyList()
        }
    }
}

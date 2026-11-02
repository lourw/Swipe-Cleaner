package dev.swipe.data

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

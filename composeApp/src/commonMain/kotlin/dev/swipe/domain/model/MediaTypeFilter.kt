package dev.swipe.domain.model

/**
 * The Home media-type toggle, applied on top of any source (FR-5).
 * [PHOTOS] includes [MediaType.LIVE_PHOTO](REQUIREMENTS §7).
 */
enum class MediaTypeFilter {
    /** Every item. */
    ALL,

    /** Photos only, Live Photos included (FR-5). */
    PHOTOS,

    /** Videos only (FR-5). */
    VIDEOS,
}

/** Whether [type] passes this filter (FR-5). */
fun MediaTypeFilter.matches(type: MediaType): Boolean =
    when (this) {
        MediaTypeFilter.ALL -> true
        MediaTypeFilter.PHOTOS -> type == MediaType.PHOTO || type == MediaType.LIVE_PHOTO
        MediaTypeFilter.VIDEOS -> type == MediaType.VIDEO
    }

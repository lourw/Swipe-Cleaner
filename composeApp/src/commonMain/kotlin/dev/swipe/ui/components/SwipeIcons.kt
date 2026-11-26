package dev.swipe.ui.components

/**
 * The app's icon set (REQUIREMENTS §5.5) as Material Symbols names, so call
 * sites never carry magic strings. Values are the glyph names from the
 * Material Symbols catalog (https://fonts.google.com/icons) and render in the
 * Rounded style through [SwipeIcon].
 */
object SwipeIcons {
    const val CHECK: String = "check"
    const val CLOSE: String = "close"
    const val ARROW_UPWARD: String = "arrow_upward"
    const val UNDO: String = "undo"
    const val DELETE: String = "delete"
    const val SETTINGS: String = "settings"
    const val CLOUD: String = "cloud"
    const val FAVORITE: String = "favorite"
    const val PLAY_ARROW: String = "play_arrow"

    /** FR-16 mute button while the video is muted: the `volume_off` glyph. */
    const val VOLUME_OFF: String = "volume_off"

    /** FR-16 mute button while the video's sound is on: the `volume_up` glyph. */
    const val VOLUME_UP: String = "volume_up"
    const val CHEVRON_RIGHT: String = "chevron_right"
    const val ARROW_BACK: String = "arrow_back"
    const val FILTER_LIST: String = "filter_list"

    /** FR-38 Hide: the crossed-out eye, `visibility_off` (§5.5 mapping). */
    const val VISIBILITY_OFF: String = "visibility_off"

    /** FR-17 Live Photo badge: the iOS Live Photo glyph, `motion_photos_on`. */
    const val LIVE: String = "motion_photos_on"

    const val SHUFFLE: String = "shuffle"
    const val PHOTO_LIBRARY: String = "photo_library"
    const val CALENDAR_MONTH: String = "calendar_month"

    /** FR-32 share button: the iOS-style share glyph. */
    const val SHARE: String = "ios_share"

    /** FR-32 share button while the media loads (e.g. an iCloud download). */
    const val SHARE_LOADING: String = "downloading"
}

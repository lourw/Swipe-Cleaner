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
    const val CHEVRON_RIGHT: String = "chevron_right"
    const val ARROW_BACK: String = "arrow_back"
    const val FILTER_LIST: String = "filter_list"

    /** FR-17 Live Photo badge: the iOS Live Photo glyph, `motion_photos_on`. */
    const val LIVE: String = "motion_photos_on"

    const val SHUFFLE: String = "shuffle"
    const val PHOTO_LIBRARY: String = "photo_library"
    const val CALENDAR_MONTH: String = "calendar_month"
}

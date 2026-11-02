package dev.swipe.data

import androidx.compose.ui.graphics.ImageBitmap

/**
 * One emission from [MediaLoader.image] (WP-1.5, §6.4): the decoded bitmap plus the
 * status flags a card needs.
 *
 * [isDegraded] distinguishes the fast placeholder from the sharp result (NFR-1);
 * [isCloud] reports that PhotoKit had to reach iCloud for the pixels, so the card
 * can show the cloud badge while the load is slow (R6).
 */
data class LoadedImage(
    val bitmap: ImageBitmap,
    val isDegraded: Boolean,
    val isCloud: Boolean,
)

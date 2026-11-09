package dev.swipe.data

import dev.swipe.domain.model.ShareResult

/**
 * Opens the system share sheet for one library item (FR-32). The iOS implementation
 * (`platform/PhotoKitMediaSharer.kt`) writes the item's original resources to temp
 * files — the photo, the video, or a Live Photo's photo *and* paired video, so videos
 * and Live Photos share as real media, never a still frame — and hands their file
 * URLs to `UIActivityViewController`. The temp files are deleted once the sheet
 * finishes.
 *
 * Nothing is loaded ahead of time: the export starts only when [share] is called
 * (the button tap), and an iCloud-only original downloads with
 * `networkAccessAllowed` per [SettingsStore.includeCloudOnly], like every other
 * load (FR-29, NFR-6).
 *
 * Sharing never records a decision: the interface has no way to report what the
 * user did in the sheet, so the deck cannot react to it (FR-32).
 */
interface MediaSharer {
    /**
     * Load [id]'s original media and present the share sheet. Suspends while the
     * media loads (the caller shows a loading state) and returns once the sheet is
     * on screen ([ShareResult.PRESENTED]) or the load failed ([ShareResult.FAILED]).
     * Cancelling the caller during the load abandons it and deletes any temp file;
     * nothing is presented. Must be called from the main thread.
     */
    suspend fun share(id: String): ShareResult
}

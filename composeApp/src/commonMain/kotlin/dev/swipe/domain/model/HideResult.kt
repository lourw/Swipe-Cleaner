package dev.swipe.domain.model

/** Outcome of one FR-38 hide/unhide write ([dev.swipe.data.MediaHider.setHidden]). */
enum class HideResult {
    /**
     * The change request completed: the asset's `hidden` flag now matches what was
     * asked for, and the Photos library (and every `changes()` observer) updates.
     */
    APPLIED,

    /**
     * The asset could not be fetched (deleted, outside a Limited selection) or the
     * change request failed; nothing was written and a toast says so (FR-38).
     */
    FAILED,
}

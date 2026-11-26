package dev.swipe.domain.model

/**
 * The FR-34 estimate of the on-disk size of a set of library items, as reported by
 * [dev.swipe.data.MediaSizing]. Three states because the underlying read is
 * best-effort (Risk R3): every size readable is [Complete], some readable is
 * [Partial] with what was found, and nothing readable is [Unavailable] — the UI
 * omits the estimate line rather than showing a number it does not have.
 */
sealed interface StorageEstimate {
    /** Every item's size was read; [totalBytes] is the exact sum of what PhotoKit reported. */
    data class Complete(
        val totalBytes: Long,
    ) : StorageEstimate

    /**
     * Some sizes could not be read; [totalBytes] is the sum of the ones that
     * could — a **lower bound**, rendered as "Frees at least …" (FR-34).
     */
    data class Partial(
        val totalBytes: Long,
    ) : StorageEstimate

    /** Nothing could be read (or the whole read failed); the line is omitted (R3). */
    data object Unavailable : StorageEstimate
}

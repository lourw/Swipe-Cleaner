package dev.swipe.ui.screens.queue

import dev.swipe.domain.model.StorageEstimate

// FR-34: the confirm dialog's estimate line, and the byte formatting behind
// it. Both live apart from the screen so the copy and the rounding are testable
// without a Compose host, and the screen stays a pure rendering of
// QueueUiState.

/**
 * [bytes] as a short human string: decimal units (1000-based, the way iOS
 * labels storage), one decimal below 10 units and none above — "9.5 MB",
 * "850 MB", "1.2 GB", "25 GB". Rounding carries into the next unit, so the
 * value never reads "1000 MB".
 */
internal fun formatStorageBytes(bytes: Long): String {
    if (bytes < BYTES_PER_KB) return "$bytes B"
    var unit = 0
    while (unit < UNIT_DIVISORS.lastIndex && UNIT_DIVISORS[unit + 1] <= bytes) unit++
    var tenths = roundedTenths(bytes, UNIT_DIVISORS[unit])
    // The tenth-place rounding can carry a 999.6 MB into 1000.0 MB; show 1 GB.
    if (tenths >= CARRY_TENTHS && unit < UNIT_DIVISORS.lastIndex) {
        unit++
        tenths = roundedTenths(bytes, UNIT_DIVISORS[unit])
    }
    val whole = tenths / TEN_TENTHS
    val fraction = tenths % TEN_TENTHS
    val number =
        if (fraction > 0L && tenths < MAX_TENTHS_WITH_FRACTION) {
            "$whole.$fraction"
        } else {
            whole.toString()
        }
    return "$number ${UNIT_NAMES[unit]}"
}

/** [bytes] in tenths of [divisor], rounded to the nearest tenth. */
private fun roundedTenths(
    bytes: Long,
    divisor: Long,
): Long {
    val tenth = divisor / TEN_TENTHS
    return (bytes + tenth / 2) / tenth
}

/**
 * The dialog line for [estimate]: null omits it — while loading, when nothing
 * read (R3), or for a zero total, which only an empty queue produces and that
 * cannot raise the dialog. A complete estimate hedges with "About"; a partial
 * one states what it is — a lower bound, "At least". Both name the Recently
 * Deleted delay, because the space is not free until it clears.
 */
internal fun deleteSizeLabel(estimate: StorageEstimate?): String? {
    val bytes =
        when (estimate) {
            null,
            StorageEstimate.Unavailable,
            -> return null

            is StorageEstimate.Complete -> estimate.totalBytes

            is StorageEstimate.Partial -> estimate.totalBytes
        }
    if (bytes <= 0L) return null
    val qualifier =
        when (estimate) {
            is StorageEstimate.Partial -> "At least"
            else -> "About"
        }
    return "$qualifier ${formatStorageBytes(bytes)}. Space is freed after Recently Deleted clears."
}

// Decimal (1000-based) units, the way iOS labels storage.
private const val BYTES_PER_KB = 1_000L
private val UNIT_DIVISORS =
    longArrayOf(
        BYTES_PER_KB,
        BYTES_PER_KB * 1_000L, // MB
        BYTES_PER_KB * 1_000L * 1_000L, // GB
        BYTES_PER_KB * 1_000L * 1_000L * 1_000L, // TB
        BYTES_PER_KB * 1_000L * 1_000L * 1_000L * 1_000L, // PB
    )
private val UNIT_NAMES = listOf("KB", "MB", "GB", "TB", "PB")
private const val TEN_TENTHS = 10L

/** Tenths at which a value rounds up to 1000.0 of its unit and carries to the next. */
private const val CARRY_TENTHS = 10_000L

/** Above this (10.0 units) the fraction is dropped: "25 GB", not "25.4 GB". */
private const val MAX_TENTHS_WITH_FRACTION = 100L

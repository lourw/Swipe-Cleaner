package dev.swipe.presentation

/**
 * Where a paged list screen (Albums, By month — WP-9.6, FR-7, FR-8, §5.8)
 * stands with its library scan. The screen opens in [Loading]; the scan's
 * first answer moves it to [Ready] or [Empty]; a scan that cannot answer at
 * all explains itself with [Error], and the screen's retry re-runs it.
 *
 * **Error never replaces shown rows.** Once the scan has answered once, a
 * failed later refresh keeps the screen exactly as it is for the next
 * `changes()` retry (§6.8) — [Error] appears only while the screen still has
 * nothing to show, so a transient failure never blanks settled rows and never
 * hides the "nothing here" answer behind an error the scan did not give.
 */
sealed interface ListStatus {
    /** The first scan is still running; the screen shows its loading message (NFR-4). */
    data object Loading : ListStatus

    /** The scan answered with one or more rows. */
    data object Ready : ListStatus

    /** The scan answered, and there is genuinely nothing to show. */
    data object Empty : ListStatus

    /** The scan failed while the screen had nothing to show; retry starts a fresh scan. */
    data object Error : ListStatus
}

package dev.swipe.ui.components

/**
 * Tone of a [Banner] row; only the leading bar color changes. §5.7 specifies
 * one banner — a 4 dp leading bar in `warning` (the limited-access notice,
 * FR-2) — so [Warning] is the default. [Info] renders the bar in `accent`
 * for a neutral variant a future screen may want; that variant is not from
 * §5.7. `delete` is reserved for delete verdicts (§5.3), so there is
 * deliberately no error tone.
 */
enum class BannerTone { Info, Warning }

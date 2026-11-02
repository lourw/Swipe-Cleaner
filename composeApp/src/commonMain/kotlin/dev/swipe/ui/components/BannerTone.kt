package dev.swipe.ui.components

/**
 * Tone of a [Banner] row; only the leading bar color changes (§5.7). The
 * spec's banners are the limited-access notice (`warning`, FR-2) and neutral
 * queue rows (`accent`); `delete` is reserved for delete verdicts (§5.3), so
 * there is deliberately no error tone.
 */
enum class BannerTone { Info, Warning }

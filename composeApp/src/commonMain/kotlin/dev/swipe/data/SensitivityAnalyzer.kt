package dev.swipe.data

/**
 * The on-device sensitive-media check for FR-33 (hide sensitive photos by
 * default): reports whether an asset is flagged by the OS. Analysis never
 * leaves the device (NFR-6) — there is no scoring service and this interface
 * never sends an asset anywhere. Callers pass only ids inside the prefetch
 * window (hard rule 6, NFR-4): the analyzer is asked about the handful of ids
 * paging is about to show, never about the whole library.
 *
 * Implemented in `iosMain/platform/` over the OS sensitivity APIs;
 * [Unavailable] is the do-nothing default where nothing is wired, and
 * `commonTest/fakes/FakeSensitivityAnalyzer` backs the tests (§8.2).
 */
interface SensitivityAnalyzer {
    /** True when iOS will analyze at all: the entitlement is present and the user turned on
     *  Sensitive Content Warning or Communication Safety (policy != Disabled). Cheap; read when needed. */
    fun isAvailable(): Boolean

    /** True only when the item is flagged. False for videos, items with no local image, and any
     *  failure (fails open, FR-33). Never throws except CancellationException. */
    suspend fun isSensitive(id: String): Boolean

    /** No analysis: the default where nothing is wired (tests, other decks). */
    object Unavailable : SensitivityAnalyzer {
        override fun isAvailable(): Boolean = false

        override suspend fun isSensitive(id: String): Boolean = false
    }
}

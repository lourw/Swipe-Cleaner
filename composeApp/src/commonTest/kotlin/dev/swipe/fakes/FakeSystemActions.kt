package dev.swipe.fakes

import dev.swipe.data.SystemActions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

/**
 * In-memory [SystemActions] for tests (§8.2). System calls are counted
 * (`openSettingsCalls`, …), [appVersionResult] and [signingExpiryResult] are settable
 * (FR-31), and [setReduceMotion] drives every [isReduceMotionEnabled] collector (§5.6).
 */
class FakeSystemActions : SystemActions {
    var openSettingsCalls = 0
        private set
    var presentLimitedLibraryPickerCalls = 0
        private set
    var appVersionCalls = 0
        private set
    var signingExpiryCalls = 0
        private set

    /** Returned by [appVersion] (FR-31). */
    var appVersionResult = "1.0 (1)"

    /** Returned by [signingExpiry]; null means "row hidden" (FR-31). */
    var signingExpiryResult: Instant? = null

    /** When non-null, the next [openSettings] throws this once and the switch resets. */
    var failNextOpenSettings: Throwable? = null

    private val reduceMotion = MutableStateFlow(false)

    override fun openSettings() {
        openSettingsCalls++
        failNextOpenSettings?.let {
            failNextOpenSettings = null
            throw it
        }
    }

    override fun presentLimitedLibraryPicker() {
        presentLimitedLibraryPickerCalls++
    }

    override fun isReduceMotionEnabled(): Flow<Boolean> = reduceMotion

    override fun appVersion(): String {
        appVersionCalls++
        return appVersionResult
    }

    override fun signingExpiry(): Instant? {
        signingExpiryCalls++
        return signingExpiryResult
    }

    /** Flip Reduce Motion; every [isReduceMotionEnabled] collector sees it (§5.6, NFR-8). */
    fun setReduceMotion(enabled: Boolean) {
        reduceMotion.value = enabled
    }
}

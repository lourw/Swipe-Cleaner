@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-33: the iOS [SensitivityAnalyzer] must fail open on every path the
 * test host can reach without the Sensitive Content Warning entitlement: an
 * unknown id resolves to no asset and answers "not sensitive", and reading the
 * analysis policy must not throw even while the entitlement is missing
 * (policy Disabled is the expected simulator answer; its value is unspecified,
 * so only the no-throw behaviour is asserted).
 */
class PhotoKitSensitivityAnalyzerTest {
    @Test
    fun `FR-33 an unknown id is never flagged`() =
        runTest {
            assertFalse(PhotoKitSensitivityAnalyzer().isSensitive("no-such-asset/L0/001"))
        }

    @Test
    fun `FR-33 availability can be read without the entitlement`() {
        val result = runCatching { PhotoKitSensitivityAnalyzer().isAvailable() }
        assertTrue(result.isSuccess, "reading the analysis policy must not throw: ${result.exceptionOrNull()}")
    }
}

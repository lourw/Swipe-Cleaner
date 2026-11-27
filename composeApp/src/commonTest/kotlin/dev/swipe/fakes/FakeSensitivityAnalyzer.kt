package dev.swipe.fakes

import dev.swipe.data.SensitivityAnalyzer

/**
 * In-memory [SensitivityAnalyzer] for tests (§8.2): flags the ids in [flagged]
 * and reports [available] otherwise.
 */
class FakeSensitivityAnalyzer(
    var flagged: Set<String> = emptySet(),
    var available: Boolean = true,
) : SensitivityAnalyzer {
    /** Every id passed to isSensitive, oldest first. */
    val analyzed = mutableListOf<String>()

    override fun isAvailable(): Boolean = available

    override suspend fun isSensitive(id: String): Boolean {
        analyzed += id
        return id in flagged
    }
}

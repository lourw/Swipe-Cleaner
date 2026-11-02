package dev.swipe.domain.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `session.source` is stored as JSON (REQUIREMENTS §7) and routes carry the same
 * serialization (§6.3), so every variant must survive a round-trip.
 */
class DeckSourceTest {
    private val json = Json

    @Test
    fun `DeckSource round-trips through JSON`() {
        val sources: List<DeckSource> =
            listOf(
                DeckSource.All,
                DeckSource.Album(id = "album-1", title = "Kyoto Trip"),
                DeckSource.Month(year = 2026, month = 11),
                DeckSource.Month(year = 1999, month = 1),
                DeckSource.Smart(kind = SmartFilter.SCREENSHOTS),
                DeckSource.Smart(kind = SmartFilter.LIKELY_DUPLICATES),
            )
        for (source in sources) {
            val encoded = json.encodeToString(DeckSource.serializer(), source)
            assertEquals(source, json.decodeFromString(DeckSource.serializer(), encoded), "round-trip of $encoded")
        }
    }
}

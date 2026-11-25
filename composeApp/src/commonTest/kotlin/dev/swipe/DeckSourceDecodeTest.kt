package dev.swipe

import dev.swipe.domain.model.DeckSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/**
 * The [decodeDeckSource] contract behind the Swipe route: a source encoded by
 * this build round-trips, and anything older or malformed decodes to null so
 * the destination can fall back to Home instead of crashing on launch.
 */
class DeckSourceDecodeTest {
    @Test
    fun `NFR-7 a source encoded by this build round-trips`() {
        val sources = listOf(DeckSource.All, DeckSource.Album("album-1", "Trip"), DeckSource.Month(2026, 11))
        for (source in sources) {
            val json = Json.encodeToString(DeckSource.serializer(), source)
            assertEquals(source, decodeDeckSource(json))
        }
    }

    @Test
    fun `NFR-7 malformed or foreign source json decodes to null`() {
        assertNull(decodeDeckSource(""))
        assertNull(decodeDeckSource("not json"))
        // A shape this build's sealed interface does not know (an old or
        // future route argument) must not throw.
        assertNull(decodeDeckSource("""{"kind":"RecentlyDeleted"}"""))
    }
}

package dev.swipe.ui.screens.swipe

import dev.swipe.domain.model.MediaType
import dev.swipe.presentation.swipe.CardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * NFR-8: the deck card's VoiceOver line is date, type, duration, favorite —
 * the date-then-type shape the NFR-8 row describes, pinned here as a pure
 * function so the format can never drift silently.
 */
class CardMetadataTest {
    private val date = Instant.parse("2021-03-14T12:00:00Z")

    @Test
    fun `NFR-8 card description is date then type`() {
        assertEquals(
            "${formatCardDate(date)}, photo",
            cardAccessibilityDescription(state(MediaType.PHOTO)),
        )
    }

    @Test
    fun `NFR-8 card description adds duration for videos`() {
        assertEquals(
            "${formatCardDate(date)}, video, 0:32",
            cardAccessibilityDescription(state(MediaType.VIDEO, durationMs = 32_000)),
        )
    }

    @Test
    fun `NFR-8 card description ends with favorite when starred`() {
        assertEquals(
            "${formatCardDate(date)}, photo, favorite",
            cardAccessibilityDescription(state(MediaType.PHOTO, isFavorite = true)),
        )
    }

    private fun state(
        type: MediaType,
        durationMs: Long? = null,
        isFavorite: Boolean = false,
    ) = CardState(
        id = "asset-1",
        type = type,
        createdAt = date,
        durationMs = durationMs,
        isFavorite = isFavorite,
    )
}

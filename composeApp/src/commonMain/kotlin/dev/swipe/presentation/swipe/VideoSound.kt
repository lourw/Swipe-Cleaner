package dev.swipe.presentation.swipe

import dev.swipe.data.AudioSession
import dev.swipe.domain.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * FR-16: keeps the platform audio session matched to the card on top — the fix
 * for "video sound does not work": nothing ever requested a playback session, so
 * even unmuted videos stayed on the silent, non-mixable default category.
 *
 * It only reacts to state: when a VIDEO card is on top and the user unmutes, it
 * calls [AudioSession.requestPlayback] exactly once; muting again calls
 * [AudioSession.releasePlayback]. It waits for a VIDEO card because a photo makes
 * no sound — unmuting on a photo must never interrupt other apps' audio — and it
 * never requests while muted, so silent autoplay keeps mixing silently. Moving on
 * while unmuted keeps the session requested across cards: no second request per
 * card, and no release until the user mutes or the deck closes ([release]).
 *
 * Every door call is best effort: a broken audio session must never break the
 * deck, so failures other than cancellation are swallowed.
 */
internal class VideoSound(
    scope: CoroutineScope,
    private val audio: AudioSession,
    state: StateFlow<SwipeUiState>,
) {
    /** True between a request and its release, so calls come in pairs. */
    private var requested = false

    init {
        scope.launch {
            state.collect { s ->
                if (!s.muted && s.current?.type == MediaType.VIDEO && !requested) {
                    requestQuietly()
                    requested = true
                } else if (s.muted && requested) {
                    releaseQuietly()
                    requested = false
                }
            }
        }
    }

    /** Hand the audio session back when the deck closes (`onCleared`); no-op while muted. */
    fun release() {
        if (!requested) return
        releaseQuietly()
        requested = false
    }

    private fun requestQuietly() {
        try {
            audio.requestPlayback()
        } catch (cancellation: CancellationException) {
            throw cancellation // ViewModel teardown, not a broken session
        } catch (_: Exception) {
            // Best effort: the deck keeps swiping even if the session call fails.
        }
    }

    private fun releaseQuietly() {
        try {
            audio.releasePlayback()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Best effort, same as the request.
        }
    }
}

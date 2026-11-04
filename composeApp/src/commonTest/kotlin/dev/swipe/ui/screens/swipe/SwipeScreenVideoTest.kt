// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.swipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.data.MediaLoader
import dev.swipe.domain.model.MediaType
import dev.swipe.fakes.FakeHaptics
import dev.swipe.fakes.FakeMediaLoader
import dev.swipe.presentation.swipe.CardState
import dev.swipe.presentation.swipe.SwipeIntent
import dev.swipe.presentation.swipe.SwipeUiState
import dev.swipe.ui.components.media.SeekTarget
import dev.swipe.ui.components.media.VideoPlayerSlot
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The WP-4.2 video and Live Photo UX: FR-16 (tap toggles mute, the session
 * remembers it, the scrubber shows for videos only, drags seek), FR-17 (hold
 * a Live Photo to play it, release stops), FR-18 (date, duration, and the
 * Live/cloud/favorite badges), §5.6 (card drags and scrubber drags stay
 * disjoint), and R2 (the player mounts only while the card is at rest).
 *
 * Live playback is captured through a recording [VideoPlayerSlot] fake — the
 * real `actual` needs the Koin-resolved cache, so the slot is the seam
 * (see `VideoPlayerSlot.kt`).
 */
class SwipeScreenVideoTest {
    /** Records every [VideoPlayerSlot] composition and mount/unmount cycle. */
    private class RecordingSlot {
        var invocations = 0
        var lastId: String? = null
        var lastMuted: Boolean? = null
        var lastPlaying: Boolean? = null
        var lastLivePhoto: Boolean? = null
        var lastSeekTarget: SeekTarget? = null
        var lastOnProgress: ((Float) -> Unit)? = null

        /** Keys of the players currently in composition (`id:video` / `id:live`). */
        val active = mutableListOf<String>()

        val composable: VideoPlayerSlot =
            { id, muted, playing, onProgress, modifier, livePhoto, seekTarget ->
                invocations++
                lastId = id
                lastMuted = muted
                lastPlaying = playing
                lastLivePhoto = livePhoto
                lastSeekTarget = seekTarget
                lastOnProgress = onProgress
                val key = "$id:${if (livePhoto) "live" else "video"}"
                DisposableEffect(key) {
                    active += key
                    onDispose { active -= key }
                }
            }
    }

    private fun card(
        id: String = "card-1",
        type: MediaType = MediaType.VIDEO,
        createdAt: Instant? = null,
        durationMs: Long? = null,
        isFavorite: Boolean = false,
        isCloudOnly: Boolean = false,
    ) = CardState(
        id = id,
        type = type,
        createdAt = createdAt,
        durationMs = durationMs,
        isFavorite = isFavorite,
        isCloudOnly = isCloudOnly,
    )

    @Composable
    private fun VideoTestScreen(
        stateFlow: StateFlow<SwipeUiState>,
        onIntent: (SwipeIntent) -> Unit = {},
        slot: RecordingSlot = RecordingSlot(),
        mediaLoader: MediaLoader = FakeMediaLoader(),
        autoplayVideos: Boolean = true,
    ) {
        val state by stateFlow.collectAsState()
        SwipeTheme {
            SwipeScreen(
                state = state,
                events = flowOf(),
                onIntent = onIntent,
                mediaLoader = mediaLoader,
                haptics = FakeHaptics(),
                reduceMotion = false,
                onClose = {},
                onOpenQueue = {},
                autoplayVideos = autoplayVideos,
                videoSlot = slot.composable,
            )
        }
    }

    private fun SemanticsNodeInteractionsProvider.assertScrubberAt(fraction: Float) {
        val current =
            onNodeWithTag(VIDEO_SCRUBBER_TEST_TAG)
                .fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo]
                .current
        assertEquals(fraction, current)
    }

    @Test
    fun `FR-16 tap on video card toggles mute`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            val slot = RecordingSlot()
            val stateFlow =
                MutableStateFlow(SwipeUiState(current = card(id = "vid", durationMs = 5_000L)))
            setContent { VideoTestScreen(stateFlow, { intents.add(it) }, slot) }
            waitForIdle()
            // At rest with autoplay on the card mounts muted and playing (FR-16).
            assertEquals(listOf("vid:video"), slot.active)
            assertEquals(true, slot.lastMuted)
            assertEquals(true, slot.lastPlaying)
            assertEquals(false, slot.lastLivePhoto)
            // Tap → the ToggleMute intent; the session state rides the VM.
            onNodeWithTag(SWIPE_CARD_TEST_TAG).performClick()
            mainClock.advanceTimeBy(400L) // the double-tap window closes, onClick fires
            waitForIdle()
            assertEquals(listOf<SwipeIntent>(SwipeIntent.ToggleMute), intents)
            // The flipped `muted` (remembered for the session) reaches the player.
            stateFlow.value = stateFlow.value.copy(muted = false)
            waitForIdle()
            assertEquals(false, slot.lastMuted)
        }

    @Test
    fun `FR-16 autoplay off shows the play affordance and the first tap starts playback`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            val slot = RecordingSlot()
            val stateFlow =
                MutableStateFlow(SwipeUiState(current = card(id = "vid", durationMs = 5_000L)))
            setContent {
                VideoTestScreen(stateFlow, { intents.add(it) }, slot, autoplayVideos = false)
            }
            waitForIdle()
            // Poster + affordance, no player yet.
            assertEquals(0, slot.invocations)
            onNodeWithTag(VIDEO_PLAY_AFFORDANCE_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
            // First tap starts playback — it does NOT toggle mute.
            onNodeWithTag(SWIPE_CARD_TEST_TAG).performClick()
            mainClock.advanceTimeBy(400L) // the double-tap window closes, onClick fires
            waitForIdle()
            assertEquals(emptyList(), intents)
            assertEquals(listOf("vid:video"), slot.active)
            onNodeWithTag(VIDEO_PLAY_AFFORDANCE_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
            // Later taps toggle mute as usual.
            onNodeWithTag(SWIPE_CARD_TEST_TAG).performClick()
            mainClock.advanceTimeBy(400L)
            waitForIdle()
            assertEquals(listOf<SwipeIntent>(SwipeIntent.ToggleMute), intents)
        }

    @Test
    fun `FR-16 scrubber only shown for videos`() =
        runComposeUiTest {
            val slot = RecordingSlot()
            val stateFlow =
                MutableStateFlow(SwipeUiState(current = card(id = "vid", durationMs = 61_000L)))
            setContent { VideoTestScreen(stateFlow, slot = slot) }
            waitForIdle()
            onNodeWithTag(VIDEO_SCRUBBER_TEST_TAG).assertIsDisplayed()
            // Photos never show it…
            stateFlow.value = SwipeUiState(current = card(id = "photo", type = MediaType.PHOTO))
            waitForIdle()
            onNodeWithTag(VIDEO_SCRUBBER_TEST_TAG).assertDoesNotExist()
            // …and neither do Live Photos (FR-17: the paired video plays once).
            stateFlow.value = SwipeUiState(current = card(id = "live", type = MediaType.LIVE_PHOTO))
            waitForIdle()
            onNodeWithTag(VIDEO_SCRUBBER_TEST_TAG).assertDoesNotExist()
        }

    @Test
    fun `FR-16 scrubber drag seeks the player`() =
        runComposeUiTest {
            val slot = RecordingSlot()
            val stateFlow =
                MutableStateFlow(SwipeUiState(current = card(id = "vid", durationMs = 60_000L)))
            setContent { VideoTestScreen(stateFlow, slot = slot) }
            waitForIdle()
            val size = onNodeWithTag(VIDEO_SCRUBBER_TEST_TAG).fetchSemanticsNode().size
            // The drag starts at the left edge: onDragStart fires once the
            // slop is crossed with the DOWN position (fraction ≈ 0, token 1),
            // then every move reports its own position.
            onNodeWithTag(VIDEO_SCRUBBER_TEST_TAG).performTouchInput {
                down(Offset(1f, size.height / 2f))
                moveTo(Offset(size.width * 0.25f, size.height / 2f))
            }
            waitForIdle()
            assertEquals(0.25f, slot.lastSeekTarget?.fraction)
            // The label follows the finger: 25 % of 60 s is 0:15.
            onNodeWithText("0:15").assertIsDisplayed()
            onNodeWithTag(VIDEO_SCRUBBER_TEST_TAG).performTouchInput {
                moveTo(Offset(size.width * 0.5f, size.height / 2f))
            }
            waitForIdle()
            assertEquals(0.5f, slot.lastSeekTarget?.fraction)
            // Each drag step is a distinct seek event (start + two moves).
            assertEquals(3L, slot.lastSeekTarget?.token)
            onNodeWithTag(VIDEO_SCRUBBER_TEST_TAG).performTouchInput { up() }
            waitForIdle()
        }

    @Test
    fun `FR-18 metadata shows date duration and badges`() =
        runComposeUiTest {
            val loader = FakeMediaLoader().apply { isCloud = true }
            val stateFlow =
                MutableStateFlow(
                    SwipeUiState(
                        current =
                            card(
                                id = "rich",
                                type = MediaType.LIVE_PHOTO,
                                createdAt = Instant.parse("2021-03-14T12:00:00Z"),
                                durationMs = 61_000L,
                                isFavorite = true,
                            ),
                    ),
                )
            setContent { VideoTestScreen(stateFlow, mediaLoader = loader) }
            waitForIdle()
            // `Mar 14, 2021 · 1:01` (§5.7) — date plus the m:ss duration.
            onNodeWithText("Mar 14, 2021 · 1:01").assertIsDisplayed()
            onNodeWithTag(BADGE_LIVE_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(BADGE_FAVORITE_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
            // R6: the loaded image's cloud signal lights the cloud badge.
            onNodeWithTag(BADGE_CLOUD_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
            // A plain photo without any signal carries no badges at all.
            loader.isCloud = false
            stateFlow.value =
                SwipeUiState(
                    current =
                        card(
                            id = "plain",
                            type = MediaType.PHOTO,
                            createdAt = Instant.parse("2021-03-14T12:00:00Z"),
                        ),
                )
            waitForIdle()
            onNodeWithText("Mar 14, 2021").assertIsDisplayed()
            onNodeWithTag(BADGE_LIVE_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(BADGE_FAVORITE_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(BADGE_CLOUD_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
            // The deck-time R6 flag lights it too, before any pixel loads.
            stateFlow.value =
                SwipeUiState(current = card(id = "cloudy", type = MediaType.PHOTO, isCloudOnly = true))
            waitForIdle()
            onNodeWithTag(BADGE_CLOUD_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
        }

    @Test
    fun `FR-17 long press on live photo starts playback`() =
        runComposeUiTest {
            val slot = RecordingSlot()
            val stateFlow =
                MutableStateFlow(SwipeUiState(current = card(id = "live", type = MediaType.LIVE_PHOTO)))
            setContent { VideoTestScreen(stateFlow, slot = slot) }
            waitForIdle()
            // No live player at rest — only the still is up.
            assertEquals(emptyList(), slot.active)
            val size = onNodeWithTag(SWIPE_CARD_TEST_TAG).fetchSemanticsNode().size
            onNodeWithTag(SWIPE_CARD_TEST_TAG).performTouchInput {
                down(Offset(size.width / 2f, size.height / 2f))
            }
            mainClock.advanceTimeBy(600L) // the long-press fires at ~500 ms
            waitForIdle()
            // Hold plays the paired video: over the still, muted, once.
            assertEquals(listOf("live:live"), slot.active)
            assertEquals(true, slot.lastLivePhoto)
            assertEquals(true, slot.lastPlaying)
            assertEquals(true, slot.lastMuted)
            // Release stops it (FR-17).
            onNodeWithTag(SWIPE_CARD_TEST_TAG).performTouchInput { up() }
            waitForIdle()
            assertEquals(emptyList(), slot.active)
        }

    @Test
    fun `5-6 horizontal drag on card never moves the scrubber`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            val slot = RecordingSlot()
            val stateFlow =
                MutableStateFlow(SwipeUiState(current = card(id = "vid", durationMs = 60_000L)))
            setContent { VideoTestScreen(stateFlow, { intents.add(it) }, slot) }
            waitForIdle()
            // A player tick parks the scrubber at the middle of the item.
            slot.lastOnProgress!!.invoke(0.5f)
            waitForIdle()
            assertScrubberAt(0.5f)
            // Drag the card sideways (below the commit threshold) and release.
            val size = onNodeWithTag(SWIPE_CARD_TEST_TAG).fetchSemanticsNode().size
            onNodeWithTag(SWIPE_CARD_TEST_TAG).performTouchInput {
                down(Offset(size.width / 2f, size.height / 2f))
                moveTo(Offset(size.width * 0.15f, size.height / 2f))
            }
            mainClock.advanceTimeBy(32L)
            onNodeWithTag(SWIPE_CARD_TEST_TAG).performTouchInput { up() }
            mainClock.advanceTimeBy(1_000L) // the snap-back spring settles
            waitForIdle()
            // The scrubber never moved: no seek, and the fraction is unchanged.
            assertScrubberAt(0.5f)
            assertEquals(null, slot.lastSeekTarget)
            assertEquals(emptyList(), intents)
        }

    @Test
    fun `R2 video player unmounts while the card is dragged`() =
        runComposeUiTest {
            val slot = RecordingSlot()
            val stateFlow =
                MutableStateFlow(SwipeUiState(current = card(id = "vid", durationMs = 5_000L)))
            setContent { VideoTestScreen(stateFlow, slot = slot) }
            waitForIdle()
            assertEquals(listOf("vid:video"), slot.active)
            val size = onNodeWithTag(SWIPE_CARD_TEST_TAG).fetchSemanticsNode().size
            onNodeWithTag(SWIPE_CARD_TEST_TAG).performTouchInput {
                down(Offset(size.width / 2f, size.height / 2f))
                moveTo(Offset(size.width * 0.3f, size.height / 2f))
            }
            mainClock.advanceTimeBy(32L)
            waitForIdle()
            // R2: the hosted player ignores card transforms, so mid-drag only
            // the poster is up.
            assertEquals(emptyList(), slot.active)
            onNodeWithTag(SWIPE_CARD_TEST_TAG).performTouchInput { up() }
            mainClock.advanceTimeBy(1_000L)
            waitForIdle()
            // At rest again, the player comes back.
            assertEquals(listOf("vid:video"), slot.active)
        }
}

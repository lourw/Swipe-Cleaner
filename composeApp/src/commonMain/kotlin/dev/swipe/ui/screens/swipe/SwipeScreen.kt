package dev.swipe.ui.screens.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.swipe.data.Haptics
import dev.swipe.data.MediaLoader
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.presentation.swipe.CardState
import dev.swipe.presentation.swipe.SwipeEvent
import dev.swipe.presentation.swipe.SwipeIntent
import dev.swipe.presentation.swipe.SwipeUiState
import dev.swipe.presentation.swipe.ToastMessage
import dev.swipe.resources.Res
import dev.swipe.resources.ic_favorite
import dev.swipe.ui.components.DEFAULT_AUTO_DISMISS
import dev.swipe.ui.components.NEVER_AUTO_DISMISS
import dev.swipe.ui.components.Toast
import dev.swipe.ui.components.media.DefaultVideoPlayerSlot
import dev.swipe.ui.components.media.SeekTarget
import dev.swipe.ui.components.media.VideoPlayerSlot
import dev.swipe.ui.components.swipe.SwipeCardState
import dev.swipe.ui.components.swipe.SwipeVerdict
import dev.swipe.ui.components.swipe.rememberSwipeCardState
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Decodes the `Swipe` route's [MediaTypeFilter] name. Unknown or missing
 * names fall back to [MediaTypeFilter.ALL], so a malformed deep link can
 * never crash the deck (a deck over everything is still a valid deck).
 */
fun decodeTypeFilter(name: String): MediaTypeFilter = MediaTypeFilter.entries.firstOrNull { it.name == name } ?: MediaTypeFilter.ALL

/** Tag the screen tests tap through for card gestures and card taps. */
internal const val SWIPE_CARD_TEST_TAG = "swipe-card"

/** Tag of the autoplay-off play affordance over a video poster (§5.6, R2 wiring). */
internal const val VIDEO_PLAY_AFFORDANCE_TEST_TAG = "video-play-affordance"

/**
 * The Swipe screen (§5.8, WP-2.3): [TopBar] over the deck over the [ActionBar]
 * (§5.7), the §5.6 [SwipeCard] gesture in the middle, and the [Toast] slot
 * above the bar (FR-15 "Undone", FR-22 favorite confirm, NFR-7 revert).
 *
 * Stateless by convention (AGENTS.md): one [SwipeUiState] plus the ViewModel's
 * one-shot [events] flow in, committed [SwipeIntent]s out. Transient drag and
 * animation state stays here in the hoisted `SwipeCardState`
 * (rememberSwipeCardState):
 *
 * - **Commit paths.** A gesture commit arrives at `SwipeCard`'s `onDismiss`
 *   after its fly-off; an ActionBar button runs the §5.6 button path
 *   (`SwipeCardState.flyOff` — nudge + fly-off, crossfade under Reduce
 *   Motion) before committing. Both then fire the verdict's haptic
 *   (`impactLight` for Keep/Skip, `impactMedium` for Delete, §5.6) and send
 *   the intent — persist-before-advance stays in the ViewModel (NFR-7).
 * - **Card return.** `SwipeEvent.ReturnCard` (undo, FR-15; favorite refusal,
 *   FR-22) flies the card back in from the side it left, and a changed
 *   [SwipeUiState.revertToken] (a failed decision write, NFR-7) reverts it
 *   the same way (see `CardMotionEffects` in DeckMotion.kt).
 * - **Card taps** (§5.6): a photo tap toggles the chrome (top bar and
 *   metadata), a video tap toggles mute through [SwipeIntent.ToggleMute]
 *   (with autoplay off the first tap starts playback instead), a double-tap
 *   toggles aspect-fit/fill locally, and holding a Live Photo plays its
 *   paired video until release (FR-17; see `CardStack` in DeckMotion.kt).
 * - **Video scrubber** (FR-16): videos only, below the card in the
 *   action-bar area — [VideoScrubber] seeks through a [SeekTarget] while the
 *   finger drags (Live Photos show none; they play once, no loop).
 *
 * With no card and no summary to show the screen renders the §5.8
 * [EmptyState]; a moment of it during the deck's first load is accepted.
 * Once [SwipeUiState.finished] is set the container navigates to the
 * summary (FR-21), so this keeps the deck area empty meanwhile.
 */
@Composable
fun SwipeScreen(
    state: SwipeUiState,
    events: Flow<SwipeEvent>,
    onIntent: (SwipeIntent) -> Unit,
    mediaLoader: MediaLoader,
    haptics: Haptics,
    reduceMotion: Boolean,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
    autoplayVideos: Boolean = true,
    videoSlot: VideoPlayerSlot = DefaultVideoPlayerSlot,
) {
    val spacing = SwipeTheme.spacing

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(SwipeTheme.colors.bg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            state.finished != null -> {
                // FR-21: the container navigates to the summary on `finished`;
                // hold the background until the route swap lands.
            }

            state.current == null -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    EmptyState(
                        onTryAnotherSource = onClose,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // A start failure (or the counts-unavailable finish,
                    // WP-2.2) still surfaces here: the empty state carries no
                    // action bar, so the §5.7 slot rides the bottom edge.
                    state.toast?.let { message ->
                        ToastSlot(
                            message = message,
                            onIntent = onIntent,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = spacing.gutter, vertical = spacing.s),
                        )
                    }
                }
            }

            else -> {
                DeckContent(
                    state = state,
                    events = events,
                    onIntent = onIntent,
                    mediaLoader = mediaLoader,
                    haptics = haptics,
                    reduceMotion = reduceMotion,
                    onClose = onClose,
                    onOpenQueue = onOpenQueue,
                    autoplayVideos = autoplayVideos,
                    videoSlot = videoSlot,
                )
            }
        }
    }
}

/**
 * The §5.7 [Toast] for the current [ToastMessage]: the FR-22 favorite confirm
 * carries the warning-tinted heart and its "Delete" action, every other
 * message rides alone; all of them dismiss through [SwipeIntent.DismissToast].
 */
@Composable
private fun ToastSlot(
    message: ToastMessage,
    onIntent: (SwipeIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val confirm = message == ToastMessage.FavoriteConfirm
    Toast(
        text = toastText(message),
        icon = if (confirm) Res.drawable.ic_favorite else null,
        iconTint = SwipeTheme.colors.warning,
        // §5.7: a confirm-style toast stays until the user acts or the deck
        // moves on; a 3 s fuse would silently cancel the confirmation window.
        autoDismissDelay = if (confirm) NEVER_AUTO_DISMISS else DEFAULT_AUTO_DISMISS,
        actionLabel = if (confirm) "Delete" else null,
        onAction =
            if (confirm) {
                { onIntent(SwipeIntent.ConfirmFavoriteDelete) }
            } else {
                null
            },
        onDismiss = { onIntent(SwipeIntent.DismissToast) },
        modifier = modifier,
    )
}

/**
 * The deck column: TopBar, the card stack, and the ActionBar, with the
 * hoisted `SwipeCardState` driving every card animation.
 */
@Composable
private fun DeckContent(
    state: SwipeUiState,
    events: Flow<SwipeEvent>,
    onIntent: (SwipeIntent) -> Unit,
    mediaLoader: MediaLoader,
    haptics: Haptics,
    reduceMotion: Boolean,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    autoplayVideos: Boolean,
    videoSlot: VideoPlayerSlot,
) {
    val cardState = rememberSwipeCardState()
    val scope = rememberCoroutineScope()
    var chromeVisible by remember(state.current?.id) { mutableStateOf(true) }
    var fillMode by remember(state.current?.id) { mutableStateOf(false) }

    // Shared commit for both paths: the §5.6 haptic, then the intent — the
    // ViewModel persists before the deck advances (NFR-7).
    val commit: (SwipeVerdict) -> Unit = { verdict ->
        verdict.fireHaptic(haptics)
        onIntent(verdict.toIntent())
    }
    // §5.6 button path: nudge + fly-off first so the motion reads, then the
    // same commit a gesture makes.
    val buttonCommit: (SwipeVerdict) -> Unit = { verdict ->
        scope.launch {
            cardState.flyOff(verdict, reduceMotion)
            commit(verdict)
        }
    }

    CardMotionEffects(cardState, events, state.revertToken, state.current?.id, reduceMotion)

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            progress = state.progress,
            queueCount = state.queueCount,
            onClose = onClose,
            onOpenQueue = onOpenQueue,
            visible = chromeVisible,
        )
        state.current?.let { current ->
            DeckStage(
                current = current,
                state = state,
                cardState = cardState,
                mediaLoader = mediaLoader,
                haptics = haptics,
                reduceMotion = reduceMotion,
                chromeVisible = chromeVisible,
                fillMode = fillMode,
                autoplayVideos = autoplayVideos,
                onIntent = onIntent,
                onDismiss = commit,
                onToggleChrome = { chromeVisible = !chromeVisible },
                onToggleFill = { fillMode = !fillMode },
                videoSlot = videoSlot,
            )
        }
        // §5.7 Toast slot: above the action bar (FR-15, FR-22, NFR-7).
        state.toast?.let { message ->
            ToastSlot(
                message = message,
                onIntent = onIntent,
                modifier = Modifier.padding(horizontal = SwipeTheme.spacing.gutter),
            )
        }
        ActionBar(
            canUndo = state.canUndo,
            onKeep = { buttonCommit(SwipeVerdict.KEEP) },
            onDelete = { buttonCommit(SwipeVerdict.DELETE) },
            onSkip = { buttonCommit(SwipeVerdict.SKIP) },
            onUndo = { onIntent(SwipeIntent.Undo) },
        )
    }
}

/**
 * The FR-16/FR-17 video interaction state of the current card: the
 * autoplay-off first tap, the FR-17 hold, and the scrubber trio (the
 * player's last reported fraction, the finger's override while scrubbing,
 * and the pending seek). A new instance per card id, so the next card
 * starts at rest.
 */
private class DeckVideoState {
    var started by mutableStateOf(false)
    var livePress by mutableStateOf(false)
    var reportedFraction by mutableFloatStateOf(0f)
    var seekToken by mutableLongStateOf(0L)
    var seekTarget by mutableStateOf<SeekTarget?>(null)
    private var scrubbing by mutableStateOf(false)
    private var scrubFraction by mutableFloatStateOf(0f)

    /** The scrubber's visible fraction: the finger outranks the player's ticks. */
    val visibleFraction: Float
        get() = if (scrubbing) scrubFraction else reportedFraction

    /** A player tick; ignored while the finger is on the scrubber. */
    fun onProgress(fraction: Float) {
        if (!scrubbing) reportedFraction = fraction
    }

    /** A scrubber drag step: show it now, seek the player, count the event. */
    fun onScrub(fraction: Float) {
        scrubbing = true
        scrubFraction = fraction
        seekToken += 1L
        seekTarget = SeekTarget(seekToken, fraction)
    }

    /** Release or cancel: the player's own reports take over again. */
    fun onScrubEnd() {
        scrubbing = false
    }
}

/**
 * The current card and its scrubber: the [CardStack] plus the FR-16 scrubber
 * below it in the action-bar area — videos only, so §5.6 keeps card drags and
 * scrubber drags disjoint.
 */
@Composable
private fun ColumnScope.DeckStage(
    current: CardState,
    state: SwipeUiState,
    cardState: SwipeCardState,
    mediaLoader: MediaLoader,
    haptics: Haptics,
    reduceMotion: Boolean,
    chromeVisible: Boolean,
    fillMode: Boolean,
    autoplayVideos: Boolean,
    onIntent: (SwipeIntent) -> Unit,
    onDismiss: (SwipeVerdict) -> Unit,
    onToggleChrome: () -> Unit,
    onToggleFill: () -> Unit,
    videoSlot: VideoPlayerSlot,
) {
    // All video state is per-card (kept fresh by the current id's key).
    val video = remember(current.id) { DeckVideoState() }
    CardStack(
        card = current,
        next = state.next,
        cardState = cardState,
        mediaLoader = mediaLoader,
        reduceMotion = reduceMotion,
        chromeVisible = chromeVisible,
        fillMode = fillMode,
        autoplayVideos = autoplayVideos,
        videoStarted = video.started,
        muted = state.muted,
        livePress = video.livePress,
        seekTarget = video.seekTarget,
        onDismiss = onDismiss,
        onToggleChrome = onToggleChrome,
        onToggleMute = { onIntent(SwipeIntent.ToggleMute) },
        onToggleFill = onToggleFill,
        onThresholdCrossed = { haptics.selection() },
        onVideoStart = { video.started = true },
        onVideoProgress = video::onProgress,
        onLivePressStart = { video.livePress = true },
        onPressUp = { video.livePress = false },
        videoSlot = videoSlot,
    )
    if (current.type == MediaType.VIDEO) {
        VideoScrubber(
            durationMs = current.durationMs ?: 0L,
            fraction = video.visibleFraction,
            onScrub = video::onScrub,
            onScrubEnd = video::onScrubEnd,
        )
    }
}

/** Copy for each [ToastMessage]; only FR-22 and FR-15 strings are spec-fixed. */
private fun toastText(message: ToastMessage): String =
    when (message) {
        ToastMessage.SaveFailed -> "Couldn't save — try again"
        ToastMessage.Undone -> "Undone"
        ToastMessage.FavoriteConfirm -> "This is a favorite — delete anyway?"
        ToastMessage.StartFailed -> "Couldn't load your library — try again"
    }

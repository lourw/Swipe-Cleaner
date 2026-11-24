package dev.swipe.ui.screens.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import dev.swipe.data.Haptics
import dev.swipe.data.MediaLoader
import dev.swipe.data.NoPerfTrace
import dev.swipe.data.PerfTrace
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.presentation.swipe.CardState
import dev.swipe.presentation.swipe.SwipeEvent
import dev.swipe.presentation.swipe.SwipeIntent
import dev.swipe.presentation.swipe.SwipeUiState
import dev.swipe.presentation.swipe.ToastMessage
import dev.swipe.ui.components.FavoriteConfirmCard
import dev.swipe.ui.components.ToastHost
import dev.swipe.ui.components.ToastSpec
import dev.swipe.ui.components.media.DefaultVideoPlayerSlot
import dev.swipe.ui.components.media.SeekTarget
import dev.swipe.ui.components.media.VideoPlayerSlot
import dev.swipe.ui.components.swipe.SwipeCardState
import dev.swipe.ui.components.swipe.SwipeVerdict
import dev.swipe.ui.components.swipe.rememberSwipeCardState
import dev.swipe.ui.components.toastHostPlacement
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
 * (§5.7), the §5.6 [SwipeCard] gesture in the middle, and the §5.7 overlay
 * above the bar (FR-15 "Undone", NFR-7 revert toasts, and the FR-22 favorite
 * confirm card — WP-8.9).
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
 * - **Share** (FR-32): the ActionBar's share button sends
 *   [SwipeIntent.Share] straight away — no fly-off, haptic, or verdict —
 *   and shows its loading state while [SwipeUiState.sharing].
 *
 * With no card and no summary to show the screen renders the §5.8
 * [EmptyState]; while the deck is still opening ([SwipeUiState.loading], FR-6,
 * WP-8.3) the [DeckLoadingPlaceholder] stands in instead, so a slow open never
 * flashes "nothing to review". Once [SwipeUiState.finished] is set the container
 * navigates to the summary (FR-21), so this keeps the deck area empty meanwhile.
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
    perfTrace: PerfTrace = NoPerfTrace,
    autoplayVideos: Boolean = true,
    videoSlot: VideoPlayerSlot = DefaultVideoPlayerSlot,
) {
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

            state.current == null && state.loading -> {
                // FR-6 (WP-8.3): the deck is still opening — a card placeholder
                // stands in so a slow first metadata page never flashes the
                // §5.8 empty state.
                DeckLoadingPlaceholder(modifier = Modifier.fillMaxSize())
            }

            state.current == null -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    EmptyState(
                        onTryAnotherSource = onClose,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // A start failure (or the counts-unavailable finish,
                    // WP-2.2) still surfaces here: the empty state carries no
                    // action bar, so the §5.7 host rides the bottom edge.
                    state.toast?.let { message ->
                        ToastHost(
                            toast = ToastSpec(text = toastText(message)),
                            onDismiss = { onIntent(SwipeIntent.DismissToast) },
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    // Safe area (WP-6.4): clear the Home Indicator.
                                    .toastHostPlacement(),
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
                    perfTrace = perfTrace,
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

/** Tag of the deck loading placeholder the screen tests assert on (FR-6, WP-8.3). */
internal const val DECK_LOADING_TEST_TAG = "deck-loading-placeholder"

/**
 * The deck-open placeholder (FR-6, WP-8.3): a raised card-shaped panel in the
 * deck area while [SwipeUiState.loading] is up — the start path is running and
 * the first card has not been published yet. Tokens only (§5): `surfaceRaised`
 * on the page background with the card radius; no spinner, no copy.
 */
@Composable
internal fun DeckLoadingPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .padding(
                    horizontal = SwipeTheme.spacing.m,
                    vertical = SwipeTheme.spacing.l,
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        SwipeTheme.colors.surfaceRaised,
                        RoundedCornerShape(SwipeTheme.radii.card),
                    ).testTag(DECK_LOADING_TEST_TAG),
        )
    }
}

/**
 * The deck's NFR-1 span tracker plus the shared commit lambda both the
 * gesture and the ActionBar paths take: open both next-card spans, fire the
 * §5.6 haptic, then the intent — the ViewModel persists before the deck
 * advances (NFR-7). The emissions in cardMedia close the spans.
 */
@Composable
internal fun rememberCommit(
    perfTrace: PerfTrace,
    haptics: Haptics,
    cardId: String?,
    onIntent: (SwipeIntent) -> Unit,
): Pair<CommitLatencyTrace, (SwipeVerdict) -> Unit> {
    val latency = remember(perfTrace) { CommitLatencyTrace(perfTrace) }
    val commit: (SwipeVerdict) -> Unit = { verdict ->
        latency.onCommit(cardId)
        verdict.fireHaptic(haptics)
        // FR-13: the verdict is bound to the card it was made on; the
        // callback is captured at release/tap time, so this is that card.
        if (cardId != null) onIntent(verdict.toIntent(cardId))
    }
    return latency to commit
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
    perfTrace: PerfTrace,
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

    // FR-15 (WP-9.10): the animation half of the queued-Undo window. The Decide
    // intent only goes out when the fly-off ends, so an Undo tapped mid-flight
    // belongs to the swipe in flight, not to any older step: hold it here (one
    // slot; extra taps are ignored) and forward it right after the Decide, where
    // the ViewModel's queue fires it once the commit lands — or drops it if the
    // write fails and the failure path returns the card anyway (NFR-7).
    var undoHeldForFlight by remember { mutableStateOf(false) }

    // NFR-1 spans and the shared commit: see [rememberCommit].
    val (latency, sendCommit) = rememberCommit(perfTrace, haptics, state.current?.id, onIntent)
    val commit: (SwipeVerdict) -> Unit = { verdict ->
        sendCommit(verdict)
        if (undoHeldForFlight) {
            undoHeldForFlight = false
            onIntent(SwipeIntent.Undo)
        }
    }
    // The single Undo affordance for the bar and the card's custom action: sent
    // straight when the card is at rest, held while one is committed and flying.
    val undo: () -> Unit = {
        if (cardState.committedVerdict != null) {
            undoHeldForFlight = true
        } else {
            onIntent(SwipeIntent.Undo)
        }
    }
    // §5.6 button path: nudge + fly-off first so the motion reads, then the
    // same commit a gesture makes. A card that is not at rest (a flight, a
    // snap-back, a finger on it) refuses the fly-off, and nothing is
    // committed or sent for it (FR-12, FR-13).
    val buttonCommit: (SwipeVerdict) -> Unit = { verdict ->
        scope.launch {
            if (cardState.flyOff(verdict, reduceMotion)) commit(verdict)
        }
    }

    CardMotionEffects(cardState, events, state.revertToken, state.current?.id, reduceMotion)

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            progress = state.progress,
            queueCount = state.queueCount,
            onClose = onClose,
            onOpenQueue = onOpenQueue,
            // FR-38: the Hide button names the card on screen and disables
            // itself while a hide or a decision write is in flight.
            onHide = { state.current?.let { onIntent(SwipeIntent.Hide(it.id)) } },
            hideEnabled = !(state.hiding || state.deciding),
            visible = chromeVisible,
        )
        state.current?.let { current ->
            // WP-9.3: the stage Box is the toast's anchor — its bottom edge
            // is the action bar's top edge — so the §5.7 toast overlays the
            // card instead of resizing it (the "Undone" toast used to shrink
            // the card mid-undo-return-animation).
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                DeckStage(
                    current = current,
                    state = state,
                    cardState = cardState,
                    mediaLoader = mediaLoader,
                    latency = latency,
                    haptics = haptics,
                    display = DeckDisplay(reduceMotion, chromeVisible, fillMode, autoplayVideos),
                    onIntent = onIntent,
                    onUndo = undo,
                    onDismiss = commit,
                    onButtonCommit = buttonCommit,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onToggleFill = { fillMode = !fillMode },
                    videoSlot = videoSlot,
                )
                DeckToastOverlay(state = state, reduceMotion = reduceMotion, onIntent = onIntent)
            }
        }
        ActionBar(
            canUndo = state.canUndo,
            onKeep = { buttonCommit(SwipeVerdict.KEEP) },
            onDelete = { buttonCommit(SwipeVerdict.DELETE) },
            onSkip = { buttonCommit(SwipeVerdict.SKIP) },
            onUndo = undo,
            // FR-32: straight to the intent — no fly-off, no haptic, no verdict.
            onShare = { onIntent(SwipeIntent.Share) },
            sharing = state.sharing,
        )
    }
}

/**
 * The §5.7 overlay of the deck: the FR-22 favorite confirm renders as the
 * [FavoriteConfirmCard] (WP-8.9) and every other message as a plain [ToastSpec]
 * through [ToastHost] — both anchored above the action bar (FR-15, NFR-7) so
 * the card's size never depends on them. The confirm's Delete names the
 * pending card (FR-13); its Cancel and any toast dismissal are the same
 * [SwipeIntent.DismissToast], which records nothing.
 */
@Composable
private fun BoxScope.DeckToastOverlay(
    state: SwipeUiState,
    reduceMotion: Boolean,
    onIntent: (SwipeIntent) -> Unit,
) {
    state.toast?.let { message ->
        val pending = state.pendingFavoriteDelete
        if (message == ToastMessage.FavoriteConfirm && pending != null) {
            FavoriteConfirmCard(
                onCancel = { onIntent(SwipeIntent.DismissToast) },
                // FR-13: the confirm names the pending card.
                onDelete = { onIntent(SwipeIntent.ConfirmFavoriteDelete(pending)) },
                reduceMotion = reduceMotion,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        // The action bar below clears the Home
                        // Indicator itself, so the card doesn't.
                        .toastHostPlacement(clearHomeIndicator = false),
            )
        } else {
            ToastHost(
                toast = ToastSpec(text = toastText(message)),
                onDismiss = { onIntent(SwipeIntent.DismissToast) },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        // The action bar below clears the Home
                        // Indicator itself, so the toast doesn't.
                        .toastHostPlacement(clearHomeIndicator = false),
            )
        }
    }
}

/**
 * The FR-16/FR-17 video interaction state of the current card: the
 * autoplay-off first tap, the FR-17 hold, and the scrubber trio (the
 * player's last reported fraction, the finger's override while scrubbing,
 * and the pending seek). A new instance per card id, so the next card
 * starts at rest. R2 remounts the player fresh on every settle, so a video
 * restarts from 0 after a drag, an undo, or a swipe-away-and-back — the
 * watched position is deliberately not carried across unmounts. Each
 * composition it is snapshotted into an immutable [VideoUiState] for
 * [CardStack] (WP-9.2); the scrubbing state stays here, on the scrubber side.
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
 * scrubber drags disjoint. The scrubber **row's height is reserved on every
 * card** (WP-9.3): a photo card owns the same slot as a video's scrubber, so
 * switching card types never resizes the card.
 */
@Composable
private fun BoxScope.DeckStage(
    current: CardState,
    state: SwipeUiState,
    cardState: SwipeCardState,
    mediaLoader: MediaLoader,
    latency: CommitLatencyTrace,
    haptics: Haptics,
    display: DeckDisplay,
    onIntent: (SwipeIntent) -> Unit,
    onUndo: () -> Unit,
    onDismiss: (SwipeVerdict) -> Unit,
    onButtonCommit: (SwipeVerdict) -> Unit,
    onToggleChrome: () -> Unit,
    onToggleFill: () -> Unit,
    videoSlot: VideoPlayerSlot,
) {
    // All video state is per-card (kept fresh by the current id's key).
    val video = remember(current.id) { DeckVideoState() }
    // WP-9.2: the card's whole interaction surface, assembled here from the
    // stage's intents and the per-card video state and handed to the stack
    // as one holder.
    val callbacks =
        DeckCallbacks(
            onDismiss = onDismiss,
            onButtonCommit = onButtonCommit,
            // FR-15 (WP-9.10): the card's Undo custom action rides the same
            // fly-off-window gate as the action-bar button.
            onUndo = onUndo,
            onToggleChrome = onToggleChrome,
            onToggleMute = { onIntent(SwipeIntent.ToggleMute) },
            onToggleFill = onToggleFill,
            onThresholdCrossed = { haptics.selection() },
            // U3 (WP-8.9): the finger moving over the card while the FR-22
            // confirm is up cancels it — Cancel and drag-out are the same
            // dismissal, and nothing records a verdict on the pending
            // favorite without the confirm.
            onDragStart = {
                if (state.pendingFavoriteDelete != null) onIntent(SwipeIntent.DismissToast)
            },
            // FR-38: the card's Hide custom action sends the same intent the
            // TopBar button does, for the card on screen.
            onHide = { onIntent(SwipeIntent.Hide(current.id)) },
            onVideoStart = { video.started = true },
            onVideoProgress = video::onProgress,
            onLivePressStart = { video.livePress = true },
            onPressUp = { video.livePress = false },
        )
    Column(modifier = Modifier.fillMaxSize()) {
        CardStack(
            card = current,
            next = state.next,
            cardState = cardState,
            mediaLoader = mediaLoader,
            latency = latency,
            display = display,
            video =
                VideoUiState(
                    started = video.started,
                    muted = state.muted,
                    livePress = video.livePress,
                    seekTarget = video.seekTarget,
                ),
            canUndo = state.canUndo,
            callbacks = callbacks,
            videoSlot = videoSlot,
        )
        // WP-9.3: the scrubber row's height is reserved on every card, so
        // switching a photo card for a video card (or back) never resizes
        // the card; only the scrubber itself appears for videos.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(SwipeTheme.componentSizes.videoScrubberTouchHeight),
        ) {
            if (current.type == MediaType.VIDEO) {
                VideoScrubber(
                    durationMs = current.durationMs ?: 0L,
                    fraction = video.visibleFraction,
                    onScrub = video::onScrub,
                    onScrubEnd = video::onScrubEnd,
                )
            }
        }
    }
}

/**
 * Copy for each [ToastMessage]; only FR-22 and FR-15 strings are spec-fixed.
 * The FR-22 string rides the fallback toast only — the deck renders the
 * favorite confirm as the [FavoriteConfirmCard] (WP-8.9), which carries its
 * own title and message.
 */
private fun toastText(message: ToastMessage): String =
    when (message) {
        ToastMessage.SaveFailed -> "Couldn't save — try again"
        ToastMessage.Undone -> "Undone"
        ToastMessage.Hidden -> "Hidden in Photos"
        ToastMessage.HideFailed -> "Couldn't hide this item — try again"
        ToastMessage.FavoriteConfirm -> "This is a favorite — delete anyway?"
        ToastMessage.StartFailed -> "Couldn't load your library — try again"
        ToastMessage.ShareFailed -> "Couldn't load this item to share — try again"
    }

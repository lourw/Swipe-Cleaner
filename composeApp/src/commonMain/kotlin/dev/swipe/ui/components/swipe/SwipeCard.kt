package dev.swipe.ui.components.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.toSize
import dev.swipe.ui.theme.CARD_SHADOW_ALPHA
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Test hook for §5.6 Reduce Motion: the rotation the card layer actually
 * applies, published as a semantics property on the card node so
 * `SwipeCardTest` can assert it stays 0. graphicsLayer transforms are
 * invisible to semantics; this is the only property the card publishes.
 */
internal val RotationZSemanticsPropertyKey = SemanticsPropertyKey<Float>("SwipeCardRotationZ")

/**
 * One NFR-8 VoiceOver custom action on the [SwipeCard] node (Keep, Delete,
 * Skip, Undo): the label VoiceOver announces and the callback that runs the
 * decision — the caller wires it to the same path the action buttons take,
 * so accessibility decisions behave exactly like button decisions.
 */
class CardAccessibilityAction(
    val label: String,
    val action: () -> Unit,
)

/**
 * The full-bleed decision card (§5.7) with the §5.6 gesture stack:
 * horizontal drags toward KEEP/DELETE and up toward SKIP follow the finger,
 * rotate up to [MAX_ROTATION_DEG] about the bottom center, light up the
 * [VerdictStamp] and [EdgeGlow] linearly, and commit on release past the
 * threshold or on a fling over [FLING_VELOCITY_DP_S]; anything else springs
 * back. A commit flies the card off in [FLY_OFF_MS] and then calls
 * [onDismiss]; with [reduceMotion] it crossfades instead and never rotates.
 *
 * The component knows nothing about media or verdicts beyond the three
 * swipe directions: content comes from [content], decisions leave through
 * the callbacks, and haptics fire through [onThresholdCrossed] (the caller
 * decides what the device does).
 *
 * [onLongPress] fires while the finger is still down (Compose's long-click
 * semantics); [onPressUp] fires on the matching release or when a drag
 * cancels the press — the pair the Live Photo playback needs to stop on
 * release (FR-17).
 *
 * NFR-8: [accessibilityDescription] is the card's VoiceOver line and
 * [accessibilityActions] (Keep / Delete / Skip / Undo) ride the card node,
 * so a VoiceOver user decides without ever performing a swipe — the same
 * decisions the buttons run, wired by the caller to behave identically.
 */
@Composable
fun SwipeCard(
    onDismiss: (SwipeVerdict) -> Unit,
    modifier: Modifier = Modifier,
    state: SwipeCardState = rememberSwipeCardState(),
    reduceMotion: Boolean = false,
    accessibilityDescription: String? = null,
    accessibilityActions: List<CardAccessibilityAction> = emptyList(),
    onTap: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onPressUp: () -> Unit = {},
    onThresholdCrossed: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnThresholdCrossed by rememberUpdatedState(onThresholdCrossed)
    var cardSize by remember { mutableStateOf(Size.Zero) }
    // Observing the combinedClickable press: the up-edge the long-press pair
    // needs (FR-17); combinedClickable gives no release callback of its own.
    val clickInteractionSource = pressUpInteractionSource(onPressUp)

    val shape = RoundedCornerShape(SwipeTheme.radii.card)
    val activeVerdict = state.committedVerdict ?: if (state.dragging) previewVerdict(state.offset, cardSize) else null
    // §5.6: rotation is motion; Reduce Motion keeps the card square while it
    // crossfades out. Hoisted so the semantics hook below publishes exactly
    // what the layer applies.
    val appliedRotationZ = if (reduceMotion) 0f else rotationFor(state.offset, cardSize.width)

    Box(
        modifier =
            modifier
                .onSizeChanged { px ->
                    cardSize = px.toSize()
                    state.cardSize = cardSize
                    state.density = density
                }.semantics { set(RotationZSemanticsPropertyKey, appliedRotationZ) },
    ) {
        SwipeBackdrop(activeVerdict, state.offset, cardSize)
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        translationX = state.offset.x
                        translationY = state.offset.y
                        rotationZ = appliedRotationZ
                        transformOrigin = DRAG_TRANSFORM_ORIGIN
                        alpha = state.alpha
                    }.shadow(
                        elevation = SwipeTheme.componentSizes.cardShadowBlur,
                        shape = shape,
                        // §5.5: the one shadow in the app is soft black, blur 24, 40%.
                        ambientColor = Color.Black.copy(alpha = CARD_SHADOW_ALPHA),
                        spotColor = Color.Black.copy(alpha = CARD_SHADOW_ALPHA),
                    ).clip(shape)
                    .background(SwipeTheme.colors.bg)
                    .combinedClickable(
                        interactionSource = clickInteractionSource,
                        indication = null,
                        onClick = onTap,
                        onDoubleClick = onDoubleTap,
                        onLongClick = onLongPress,
                    ).semantics {
                        // NFR-8: the card is one VoiceOver element — its line
                        // and the Keep/Delete/Skip(/Undo) actions, so no
                        // gesture is ever required to decide.
                        if (accessibilityDescription != null) {
                            contentDescription = accessibilityDescription
                            role = Role.Image
                        }
                        if (accessibilityActions.isNotEmpty()) {
                            // Compose's custom-action contract wants a
                            // handled-flag; the decision always runs.
                            customActions =
                                accessibilityActions.map {
                                    CustomAccessibilityAction(
                                        label = it.label,
                                        action = {
                                            it.action()
                                            true
                                        },
                                    )
                                }
                        }
                    }.pointerInput(reduceMotion) {
                        swipeDragGestures(
                            state = state,
                            cardSize = { cardSize },
                            density = density,
                            reduceMotion = reduceMotion,
                            scope = scope,
                            onThresholdCrossed = currentOnThresholdCrossed,
                            onDismiss = currentOnDismiss,
                        )
                    },
        ) {
            content()
            VerdictStampOverlay(state.offset, cardSize)
        }
    }
}

/**
 * A [MutableInteractionSource] that reports the press's up-edge through
 * [onPressUp] (FR-17): [PressInteraction.Release] on a normal release and
 * [PressInteraction.Cancel] when a drag or long-press wins the gesture —
 * [combinedClickable] offers no release callback of its own, and the Live
 * Photo hold needs to stop on either edge.
 */
@Composable
private fun pressUpInteractionSource(onPressUp: () -> Unit): MutableInteractionSource {
    val source = remember { MutableInteractionSource() }
    val currentOnPressUp by rememberUpdatedState(onPressUp)
    LaunchedEffect(source) {
        source.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release || interaction is PressInteraction.Cancel) {
                currentOnPressUp()
            }
        }
    }
    return source
}

/**
 * The verdict-tinted glow behind the card (FR-18): it sits under the card,
 * so the card travelling over it reveals the pooled color on the swipe side.
 */
@Composable
private fun BoxScope.SwipeBackdrop(
    activeVerdict: SwipeVerdict?,
    offset: Offset,
    cardSize: Size,
) {
    if (activeVerdict != null) {
        EdgeGlow(
            verdict = activeVerdict,
            alpha = stampAlpha(activeVerdict, offset, cardSize),
        )
    }
}

/**
 * The verdict stamps riding on the card (§5.6/§5.7): KEEP top-left, DELETE
 * top-right, SKIP bottom-center, each at its own [stampAlpha] — never gated
 * on motion, so Reduce Motion still shows them. A stamp is mounted only
 * while it is fading in.
 */
@Composable
private fun BoxScope.VerdictStampOverlay(
    offset: Offset,
    cardSize: Size,
) {
    val keepAlpha = stampAlpha(SwipeVerdict.KEEP, offset, cardSize)
    if (keepAlpha > 0f) {
        VerdictStamp(
            verdict = SwipeVerdict.KEEP,
            alpha = keepAlpha,
            modifier = Modifier.align(Alignment.TopStart).padding(SwipeTheme.spacing.m),
        )
    }
    val deleteAlpha = stampAlpha(SwipeVerdict.DELETE, offset, cardSize)
    if (deleteAlpha > 0f) {
        VerdictStamp(
            verdict = SwipeVerdict.DELETE,
            alpha = deleteAlpha,
            modifier = Modifier.align(Alignment.TopEnd).padding(SwipeTheme.spacing.m),
        )
    }
    val skipAlpha = stampAlpha(SwipeVerdict.SKIP, offset, cardSize)
    if (skipAlpha > 0f) {
        VerdictStamp(
            verdict = SwipeVerdict.SKIP,
            alpha = skipAlpha,
            modifier = Modifier.align(Alignment.BottomCenter).padding(SwipeTheme.spacing.m),
        )
    }
}

/**
 * The §5.6 drag recognizer: the card follows the finger, [onThresholdCrossed]
 * fires once per threshold crossing (never again until the card re-enters
 * and crosses again), a release is judged by [verdictFor] — offset past the
 * direction threshold or a fling over [FLING_VELOCITY_DP_S] — and either
 * flies off and reports [onDismiss] or springs back.
 */
private suspend fun PointerInputScope.swipeDragGestures(
    state: SwipeCardState,
    cardSize: () -> Size,
    density: Float,
    reduceMotion: Boolean,
    scope: CoroutineScope,
    onThresholdCrossed: () -> Unit,
    onDismiss: (SwipeVerdict) -> Unit,
) {
    val tracker = VelocityTracker()
    var dragTotal = Offset.Zero
    var crossed: SwipeVerdict? = null
    detectDragGestures(
        onDragStart = { _: Offset ->
            state.dragging = true
            dragTotal = Offset.Zero
            crossed = null
            tracker.resetTracking()
        },
        onDrag = { change: PointerInputChange, dragAmount: Offset ->
            change.consume()
            tracker.addPosition(change.uptimeMillis, change.position)
            dragTotal += dragAmount
            scope.launch { state.snapTo(dragTotal) }
            val now = previewVerdict(dragTotal, cardSize())
            if (now != null && now != crossed) {
                crossed = now
                onThresholdCrossed()
            }
        },
        onDragEnd = {
            state.dragging = false
            val velocity = tracker.calculateVelocity()
            val verdict = verdictFor(dragTotal, Offset(velocity.x, velocity.y), cardSize(), density)
            if (verdict != null) {
                state.committedVerdict = verdict
                scope.launch {
                    state.flyOffCommitted(reduceMotion)
                    onDismiss(verdict)
                }
            } else {
                // Set the flag before the launch so no frame between release
                // and the first snap-back frame counts as idle (R2: the live
                // player must not mount for a frame mid-snap-back).
                state.animating = true
                scope.launch { state.snapBack() }
            }
        },
        onDragCancel = {
            state.dragging = false
            state.animating = true
            scope.launch { state.snapBack() }
        },
    )
}

package dev.swipe.ui.components.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.toSize
import dev.swipe.ui.theme.CARD_SHADOW_ALPHA
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
 */
@Composable
fun SwipeCard(
    onDismiss: (SwipeVerdict) -> Unit,
    modifier: Modifier = Modifier,
    state: SwipeCardState = rememberSwipeCardState(),
    reduceMotion: Boolean = false,
    onTap: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onThresholdCrossed: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnThresholdCrossed by rememberUpdatedState(onThresholdCrossed)
    var cardSize by remember { mutableStateOf(Size.Zero) }

    val shape = RoundedCornerShape(SwipeTheme.radii.card)
    val activeVerdict = state.committedVerdict ?: if (state.dragging) previewVerdict(state.offset, cardSize) else null

    Box(
        modifier =
            modifier.onSizeChanged { px ->
                cardSize = px.toSize()
                state.cardSize = cardSize
                state.density = density
            },
    ) {
        // The glow sits behind the card, so the card travelling over it
        // reveals the pooled color on the swipe side.
        if (activeVerdict != null) {
            EdgeGlow(
                verdict = activeVerdict,
                alpha = stampAlpha(activeVerdict, state.offset, cardSize),
            )
        }
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        translationX = state.offset.x
                        translationY = state.offset.y
                        // §5.6: rotation is motion; Reduce Motion keeps the card
                        // square while it crossfades out.
                        rotationZ = if (reduceMotion) 0f else rotationFor(state.offset, cardSize.width)
                        transformOrigin = DRAG_TRANSFORM_ORIGIN
                        alpha = state.alpha
                    }.shadow(
                        elevation = SwipeTheme.componentSizes.cardShadowBlur,
                        shape = shape,
                        // §5.5: the one shadow in the app is soft black, blur 24, 40%.
                        ambientColor = Color.Black.copy(alpha = CARD_SHADOW_ALPHA),
                        spotColor = Color.Black.copy(alpha = CARD_SHADOW_ALPHA),
                    ).clip(shape)
                    .background(SwipeTheme.colors.surfaceRaised)
                    .combinedClickable(
                        onClick = onTap,
                        onDoubleClick = onDoubleTap,
                        onLongClick = onLongPress,
                    ).pointerInput(reduceMotion) {
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
            content = content,
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
                    state.flyOff(reduceMotion)
                    onDismiss(verdict)
                }
            } else {
                scope.launch { state.snapBack() }
            }
        },
        onDragCancel = {
            state.dragging = false
            scope.launch { state.snapBack() }
        },
    )
}

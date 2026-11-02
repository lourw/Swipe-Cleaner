package dev.swipe.ui.components.swipe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * Hoisted gesture state of a [SwipeCard]: the current drag [offset], the
 * flight/return/snap animations, and the nudge used by the action buttons
 * (§5.6). All durations, springs, and travel distances come from
 * [SwipeSpec.kt](SwipeSpec.kt).
 *
 * The card itself only renders this state; callers hold it to drive undo
 * (`returnFrom`) and button nudges from outside the gesture.
 */
@Stable
class SwipeCardState internal constructor(
    private val offsetAnim: Animatable<Offset, AnimationVector2D>,
    private val alphaAnim: Animatable<Float, AnimationVector1D>,
) {
    /** Live drag offset in pixels, read by the card's transform. */
    val offset: Offset get() = offsetAnim.value

    /** Fade for the reduce-motion crossfade; 1 while motion is on. */
    val alpha: Float get() = alphaAnim.value

    /** True while a finger is dragging the card. */
    var dragging: Boolean by mutableStateOf(false)
        internal set

    /** The verdict a completed fly-off committed, or null while in the deck. */
    var committedVerdict: SwipeVerdict? by mutableStateOf(null)
        internal set

    internal var cardSize: Size = Size.Zero
    internal var density: Float = 1f

    /** Where the card must be for its edges to clear the screen. */
    private fun exitTarget(verdict: SwipeVerdict): Offset =
        when (verdict) {
            SwipeVerdict.KEEP -> Offset(cardSize.width * FLY_OFF_TRAVEL, offsetAnim.value.y)
            SwipeVerdict.DELETE -> Offset(-cardSize.width * FLY_OFF_TRAVEL, offsetAnim.value.y)
            SwipeVerdict.SKIP -> Offset(offsetAnim.value.x, -cardSize.height * FLY_OFF_TRAVEL)
        }

    /**
     * Throw the card off-screen along its current drag line in
     * [FLY_OFF_MS] (§5.6); with [reduceMotion] it crossfades out over
     * [REDUCE_MOTION_CROSSFADE_MS] instead.
     */
    suspend fun flyOff(reduceMotion: Boolean = false) {
        val verdict = committedVerdict ?: return
        if (reduceMotion) {
            alphaAnim.animateTo(0f, tween(REDUCE_MOTION_CROSSFADE_MS, easing = LinearEasing))
        } else {
            offsetAnim.animateTo(
                targetValue = exitTarget(verdict),
                animationSpec = tween(FLY_OFF_MS, easing = FastOutLinearInEasing),
            )
        }
    }

    /**
     * Undo: bring a committed card back from its exit edge in
     * [UNDO_RETURN_MS] with [FastOutSlowInEasing] (§5.6, FR-15), or
     * crossfade back in with [reduceMotion].
     */
    suspend fun returnFrom(reduceMotion: Boolean = false) {
        val verdict = committedVerdict
        if (reduceMotion) {
            offsetAnim.snapTo(Offset.Zero)
            alphaAnim.snapTo(0f)
            committedVerdict = null
            alphaAnim.animateTo(1f, tween(REDUCE_MOTION_CROSSFADE_MS, easing = LinearEasing))
        } else {
            val entry = verdict?.let { exitTarget(it) } ?: Offset.Zero
            offsetAnim.snapTo(entry)
            alphaAnim.snapTo(1f)
            committedVerdict = null
            offsetAnim.animateTo(
                targetValue = Offset.Zero,
                animationSpec = tween(UNDO_RETURN_MS, easing = FastOutSlowInEasing),
            )
        }
    }

    /** A below-threshold release springs the card back to center (§5.6). */
    suspend fun snapBack() {
        offsetAnim.animateTo(
            targetValue = Offset.Zero,
            animationSpec = spring(dampingRatio = SNAP_DAMPING, stiffness = SNAP_STIFFNESS),
        )
    }

    /**
     * The action buttons push the card [NUDGE_DP] toward the verdict and
     * back over [NUDGE_MS] (§5.6 "tapping a button nudges the card") so the
     * button feels like the gesture it replaces.
     */
    suspend fun nudge(verdict: SwipeVerdict) {
        if (dragging || committedVerdict != null) return
        val nudgePx = NUDGE_DP.value * density
        val peak =
            when (verdict) {
                SwipeVerdict.KEEP -> Offset(nudgePx, 0f)
                SwipeVerdict.DELETE -> Offset(-nudgePx, 0f)
                SwipeVerdict.SKIP -> Offset(0f, -nudgePx)
            }
        offsetAnim.animateTo(peak, tween(NUDGE_MS / 2, easing = FastOutLinearInEasing))
        offsetAnim.animateTo(Offset.Zero, tween(NUDGE_MS / 2, easing = FastOutSlowInEasing))
    }

    internal suspend fun snapTo(offset: Offset) = offsetAnim.snapTo(offset)
}

/** Remembered [SwipeCardState] for one card slot. */
@Composable
fun rememberSwipeCardState(): SwipeCardState =
    remember {
        SwipeCardState(
            offsetAnim = Animatable(Offset.Zero, Offset.VectorConverter),
            alphaAnim = Animatable(1f, Float.VectorConverter),
        )
    }

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
 * flight/return/snap animations, and the button-path commit (§5.6). All
 * durations, springs, and travel distances come from
 * [SwipeSpec.kt](SwipeSpec.kt).
 *
 * The card itself only renders this state; callers hold it to drive undo
 * (`returnFrom`) and the button path (`flyOff`) from outside the gesture.
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

    /**
     * Where the card must sit for its edges to clear the screen when it
     * left toward [verdict] — the point undo flies it back in from (§5.6).
     */
    private fun undoEntryPoint(verdict: SwipeVerdict): Offset =
        when (verdict) {
            SwipeVerdict.KEEP -> Offset(cardSize.width * FLY_OFF_TRAVEL, 0f)
            SwipeVerdict.DELETE -> Offset(-cardSize.width * FLY_OFF_TRAVEL, 0f)
            SwipeVerdict.SKIP -> Offset(0f, -cardSize.height * FLY_OFF_TRAVEL)
        }

    /**
     * Throw the card off-screen along its current drag line — the release
     * direction extended until off-screen ([flyOffTarget], §5.6) — in
     * [FLY_OFF_MS]; with [reduceMotion] it crossfades out over
     * [REDUCE_MOTION_CROSSFADE_MS] instead. The gesture path: the verdict
     * must already be committed.
     */
    internal suspend fun flyOffCommitted(reduceMotion: Boolean = false) {
        val verdict = committedVerdict ?: return
        if (reduceMotion) {
            alphaAnim.animateTo(0f, tween(REDUCE_MOTION_CROSSFADE_MS, easing = LinearEasing))
        } else {
            offsetAnim.animateTo(
                targetValue = flyOffTarget(offsetAnim.value, directionToward(verdict), cardSize),
                animationSpec = tween(FLY_OFF_MS, easing = FastOutLinearInEasing),
            )
        }
    }

    /**
     * The button path (§5.6 "Button-triggered action — Same fly-off, with a
     * synthetic 30 dp nudge first (80 ms) so the motion reads"): commit
     * [verdict], push the card [NUDGE_DP] toward it over [NUDGE_MS], then run
     * the normal [FLY_OFF_MS] fly-off. Returns once the card is off-screen
     * with [committedVerdict] set; the caller then does what a gesture commit
     * does — dismiss the item and advance the deck. Ignored while dragging
     * or if a verdict is already committed. With [reduceMotion] the nudge is
     * skipped too and the card only crossfades (§5.6: "No rotation or
     * fly-off").
     */
    suspend fun flyOff(
        verdict: SwipeVerdict,
        reduceMotion: Boolean = false,
    ) {
        if (dragging || committedVerdict != null) return
        committedVerdict = verdict
        if (!reduceMotion) {
            val nudgePx = NUDGE_DP.value * density
            offsetAnim.animateTo(directionToward(verdict) * nudgePx, tween(NUDGE_MS, easing = FastOutLinearInEasing))
        }
        flyOffCommitted(reduceMotion)
    }

    /**
     * Undo: bring a committed card back from the edge of [verdict] in
     * [UNDO_RETURN_MS] with [FastOutSlowInEasing] (§5.6, FR-15), or
     * crossfade back in with [reduceMotion].
     */
    suspend fun returnFrom(
        verdict: SwipeVerdict,
        reduceMotion: Boolean = false,
    ) {
        if (reduceMotion) {
            offsetAnim.snapTo(Offset.Zero)
            alphaAnim.snapTo(0f)
            committedVerdict = null
            alphaAnim.animateTo(1f, tween(REDUCE_MOTION_CROSSFADE_MS, easing = LinearEasing))
        } else {
            offsetAnim.snapTo(undoEntryPoint(verdict))
            alphaAnim.snapTo(1f)
            committedVerdict = null
            offsetAnim.animateTo(
                targetValue = Offset.Zero,
                animationSpec = tween(UNDO_RETURN_MS, easing = FastOutSlowInEasing),
            )
        }
    }

    /** Put the slot back at rest for the next item after a fly-off. */
    suspend fun resetForNext() {
        committedVerdict = null
        offsetAnim.snapTo(Offset.Zero)
        alphaAnim.snapTo(1f)
    }

    /** A below-threshold release springs the card back to center (§5.6). */
    suspend fun snapBack() {
        offsetAnim.animateTo(
            targetValue = Offset.Zero,
            animationSpec = spring(dampingRatio = SNAP_DAMPING, stiffness = SNAP_STIFFNESS),
        )
    }

    /**
     * The action buttons push the card [NUDGE_DP] toward [verdict] as the
     * first move of [flyOff] (§5.6); the unit axes are just direction, the
     * distance and duration come from the spec constants.
     */
    private fun directionToward(verdict: SwipeVerdict): Offset =
        when (verdict) {
            SwipeVerdict.KEEP -> Offset(1f, 0f)
            SwipeVerdict.DELETE -> Offset(-1f, 0f)
            SwipeVerdict.SKIP -> Offset(0f, -1f)
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

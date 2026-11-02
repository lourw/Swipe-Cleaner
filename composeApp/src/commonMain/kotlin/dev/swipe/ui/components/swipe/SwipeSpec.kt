package dev.swipe.ui.components.swipe

import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Every motion and gesture number from REQUIREMENTS §5.6 as a named constant.
// AGENTS.md hard rule 4: these values are the spec, not suggestions. They
// live here and nowhere else, and change only together with §5.6.

/** Commit threshold, horizontal: 35% of the card width (§5.6). */
const val COMMIT_FRACTION_HORIZONTAL = 0.35f

/** Commit threshold, up (Skip): 25% of the card height (§5.6). */
const val COMMIT_FRACTION_UP = 0.25f

/** Fling velocity that commits below the distance threshold, in dp/s (§5.6). */
const val FLING_VELOCITY_DP_S = 1000f

/** Rotation while dragging: `(dx / cardWidth) × 12°`, clamped to ±12° (§5.6). */
const val MAX_ROTATION_DEG = 12f

/** Fly-off on commit: 220 ms, `FastOutLinearInEasing` (§5.6). */
const val FLY_OFF_MS = 220

/** Undo return: 260 ms, `FastOutSlowInEasing` (§5.6). */
const val UNDO_RETURN_MS = 260

/** Button-triggered action: synthetic nudge distance (§5.6). */
val NUDGE_DP: Dp = 30.dp

/** Button-triggered action: synthetic nudge duration, ms (§5.6). */
const val NUDGE_MS = 80

/** Snap-back spring damping ratio (§5.6). */
const val SNAP_DAMPING = 0.7f

/** Snap-back spring stiffness (§5.6). */
val SNAP_STIFFNESS: Float = Spring.StiffnessMediumLow

/** Card underneath: scale start (§5.6, to 1.0). */
const val UNDER_SCALE_FROM = 0.94f

/** Card underneath: alpha start (§5.6, to 1.0). */
const val UNDER_ALPHA_FROM = 0.6f

/** Edge glow: maximum alpha, reached at the threshold (§5.6: 30%). */
const val EDGE_GLOW_MAX_ALPHA = 0.30f

/** Stamp rotation: KEEP −12°, DELETE +12° (§5.6). */
const val STAMP_ROTATION_DEG = 12f

/** Reduce Motion commit crossfade duration, ms (§5.6). */
const val REDUCE_MOTION_CROSSFADE_MS = 150

/**
 * Card drag rotation pivot: bottom center, from §5.6 ("pivot at the bottom
 * center"). Kept here so the 0.5 / 1.0 origin numbers live with the rest of
 * the §5.6 values.
 */
val DRAG_TRANSFORM_ORIGIN: TransformOrigin = TransformOrigin(0.5f, 1f)

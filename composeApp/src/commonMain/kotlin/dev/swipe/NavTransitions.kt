package dev.swipe

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Navigation transition durations (milliseconds): the Reduce Motion
 * crossfade matches the §5.6 card crossfade; the slide is the iOS-style
 * push, with the vacating screen parallaxing at `1 / NAV_EXIT_PARALLAX_DIVISOR`
 * of the travel.
 */
private const val NAV_CROSSFADE_MS = 150
private const val NAV_SLIDE_MS = 350
private const val NAV_EXIT_PARALLAX_DIVISOR = 3

/**
 * The §5.6 Reduce Motion navigation transitions: screen changes slide while
 * motion is on and crossfade over [NAV_CROSSFADE_MS] with [reduceMotion] —
 * the same "crossfade instead of move" language the card commit uses. The
 * entering screen pushes full-width; the vacating one parallaxes at
 * `1 / NAV_EXIT_PARALLAX_DIVISOR` of the travel.
 */
internal fun navEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) {
        fadeIn(tween(NAV_CROSSFADE_MS))
    } else {
        slideInHorizontally(tween(NAV_SLIDE_MS)) { it }
    }

internal fun navExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) {
        fadeOut(tween(NAV_CROSSFADE_MS))
    } else {
        slideOutHorizontally(tween(NAV_SLIDE_MS)) { -it / NAV_EXIT_PARALLAX_DIVISOR }
    }

internal fun navPopEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) {
        fadeIn(tween(NAV_CROSSFADE_MS))
    } else {
        slideInHorizontally(tween(NAV_SLIDE_MS)) { -it / NAV_EXIT_PARALLAX_DIVISOR }
    }

internal fun navPopExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) {
        fadeOut(tween(NAV_CROSSFADE_MS))
    } else {
        slideOutHorizontally(tween(NAV_SLIDE_MS)) { it }
    }

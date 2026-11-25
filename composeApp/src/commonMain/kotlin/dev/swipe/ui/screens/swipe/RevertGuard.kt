package dev.swipe.ui.screens.swipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * The NFR-7 revert one-shot: returns a predicate that reports `true` only
 * for a token higher than every token seen before, and remembers the highest
 * across recomposition, disposal, and state restoration ([rememberSaveable]).
 * Without the saved high-water mark, `LaunchedEffect(revertToken)` restarts
 * on re-entry and replays the return animation for a revert the user already
 * watched (or for one from a previous app session).
 */
@Composable
internal fun rememberRevertGuard(): (Long) -> Boolean {
    val highestSeen = rememberSaveable { mutableLongStateOf(0L) }
    return { token ->
        if (token > highestSeen.longValue) {
            highestSeen.longValue = token
            true
        } else {
            false
        }
    }
}

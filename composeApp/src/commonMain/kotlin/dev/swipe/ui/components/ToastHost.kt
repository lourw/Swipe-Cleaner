package dev.swipe.ui.components

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * Everything one screen shows in its §5.7 toast, as one immutable value
 * (WP-9.3 — one toast pattern for every screen). The text is the only
 * required part; [icon]/[iconTint] carry the §5.3 tone of a special toast
 * (the FR-22 favorite confirm's warning heart), [actionLabel] adds the
 * confirm's trailing "Delete", and [autoDismisses] marks the confirm style:
 * §5.7 keeps a confirm toast on screen until the user acts or the deck moves
 * on, where every other toast fades after 3 s.
 */
@Immutable
data class ToastSpec(
    val text: String,
    val icon: String? = null,
    /** §5.3 tone for [icon]; null keeps `textPrimary`. */
    val iconTint: Color? = null,
    /** Trailing action label (the confirm's "Delete"); null renders none. */
    val actionLabel: String? = null,
    /** False for the §5.7 confirm style: stays until its owner removes it. */
    val autoDismisses: Boolean = true,
)

/**
 * The one toast host (WP-9.3, §5.7/§6.6): renders at most one [Toast] for
 * [toast] and owns the *only* auto-dismiss timer and dismissal signal —
 * neither the screen nor the [Toast] keeps a second visibility flag. When
 * the 3 s fuse lands (or the user taps the action of a toast whose owner
 * clears it on tap), [onDismiss] tells the owner to drop the spec; the
 * confirm style ([ToastSpec.autoDismisses] false) never fires the timer and
 * stays until its owner removes it.
 *
 * Placement is one place, bottom, per §5.7: every screen aligns the host to
 * the bottom of its content box and adds [toastHostPlacement] — the deck
 * overlays the card's bottom edge directly above the action bar so the card
 * never resizes when a toast appears.
 *
 * Virtual time: under `runComposeUiTest` the delay below runs on the test
 * scheduler, so tests advance it with `mainClock.advanceTimeBy` instead of
 * waiting a real 3 s (the WP-1.2 testability note).
 */
@Composable
fun ToastHost(
    toast: ToastSpec?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onAction: () -> Unit = {},
) {
    val current = toast ?: return
    val dismiss by rememberUpdatedState(onDismiss)
    if (current.autoDismisses) {
        LaunchedEffect(current) {
            delay(DEFAULT_AUTO_DISMISS)
            dismiss()
        }
    }
    Toast(
        text = current.text,
        icon = current.icon,
        iconTint = current.iconTint ?: SwipeTheme.colors.textPrimary,
        actionLabel = current.actionLabel,
        onAction = if (current.actionLabel != null) onAction else null,
        // Transitional (WP-9.3 step 1): keep the old `Toast` timer in step
        // with the host's so screens not yet on the host keep behaving;
        // dropped when `Toast` becomes pure presentation.
        autoDismissDelay = if (current.autoDismisses) DEFAULT_AUTO_DISMISS else Duration.INFINITE,
        modifier = modifier,
    )
}

/**
 * The one §5.7 placement shared by every [ToastHost]: the gutter inset from
 * the screen edges and a small lift off the bottom edge. Hosts align
 * themselves `BottomCenter` inside their content box first;
 * [clearHomeIndicator] adds the Home-Indicator inset for hosts whose bottom
 * edge *is* the screen's — the deck's toast sits above the action bar, which
 * clears the inset itself, so it passes false.
 */
@Composable
fun Modifier.toastHostPlacement(clearHomeIndicator: Boolean = true): Modifier {
    val spacing = SwipeTheme.spacing
    val inset = if (clearHomeIndicator) Modifier.navigationBarsPadding() else Modifier
    return this
        .then(inset)
        .padding(horizontal = spacing.gutter, vertical = spacing.s)
}

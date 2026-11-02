package dev.swipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.swipe.ui.theme.SwipeTheme
import org.jetbrains.compose.resources.DrawableResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The §5.7 `Toast`: a `surfaceRaised`, radius-12 pill for short confirmations
 * ("Undone", the favorite confirmation), with a 3 s auto-dismiss.
 *
 * Dismissal timing is driven by the Compose frame clock ([withFrameMillis]),
 * so Compose UI tests advance it virtually with `mainClock.advanceTimeBy`
 * instead of waiting a real 3 s (WP-1.2 testability note).
 */
@Composable
fun Toast(
    text: String,
    modifier: Modifier = Modifier,
    icon: DrawableResource? = null,
    autoDismissDelay: Duration = DEFAULT_AUTO_DISMISS,
    onDismiss: (() -> Unit)? = null,
) {
    val tokens = SwipeTheme.componentSizes
    var dismissed by remember(text) { mutableStateOf(autoDismissDelay < Duration.ZERO) }
    if (!dismissed) {
        LaunchedEffect(text) {
            var elapsed = Duration.ZERO
            var last: Long? = null
            while (elapsed < autoDismissDelay) {
                withFrameMillis { now ->
                    last = last ?: now
                    elapsed = (now - last!!).milliseconds
                    last = now
                }
            }
            dismissed = true
            onDismiss?.invoke()
        }
    }
    if (dismissed) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth(tokens.toastWidthFraction)
                .background(SwipeTheme.colors.surfaceRaised, RoundedCornerShape(SwipeTheme.radii.toast))
                .padding(horizontal = tokens.toastPaddingHorizontal, vertical = tokens.toastPaddingVertical)
                .semantics {
                    liveRegion = LiveRegionMode.Assertive
                    this.contentDescription = text
                },
    ) {
        if (icon != null) {
            SwipeIcon(resource = icon, contentDescription = null)
        }
        BasicText(
            text = text,
            style = SwipeTheme.typography.callout.copy(color = SwipeTheme.colors.textPrimary),
        )
    }
}

/** §5.7: toasts auto-dismiss 3 seconds after they appear. */
val DEFAULT_AUTO_DISMISS: Duration = 3000.milliseconds

/**
 * Pass [NEVER_AUTO_DISMISS] when the caller wants the toast to stay until
 * explicitly removed (e.g. in tests that assert the visible state).
 */
val NEVER_AUTO_DISMISS: Duration = Duration.INFINITE

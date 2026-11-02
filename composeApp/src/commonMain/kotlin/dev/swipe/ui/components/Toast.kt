package dev.swipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.DrawableResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The §5.7 `Toast`: a `surfaceRaised`, radius-12 pill for short confirmations
 * ("Undone", the favorite confirmation), with a 3 s auto-dismiss. An optional
 * trailing action (the favorite confirm's "Delete anyway") sits in `accent`,
 * and [iconTint] carries the §5.3 `warning` tone for favorite confirms.
 */
@Composable
fun Toast(
    text: String,
    modifier: Modifier = Modifier,
    icon: DrawableResource? = null,
    iconTint: Color = SwipeTheme.colors.textPrimary,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    autoDismissDelay: Duration = DEFAULT_AUTO_DISMISS,
    onDismiss: (() -> Unit)? = null,
) {
    val tokens = SwipeTheme.componentSizes
    var dismissed by remember(text) { mutableStateOf(autoDismissDelay < Duration.ZERO) }
    if (!dismissed) {
        // Virtual time: under `runComposeUiTest` the delay below runs on the
        // test scheduler, so tests advance it with `mainClock.advanceTimeBy`
        // instead of waiting a real 3 s (WP-1.2 testability note).
        LaunchedEffect(text, autoDismissDelay) {
            delay(autoDismissDelay)
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
            SwipeIcon(resource = icon, tint = iconTint, contentDescription = null)
        }
        BasicText(
            text = text,
            style = SwipeTheme.typography.callout.copy(color = SwipeTheme.colors.textPrimary),
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            BasicText(
                text = actionLabel,
                style = SwipeTheme.typography.callout.copy(color = SwipeTheme.colors.accent),
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .clickable(onClick = onAction)
                        .padding(start = SwipeTheme.spacing.s)
                        .semantics {
                            role = Role.Button
                            this.contentDescription = actionLabel
                        },
            )
        }
    }
}

/** §5.7: toasts auto-dismiss 3 seconds after they appear. */
val DEFAULT_AUTO_DISMISS: Duration = 3000.milliseconds

/**
 * Pass [NEVER_AUTO_DISMISS] when the caller wants the toast to stay until
 * explicitly removed (e.g. in tests that assert the visible state).
 */
val NEVER_AUTO_DISMISS: Duration = Duration.INFINITE

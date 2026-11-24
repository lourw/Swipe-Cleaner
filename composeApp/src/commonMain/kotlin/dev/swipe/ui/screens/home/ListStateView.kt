package dev.swipe.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.swipe.ui.components.PrimaryButton
import dev.swipe.ui.theme.SwipeTheme

/** The loading message both list screens show while their scan runs (WP-9.6). */
internal const val LIST_LOADING_MESSAGE = "Loading…"

/** The retry CTA under the error message (WP-9.6); the app's "try again" copy. */
internal const val LIST_RETRY_LABEL = "Try again"

/** The state block's test tag: its bounds pin the bottom-inset layout (WP-9.6). */
internal const val LIST_STATE_TEST_TAG = "list-state"

/**
 * The centered status block for the non-ready states of the Albums and
 * By month lists (WP-9.6, FR-7, FR-8, §5.8): a `callout textSecondary`
 * message, and for the error state a **Try again** [PrimaryButton] that
 * re-runs the scan. The block fills the height its host gives it (the
 * screens pass `Modifier.weight(1f)`) and centers its content inside
 * that space; the host passes its **bottom safe area** through the
 * call-site modifier's `navigationBarsPadding()` — the same way the
 * Ready-state `LazyColumn` takes it — so the message never sits under
 * the Home Indicator while the screen's `bg` stays full-bleed (§5.1.5).
 * The block applies no inset of its own: like `ScreenHeader` it takes
 * its insets from the screen hosting it. The message is plain text, so
 * VoiceOver reads it as soon as it replaces the list (NFR-8).
 */
@Composable
internal fun ListStateMessage(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier =
            modifier
                .testTag(LIST_STATE_TEST_TAG)
                .fillMaxWidth()
                .padding(
                    horizontal = SwipeTheme.spacing.gutter,
                    vertical = SwipeTheme.spacing.l,
                ),
    ) {
        BasicText(
            text = message,
            style = SwipeTheme.typography.callout.copy(color = SwipeTheme.colors.textSecondary),
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(SwipeTheme.spacing.m))
            PrimaryButton(
                label = LIST_RETRY_LABEL,
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

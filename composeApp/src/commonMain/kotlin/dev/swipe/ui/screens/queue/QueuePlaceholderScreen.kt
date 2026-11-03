package dev.swipe.ui.screens.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.swipe.resources.Res
import dev.swipe.resources.ic_arrow_back
import dev.swipe.ui.components.CircleIconButton
import dev.swipe.ui.theme.SwipeTheme

/**
 * Temporary Queue destination placeholder (WP-2.3): the §5.8 route needs a
 * registered screen so the Swipe top bar and the summary CTA can navigate
 * here, and the deck's queue badge always has somewhere to go. WP-3.3
 * replaces this with the full review queue (FR-23).
 */
@Composable
fun QueuePlaceholderScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(SwipeTheme.colors.bg)
                .padding(SwipeTheme.spacing.gutter),
    ) {
        CircleIconButton(
            resource = Res.drawable.ic_arrow_back,
            label = "Back",
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart),
        )
        BasicText(
            text = "To delete",
            style = SwipeTheme.typography.title.copy(color = SwipeTheme.colors.textPrimary),
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

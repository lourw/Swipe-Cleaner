package dev.swipe.ui.screens.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
 * The §5.8 sub-screen header: a back chevron and the screen title in `title`
 * (the shape the Review queue header uses, FR-23), shared by the WP-5.3
 * source screens and the Settings placeholder.
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = SwipeTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.gutter - spacing.xs, vertical = spacing.xs),
    ) {
        CircleIconButton(
            resource = Res.drawable.ic_arrow_back,
            label = "Back",
            onClick = onBack,
        )
        BasicText(
            text = title,
            style = SwipeTheme.typography.title.copy(color = SwipeTheme.colors.textPrimary),
            modifier = Modifier.padding(start = spacing.xs),
        )
    }
}

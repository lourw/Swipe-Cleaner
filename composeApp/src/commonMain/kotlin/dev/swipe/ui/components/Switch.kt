package dev.swipe.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.swipe.ui.theme.SwipeTheme

/**
 * The Settings switch (FR-29, WP-6.1): the iOS-shape track-and-thumb toggle in
 * the §5.3 tokens — `accent` when on, `border` when off, a `textPrimary` thumb.
 * Purely visual: the settings row owns the touch target and the switch
 * semantics (`Role.Switch` + `selected`), so VoiceOver reads one control.
 */
@Composable
fun SwipeSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val sizes = SwipeTheme.componentSizes
    val inset = (sizes.switchTrackHeight - sizes.switchThumb) / 2
    val travel = sizes.switchTrackWidth - sizes.switchThumb - inset * 2
    val thumbOffset by animateDpAsState(if (checked) travel else 0.dp, label = "switchThumb")
    Box(
        modifier =
            modifier
                .size(width = sizes.switchTrackWidth, height = sizes.switchTrackHeight)
                .background(
                    if (checked) SwipeTheme.colors.accent else SwipeTheme.colors.border,
                    CircleShape,
                ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier =
                Modifier
                    .padding(start = inset + thumbOffset, top = inset, bottom = inset)
                    .size(sizes.switchThumb)
                    .background(SwipeTheme.colors.textPrimary, CircleShape),
        )
    }
}

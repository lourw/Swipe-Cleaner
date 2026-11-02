package dev.swipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.swipe.ui.theme.SwipeTheme

/**
 * The §5.7 `Banner`: a `surfaceRaised` row with a 4 dp leading tone bar,
 * `callout` body text, an optional trailing action in the `accent` color, and
 * a ≥44 dp touch target (§5.5). Announced as a polite live region.
 */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = SwipeTheme.colors
    val tokens = SwipeTheme.componentSizes
    val toneColor: Color =
        when (tone) {
            BannerTone.Info -> colors.accent
            BannerTone.Warning -> colors.warning
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .background(colors.surfaceRaised, RoundedCornerShape(SwipeTheme.radii.tile))
                .height(IntrinsicSize.Min)
                .heightIn(min = tokens.bannerMinHeight)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    this.contentDescription = text
                },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .width(tokens.bannerLeadingBarWidth)
                    .background(toneColor),
        )
        BasicText(
            text = text,
            style = SwipeTheme.typography.callout.copy(color = colors.textPrimary),
            modifier =
                Modifier
                    .weight(1f)
                    .padding(SwipeTheme.spacing.s),
        )
        if (actionLabel != null && onAction != null) {
            BasicText(
                text = actionLabel,
                style = SwipeTheme.typography.callout.copy(color = colors.accent),
                modifier =
                    Modifier
                        .clickable(onClick = onAction)
                        .padding(SwipeTheme.spacing.s)
                        .semantics { this.contentDescription = actionLabel },
            )
        }
    }
}

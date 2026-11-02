package dev.swipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.swipe.ui.theme.SwipeTheme

/**
 * The §5.7 `PrimaryButton`: a 52 dp full-round pill, `accent` fill, `headline`
 * label in the card-foreground color. Set [container] to override the fill
 * (e.g. `SwipeTheme.colors.delete` for the destructive variant).
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = SwipeTheme.colors.accent,
    contentColor: Color = SwipeTheme.colors.textPrimary,
    contentDescription: String? = null,
) {
    val tokens = SwipeTheme.componentSizes
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .heightIn(min = tokens.primaryButtonHeight)
                .background(
                    if (enabled) {
                        container
                    } else {
                        container.copy(alpha = tokens.tintedFillAlpha)
                    },
                    CircleShape,
                ).clickable(enabled = enabled, onClick = onClick).semantics {
                    role = Role.Button
                    this.contentDescription = contentDescription ?: label
                    if (!enabled) {
                        disabled()
                    }
                },
    ) {
        BasicText(
            text = label,
            style =
                SwipeTheme.typography.headline.let {
                    if (enabled) {
                        it.copy(color = contentColor)
                    } else {
                        it.copy(color = contentColor.copy(alpha = tokens.disabledAlpha))
                    }
                },
        )
    }
}

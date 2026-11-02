package dev.swipe.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.swipe.ui.components.LimitedAccessBanner
import dev.swipe.ui.components.PrimaryButton
import dev.swipe.ui.theme.SwipeTheme

/**
 * Placeholder Home for WP-1.7: the §5.8 title, the FR-2 Limited-access banner
 * when access is limited, and a "Start shuffle" `PrimaryButton`. WP-5.3
 * replaces this with the full source list.
 */
@Composable
fun HomeScreen(
    onStartShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    limitedItemCount: Int? = null,
    onManageLimitedAccess: () -> Unit = {},
) {
    val colors = SwipeTheme.colors
    val spacing = SwipeTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.bg)
                .padding(horizontal = spacing.gutter, vertical = spacing.m),
    ) {
        BasicText(
            text = "Swipe Cleaner",
            style = SwipeTheme.typography.title.copy(color = colors.textPrimary),
        )
        Spacer(modifier = Modifier.height(spacing.m))
        if (limitedItemCount != null) {
            LimitedAccessBanner(
                itemCount = limitedItemCount,
                onManage = onManageLimitedAccess,
            )
            Spacer(modifier = Modifier.height(spacing.m))
        }
        Spacer(modifier = Modifier.weight(1f))
        PrimaryButton(
            label = "Start shuffle",
            onClick = onStartShuffle,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

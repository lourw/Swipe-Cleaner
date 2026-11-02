package dev.swipe.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * FR-2 Limited-access banner: the §5.7 [Banner] carrying the spec copy
 * "You've given access to N items. Manage." The action opens
 * `presentLimitedLibraryPicker` (wired by the caller).
 */
@Composable
fun LimitedAccessBanner(
    itemCount: Int,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Banner(
        text = "You've given access to $itemCount items.",
        modifier = modifier,
        actionLabel = "Manage",
        onAction = onManage,
    )
}

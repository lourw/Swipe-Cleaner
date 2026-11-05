package dev.swipe.ui.screens.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.swipe.domain.model.Progress
import dev.swipe.ui.components.CircleIconButton
import dev.swipe.ui.components.QueueBadge
import dev.swipe.ui.components.SwipeIcons
import dev.swipe.ui.theme.SwipeTheme
import dev.swipe.ui.theme.TABULAR_FIGURES

/**
 * The §5.7 `TopBar`: 44 dp tall over `scrim`, close (×) at the start, the
 * FR-19 progress `128 / 2,340` centered in `caption` with tnum, and the
 * delete-queue button at the end with its red [QueueBadge] (hidden at 0,
 * FR-10). A 2 dp progress line runs under the row: `accent` filled to
 * `reviewed / total`, `border` for the rest.
 *
 * [visible] is the photo-tap chrome toggle (§5.6): the whole bar leaves,
 * space and all, and the card claims the room back.
 */
@Composable
fun TopBar(
    progress: Progress,
    queueCount: Int,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    if (!visible) return
    val colors = SwipeTheme.colors
    val sizes = SwipeTheme.componentSizes
    // Safe area (WP-6.4): the scrim paints up into the status bar (background
    // before the inset padding); the row itself clears it.
    Column(modifier = modifier.fillMaxWidth().background(colors.scrim).statusBarsPadding()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(sizes.topBarHeight)
                    .padding(horizontal = SwipeTheme.spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(
                    symbol = SwipeIcons.CLOSE,
                    label = "Close",
                    onClick = onClose,
                )
                Box(contentAlignment = Alignment.TopEnd) {
                    CircleIconButton(
                        symbol = SwipeIcons.DELETE,
                        label = if (queueCount > 0) "$queueCount queued — open the queue" else "To delete queue",
                        onClick = onOpenQueue,
                    )
                    if (queueCount > 0) {
                        QueueBadge(
                            count = queueCount,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                }
            }
            BasicText(
                text = progressLabel(progress),
                style =
                    SwipeTheme.typography.caption.copy(
                        color = colors.textPrimary,
                        fontFeatureSettings = TABULAR_FIGURES,
                    ),
                modifier = Modifier.semantics { contentDescription = progressDescription(progress) },
            )
        }
        ProgressLine(progress = progress)
    }
}

/** §5.7: 2 dp progress line under the centered count. */
@Composable
private fun ProgressLine(progress: Progress) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SwipeTheme.componentSizes.progressLineThickness)
                .background(SwipeTheme.colors.border),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(progressFraction(progress))
                    .height(SwipeTheme.componentSizes.progressLineThickness)
                    .background(SwipeTheme.colors.accent),
        )
    }
}

/** FR-19: `reviewed / total`, both sides grouped with thousands separators. */
fun progressLabel(progress: Progress): String = "${groupDigits(progress.reviewed)} / ${groupDigits(progress.total)}"

/** Screen-reader copy for the centered count (the bare digits read as one number). */
fun progressDescription(progress: Progress): String = "${groupDigits(progress.reviewed)} of ${groupDigits(progress.total)} reviewed"

/** Fill of the progress line, 0 → 1; an empty deck is 0, never NaN. */
fun progressFraction(progress: Progress): Float =
    if (progress.total <= 0) {
        0f
    } else {
        (progress.reviewed.toFloat() / progress.total).coerceIn(0f, 1f)
    }

/**
 * Groups [value] with `,` every three digits (`2340` → `2,340`, §5.7
 * `128 / 2,340`). Own helper: the common-stdlib has no locale-aware number
 * format, and the spec fixes exactly this grouping.
 */
fun groupDigits(value: Int): String {
    val digits = value.toString()
    if (value < 0 || digits.length <= GROUP_SIZE) return digits
    val builder = StringBuilder()
    digits.forEachIndexed { index, char ->
        if (index > 0 && (digits.length - index) % GROUP_SIZE == 0) builder.append(',')
        builder.append(char)
    }
    return builder.toString()
}

/** Digits per `,` group in [groupDigits] (the spec's `128 / 2,340` shape). */
private const val GROUP_SIZE = 3

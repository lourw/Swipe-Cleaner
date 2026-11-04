package dev.swipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import dev.swipe.ui.theme.SwipeTheme

/**
 * The §6.7 in-app confirm dialog — the NFR-5 gate in front of every batch
 * delete. A `surfaceRaised` sheet (radius `radii.sheet`) with a title, an
 * optional message, the destructive confirm pill, and a quiet Cancel.
 *
 * Dismissing the dialog (Cancel or tapping outside) is the **cancel** path:
 * it calls [onCancel] and deletes nothing (§6.7). The confirm button is the
 * only gesture that proceeds; the caller wires it to the confirm intent.
 */
@Composable
fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    cancelLabel: String = "Cancel",
) {
    val colors = SwipeTheme.colors
    Dialog(onDismissRequest = onCancel) {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .background(colors.surfaceRaised, RoundedCornerShape(SwipeTheme.radii.sheet))
                    .padding(SwipeTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.s),
        ) {
            BasicText(
                text = title,
                style = SwipeTheme.typography.title.copy(color = colors.textPrimary),
            )
            if (message != null) {
                BasicText(
                    text = message,
                    style = SwipeTheme.typography.callout.copy(color = colors.textSecondary),
                )
            }
            PrimaryButton(
                label = confirmLabel,
                onClick = onConfirm,
                destructive = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // WP-1.2 component rule: every touch target is at least 44 dp, so
            // the text button rides in a minTouchTarget-floored, centered box.
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = SwipeTheme.sizes.minTouchTarget)
                        .clickable(onClick = onCancel)
                        .semantics {
                            role = Role.Button
                            this.contentDescription = cancelLabel
                        },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = cancelLabel,
                    style =
                        SwipeTheme.typography.callout.copy(
                            color = colors.accent,
                            textAlign = TextAlign.Center,
                        ),
                )
            }
        }
    }
}

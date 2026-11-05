package dev.swipe.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.swipe.presentation.settings.SettingsEvent
import dev.swipe.presentation.settings.SettingsIntent
import dev.swipe.presentation.settings.SettingsUiState
import dev.swipe.ui.components.ConfirmDialog
import dev.swipe.ui.components.SwipeSwitch
import dev.swipe.ui.components.Toast
import dev.swipe.ui.screens.home.ScreenHeader
import dev.swipe.ui.screens.swipe.formatCardDate
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.flow.Flow

internal const val SWITCH_HAPTICS_TEST_TAG = "switch-haptics"
internal const val SWITCH_CLOUD_TEST_TAG = "switch-cloud"
internal const val SWITCH_AUTOPLAY_TEST_TAG = "switch-autoplay"
internal const val RESET_ROW_TEST_TAG = "settings-reset-row"
internal const val VERSION_ROW_TEST_TAG = "settings-version-row"
internal const val EXPIRY_ROW_TEST_TAG = "settings-expiry-row"

/**
 * The Settings screen (FR-29–31, §5.8): three toggle rows bound to
 * `SettingsStore` (haptics, include iCloud-only items, autoplay videos), the
 * "Reset all decisions" row behind a [ConfirmDialog] (FR-30), and the info
 * rows — app version and, when readable, the free-provisioning signing expiry
 * (FR-31; the row is simply absent otherwise). One-shot [events] surface as
 * the §5.7 [Toast].
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    events: Flow<SettingsEvent>,
    onIntent: (SettingsIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SwipeTheme.colors
    var toastText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(events) {
        events.collect { event ->
            toastText =
                when (event) {
                    SettingsEvent.ResetDone -> "Keep history cleared"
                    SettingsEvent.ResetFailed -> "Couldn't reset"
                    SettingsEvent.SaveFailed -> "Couldn't save setting"
                }
        }
    }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.bg),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(title = "Settings", onBack = onBack)
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
            ) {
                SettingsRows(state = state, onIntent = onIntent)
            }
        }
        toastText?.let { text ->
            Toast(
                text = text,
                onDismiss = { toastText = null },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = SwipeTheme.spacing.xl),
            )
        }
    }
    if (state.confirmResetVisible) {
        ConfirmDialog(
            title = "Reset all decisions?",
            message =
                "This clears your KEEP history so everything is undecided again. " +
                    "Items waiting in the review queue are not deleted.",
            confirmLabel = "Reset",
            onConfirm = { onIntent(SettingsIntent.ConfirmReset) },
            onCancel = { onIntent(SettingsIntent.CancelReset) },
        )
        // Dialog host: the dialog renders in its own window; the flag drives it.
    }
}

/** The three toggles, the reset row, and the info rows (FR-29–31, §5.8 order). */
@Composable
private fun SettingsRows(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ToggleRow(
            title = "Haptics",
            caption = "Feel swipes, undo, and deletes",
            checked = state.hapticsEnabled,
            onCheckedChange = { onIntent(SettingsIntent.SetHaptics(!state.hapticsEnabled)) },
            testTag = SWITCH_HAPTICS_TEST_TAG,
        )
        ToggleRow(
            title = "Include iCloud-only items",
            caption = "Download originals that aren't on this device",
            checked = state.includeCloudOnly,
            onCheckedChange = { onIntent(SettingsIntent.SetIncludeCloudOnly(!state.includeCloudOnly)) },
            testTag = SWITCH_CLOUD_TEST_TAG,
        )
        ToggleRow(
            title = "Autoplay videos",
            caption = "Play videos when their card is on top",
            checked = state.autoplayVideos,
            onCheckedChange = { onIntent(SettingsIntent.SetAutoplayVideos(!state.autoplayVideos)) },
            testTag = SWITCH_AUTOPLAY_TEST_TAG,
        )
        Hairline()
        ResetRow(onClick = { onIntent(SettingsIntent.RequestReset) })
        Hairline()
        InfoRow(
            title = "Version",
            value = state.appVersion,
            testTag = VERSION_ROW_TEST_TAG,
        )
        // FR-31: the expiry row appears only when the profile could be read.
        state.signingExpiry?.let { expiry ->
            InfoRow(
                title = "Apple ID signing expires",
                value = formatCardDate(expiry),
                testTag = EXPIRY_ROW_TEST_TAG,
            )
        }
    }
}

/** One settings row: title + caption on the left, the switch trailing; the whole row toggles (FR-29). */
@Composable
private fun ToggleRow(
    title: String,
    caption: String,
    checked: Boolean,
    onCheckedChange: () -> Unit,
    testTag: String,
) {
    val spacing = SwipeTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = SwipeTheme.sizes.minTouchTarget)
                .clickable(role = Role.Switch, onClick = onCheckedChange)
                .semantics {
                    contentDescription = title
                    selected = checked
                }.testTag(testTag)
                .padding(horizontal = spacing.gutter, vertical = spacing.s),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = SwipeTheme.typography.body.copy(color = SwipeTheme.colors.textPrimary),
            )
            BasicText(
                text = caption,
                style = SwipeTheme.typography.callout.copy(color = SwipeTheme.colors.textSecondary),
            )
        }
        SwipeSwitch(checked = checked, modifier = Modifier.padding(start = spacing.m))
    }
}

/** The FR-30 "Reset all decisions" row; tapping it only raises the confirm dialog. */
@Composable
private fun ResetRow(onClick: () -> Unit) {
    val spacing = SwipeTheme.spacing
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = SwipeTheme.sizes.minTouchTarget)
                .clickable(role = Role.Button, onClick = onClick)
                .testTag(RESET_ROW_TEST_TAG)
                .padding(horizontal = spacing.gutter, vertical = spacing.s),
    ) {
        BasicText(
            text = "Reset all decisions",
            style = SwipeTheme.typography.body.copy(color = SwipeTheme.colors.textPrimary),
        )
        BasicText(
            text = "Clear your KEEP history. The review queue stays as it is.",
            style = SwipeTheme.typography.callout.copy(color = SwipeTheme.colors.textSecondary),
        )
    }
}

/** A read-only label/value row (FR-31). */
@Composable
private fun InfoRow(
    title: String,
    value: String,
    testTag: String,
) {
    val spacing = SwipeTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = SwipeTheme.sizes.minTouchTarget)
                .testTag(testTag)
                .padding(horizontal = spacing.gutter, vertical = spacing.s),
    ) {
        BasicText(
            text = title,
            style = SwipeTheme.typography.body.copy(color = SwipeTheme.colors.textPrimary),
            modifier = Modifier.weight(1f),
        )
        BasicText(
            text = value,
            style =
                SwipeTheme.typography
                    .callout
                    .copy(
                        color = SwipeTheme.colors.textSecondary,
                        textAlign = TextAlign.End,
                    ),
        )
    }
}

/** §5.5 hairline separator between settings groups. */
@Composable
private fun Hairline() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SwipeTheme.sizes.hairline)
                .background(SwipeTheme.colors.border),
    )
}

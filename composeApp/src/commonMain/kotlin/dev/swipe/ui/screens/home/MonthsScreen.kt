package dev.swipe.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.swipe.presentation.ListStatus
import dev.swipe.presentation.months.MonthRow
import dev.swipe.presentation.months.MonthsIntent
import dev.swipe.presentation.months.MonthsUiState
import dev.swipe.ui.components.SourceRow
import dev.swipe.ui.components.SwipeIcons
import dev.swipe.ui.components.formatCount
import dev.swipe.ui.theme.SwipeTheme

/** The empty-state message: the scan answered and nothing matches (WP-9.6, FR-7). */
internal const val MONTHS_EMPTY_MESSAGE = "Nothing to show"

/** The error-state message: the scan failed with nothing on screen (WP-9.6, FR-7). */
internal const val MONTHS_ERROR_MESSAGE = "Couldn't load your library"

/**
 * The By month screen (§5.8, FR-7): a back header and the month buckets in
 * **year sections, newest first** — the year header row (its four digits,
 * `headline`) above that year's month rows, each a [SourceRow] with the
 * month's full name and count. The buckets arrive newest-first from
 * [dev.swipe.data.MediaLibrary.months]; the scan itself runs only while this
 * screen is open (NFR-4). The scan's states (WP-9.6, §5.8): while it runs the
 * screen shows its loading message, an answered empty list and a failed scan
 * each say so, and a failed *refresh* keeps the rows shown (§6.8).
 *
 * Taps leave as [MonthsIntent]s; the App host collects the ViewModel's
 * [dev.swipe.presentation.months.MonthsEvent]s and navigates to the month
 * deck with the screen's media-type filter (§6.2, FR-5).
 */
@Composable
fun MonthsScreen(
    state: MonthsUiState,
    onIntent: (MonthsIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(SwipeTheme.colors.bg)
                // Safe areas (WP-6.4): header below the status bar, last row
                // above the Home Indicator; bg paints edge-to-edge.
                .statusBarsPadding(),
    ) {
        ScreenHeader(title = "By month", onBack = onBack)
        // WP-9.6 (FR-7, NFR-4, §5.8): the scan's four states each render
        // their own body under the header.
        when (state.status) {
            ListStatus.Loading -> {
                ListStateMessage(LIST_LOADING_MESSAGE)
            }

            ListStatus.Empty -> {
                ListStateMessage(MONTHS_EMPTY_MESSAGE)
            }

            ListStatus.Error -> {
                ListStateMessage(
                    message = MONTHS_ERROR_MESSAGE,
                    onRetry = { onIntent(MonthsIntent.Retry) },
                )
            }

            ListStatus.Ready -> {
                val items = remember(state.rows) { monthsListItems(state.rows) }
                LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                    items(items) { item ->
                        when (item) {
                            is MonthsListItem.Year -> {
                                YearHeader(item.year)
                            }

                            is MonthsListItem.Month -> {
                                SourceRow(
                                    title = monthName(item.row.bucket.month),
                                    count = formatCount(item.row.count),
                                    icon = SwipeIcons.CALENDAR_MONTH,
                                    onClick = { onIntent(MonthsIntent.OpenMonth(item.row.bucket)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One row of the By month list: a year section header or one month. */
internal sealed interface MonthsListItem {
    data class Year(
        val year: Int,
    ) : MonthsListItem

    data class Month(
        val row: MonthRow,
    ) : MonthsListItem
}

/**
 * Flatten newest-first [rows] (the [dev.swipe.domain.model.MonthBucket]
 * contract) into year sections: consecutive same-year buckets group under
 * one year header, so the year order is the buckets' own (FR-7, newest
 * first). Pure — tested through the screen.
 */
internal fun monthsListItems(rows: List<MonthRow>): List<MonthsListItem> {
    val items = mutableListOf<MonthsListItem>()
    var lastYear: Int? = null
    rows.forEach { row ->
        if (row.bucket.year != lastYear) {
            items += MonthsListItem.Year(row.bucket.year)
            lastYear = row.bucket.year
        }
        items += MonthsListItem.Month(row)
    }
    return items
}

@Composable
private fun YearHeader(year: Int) {
    BasicText(
        text = year.toString(),
        style = SwipeTheme.typography.headline.copy(color = SwipeTheme.colors.textPrimary),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SwipeTheme.spacing.gutter, vertical = SwipeTheme.spacing.xs)
                // NFR-8: VoiceOver announces this as a heading while the
                // month list scrolls, so the user knows where they are.
                .semantics { heading() },
    )
}

/** English month names, 1-based ([MonthBucket.month] is 1..12); v1 is English-only. */
private val monthNames =
    listOf(
        "January",
        "February",
        "March",
        "April",
        "May",
        "June",
        "July",
        "August",
        "September",
        "October",
        "November",
        "December",
    )

/** Full month name for a 1-based [month] (FR-7 row titles). */
internal fun monthName(month: Int): String = monthNames[(month - 1).coerceIn(monthNames.indices)]

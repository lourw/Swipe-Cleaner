package dev.swipe.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.swipe.domain.model.MonthBucket
import dev.swipe.presentation.months.MonthsIntent
import dev.swipe.presentation.months.MonthsUiState
import dev.swipe.resources.Res
import dev.swipe.resources.ic_calendar_month
import dev.swipe.ui.components.SourceRow
import dev.swipe.ui.screens.swipe.groupDigits
import dev.swipe.ui.theme.SwipeTheme

/**
 * The By month screen (§5.8, FR-7): a back header and the month buckets in
 * **year sections, newest first** — the year header row (its four digits,
 * `headline`) above that year's month rows, each a [SourceRow] with the
 * month's full name and count. The buckets arrive newest-first from
 * [dev.swipe.data.MediaLibrary.months]; the scan itself runs only while this
 * screen is open (NFR-4), so an empty list is simply "still scanning".
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
                .background(SwipeTheme.colors.bg),
    ) {
        ScreenHeader(title = "By month", onBack = onBack)
        val items = remember(state.buckets) { monthsListItems(state.buckets) }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items) { item ->
                when (item) {
                    is MonthsListItem.Year -> {
                        YearHeader(item.year)
                    }

                    is MonthsListItem.Month -> {
                        SourceRow(
                            title = monthName(item.bucket.month),
                            count = groupDigits(item.bucket.count),
                            icon = Res.drawable.ic_calendar_month,
                            onClick = { onIntent(MonthsIntent.OpenMonth(item.bucket)) },
                        )
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
        val bucket: MonthBucket,
    ) : MonthsListItem
}

/**
 * Flatten newest-first [buckets] (the [dev.swipe.domain.model.MonthBucket]
 * contract) into year sections: consecutive same-year buckets group under
 * one year header, so the year order is the buckets' own (FR-7, newest
 * first). Pure — tested through the screen.
 */
internal fun monthsListItems(buckets: List<MonthBucket>): List<MonthsListItem> {
    val items = mutableListOf<MonthsListItem>()
    var lastYear: Int? = null
    buckets.forEach { bucket ->
        if (bucket.year != lastYear) {
            items += MonthsListItem.Year(bucket.year)
            lastYear = bucket.year
        }
        items += MonthsListItem.Month(bucket)
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
                .padding(horizontal = SwipeTheme.spacing.gutter, vertical = SwipeTheme.spacing.xs),
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

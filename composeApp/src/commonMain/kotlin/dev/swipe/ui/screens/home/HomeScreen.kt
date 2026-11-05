package dev.swipe.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import dev.swipe.domain.model.Stats
import dev.swipe.presentation.home.HomeIntent
import dev.swipe.presentation.home.HomeSmartRow
import dev.swipe.presentation.home.HomeUiState
import dev.swipe.resources.Res
import dev.swipe.resources.ic_calendar_month
import dev.swipe.resources.ic_filter_list
import dev.swipe.resources.ic_photo_library
import dev.swipe.resources.ic_settings
import dev.swipe.resources.ic_shuffle
import dev.swipe.ui.components.Banner
import dev.swipe.ui.components.COUNT_PLACEHOLDER
import dev.swipe.ui.components.CircleIconButton
import dev.swipe.ui.components.LimitedAccessBanner
import dev.swipe.ui.components.SegmentedControl
import dev.swipe.ui.components.SourceRow
import dev.swipe.ui.components.formatCount
import dev.swipe.ui.screens.swipe.groupDigits
import dev.swipe.ui.theme.SwipeTheme
import dev.swipe.ui.theme.TABULAR_FIGURES

/**
 * Home, the session-setup screen (§5.8 wireframe, FR-4, FR-5, FR-10, FR-2):
 * the title row with the Settings gear, the lifetime stats line
 * (`1,204 reviewed · 312 deleted`), the To Delete queue [Banner] (only while
 * the queue is non-empty, FR-10) under the FR-2 [LimitedAccessBanner] when
 * both apply — limited first — the All / Photos / Videos
 * [SegmentedControl] (FR-5), and the source rows (FR-4): **Shuffle
 * everything**, **Albums**, **By month**, and the five **SMART FILTERS**
 * rows under their caption header.
 *
 * Every count arrives null until loaded and renders [COUNT_PLACEHOLDER]
 * (NFR-4); arrived counts use grouping separators and tabular figures
 * (§5.7). Taps leave only as [HomeIntent]s; navigation goes out through
 * [HomeIntent]-driven events collected by the App host (§6.2).
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onIntent: (HomeIntent) -> Unit,
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
                .verticalScroll(rememberScrollState())
                .padding(bottom = spacing.l),
    ) {
        HomeHeaderRow(onOpenSettings = { onIntent(HomeIntent.OpenSettings) })
        HomeStatsLine(stats = state.stats)
        HomeBanners(
            state = state,
            limitedItemCount = limitedItemCount,
            onManageLimitedAccess = onManageLimitedAccess,
            onOpenQueue = { onIntent(HomeIntent.OpenQueue) },
        )
        Spacer(modifier = Modifier.height(spacing.m))
        SegmentedControl(
            options = MediaTypeFilter.entries.toList(),
            selected = state.typeFilter,
            labelOf = ::typeFilterLabel,
            onSelect = { onIntent(HomeIntent.SetTypeFilter(it)) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.gutter),
        )
        Spacer(modifier = Modifier.height(spacing.m))
        SourceRow(
            title = "Shuffle everything",
            count = formatCount(state.shuffleCount),
            icon = Res.drawable.ic_shuffle,
            onClick = { onIntent(HomeIntent.OpenSource.Shuffle) },
        )
        SourceRow(
            title = "Albums",
            icon = Res.drawable.ic_photo_library,
            onClick = { onIntent(HomeIntent.OpenAlbums) },
        )
        SourceRow(
            title = "By month",
            icon = Res.drawable.ic_calendar_month,
            onClick = { onIntent(HomeIntent.OpenMonths) },
        )
        SmartFiltersSection(rows = state.smartRows) { kind ->
            onIntent(HomeIntent.OpenSource.Smart(kind))
        }
    }
}

/**
 * The FR-10 lifetime stats line: `reviewed · kept · deleted` with grouping
 * separators and tabular figures (§5.4, §5.8). Em dashes until `stats` lands.
 */
@Composable
private fun HomeStatsLine(stats: Stats?) {
    val reviewed = stats?.reviewed?.let(::groupDigits) ?: COUNT_PLACEHOLDER
    val kept = stats?.kept?.let(::groupDigits) ?: COUNT_PLACEHOLDER
    val deleted = stats?.deleted?.let(::groupDigits) ?: COUNT_PLACEHOLDER
    BasicText(
        text = "$reviewed reviewed · $kept kept · $deleted deleted",
        style =
            SwipeTheme.typography.callout.copy(
                color = SwipeTheme.colors.textSecondary,
                fontFeatureSettings = TABULAR_FIGURES,
            ),
        modifier = Modifier.padding(horizontal = SwipeTheme.spacing.gutter),
    )
}

/** The §5.8 **SMART FILTERS** caption and its five rows (FR-4, FR-6). */
@Composable
private fun SmartFiltersSection(
    rows: List<HomeSmartRow>,
    onOpen: (SmartFilter) -> Unit,
) {
    Column {
        BasicText(
            text = "SMART FILTERS",
            style = SwipeTheme.typography.caption.copy(color = SwipeTheme.colors.textSecondary),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SwipeTheme.spacing.gutter, vertical = SwipeTheme.spacing.xs),
        )
        rows.forEach { row ->
            SourceRow(
                title = smartFilterTitle(row.kind),
                count = formatCount(row.count),
                icon = Res.drawable.ic_filter_list,
                onClick = { onOpen(row.kind) },
            )
        }
    }
}

/** The §5.8 title row: "Swipe Cleaner" and the Settings gear (FR-29). */
@Composable
private fun HomeHeaderRow(onOpenSettings: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SwipeTheme.spacing.gutter - SwipeTheme.spacing.xs),
    ) {
        BasicText(
            text = "Swipe Cleaner",
            style = SwipeTheme.typography.title.copy(color = SwipeTheme.colors.textPrimary),
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = SwipeTheme.spacing.xs, top = SwipeTheme.spacing.m, bottom = SwipeTheme.spacing.xs),
        )
        CircleIconButton(
            resource = Res.drawable.ic_settings,
            label = "Settings",
            onClick = onOpenSettings,
        )
    }
}

/**
 * The two §5.8 banners: FR-2 first, then FR-10 — the access warning outranks
 * the queue reminder — each shown only when it applies.
 */
@Composable
private fun HomeBanners(
    state: HomeUiState,
    limitedItemCount: Int?,
    onManageLimitedAccess: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val spacing = SwipeTheme.spacing
    Column {
        if (state.access == LibraryAccess.LIMITED && limitedItemCount != null) {
            Spacer(modifier = Modifier.height(spacing.m))
            LimitedAccessBanner(
                itemCount = limitedItemCount,
                onManage = onManageLimitedAccess,
                modifier = Modifier.padding(horizontal = SwipeTheme.spacing.gutter),
            )
        }
        // FR-10: the queue Banner shows iff items wait in the To Delete queue.
        if (state.queueCount > 0) {
            Spacer(modifier = Modifier.height(spacing.m))
            Banner(
                text = "🗑 ${groupDigits(state.queueCount)} waiting to delete ›",
                modifier =
                    Modifier
                        .padding(horizontal = SwipeTheme.spacing.gutter)
                        .clickable(role = Role.Button, onClick = onOpenQueue),
            )
        }
    }
}

/** The FR-5 toggle labels, in [MediaTypeFilter] declaration order (All / Photos / Videos). */
fun typeFilterLabel(type: MediaTypeFilter): String =
    when (type) {
        MediaTypeFilter.ALL -> "All"
        MediaTypeFilter.PHOTOS -> "Photos"
        MediaTypeFilter.VIDEOS -> "Videos"
    }

/** The §5.8 row titles for the five smart filters; the FR-6 order of [SmartFilter]. */
fun smartFilterTitle(kind: SmartFilter): String =
    when (kind) {
        SmartFilter.SCREENSHOTS -> "Screenshots"
        SmartFilter.LONG_VIDEOS -> "Long videos"
        SmartFilter.BURSTS -> "Bursts"
        SmartFilter.LIKELY_DUPLICATES -> "Likely duplicates"
        SmartFilter.SELFIES -> "Selfies"
    }

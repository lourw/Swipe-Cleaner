package dev.swipe.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntSize
import dev.swipe.data.MediaLoader
import dev.swipe.ui.components.media.rememberMediaImage
import dev.swipe.ui.screens.swipe.groupDigits
import dev.swipe.ui.theme.SwipeTheme
import dev.swipe.ui.theme.TABULAR_FIGURES

/**
 * The §5.7 `SourceRow`: a 64 dp row on `surface` with a 40 dp leading slot —
 * the album [coverAssetId] thumbnail (radius `radii.tile`) when one is given
 * and [mediaLoader] with it, otherwise the decorative [icon] in `accent` —
 * the title in `headline`, an optional trailing [count] in `callout
 * textSecondary`, and a chevron. Used by Home (FR-4) and the Albums screen
 * (FR-8); the tap opens the row's destination.
 *
 * With [onSwitchToggle] the row grows a trailing [SwipeSwitch] before the
 * chevron — the FR-37 **In Shuffle** control (WP-8.15: on means the album's
 * items are in Shuffle; the app still stores *exclusions*, so the caller
 * passes `checked = id !in excludedAlbumIds`), the same iOS-shape switch and
 * `Role.Switch` semantics the Settings rows use. The switch is its own touch
 * target and consumes its taps, so the row tap still navigates. Its touch box
 * is as wide as the track (WP-8.14), and `s` gaps separate count → switch →
 * chevron; rows without the switch keep their original spacing, so Home and
 * By month render exactly as before.
 *
 * [subtitle] (FR-37: "Not in Shuffle", on a row whose switch is off) sits
 * under the title in `caption textSecondary`; the row keeps its fixed height.
 * When present it joins the row's VoiceOver description (NFR-8).
 *
 * The title column may shrink below its text's intrinsic width (WP-8.15), so
 * a long title can never push the switch or the chevron out of the gutter —
 * the chevron always sits fully inside the row's end padding.
 *
 * The cover is a *small-target* request (the 40 dp slot's pixels, never the
 * full-screen target), so row lists can't evict card bitmaps from the
 * loader's cache (NFR-3, §6.8), and leaving the composition cancels the
 * request (§6.8).
 */
@Composable
fun SourceRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: String? = null,
    subtitle: String? = null,
    coverAssetId: String? = null,
    mediaLoader: MediaLoader? = null,
    icon: String? = null,
    switchChecked: Boolean? = null,
    onSwitchToggle: (() -> Unit)? = null,
    switchContentDescription: String? = null,
) {
    val colors = SwipeTheme.colors
    val tokens = SwipeTheme.componentSizes
    // NFR-8: the description carries the subtitle too, so an album whose
    // switch is off reads "…, Not in Shuffle" (WP-8.14, WP-8.15).
    val description = listOfNotNull(title, count, subtitle).joinToString(separator = ", ")
    val checked = switchChecked
    val toggle = onSwitchToggle
    val hasSwitch = checked != null && toggle != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .height(tokens.sourceRowHeight)
                .background(colors.surface)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description }
                .padding(horizontal = SwipeTheme.spacing.gutter),
    ) {
        SourceRowLeading(coverAssetId, mediaLoader, icon)
        SourceRowTitle(
            title = title,
            subtitle = subtitle,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = SwipeTheme.spacing.s),
        )
        if (count != null) {
            BasicText(
                text = count,
                style =
                    SwipeTheme.typography.callout.copy(
                        color = colors.textSecondary,
                        fontFeatureSettings = TABULAR_FIGURES,
                    ),
            )
        }
        if (checked != null && toggle != null) {
            // The FR-37 gap is its own node (a padding modifier would ride
            // inside the count's semantics bounds, and the FR-37 layout test
            // measures the edge-to-edge gap).
            if (count != null) {
                Spacer(Modifier.width(SwipeTheme.spacing.s))
            }
            SourceRowSwitch(
                checked = checked,
                contentDescription = switchContentDescription ?: "Include $title in Shuffle",
                onToggle = toggle,
            )
        }
        SourceRowChevron(hasSwitch)
    }
}

/** Test tag on every [SourceRow]'s chevron (the WP-8.15 clipping layout test). */
const val SOURCE_ROW_CHEVRON_TEST_TAG = "source-row-chevron"

/**
 * The trailing chevron of a [SourceRow]. WP-8.15 (FR-37): on a switch row the
 * gap lives *outside* the icon — a padding inside size() squeezes the glyph
 * into `size − gap`, which cut the chevron off (seen on the simulator). Rows
 * without a switch keep their original modifier chain, so Home and By month
 * render exactly as before. The semantics node is innermost so the layout
 * test measures the glyph's own box: full icon width, right edge inside the
 * row's end gutter.
 */
@Composable
private fun SourceRowChevron(hasSwitch: Boolean) {
    val chain =
        if (hasSwitch) {
            Modifier
                .padding(start = SwipeTheme.spacing.s)
                .size(SwipeTheme.sizes.icon)
        } else {
            Modifier
                .size(SwipeTheme.sizes.icon)
                .padding(start = SwipeTheme.spacing.xs)
        }
    SwipeIcon(
        symbol = SwipeIcons.CHEVRON_RIGHT,
        tint = SwipeTheme.colors.textSecondary,
        modifier = chain.semantics { testTag = SOURCE_ROW_CHEVRON_TEST_TAG },
    )
}

/**
 * The text column of a [SourceRow]: the title in `headline` and, when given,
 * the FR-37 [subtitle] under it in `caption textSecondary` (§5.7).
 */
@Composable
private fun SourceRowTitle(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    val colors = SwipeTheme.colors
    Column(modifier = modifier) {
        BasicText(
            text = title,
            style = SwipeTheme.typography.headline.copy(color = colors.textPrimary),
        )
        if (subtitle != null) {
            BasicText(
                text = subtitle,
                style = SwipeTheme.typography.caption.copy(color = colors.textSecondary),
            )
        }
    }
}

/**
 * The 40 dp leading slot of a [SourceRow]: the album cover when [coverAssetId]
 * and [mediaLoader] are both given, otherwise the decorative [icon] in
 * `accent`, otherwise nothing (§5.7).
 */
@Composable
private fun SourceRowLeading(
    coverAssetId: String?,
    mediaLoader: MediaLoader?,
    icon: String?,
) {
    val colors = SwipeTheme.colors
    val tokens = SwipeTheme.componentSizes
    val loader = mediaLoader
    val cover = coverAssetId
    if (cover != null && loader != null) {
        SourceRowCover(cover, loader)
    } else if (icon != null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(tokens.sourceRowThumbnail),
        ) {
            SwipeIcon(
                symbol = icon,
                tint = colors.accent,
                modifier = Modifier.size(SwipeTheme.sizes.icon),
            )
        }
    }
}

/**
 * The 40 dp album cover slot: a small-target [MediaLoader.image] request for
 * the row's own pixels (never the full-screen target, NFR-3), clipped to the
 * tile radius; leaving the composition cancels the request (§6.8).
 */
@Composable
private fun SourceRowCover(
    coverAssetId: String,
    mediaLoader: MediaLoader,
) {
    val sidePx = with(LocalDensity.current) { SwipeTheme.componentSizes.sourceRowThumbnail.roundToPx() }
    val loaded = rememberMediaImage(coverAssetId, IntSize(sidePx, sidePx), mediaLoader)
    Box(
        modifier =
            Modifier
                .size(SwipeTheme.componentSizes.sourceRowThumbnail)
                .clip(RoundedCornerShape(SwipeTheme.radii.tile))
                .background(SwipeTheme.colors.surfaceRaised),
    ) {
        loaded?.bitmap?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null, // the row-level description covers it
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The FR-37 trailing switch of a [SourceRow]: the visual [SwipeSwitch] inside a
 * touch box that owns the `Role.Switch` semantics, mirroring the Settings
 * `ToggleRow` (WP-6.1). Its clickable consumes the tap, so toggling never
 * opens the row.
 *
 * The box is `max(minTouchTarget, switchTrackWidth)` wide and at least 44 dp
 * tall (§5.5, WP-8.14): the 51 dp track fits it exactly instead of spilling
 * ~3.5 dp past a 44 dp square and touching the count.
 */
@Composable
private fun SourceRowSwitch(
    checked: Boolean,
    contentDescription: String,
    onToggle: () -> Unit,
) {
    val trackWidth = SwipeTheme.componentSizes.switchTrackWidth
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(
                    width = trackWidth.coerceAtLeast(SwipeTheme.sizes.minTouchTarget),
                    height = SwipeTheme.sizes.minTouchTarget,
                ).clickable(role = Role.Switch, onClick = onToggle)
                .semantics {
                    this.contentDescription = contentDescription
                    selected = checked
                },
    ) {
        SwipeSwitch(checked = checked)
    }
}

/**
 * NFR-4 placeholder for a count that has not arrived yet: the em dash (§6.5
 * *Home counts* — the row is on screen before its number is).
 */
const val COUNT_PLACEHOLDER = "—"

/** A count for [value] with §5.7 grouping separators, or [COUNT_PLACEHOLDER] while loading. */
fun formatCount(value: Int?): String = value?.let(::groupDigits) ?: COUNT_PLACEHOLDER

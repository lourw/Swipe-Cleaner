package dev.swipe.ui.screens.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import dev.swipe.domain.model.MediaType
import dev.swipe.presentation.swipe.CardState
import dev.swipe.ui.components.SwipeIcon
import dev.swipe.ui.components.SwipeIcons
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Test tags for the FR-18 badges (decorative, so tags are the only handle). */
internal const val BADGE_LIVE_TEST_TAG = "badge-live"
internal const val BADGE_CLOUD_TEST_TAG = "badge-cloud"
internal const val BADGE_FAVORITE_TEST_TAG = "badge-favorite"

/**
 * The §5.7 card scrim: the bottom 96 dp of the card fades to `scrim` under
 * the FR-18 metadata row — date (`caption`, `textPrimary`), the duration for
 * videos, and the Live Photo / iCloud / favorite badges at the end (WP-4.2).
 *
 * The badges are decorative (NFR-8 puts VoiceOver labels on the decision
 * controls). [isCloudOnly] lights the cloud badge on either R6 signal — the
 * caller ORs the deck-time [CardState.isCloudOnly] with the loaded image's
 * [dev.swipe.data.LoadedImage.isCloud].
 */
@Composable
fun CardMetadata(
    card: CardState,
    modifier: Modifier = Modifier,
    isCloudOnly: Boolean = false,
) {
    val colors = SwipeTheme.colors
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(SwipeTheme.componentSizes.cardScrimHeight)
                .background(
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                colors.scrim.copy(alpha = 0f),
                                colors.scrim,
                            ),
                    ),
                ).padding(horizontal = SwipeTheme.spacing.m, vertical = SwipeTheme.spacing.s),
        contentAlignment = Alignment.BottomStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.xs),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BasicText(
                text = metadataLine(card),
                style = SwipeTheme.typography.caption.copy(color = colors.textPrimary),
                modifier = Modifier.weight(1f),
            )
            if (card.type == MediaType.LIVE_PHOTO) {
                SwipeIcon(
                    SwipeIcons.LIVE,
                    contentDescription = null,
                    size = SwipeTheme.sizes.icon,
                    tint = colors.textPrimary,
                    modifier = Modifier.testTag(BADGE_LIVE_TEST_TAG),
                )
            }
            if (isCloudOnly) {
                SwipeIcon(
                    SwipeIcons.CLOUD,
                    contentDescription = null,
                    size = SwipeTheme.sizes.icon,
                    tint = colors.textPrimary,
                    modifier = Modifier.testTag(BADGE_CLOUD_TEST_TAG),
                )
            }
            if (card.isFavorite) {
                SwipeIcon(
                    SwipeIcons.FAVORITE,
                    contentDescription = null,
                    size = SwipeTheme.sizes.icon,
                    tint = colors.textPrimary,
                    modifier = Modifier.testTag(BADGE_FAVORITE_TEST_TAG),
                )
            }
        }
    }
}

/**
 * FR-18 metadata line: `Mar 14, 2021 · 0:32` — the date, then ` · ` and the
 * duration for videos; a missing date shows the duration alone (or nothing).
 */
fun metadataLine(card: CardState): String {
    val date = card.createdAt?.let(::formatCardDate)
    val duration = card.durationMs?.let(::formatDuration)
    return listOfNotNull(date, duration).joinToString(" · ")
}

/**
 * NFR-8 VoiceOver line for the deck card: date, then type, then duration,
 * then "favorite" — the date-then-type shape of
 * [dev.swipe.ui.components.tileDescription], so the FR-18 badges' meaning
 * rides the line instead of separate labels. A card always knows its type,
 * so this is never empty.
 */
fun cardAccessibilityDescription(card: CardState): String {
    val parts =
        listOfNotNull(
            card.createdAt?.let(::formatCardDate),
            card.type.voiceOverLabel(),
            card.durationMs?.let(::formatDuration),
            "favorite".takeIf { card.isFavorite },
        )
    return parts.joinToString(", ")
}

/** The type word for VoiceOver lines; lowercase mid-sentence, §5.7 wording. */
internal fun MediaType.voiceOverLabel(): String =
    when (this) {
        MediaType.PHOTO -> "photo"
        MediaType.VIDEO -> "video"
        MediaType.LIVE_PHOTO -> "Live Photo"
    }

/** `Mar 14, 2021` in the device's timezone (§5.7 example); English abbrevs. */
fun formatCardDate(instant: Instant): String {
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${monthAbbreviations[local.month.ordinal]} ${local.day}, ${local.year}"
}

/** `0:32`, or `1:02:03` past an hour (the §5.6/§5.7 duration shape). */
fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    val secondPart = seconds.toString().padStart(2, '0')
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:$secondPart"
    } else {
        "$minutes:$secondPart"
    }
}

private val monthAbbreviations =
    listOf(
        "Jan",
        "Feb",
        "Mar",
        "Apr",
        "May",
        "Jun",
        "Jul",
        "Aug",
        "Sep",
        "Oct",
        "Nov",
        "Dec",
    )

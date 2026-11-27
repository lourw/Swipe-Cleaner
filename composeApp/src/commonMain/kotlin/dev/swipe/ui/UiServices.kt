package dev.swipe.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import dev.swipe.data.Haptics
import dev.swipe.data.MediaLoader
import dev.swipe.data.NoPerfTrace
import dev.swipe.data.PerfTrace

/**
 * The sanctioned UI services (§6.1): image loading ([LocalMediaLoader]),
 * haptics ([LocalHaptics]), and perf tracing ([LocalPerfTrace]) reach
 * composables through these locals, provided once by `App()` from Koin via
 * [ProvideUiServices]. Hard rule 5: every other `data` interface is used
 * only through ViewModels; keep it that way — nothing else joins this list.
 */
val LocalMediaLoader = staticCompositionLocalOf<MediaLoader> { error("LocalMediaLoader not provided") }

val LocalHaptics = staticCompositionLocalOf<Haptics> { error("LocalHaptics not provided") }

val LocalPerfTrace = staticCompositionLocalOf<PerfTrace> { NoPerfTrace }

/**
 * §5.6 Reduce Motion: a **value**, not a service — the one exception to the rule
 * above, and it stays a Boolean. Provided by the permission flow from
 * [dev.swipe.presentation.permission.PermissionUiState.reduceMotion] (the
 * ViewModel mirrors `SystemActions`), read by the deck. Never put a `data`
 * interface itself in a composition local (hard rule 5). Dynamic, because the
 * setting changes while the app runs.
 */
val LocalReduceMotion = compositionLocalOf { false }

/** Provides the three sanctioned UI services (§6.1) to [content]. */
@Composable
fun ProvideUiServices(
    mediaLoader: MediaLoader,
    haptics: Haptics,
    perfTrace: PerfTrace,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalMediaLoader provides mediaLoader,
        LocalHaptics provides haptics,
        LocalPerfTrace provides perfTrace,
        content = content,
    )
}

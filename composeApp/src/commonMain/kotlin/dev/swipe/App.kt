package dev.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.swipe.ui.components.ComponentGallery
import dev.swipe.ui.theme.SwipeTheme

/**
 * Root composable. Navigation and screens are added from M1 onwards
 * (REQUIREMENTS §9). The debug WP-1.2 [gallery] (ComponentGallery) is only
 * passed from debug entry points; release builds never reach it.
 */
@Composable
fun App(modifier: Modifier = Modifier, gallery: Boolean = false) {
    SwipeTheme {
        Box(
            modifier = modifier.fillMaxSize().background(SwipeTheme.colors.bg),
            contentAlignment = Alignment.Center,
        ) {
            if (gallery) {
                ComponentGallery()
            } else {
                BasicText(
                    text = "Swipe Cleaner",
                    style = SwipeTheme.typography.title.copy(color = SwipeTheme.colors.textPrimary),
                )
            }
        }
    }
}

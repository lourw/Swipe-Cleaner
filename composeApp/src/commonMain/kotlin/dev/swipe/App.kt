package dev.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.swipe.ui.theme.SwipeTheme

/** Root composable. Navigation and screens are added from M1 onwards (REQUIREMENTS §9). */
@Composable
fun App(modifier: Modifier = Modifier) {
    SwipeTheme {
        Box(
            modifier = modifier.fillMaxSize().background(SwipeTheme.colors.bg),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "Swipe Cleaner",
                style = SwipeTheme.typography.title.copy(color = SwipeTheme.colors.textPrimary),
            )
        }
    }
}

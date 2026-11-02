package dev.swipe

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AppSmokeTest {
    /**
     * `App()` now hosts the permission flow and needs a Koin context, so the
     * smoke test drives the debug gallery path, which shares the theme and
     * the composable tree up to the flow.
     */
    @Test
    fun `M0 app renders the debug gallery`() =
        runComposeUiTest {
            setContent { App(gallery = true) }
            onNodeWithText("WP-1.2 gallery").assertExists()
        }
}

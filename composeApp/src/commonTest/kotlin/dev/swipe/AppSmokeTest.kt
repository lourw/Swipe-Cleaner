package dev.swipe

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AppSmokeTest {
    @Test
    fun `M0 app renders the title`() =
        runComposeUiTest {
            setContent { App() }
            onNodeWithText("Swipe Cleaner").assertExists()
        }
}

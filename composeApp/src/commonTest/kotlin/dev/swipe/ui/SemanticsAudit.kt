// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import kotlin.test.assertTrue

/**
 * The NFR-8 screen audit: walk the merged semantics tree and fail on every
 * node that offers a click or long-click without a contentDescription or
 * visible text — whatever a VoiceOver user can activate, they can hear. The
 * merged tree is the tree the accessibility bridge renders, and every control
 * in it carries its own label (its children's text merges in, exactly as
 * VoiceOver would read it).
 *
 * The test v2 API exposes no root-node accessor, so the walk starts at a
 * clickable node ([hasClickAction] is the one matcher the audit needs to
 * find a node with) and climbs [SemanticsNode.parent] to the root. Screens
 * call this from their own tests with their fixture up.
 */
fun SemanticsNodeInteractionsProvider.assertAllClickablesLabeled() {
    val clickables = onAllNodes(hasClickAction()).fetchSemanticsNodes()
    assertTrue(
        clickables.isNotEmpty(),
        "NFR-8 audit: the fixture renders no clickable node at all — the audit " +
            "would pass vacuously, so it is a fixture bug.",
    )
    var root = clickables.first()
    while (true) {
        root = root.parent ?: break
    }

    val unlabeled = mutableListOf<SemanticsNode>()

    fun walk(node: SemanticsNode) {
        val config = node.config
        val clickable =
            config.contains(SemanticsActions.OnClick) ||
                config.contains(SemanticsActions.OnLongClick)
        val labeled =
            (
                config.contains(SemanticsProperties.ContentDescription) &&
                    config[SemanticsProperties.ContentDescription].any { it.isNotBlank() }
            ) ||
                (
                    config.contains(SemanticsProperties.Text) &&
                        config[SemanticsProperties.Text].any { it.isNotBlank() }
                )
        if (clickable && !labeled) {
            unlabeled += node
        }
        node.children.forEach(::walk)
    }

    walk(root)
    assertTrue(
        unlabeled.isEmpty(),
        "NFR-8: clickable nodes without a contentDescription or text: " +
            unlabeled.joinToString { node -> node.boundsInRoot.toString() },
    )
}

package dev.swipe.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Item 005 (FR-2, FR-32): one shared presenter walk. The FR-2 limited-library picker
 * used a lookup that stopped at the key window's root, so it could silently not appear
 * while a sheet was presented. [topOfChain] is the pure core of the shared
 * [topViewController]: it must follow `presentedViewController` to the end of the
 * chain, not stop at the root.
 */
class TopViewControllerTest {
    @Test
    fun `FR-2 the presenter walk follows every presented controller to the top`() {
        val chain = mapOf("root" to "sheet", "sheet" to "alert")
        val top = topOfChain("root") { chain[it] }
        assertEquals("alert", top)
    }

    @Test
    fun `FR-2 the presenter walk returns the root when nothing is presented`() {
        val top = topOfChain("root") { null }
        assertEquals("root", top)
    }
}

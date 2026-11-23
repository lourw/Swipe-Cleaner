@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import dev.swipe.domain.model.MediaTypeFilter
import platform.Foundation.NSSortDescriptor
import platform.Photos.PHAsset
import platform.Photos.PHFetchOptions
import platform.Photos.PHFetchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP-7.7 (FR-7, §6.5): the month and All fetches must run PhotoKit with the **full**
 * sorted options. The shipped bug built sorted fetch options and then handed only
 * `options.predicate` to `fetchAssetsWithOptions`, so the deck came back in PhotoKit's
 * natural order (oldest first) — an order-only regression invisible to the fake, which
 * sorts. These tests capture the exact [PHFetchOptions] each fetch passes to PhotoKit
 * (the `fetch` seam on the functions under test) and pin the sort descriptor and the
 * predicate, so the bug cannot return whatever the test host's photo-library access is.
 * WP-8.3 adds the same treatment to the shared id lookup: its options must carry
 * `includeAllBurstAssets`, and a failing fetch must answer null (audit R1).
 */
class PhotoKitFetchOptionsTest {
    /** Runs [block] capturing the options handed to the fetch seam. */
    private fun capturedOptions(block: ((PHFetchOptions) -> PHFetchResult) -> List<String>): PHFetchOptions {
        val captured = mutableListOf<PHFetchOptions>()
        block { options ->
            captured += options
            // Real fetch, so the seam behaves exactly as production: without photo
            // access it answers empty, which is all these assertions need.
            PHAsset.fetchAssetsWithOptions(options)
        }
        assertEquals(1, captured.size, "the fetch must run exactly once")
        return captured.single()
    }

    private fun assertCreationDateDescending(
        label: String,
        options: PHFetchOptions,
    ) {
        val descriptors = options.sortDescriptors.orEmpty().map { it as? NSSortDescriptor }
        val descriptor =
            descriptors.singleOrNull()
                ?: error("$label: expected exactly one sort descriptor, got ${options.sortDescriptors}")
        assertEquals("creationDate", descriptor.key, "$label: sort key")
        assertFalse(descriptor.ascending, "$label: deck order is newest first (§6.5)")
    }

    @Test
    fun `FR-7 the month fetch hands PhotoKit a creationDate descending sort`() {
        val options = capturedOptions { fetch -> monthAssetIds(2025, 3, MediaTypeFilter.ALL, fetch) }
        assertCreationDateDescending("FR-7 month deck", options)
    }

    @Test
    fun `FR-7 the month fetch predicate is the half-open creationDate range`() {
        val options = capturedOptions { fetch -> monthAssetIds(2025, 3, MediaTypeFilter.ALL, fetch) }
        val predicate = assertNotNull(options.predicate, "the month fetch must carry its range predicate")
        val text = predicate.toString()
        assertTrue("creationDate >=" in text, "range start bound in: $text")
        assertTrue("creationDate <" in text, "range end bound in: $text")
    }

    @Test
    fun `FR-5 the month fetch ANDs the type filter into the predicate`() {
        val options = capturedOptions { fetch -> monthAssetIds(2025, 3, MediaTypeFilter.PHOTOS, fetch) }
        assertTrue("mediaType" in assertNotNull(options.predicate).toString())
    }

    @Test
    fun `FR-5 the All fetch hands PhotoKit a creationDate descending sort`() {
        val options = capturedOptions { fetch -> allAssetIds(MediaTypeFilter.ALL, fetch) }
        assertCreationDateDescending("FR-5 All deck", options)
        assertNull(options.predicate, "the ALL type filter must not restrict the fetch")
    }

    @Test
    fun `FR-6 the id lookup hands PhotoKit includeAllBurstAssets options`() {
        // WP-8.3: the empty Bursts deck. Source fetches enumerate every burst
        // frame with includeAllBurstAssets, so every id → asset lookup must
        // use the same view — with nil options PhotoKit's default view carries
        // one representative per burst and the deck's burst ids resolved to
        // nothing. This pins the shared fetchAssetsByIds options on any host.
        val requested = listOf("wp83-burst-a1", "wp83-burst-b2")
        val captured = mutableListOf<PHFetchOptions>()
        fetchAssetsByIds(requested) { ids, options ->
            assertEquals(requested, ids, "the lookup must fetch exactly the requested ids")
            captured += options
            null // No real fetch needed; the host may have no photo library at all.
        }
        assertEquals(1, captured.size, "the lookup must fetch exactly once")
        assertTrue(
            captured.single().includeAllBurstAssets,
            "id lookups must pass includeAllBurstAssets so every frame a source fetch enumerated stays resolvable (FR-6, WP-8.3)",
        )
    }

    @Test
    fun `FR-6 a failed id fetch answers null not an empty result`() {
        // Audit R1: callers separate "PhotoKit could not answer" from "no such
        // assets" on this null. The seam answering null must pass straight
        // through (the binding types the result non-null, so production wraps
        // the call in runCatching — this pins that the seam's null is honored).
        assertNull(fetchAssetsByIds(listOf("wp83-any-id")) { _, _ -> null })
    }
}

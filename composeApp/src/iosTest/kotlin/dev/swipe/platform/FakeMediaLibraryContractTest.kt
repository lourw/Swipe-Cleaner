package dev.swipe.platform

import dev.swipe.data.MediaLibrary
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.fakes.FakeMediaLibrary
import kotlin.time.Instant

/**
 * WP-7.7: the [MediaLibraryContract] as [FakeMediaLibrary] honors it. This is
 * the baseline the FR-7 month bug drifted from — the fake always sorted, so
 * the shared fake-side assertions pin the contract the real runner must
 * match. Never assumption-skips: the fake has no environment to fail, and it
 * models the WP-8.3 burst fixture through [MediaItem.burstId].
 */
class FakeMediaLibraryContractTest : MediaLibraryContract() {
    private val fake = FakeMediaLibrary(items = emptyList())

    override val library: MediaLibrary get() = fake

    override suspend fun seed(): Seeded {
        fixtureAssets.forEach { asset ->
            fake.items +=
                MediaItem(
                    id = asset.name,
                    type = asset.type,
                    createdAt = createdAt(asset),
                    durationMs = if (asset.type == MediaType.VIDEO) VIDEO_DURATION_MS else null,
                    widthPx = ASSET_EDGE_PX,
                    heightPx = ASSET_EDGE_PX,
                    isFavorite = false,
                    isScreenshot = false,
                    burstId = null,
                    isCloudOnly = null,
                )
        }
        // WP-8.3: the fake models bursts as plain items carrying a burstId —
        // exactly what a burst-inclusive id lookup must resolve.
        burstFixture.forEach { frame ->
            fake.items +=
                MediaItem(
                    id = frame.id,
                    type = MediaType.PHOTO,
                    createdAt = Instant.fromEpochSeconds(epochSeconds(newerMonth, frame.day)),
                    durationMs = null,
                    widthPx = ASSET_EDGE_PX,
                    heightPx = ASSET_EDGE_PX,
                    isFavorite = false,
                    isScreenshot = false,
                    burstId = frame.burstId,
                    isCloudOnly = null,
                )
        }
        return Seeded(fixtureAssets.map { it.name })
    }

    override val burstFrameIds: List<String> get() = burstFixture.map { it.id }

    override suspend fun cleanUp(seeded: Seeded) {
        // The fake is a fresh instance per test; nothing to clean up.
    }

    private companion object {
        const val VIDEO_DURATION_MS = 1_000L
        const val ASSET_EDGE_PX = 64
    }
}

package dev.swipe.platform

import dev.swipe.data.MediaLibrary
import dev.swipe.domain.model.MediaType
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toNSDate
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextSetFillColorWithColor
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMake
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.kCVPixelBufferHeightKey
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelBufferWidthKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSArray
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAsset
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.posix.usleep
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.AfterTest

/**
 * WP-7.7: the [MediaLibraryContract] against the real [PhotoKitMediaLibrary].
 *
 * This is the runner the FR-7 bug needed: [monthAssetIds] built sorted fetch
 * options but handed PhotoKit only the predicate, so real decks came back in
 * natural (oldest-first) order while every existing test passed against the
 * fake, which sorts. The runner seeds a small deterministic fixture through
 * the PhotoKit write API (two calendar months, photos and videos, with known
 * creation dates) and runs the shared contract against the real fetches.
 *
 * Seeding is a privilege, not a given: the Gradle simulator test host has no
 * photo library entitlement, and a developer's host may deny access. When
 * read-write authorization is unavailable or the writes fail, seeding
 * reports [Seeded] with `null` and the asset-dependent tests skip with an
 * explicit assumption message (never a silent pass, never a suite failure).
 * The fetch-options specification that pins the fix deterministically on any
 * host lives in [PhotoKitFetchOptionsTest], which needs no photo library.
 *
 * Writes go to the simulator's shared photo library, so the runner cleans up
 * after itself and only ever deletes the asset ids it created (AGENTS.md
 * rule 1 holds in app code: `CommitDeletionsUseCase` remains the only delete
 * path there; this is test code removing test-owned assets, guarded to the
 * fixture ids).
 */
@OptIn(
    ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class, // NSArray.create (mirrors PhotoKitMediaLibrary, FR-25)
)
class PhotoKitMediaLibraryContractTest : MediaLibraryContract() {
    override val library: MediaLibrary = PhotoKitMediaLibrary()

    @AfterTest
    fun tearDown() {
        if (seeded.isSeeded) {
            runBlocking { cleanUp(seeded) }
        }
    }

    override suspend fun seed(): Seeded {
        // A denied or not-determined library cannot be written. We never
        // prompt from tests: on a headless host the prompt never resolves,
        // and prompting would mutate user state.
        val status = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)
        if (status != PHAuthorizationStatusAuthorized) {
            println("SKIPPED (WP-7.7): host photo library is not read-write authorized (status=$status)")
            return Seeded(null)
        }
        return seedFixture()
    }

    /** Writes the fixture once authorization is granted; skips with a reason on any failure. */
    private suspend fun seedFixture(): Seeded {
        val videoFiles = writeFixtureVideos()
        val created = mutableListOf<String>()
        val failure =
            when {
                videoFiles.size != fixtureAssets.count { it.type == MediaType.VIDEO } -> {
                    "could not encode the fixture videos on this host"
                }

                !performFixtureChanges {
                    writeFixtureAssets(videoFiles, created)
                } ||
                    created.size != fixtureAssets.size -> {
                    "fixture writes into the real photo library failed"
                }

                else -> {
                    null
                }
            }
        if (failure != null) {
            println("SKIPPED (WP-7.7): $failure")
            if (created.isNotEmpty()) {
                // A partial write must not leak into the shared library.
                deleteFixtureAssets(created.toList())
            }
            return Seeded(null)
        }
        // Placeholders are captured in fixture order, so `created` already
        // matches the fixture's ascending creation order.
        return Seeded(created.toList())
    }

    /** One `performChanges` block: create all six fixtures with their dates. */
    private fun writeFixtureAssets(
        videoFiles: Map<String, NSURL>,
        created: MutableList<String>,
    ): Boolean {
        val perAsset = fixtureAssets.map { asset -> createFixtureAsset(asset, videoFiles) }
        created += perAsset.takeWhile { it != null }.filterNotNull()
        return perAsset.size == created.size
    }

    /** Creates one fixture asset and returns its local id, or null when PhotoKit refuses. */
    private fun createFixtureAsset(
        asset: FixtureAsset,
        videoFiles: Map<String, NSURL>,
    ): String? {
        val request =
            when (asset.type) {
                MediaType.PHOTO -> {
                    PHAssetChangeRequest.creationRequestForAssetFromImage(fixtureImage(asset))
                }

                MediaType.VIDEO -> {
                    PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(
                        videoFiles.getValue(asset.name),
                    )
                }

                MediaType.LIVE_PHOTO -> {
                    error("the fixture has no live photos")
                }
            }
        if (request != null) {
            request.creationDate =
                kotlin.time.Instant
                    .fromEpochSeconds(epochSeconds(fixtureMonths[asset.month], dayOf(asset)))
                    .toNSDate()
        }
        return request?.placeholderForCreatedAsset()?.localIdentifier
    }

    override suspend fun cleanUp(seeded: Seeded) {
        val ids = seeded.ids ?: return
        deleteFixtureAssets(ids)
    }

    // ---- seeding helpers ----

    /**
     * Runs [block] inside `PHPhotoLibrary.performChanges` and reports whether
     * both the block and the change commit succeeded.
     */
    private suspend fun performFixtureChanges(block: () -> Boolean): Boolean {
        var blockOk = false
        var commitError: NSError? = null
        suspendCoroutine<Unit> { cont ->
            PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                {
                    blockOk = runCatching(block).getOrDefault(false)
                },
                completionHandler = { _, error ->
                    commitError = error
                    cont.resume(Unit)
                },
            )
        }
        return blockOk && commitError == null
    }

    /**
     * Deletes exactly the fixture asset ids this runner created — never any
     * pre-existing library content.
     */
    private suspend fun deleteFixtureAssets(ids: List<String>) {
        var ok = false
        suspendCoroutine<Unit> { cont ->
            PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                {
                    val assets = mutableListOf<PHAsset>()
                    PHAsset.fetchAssetsWithLocalIdentifiers(ids, null).enumerateObjectsUsingBlock { asset, _, _ ->
                        (asset as? PHAsset)?.let(assets::add)
                    }
                    ok = assets.size == ids.size
                    if (assets.isNotEmpty()) {
                        PHAssetChangeRequest.deleteAssets(NSArray.create(array = assets))
                    }
                },
                completionHandler = { _, _ -> cont.resume(Unit) },
            )
        }
        if (!ok) println("WP-7.7: fixture cleanup could not resolve every seeded asset")
    }

    /** Renders a small solid-color UIImage, one distinct color per fixture. */
    private fun fixtureImage(asset: FixtureAsset): platform.UIKit.UIImage {
        val palette =
            listOf(
                Triple(0.9, 0.2, 0.2),
                Triple(0.2, 0.9, 0.2),
                Triple(0.2, 0.2, 0.9),
                Triple(0.9, 0.9, 0.2),
                Triple(0.9, 0.2, 0.9),
                Triple(0.2, 0.9, 0.9),
            )
        val (r, g, b) = palette[fixtureAssets.indexOf(asset).coerceAtLeast(0) % palette.size]
        val color = UIColor.colorWithRed(r, green = g, blue = b, alpha = 1.0)
        @Suppress("DEPRECATION") // Deterministic offscreen fill; the modern renderer APIs are not exposed by the interop.
        return run {
            UIGraphicsBeginImageContextWithOptions(CGSizeMake(64.0, 64.0), true, 1.0)
            val cg = UIGraphicsGetCurrentContext()
            if (cg != null) {
                CGContextSetFillColorWithColor(cg, color.CGColor)
                CGContextFillRect(cg, CGRectMake(0.0, 0.0, 64.0, 64.0))
            }
            val image = UIGraphicsGetImageFromCurrentImageContext()
            UIGraphicsEndImageContext()
            requireNotNull(image)
        }
    }

    /**
     * Writes one short H.264 video per video fixture into the test sandbox
     * and returns name -> local file URL. PhotoKit's video ingest rejects
     * still-image containers, so each video is encoded with AVAssetWriter
     * from generated pixel buffers.
     */
    private fun writeFixtureVideos(): Map<String, NSURL> {
        val out = mutableMapOf<String, NSURL>()
        fixtureAssets.filter { it.type == MediaType.VIDEO }.forEach { asset ->
            val url = fixtureVideoUrl(asset.name)
            if (writeVideoFile(url)) out[asset.name] = url
        }
        return out
    }

    private fun fixtureVideoUrl(name: String): NSURL = NSURL.fileURLWithPath("${NSTemporaryDirectory()}/$name.mp4")

    /** Encodes a two-second solid-color video at [url]. */
    private fun writeVideoFile(url: NSURL): Boolean =
        memScoped {
            NSFileManager.defaultManager.removeItemAtURL(url, error = null)
            val writer =
                AVAssetWriter(uRL = url, fileType = AVFileTypeMPEG4, error = null)
            val input =
                AVAssetWriterInput(
                    mediaType = AVMediaTypeVideo,
                    outputSettings =
                        mapOf<Any?, Any?>(
                            FORMAT_WIDTH_KEY to WIDTH,
                            FORMAT_HEIGHT_KEY to HEIGHT,
                            AVVideoCodecKey to AVVideoCodecTypeH264,
                        ),
                ).apply { expectsMediaDataInRealTime = false }
            writer.addInput(input)
            val adaptor =
                AVAssetWriterInputPixelBufferAdaptor(
                    assetWriterInput = input,
                    sourcePixelBufferAttributes =
                        mapOf<Any?, Any?>(
                            kCVPixelBufferPixelFormatTypeKey to kCVPixelFormatType_32BGRA.toInt(),
                            kCVPixelBufferWidthKey to WIDTH,
                            kCVPixelBufferHeightKey to HEIGHT,
                        ),
                )
            if (!writer.startWriting()) return false
            writer.startSessionAtSourceTime(startTime = CMTimeMake(value = 0, timescale = TIME_SCALE))
            repeat(FRAMES) { frame ->
                val pixelBuffer = createPixelBuffer() ?: return false
                val appended =
                    adaptor.appendPixelBuffer(
                        pixelBuffer = pixelBuffer,
                        withPresentationTime = CMTimeMake(value = frame.toLong(), timescale = TIME_SCALE),
                    )
                CVPixelBufferRelease(pixelBuffer)
                if (!appended) return false
                // Pace the writer: appends must not outrun its ingest queue.
                usleep(20_000u)
            }
            input.markAsFinished()
            writer.endSessionAtSourceTime(CMTimeMake(value = FRAMES.toLong(), timescale = TIME_SCALE))
            writer.finishWriting()
            while (writer.status == AVAssetWriterStatusWriting) {
                usleep(50_000u)
            }
            writer.status == AVAssetWriterStatusCompleted
        }

    private fun createPixelBuffer(): CVPixelBufferRef? =
        memScoped {
            val pixelBuffer = alloc<CVPixelBufferRefVar>()
            val status =
                CVPixelBufferCreate(
                    allocator = null,
                    width = WIDTH.toULong(),
                    height = HEIGHT.toULong(),
                    pixelFormatType = kCVPixelFormatType_32BGRA,
                    pixelBufferAttributes = null,
                    pixelBufferOut = pixelBuffer.ptr,
                )
            if (status != 0) null else pixelBuffer.value
        }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 64
        const val TIME_SCALE = 5
        const val FRAMES = 10

        // AVFoundation settings keys; `AVFormatWidthKey` / `AVFormatHeightKey` are not exposed
        // by the Kotlin platform interop, so their documented raw values are pinned here.
        const val FORMAT_WIDTH_KEY = "width"
        const val FORMAT_HEIGHT_KEY = "height"
    }
}

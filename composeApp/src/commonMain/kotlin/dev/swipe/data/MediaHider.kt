package dev.swipe.data

import dev.swipe.domain.model.HideResult

/**
 * The **hide write door** of the photo library (FR-38, WP-8.11): the one interface whose
 * [setHidden] flips an asset's `hidden` flag. It is a sibling of [MediaDeleter], not a
 * method on it or on [MediaLibrary] (§6.4): [MediaLibrary] stays the read side every
 * ViewModel receives, and the Koin graph binds this type only into the swipe path
 * (`SwipeViewModel`), so "only the deck can hide" is a compile-time fact the same way
 * "only `CommitDeletionsUseCase` can delete" is (AGENTS.md rule 1, §8.3 check-rules).
 *
 * Hiding is immediate — no queue and no in-app confirmation (FR-38): Undo is the safety
 * net. The Photos app keeps hidden assets in the *Hidden* album, so this is reversible
 * both in-app (undo unhides) and in Photos itself.
 *
 * Implemented by `iosMain/platform/PhotoKitMediaHider.kt` (a `performChanges` change
 * request setting `PHAssetChangeRequest.hidden` — the only `performChanges` writer in the
 * app besides [MediaDeleter]'s implementation, and it never reaches `deleteAssets`), and
 * by `commonTest/fakes/FakeMediaLibrary` (§8.2).
 */
interface MediaHider {
    /**
     * Set asset [id]'s `hidden` flag to [hidden] and report the outcome
     * ([HideResult.APPLIED] once the change request completes, [HideResult.FAILED]
     * when the asset cannot be fetched or the change fails). Suspends until PhotoKit
     * answers; a cancelled caller cancels the wait, not the change.
     */
    suspend fun setHidden(
        id: String,
        hidden: Boolean,
    ): HideResult
}

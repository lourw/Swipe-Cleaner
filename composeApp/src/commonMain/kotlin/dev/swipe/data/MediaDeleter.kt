package dev.swipe.data

import dev.swipe.domain.model.DeleteResult

/**
 * The **write door** of the photo library (WP-9.1, NFR-5, §6.4): the one interface whose
 * [delete] removes assets. It is deliberately separate from [MediaLibrary] — the read
 * side every ViewModel receives — so "only `CommitDeletionsUseCase` can delete" is a
 * compile-time fact rather than a convention: a ViewModel holding a [MediaLibrary]
 * simply has no way to name a delete, and the only Koin binding of this type is the one
 * `CommitDeletionsUseCase` takes.
 *
 * Nothing else belongs here. A future second write path (FR-38's `setHidden`, WP-8.11)
 * gets its own writer interface bound the same way — never a method added here — so
 * each write path keeps a single, greppable consumer.
 *
 * Implemented by the same `iosMain/platform/PhotoKitMediaLibrary` object that
 * implements [MediaLibrary] (one PhotoKit bridge, two typed views of it), and by
 * `commonTest/fakes/FakeMediaLibrary` (§8.2). AGENTS.md rule 1 and the §8.3
 * check-rules guard keep `CommitDeletionsUseCase` the only production consumer.
 */
interface MediaDeleter {
    /**
     * Delete assets via one PhotoKit change request and the iOS system dialog (FR-25).
     * Only `CommitDeletionsUseCase` may call this (NFR-5, AGENTS.md rule 1).
     */
    suspend fun delete(ids: List<String>): DeleteResult
}

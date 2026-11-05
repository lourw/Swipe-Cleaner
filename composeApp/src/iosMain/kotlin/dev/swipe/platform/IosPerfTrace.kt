@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import dev.swipe.data.PerfSpan
import dev.swipe.data.PerfTrace
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.darwin.NSObject
import platform.darwin.OS_SIGNPOST_INTERVAL_BEGIN
import platform.darwin.OS_SIGNPOST_INTERVAL_END
import platform.darwin._os_signpost_emit_with_name_impl
import platform.darwin.os_log_create
import platform.darwin.os_signpost_id_generate

/**
 * The iOS [PerfTrace]: real `os_signpost` intervals on the unified log
 * (WP-6.3, NFR-1, NFR-4), captured by Instruments or `log stream`.
 *
 * **Why not `os_signpost_interval_begin`/`_end`?** In the current SDK those
 * are *macros* expanding to the exported `_os_signpost_emit_with_name_impl`
 * (verified against iPhoneSimulator26.5.sdk `os/signpost.h`), so the platform
 * klib never imports them. Calling the exported impl directly is exactly
 * what the macros compile to: `(dso, log, type, spid, name, format, buf,
 * size)` with an empty encoded-format buffer (`{0x00}`, size 1 — what
 * `"" __VA_ARGS__` produces) and a NULL caller-dso (the name is copied into
 * the record, the dso only attributes format-string pointers).
 *
 * The log handle comes from `os_log_create`, not `os_signpost_log_create`:
 * signposts ride any `os_log` handle; the dedicated creator only inherits
 * QoS. `begin` generates a fresh signpost id (so overlapping intervals of
 * one name — a swipe before the previous card's image landed — each pair
 * correctly); `end` closes the most recent unmatched `begin` and drops
 * unmatched calls. All call sites run on the main thread.
 */
object IosPerfTrace : PerfTrace {
    /** Subsystem the trace filters on: `subsystem == "dev.swipe.cleaner.perf"`. */
    const val SUBSYSTEM: String = "dev.swipe.cleaner.perf"

    private const val CATEGORY = "deck"

    private val log: NSObject? = os_log_create(SUBSYSTEM, CATEGORY)

    /** Span → the id of its most recent unmatched begin. Main thread only. */
    private val pending = mutableMapOf<PerfSpan, ULong>()

    override fun begin(span: PerfSpan) {
        val log = log ?: return
        val id = os_signpost_id_generate(log)
        emit(log, OS_SIGNPOST_INTERVAL_BEGIN, span.name, id)
        pending[span] = id
    }

    override fun end(span: PerfSpan) {
        val log = log ?: return
        pending.remove(span)?.let { id -> emit(log, OS_SIGNPOST_INTERVAL_END, span.name, id) }
    }

    /** Emits one interval marker. */
    private fun emit(
        log: NSObject,
        type: UByte,
        name: String,
        id: ULong,
    ) {
        memScoped {
            // The empty encoded-format buffer: one NUL byte, as the
            // os_signpost macros produce for a "" format.
            val emptyFormat: UByteVar = alloc()
            emptyFormat.value = 0u.toUByte()
            _os_signpost_emit_with_name_impl(null, log, type, id, name, "", emptyFormat.ptr, 1u)
        }
    }
}

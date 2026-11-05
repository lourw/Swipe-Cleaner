@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import dev.swipe.data.PerfSpan
import dev.swipe.data.PerfTrace
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import platform.darwin.NSObject
import platform.darwin.OS_SIGNPOST_INTERVAL_BEGIN
import platform.darwin.OS_SIGNPOST_INTERVAL_END
import platform.darwin._os_signpost_emit_with_name_impl
import platform.darwin.os_log_create
import platform.darwin.os_signpost_id_generate
import platform.posix.dl_info
import platform.posix.dladdr

/**
 * The iOS [PerfTrace]: real `os_signpost` intervals on the unified log
 * (WP-6.3, NFR-1, NFR-4), captured by Instruments or
 * `log stream --signpost` (see docs/perf.md).
 *
 * **Why not `os_signpost_interval_begin`/`_end`?** In the current SDK those
 * are *macros* expanding to the exported `_os_signpost_emit_with_name_impl`
 * (verified against iPhoneSimulator26.5.sdk `os/signpost.h`), so the platform
 * klib never imports them. Calling the exported impl directly is what the
 * macros compile to: `(dso, log, type, spid, name, format, buf, size)` with
 * plain C-string name and format (an oslogstring flag byte renders as a
 * stray character; a NULL format segfaults) and a two-byte zero argument
 * buffer. Two contracts were verified empirically:
 * a NULL caller-dso traps (`assertion failed … libsystem_trace.dylib … :
 * 0x0`), and a buffer size <= 1 traps too — libsystem_trace only emits from
 * size 2. The dso is the image base of this dylib via `dladdr` on a static
 * function pointer — byte-identical to `&__dso_handle` (the SDK declares it
 * `struct mach_header`), confirmed equal to `dli_fbase` at runtime.
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

    /** A function pointer compiled into this dylib; its image is ours. */
    private val dsoAnchor: COpaquePointer = staticCFunction<Unit> { }

    /** The mach-header base of the image containing [dsoAnchor], or null. */
    private val dso: COpaquePointer? =
        memScoped {
            val info = alloc<dl_info>()
            if (dladdr(dsoAnchor, info.ptr) != 0) info.dli_fbase else null
        }

    private val log: NSObject? = os_log_create(SUBSYSTEM, CATEGORY)

    /** Span → the id of its most recent unmatched begin. Main thread only. */
    private val pending = mutableMapOf<PerfSpan, ULong>()

    override fun begin(span: PerfSpan) {
        val log = log ?: return
        val dso = dso ?: return
        val id = os_signpost_id_generate(log)
        emit(dso, log, OS_SIGNPOST_INTERVAL_BEGIN, span.name, id)
        pending[span] = id
    }

    override fun end(span: PerfSpan) {
        val log = log ?: return
        val dso = dso ?: return
        pending.remove(span)?.let { id -> emit(dso, log, OS_SIGNPOST_INTERVAL_END, span.name, id) }
    }

    /** Emits one interval marker. */
    private fun emit(
        dso: COpaquePointer,
        log: NSObject,
        type: UByte,
        name: String,
        id: ULong,
    ) {
        memScoped {
            // Name and format go in as plain C strings (verified: an oslogstring
            // 0x01 flag prefix renders as a stray character; a NULL format
            // segfaults). The argument buffer is clang's empty one — two zero
            // bytes; libsystem_trace traps when the size argument is <= 1.
            val buf = allocArray<UByteVar>(2)
            _os_signpost_emit_with_name_impl(dso, log, type, id, name, "", buf, 2u)
        }
    }
}

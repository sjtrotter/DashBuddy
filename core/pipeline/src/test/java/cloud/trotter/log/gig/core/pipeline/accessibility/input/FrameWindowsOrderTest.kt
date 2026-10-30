package cloud.trotter.log.gig.core.pipeline.accessibility.input

import cloud.trotter.log.gig.core.pipeline.accessibility.ForegroundSkipReason
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.EventSnapshot
import cloud.trotter.log.gig.core.pipeline.accessibility.input.MemoryFrameWindowAccess.Call
import cloud.trotter.log.gig.core.pipeline.accessibility.input.MemoryFrameWindowAccess.Companion.APP
import cloud.trotter.log.gig.core.pipeline.accessibility.input.MemoryFrameWindowAccess.Companion.OVERLAY
import cloud.trotter.log.gig.core.pipeline.accessibility.input.MemoryFrameWindowAccess.Companion.OWN
import org.junit.Assert.*
import org.junit.Test

class FrameWindowsOrderTest {
    @Test fun `generation precedes every enumerated event path including both native fallbacks`() {
        // A clear in enumeration must discard ALL subsequent writes from that list. A root-fetch
        // assertion makes this stronger than merely observing the generation counter afterward.
        for (mode in listOf("active", "unreadable", "native-in-list", "native-unlisted", "ambiguous", "foreground")) {
            val h = MemoryFrames()
            val active = mode !in setOf("native-in-list", "native-unlisted", "ambiguous")
            val root = MemoryRoot(if (mode == "foreground") OWN else APP, 1)
            h.access.add(MemoryWindow(1, 10, active = active), if (mode == "unreadable") null else root)
            if (mode == "ambiguous") {
                h.access.windowList = h.access.windowList.map { it.copy(active = true) }
                h.access.add(MemoryWindow(4, 1, active = true), MemoryRoot(APP, 4))
            }
            h.access.add(MemoryWindow(2, 20, WindowKind.SYSTEM), MemoryRoot("system.shade", 2))
            h.access.add(MemoryWindow(3, 2), MemoryRoot(APP, 3))
            h.access.native = when (mode) {
                "native-unlisted" -> MemoryRoot(OWN, 999)
                else -> root
            }
            h.access.onEnumerate = { h.resolver.invalidateTopology() }
            assertTrue(mode, h.event() is EventSnapshot.Resolved)
            assertEquals(mode, 1, h.access.calls.count { it == Call.Enumerate })
            assertTrue(mode, h.access.calls.contains(Call.FetchRoot(2)))
            assertEquals("$mode must not publish old-list verdicts", 0, h.cache.size)
        }
    }

    @Test fun `generation precedes topology enumeration for every active package row`() {
        for (pkg in listOf(APP, OWN, "other.app", null)) {
            val h = MemoryFrames()
            h.access.add(MemoryWindow(1, 10, active = true), pkg?.let { MemoryRoot(it, 1) })
            h.access.add(MemoryWindow(2, 20, WindowKind.SYSTEM), MemoryRoot("system.shade", 2))
            h.access.add(MemoryWindow(3, 2), MemoryRoot(APP, 3))
            h.access.onEnumerate = { h.resolver.invalidateTopology() }
            h.topology()
            assertTrue(h.access.calls.contains(Call.FetchRoot(2)))
            assertEquals(0, h.cache.size)
        }
    }

    @Test fun `fresh foreground enumeration also captures generation before the list`() {
        for (throwFirst in listOf(false, true)) {
            val h = MemoryFrames()
            h.access.native = MemoryRoot(OWN, 1)
            h.access.add(MemoryWindow(2, 2), MemoryRoot(APP, 2))
            var enumerations = 0
            h.access.onEnumerate = {
                h.resolver.invalidateTopology()
                enumerations++
                if (throwFirst && enumerations == 1) error("stale enumeration")
            }
            // No overlay enabled takes the legacy foreground route; failed overlay enumeration
            // takes the same route with a NEW enumeration and NEW pre-enumeration generation (HH4).
            assertTrue(h.event(if (throwFirst) MemoryFrameWindowAccess.ENABLED else setOf(APP)) is EventSnapshot.Resolved)
            assertEquals(if (throwFirst) 2 else 1, enumerations)
            assertEquals(0, h.cache.size)
        }
    }

    @Test fun `invalidation during a root fetch cannot publish its old-generation package or verdict`() {
        for (topology in listOf(false, true)) {
            val h = MemoryFrames()
            h.access.add(MemoryWindow(1, 1, active = true), MemoryRoot(APP, 1))
            h.access.add(MemoryWindow(2, 2, WindowKind.SYSTEM), MemoryRoot(OVERLAY, 2))
            h.access.onRoot = { h.resolver.invalidateTopology() }
            if (topology) assertNotNull(h.topology()) else assertTrue(h.event() is EventSnapshot.Resolved)
            assertEquals(0, h.cache.size)
        }
    }

    @Test fun `overlay wins before active fetch on both paths even if active is unreadable`() {
        for (topology in listOf(false, true)) for (readable in listOf(false, true)) {
            val h = MemoryFrames()
            h.access.add(MemoryWindow(1, 1, active = true), if (readable) MemoryRoot(APP, 1) else null)
            val overlay = MemoryRoot(OVERLAY, 2)
            h.access.add(MemoryWindow(2, 2, WindowKind.SYSTEM), overlay)
            if (topology) assertNotNull(h.topology()) else assertTrue(h.event() is EventSnapshot.Resolved)
            assertEquals(listOf(Call.Enumerate, Call.DisplayRead, Call.FetchRoot(2)), h.access.calls)
            assertSame(overlay, h.access.mappedRoots.single())
            assertEquals(1L, h.stats.overlaySnapshotCount())
        }
    }

    @Test fun `no active flag is the single native-before-overlay exception and reuses that root`() {
        val h = MemoryFrames()
        val native = MemoryRoot(APP, 1)
        h.access.native = native
        h.access.add(MemoryWindow(1, 1), native)
        h.access.add(MemoryWindow(2, 2, WindowKind.SYSTEM), MemoryRoot(OVERLAY, 2))
        assertTrue(h.event() is EventSnapshot.Resolved)
        assertEquals(listOf(Call.Enumerate, Call.NativeRoot, Call.DisplayRead, Call.FetchRoot(2)), h.access.calls)
        h.access.windowList = h.access.windowList.take(1)
        h.access.calls.clear()
        assertTrue(h.event() is EventSnapshot.Resolved)
        assertEquals(listOf(Call.Enumerate, Call.NativeRoot), h.access.calls)
        assertSame(native, h.access.mappedRoots.last())
    }

    @Test fun `without an overlay platform the native fast path performs no enumeration or other root fetch`() {
        for (root in listOf(null, MemoryRoot(APP, 1))) {
            val h = MemoryFrames()
            h.access.native = root
            h.access.onEnumerate = { error("H4 must not enumerate") }
            h.access.display = { error("H4 must not read display") }
            h.event(setOf(APP))
            assertEquals(listOf(Call.NativeRoot), h.access.calls)
            assertEquals(0, h.cache.size) // the cheap generation read is allowed
        }
    }

    @Test fun `shared lazy reads display once across scan foreground and focused-overlay classification`() {
        for (topology in listOf(false, true)) for (ownActive in listOf(false, true)) {
            val h = MemoryFrames()
            // The small system window forces the overlay scan to read metrics. The foreground
            // branch visits it again, or the focused system overlay performs its own classification.
            h.access.add(MemoryWindow(1, 10, WindowKind.SYSTEM, active = true), MemoryRoot(if (ownActive) OWN else OVERLAY, 1))
            h.access.add(MemoryWindow(2, 20, WindowKind.SYSTEM, bounds = WindowVerdictCache.Bounds(0, 0, 1, 1)), MemoryRoot(OVERLAY, 2))
            h.access.add(MemoryWindow(3, 2), MemoryRoot(APP, 3))
            var reads = 0
            h.access.display = { reads++; if (reads == 1) 10_000L else 1_000_000L }
            if (topology) h.topology() else assertTrue(h.event() is EventSnapshot.Resolved)
            assertEquals(1, reads)
            assertEquals(1, h.access.calls.count { it == Call.DisplayRead })
        }
    }

    @Test fun `topology keeps diagnostic and enablement order and unknown-active refusal`() {
        val h = MemoryFrames()
        val order = mutableListOf<String>()
        h.access.onEnumerate = { order += "enumerate" }
        h.access.onTrace = { order += "trace" }
        assertNull(h.resolver.topology { order += "enabled"; setOf(APP) })
        assertEquals(listOf("enumerate", "trace", "enabled"), order)
        assertEquals(listOf(Call.Enumerate, Call.DisplayRead), h.access.calls)
        assertEquals(1L, h.stats.topologySkipCount(ForegroundSkipReason.NO_ACTIVE_ROOT))
    }
}

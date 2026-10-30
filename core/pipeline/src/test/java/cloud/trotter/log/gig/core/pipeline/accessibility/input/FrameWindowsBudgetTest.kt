package cloud.trotter.log.gig.core.pipeline.accessibility.input

import cloud.trotter.log.gig.core.pipeline.accessibility.ForegroundSkipReason
import cloud.trotter.log.gig.core.pipeline.accessibility.OverlayRejectReason
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.EventSnapshot
import cloud.trotter.log.gig.core.pipeline.accessibility.input.MemoryFrameWindowAccess.Call
import cloud.trotter.log.gig.core.pipeline.accessibility.input.MemoryFrameWindowAccess.Companion.APP
import cloud.trotter.log.gig.core.pipeline.accessibility.input.MemoryFrameWindowAccess.Companion.ENABLED
import cloud.trotter.log.gig.core.pipeline.accessibility.input.MemoryFrameWindowAccess.Companion.OVERLAY
import org.junit.Assert.*
import org.junit.Test

class FrameWindowsBudgetTest {
    @Test fun `cold probes cached overlay revalidation and deferred application fetch all consume the walk budget`() {
        for (kind in listOf(WindowKind.APPLICATION, WindowKind.SYSTEM)) {
            for (cached in listOf(false, true)) for (coldCount in listOf(7, 8, 12)) {
                val h = MemoryFrames()
                repeat(coldCount) { i ->
                    h.access.add(MemoryWindow(i + 1, 100 - i, WindowKind.SYSTEM), MemoryRoot("system.$i", i + 1))
                }
                val pkg = if (kind == WindowKind.SYSTEM) OVERLAY else APP
                val selected = h.access.add(MemoryWindow(100, 1, kind), MemoryRoot(pkg, 100))
                if (cached) {
                    if (kind == WindowKind.SYSTEM) {
                        h.cache.putVerdict(100, pkg, WindowVerdictCache.Verdict.CANDIDATE, selected.bounds, 10_000L, h.cache.generation)
                    } else {
                        h.cache.putPackage(100, pkg, h.cache.generation)
                    }
                }
                val front = h.resolver.foregroundWindow { it in ENABLED }
                val fetched = h.access.calls.filterIsInstance<Call.FetchRoot>()
                assertEquals("$kind cached=$cached cold=$coldCount", 8, fetched.size)
                if (coldCount == 7) {
                    assertTrue(front is Foreground.Found)
                    assertEquals(100, fetched.last().id)
                    assertEquals(100, (front as Foreground.Found).located.root.windowId)
                } else {
                    assertEquals(Foreground.Refused(ForegroundSkipReason.SCAN_BUDGET), front)
                    assertFalse(fetched.any { it.id == 100 })
                }
            }
        }
    }

    @Test fun `both resolutions refuse an exhausted overlay scan without fetching the active root`() {
        for (topology in listOf(false, true)) {
            val h = MemoryFrames()
            h.access.add(MemoryWindow(100, 0, active = true), MemoryRoot(APP, 100))
            repeat(12) { i ->
                h.access.add(MemoryWindow(i, 100 - i, WindowKind.SYSTEM), MemoryRoot("system.$i", i))
            }
            if (topology) {
                assertNull(h.topology())
                assertEquals(1L, h.stats.topologySkipCount(ForegroundSkipReason.SCAN_BUDGET))
            } else {
                assertEquals(EventSnapshot.Skipped(ForegroundSkipReason.SCAN_BUDGET), h.event())
            }
            assertEquals(8, h.access.calls.filterIsInstance<Call.FetchRoot>().size)
            assertFalse(h.access.calls.contains(Call.FetchRoot(100)))
            assertEquals(8L, h.stats.overlayRejectedCount(OverlayRejectReason.NOT_OVERLAY_PLATFORM))
            assertEquals(0L, h.stats.overlaySnapshotCount())
            // Event refusal counts remain the caller's responsibility; topology has its own census.
            assertEquals(0L, h.stats.foregroundSkipCount(ForegroundSkipReason.SCAN_BUDGET))
        }
    }

    @Test fun `an eighth throwing system fetch is charged and remains an unreadable barrier`() {
        val h = MemoryFrames()
        repeat(7) { i ->
            h.access.add(MemoryWindow(i, 100 - i, WindowKind.SYSTEM), MemoryRoot("system.$i", i))
        }
        h.access.add(MemoryWindow(8, 10, WindowKind.SYSTEM), null)
        h.access.scriptRoots(8, { error("dead binder") })
        h.access.add(MemoryWindow(9, 1), MemoryRoot(APP, 9))
        assertEquals(Foreground.Refused(ForegroundSkipReason.FRONT_UNREADABLE), h.resolver.foregroundWindow { it in ENABLED })
        assertEquals(8, h.access.calls.filterIsInstance<Call.FetchRoot>().size)
        assertFalse(h.access.calls.contains(Call.FetchRoot(9)))
        assertEquals(1L, h.stats.overlayRejectedCount(OverlayRejectReason.UNREADABLE))
    }

    @Test fun `a cached application stopping the overlay scan is fetched only by a foreground reader`() {
        val h = MemoryFrames()
        val window = h.access.add(MemoryWindow(1, 1), MemoryRoot(APP, 1))
        val active = MemoryWindow(0, 0, active = true)
        h.cache.putPackage(1, APP, h.cache.generation)
        assertEquals(OverlayScan.None, h.resolver.walk.overlayFront(
            listOf(active, window), active, { it in ENABLED }, lazy { 10_000L }, h.cache.generation,
        ))
        assertTrue(h.access.calls.isEmpty())
        assertTrue(h.resolver.foregroundWindow { it in ENABLED } is Foreground.Found)
        assertEquals(listOf(Call.Enumerate, Call.FetchRoot(1)), h.access.calls)
    }

    @Test fun `selected overlay map failure has no fallback and is never counted as a mapped overlay`() {
        for (topology in listOf(false, true)) {
            val h = MemoryFrames()
            h.access.add(MemoryWindow(1, 1, active = true), MemoryRoot(APP, 1))
            h.access.add(MemoryWindow(2, 2, WindowKind.SYSTEM), MemoryRoot(OVERLAY, 2))
            h.access.mappingFails = true
            if (topology) assertNull(h.topology()) else {
                assertEquals(EventSnapshot.Skipped(ForegroundSkipReason.MAP_FAILED), h.event())
            }
            assertEquals(listOf(Call.FetchRoot(2)), h.access.calls.filterIsInstance<Call.FetchRoot>())
            assertEquals(0L, h.stats.overlaySnapshotCount())
            assertEquals(0L, h.stats.topologySkipCount(ForegroundSkipReason.MAP_FAILED))
        }
    }
}

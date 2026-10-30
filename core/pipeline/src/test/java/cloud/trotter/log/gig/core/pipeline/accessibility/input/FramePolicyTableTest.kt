package cloud.trotter.log.gig.core.pipeline.accessibility.input

import cloud.trotter.log.gig.core.pipeline.accessibility.ForegroundSkipReason
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.EventSnapshot
import cloud.trotter.log.gig.core.pipeline.accessibility.input.MemoryFrameWindowAccess.Companion.APP
import cloud.trotter.log.gig.core.pipeline.accessibility.input.MemoryFrameWindowAccess.Companion.OTHER
import cloud.trotter.log.gig.core.pipeline.accessibility.input.MemoryFrameWindowAccess.Companion.OWN
import org.junit.Assert.*
import org.junit.Test

class FramePolicyTableTest {
    @Test fun `every row of both pure policy tables`() {
        val rows = listOf(
            Triple(ActivePackage.ENABLED, FrameAction.MAP_ACTIVE, FrameAction.QUIET),
            Triple(ActivePackage.NOT_ENABLED_OWN, FrameAction.FRONT_ALL, FrameAction.FRONT_ALL),
            Triple(ActivePackage.NOT_ENABLED_OTHER, FrameAction.FRONT_ALL, FrameAction.FRONT_ABOVE),
            Triple(ActivePackage.UNREADABLE, FrameAction.NATIVE_STAND_IN, FrameAction.SKIP_UNREADABLE),
        )
        assertEquals(ActivePackage.entries.toSet(), rows.map { it.first }.toSet())
        for ((active, event, topology) in rows) {
            assertEquals(active.name, event, eventAfterNoOverlay(active))
            assertEquals(active.name, topology, topologyAfterNoOverlay(active))
        }
    }

    @Test fun `both paths apply each row to the same memory topology`() {
        for (state in ActivePackage.entries) {
            fun harness() = MemoryFrames().apply {
                val pkg = when (state) {
                    ActivePackage.ENABLED -> APP
                    ActivePackage.NOT_ENABLED_OWN -> OWN
                    ActivePackage.NOT_ENABLED_OTHER -> OTHER
                    ActivePackage.UNREADABLE -> null
                }
                access.add(MemoryWindow(1, 10, active = true), pkg?.let { MemoryRoot(it, 1) })
                access.add(MemoryWindow(2, 2), MemoryRoot(APP, 2))
                access.native = MemoryRoot(APP, 1) // a proven stand-in for the unreadable row
            }
            val event = harness().event()
            val topology = harness()
            val frame = topology.topology()
            when (state) {
                ActivePackage.ENABLED, ActivePackage.UNREADABLE -> {
                    assertEquals(1, (event as EventSnapshot.Resolved).snapshot.windowContext?.windowId)
                    assertNull(frame)
                }
                ActivePackage.NOT_ENABLED_OWN -> {
                    assertEquals(2, (event as EventSnapshot.Resolved).snapshot.windowContext?.windowId)
                    assertEquals(2, frame?.windowContext?.windowId)
                }
                ActivePackage.NOT_ENABLED_OTHER -> {
                    assertEquals(EventSnapshot.Skipped(ForegroundSkipReason.FRONT_NOT_ENABLED), event)
                    assertNull(frame)
                    assertEquals(1L, topology.stats.topologySkipCount(ForegroundSkipReason.NO_CANDIDATE))
                }
            }
            assertEquals(
                if (state == ActivePackage.UNREADABLE) 1L else 0L,
                topology.stats.topologySkipCount(ForegroundSkipReason.FRONT_UNREADABLE),
            )
        }
    }
}

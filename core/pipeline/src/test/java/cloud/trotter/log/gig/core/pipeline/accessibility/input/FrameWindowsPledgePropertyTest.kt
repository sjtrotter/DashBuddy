package cloud.trotter.log.gig.core.pipeline.accessibility.input

import cloud.trotter.log.gig.core.pipeline.PropSeeds
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.EventSnapshot
import cloud.trotter.log.gig.core.pipeline.accessibility.input.MemoryFrameWindowAccess.Companion.APP
import cloud.trotter.log.gig.core.pipeline.accessibility.input.MemoryFrameWindowAccess.Companion.ENABLED
import cloud.trotter.log.gig.core.pipeline.accessibility.input.MemoryFrameWindowAccess.Companion.OTHER
import cloud.trotter.log.gig.core.pipeline.accessibility.input.MemoryFrameWindowAccess.Companion.OVERLAY
import cloud.trotter.log.gig.core.pipeline.accessibility.input.MemoryFrameWindowAccess.Companion.OWN
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FrameWindowsPledgePropertyTest {
    @Test fun `resolved frames always carry the exact freshly checked enabled root and never our bubble`() = runTest {
        // #1422 review S3: the property must not pass vacuously — both paths must resolve real frames, cold and warm.
        val resolved = IntArray(4) // [event cold, event warm, topology cold, topology warm]
        checkAll(PropSeeds.samples(600), PropSeeds.config(0x1422_000BL), Arb.list(Arb.int(0, 100_000), 0..20), Arb.int(0, 255)) { codes, scenario ->
            val enabled = if (scenario and 1 == 0) ENABLED else setOf(APP)
            val packages = listOf(APP, OVERLAY, OWN, OTHER, null)
            fun harness() = MemoryFrames().apply {
                codes.forEachIndexed { index, code ->
                    val id = index + 1
                    val pkg = packages[code % packages.size]
                    val window = MemoryWindow(
                        id, code % 30,
                        WindowKind.entries[(code / 5) % 3],
                        active = (code / 15) % 4 == 0,
                        pip = (code / 60) % 5 == 0,
                        bounds = WindowVerdictCache.Bounds(0, 0, if (code % 7 == 0) 10 else 100, 100),
                    )
                    // Our bubble deliberately looks like a platform offer. Ownership, never tree
                    // text or the event package, must decide whether this root can become a frame.
                    val root = MemoryRoot(pkg, id, "Accept offer $12.00")
                    access.add(window, if (code % 11 == 0) null else root)
                    if (code % 13 == 0) access.scriptRoots(id, { error("stale root") })
                    if (scenario and 2 != 0) {
                        // Stale positive hints force live revalidation; negative/own hints are
                        // deliberately omitted here because the framework pins id -> package.
                        val cached = if (window.kind == WindowKind.SYSTEM) OVERLAY else APP
                        cache.putVerdict(id, cached, WindowVerdictCache.Verdict.CANDIDATE, window.bounds, 10_000L, cache.generation)
                    }
                }
                val nativeId = if (codes.isEmpty()) -1 else (scenario % (codes.size + 2))
                access.native = if (scenario and 4 == 0) null else MemoryRoot(packages[scenario % packages.size], nativeId, "native")
                if (scenario and 8 != 0) access.onEnumerate = { resolver.invalidateTopology() }
                if (scenario and 16 != 0) access.display = { 0L }
            }
            for (topology in listOf(false, true)) {
                val h = harness()
                // Repeat with the same cache: covers cold probes and later revalidation/deferred fetches.
                repeat(2) { pass ->
                    val frame = if (topology) h.topology(enabled) else (h.event(enabled) as? EventSnapshot.Resolved)?.snapshot
                    if (frame != null) {
                        resolved[(if (topology) 2 else 0) + pass]++
                        assertTrue(frame.packageName in enabled)
                        assertNotEquals(OWN, frame.packageName)
                        val mapped = h.access.mappedRoots.last()
                        assertEquals(mapped.packageName, frame.packageName)
                        assertEquals(mapped.text, frame.tree.text)
                        assertTrue(mapped.packageName in enabled)
                        assertNotEquals(OWN, mapped.packageName)
                        frame.windowContext?.let { assertEquals(mapped.windowId, it.windowId) }
                    }
                }
            }
        }
        val names = listOf("event cold", "event warm", "topology cold", "topology warm")
        resolved.forEachIndexed { k, n -> assertTrue("no ${names[k]} resolution was generated", n > 0) }
    }
}

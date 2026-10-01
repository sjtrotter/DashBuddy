package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Outcome
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Refusal
import cloud.trotter.dashbuddy.domain.capture.CensusRecord
import cloud.trotter.dashbuddy.domain.capture.CensusSink
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** #1146: inert by default, UNKNOWN-only publishing, pairing, token census and fail-open behavior. */
class SkeletonPublisherTest : SkeletonBuilderTestBase() {

    private val timestamp = day.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private class FakeSink(enabled: Boolean, private val accept: Boolean = true) : CensusSink {
        override val isEnabled: Boolean = enabled
        val records = mutableListOf<CensusRecord>()

        override fun offer(record: CensusRecord): Boolean {
            records += record
            return accept
        }
    }

    private fun screen(
        timestamp: Long = this.timestamp,
        captureId: String? = null,
        ruleId: String? = null,
        target: String = UNKNOWN_TARGET,
    ) = Observation.Screen(
        timestamp = timestamp, captureId = captureId, ruleId = ruleId,
        metadata = meta, flow = Flow.Idle, modeHint = Mode.Online,
        parsed = ParsedFields.None, target = target,
    )

    private fun event(tree: UiNode, windowTitle: String? = null) = PipelineEvent.Screen(
        timestamp = timestamp,
        tree = tree,
        snapshot = TreeSnapshot(
            tree = tree,
            packageName = "com.doordash.driverapp",
            windowContext = windowTitle?.let {
                TreeSnapshot.WindowContext(
                    windowId = 1, windowType = 1, windowTitle = it, windowLayer = 0,
                    isActive = true, isFocused = true, totalWindowCount = 1,
                )
            },
        ),
        packageName = "com.doordash.driverapp",
    )

    private fun assertNoCensus(stats: PipelineStats) {
        assertEquals(0L, stats.censusSkeletonCount())
        assertEquals(0L, stats.censusTokensHashedCount())
        assertEquals(0L, stats.censusTokensWithheldCount())
        assertEquals(0L, stats.censusSinkRefusedCount())
        assertEquals(0L, stats.censusPublishFailureCount())
        Refusal.entries.forEach { assertEquals(0L, stats.censusRefusedCount(it)) }
        assertFalse(stats.summary().contains("census{"))
    }

    @Test
    fun `disabled sink - nothing is built and no counter moves`() {
        val sink = FakeSink(enabled = false)
        val stats = PipelineStats()
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"))

        publisher.publish(screen(), event(tree("Continue")))
        publisher.publish(screen(), event(tree("Transfer out", "\$45.66 available")))

        assertTrue(sink.records.isEmpty())
        assertNoCensus(stats)
    }

    @Test
    fun `enabled sink - one record per admitted UNKNOWN screen`() {
        listOf(null, "cap-1").forEach { captureId ->
            val sink = FakeSink(enabled = true)
            val stats = PipelineStats()
            val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"))

            publisher.publish(screen(captureId = captureId), event(tree("Continue")))

            val record = sink.records.single()
            assertTrue(Regex("[0-9a-f]{64}").matches(record.fingerprint))
            assertEquals(Platform.DoorDash, record.platform)
            assertEquals(captureId, record.captureId)
            assertEquals(record.skeletonJson.toByteArray(Charsets.UTF_8).size, record.itemBytes)
            assertEquals(1L, stats.censusSkeletonCount())
        }
    }

    @Test
    fun `a recognized frame never reaches the sink`() {
        val sink = FakeSink(enabled = true)
        val stats = PipelineStats()
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"))

        publisher.publish(
            screen(target = "idle_map", ruleId = "doordash.screen.idle_map"),
            event(tree("Continue")),
        )

        assertTrue(sink.records.isEmpty())
        assertNoCensus(stats)
    }

    @Test
    fun `a sensitive frame yields no record and counts SENSITIVE_FRAME`() {
        val sink = FakeSink(enabled = true)
        val stats = PipelineStats()
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"))

        publisher.publish(screen(), event(tree("Transfer out", "\$45.66 available")))

        assertTrue(sink.records.isEmpty())
        assertEquals(1L, stats.censusRefusedCount(Refusal.SENSITIVE_FRAME))
        assertEquals(0L, stats.censusSkeletonCount())
        assertEquals(0L, stats.censusPublishFailureCount())
    }

    @Test
    fun `counter parity with SkeletonBuilder`() {
        // The mixed text/description/hint fixture from SkeletonEnvelopeTest also exercises the title.
        val tree = UiNode(children = listOf(
            UiNode(
                className = "android.widget.Button",
                viewIdResourceName = "com.x:id/cta",
                text = "Accept",
                contentDescription = "Accept offer",
                hintText = "Jane S",
                uniqueId = "store_Chipotle",
                isClickable = true,
                isEnabled = true,
                isChecked = 2,
            ),
        )).restoreParents()
        val built = SkeletonBuilder.outcome(tree, "Offer", meta, platform, day) as Outcome.Built
        val (hashed, withheld) = tokenCounts(built.skeleton)
        assertTrue(hashed > 0)
        assertTrue(withheld > 0)
        assertEquals(4 to 1, hashed to withheld)
        assertEquals(3 to 2, tokenCounts(built.skeleton.copy(windowTitle = TextSlot.WITHHELD)))
        val sink = FakeSink(enabled = true)
        val stats = PipelineStats()
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"))

        publisher.publish(screen(), event(tree, windowTitle = "Offer"))

        assertEquals(1, sink.records.size)
        assertEquals(1L, stats.censusSkeletonCount())
        assertEquals(hashed.toLong(), stats.censusTokensHashedCount())
        assertEquals(withheld.toLong(), stats.censusTokensWithheldCount())
    }

    @Test
    fun `an unattributable package is refused before any build (#1171 review)`() {
        val stats = PipelineStats()
        val sink = FakeSink(enabled = true)
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"))
        val t = tree("Continue")
        publisher.publish(
            screen(target = UNKNOWN_TARGET),
            PipelineEvent.Screen(timestamp = 1_000L, tree = t, snapshot = TreeSnapshot(tree = t, packageName = "com.example.other"), packageName = "com.example.other"),
        )
        assertEquals(1L, stats.censusUnattributedPlatformCount())
        assertEquals(0L, stats.censusSkeletonCount())
        assertTrue(sink.records.isEmpty())
    }

    @Test
    fun `a throwing enablement getter never escapes (#1171 review)`() {
        val stats = PipelineStats()
        val sink = object : CensusSink {
            override val isEnabled: Boolean get() = error("boom")
            override fun offer(record: CensusRecord): Boolean = true
        }
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"))
        publisher.publish(screen(target = UNKNOWN_TARGET), event(tree("Continue")))
        assertEquals(1L, stats.censusPublishFailureCount())
        assertEquals(0L, stats.censusSkeletonCount())
    }

    @Test
    fun `a throwing sink never escapes`() {
        val sink = object : CensusSink {
            override val isEnabled: Boolean = true
            override fun offer(record: CensusRecord): Boolean = throw IllegalStateException("not logged")
        }
        val stats = PipelineStats()
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"))

        publisher.publish(screen(), event(tree("Continue")))

        assertEquals(1L, stats.censusPublishFailureCount())
        assertEquals(1L, stats.censusSkeletonCount())
        assertEquals(0L, stats.censusSinkRefusedCount())
    }

    @Test
    fun `a sink refusal counts separately from a build refusal`() {
        val sink = FakeSink(enabled = true, accept = false)
        val stats = PipelineStats()
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"))

        publisher.publish(screen(), event(tree("Continue")))

        assertEquals(1, sink.records.size)
        assertEquals(1L, stats.censusSkeletonCount())
        assertEquals(1L, stats.censusSinkRefusedCount())
        assertEquals(0L, stats.censusPublishFailureCount())
        Refusal.entries.forEach { assertEquals(0L, stats.censusRefusedCount(it)) }
    }

    @Test
    fun `cancellation escapes without counting a publish failure`() {
        val cancellation = CancellationException("cancelled")
        val sink = object : CensusSink {
            override val isEnabled: Boolean = true
            override fun offer(record: CensusRecord): Boolean = throw cancellation
        }
        val stats = PipelineStats()
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"))

        try {
            publisher.publish(screen(), event(tree("Continue")))
            fail("Cancellation must escape")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }

        assertEquals(1L, stats.censusSkeletonCount())
        assertEquals(0L, stats.censusPublishFailureCount())
    }

    @Test
    fun `day comes from the observation timestamp in the publisher's zone`() {
        val timestamp = Instant.parse("2026-10-01T03:30:00Z").toEpochMilli()
        mapOf("America/Chicago" to "2026-09-30", "UTC" to "2026-10-01").forEach { (zone, day) ->
            val sink = FakeSink(enabled = true)
            val publisher = SkeletonPublisher(sink, PipelineStats(), ZoneId.of(zone))

            // The event keeps the fixture timestamp; only the observation supplies the day.
            publisher.publish(screen(timestamp = timestamp), event(tree("Continue")))

            assertTrue(sink.records.single().skeletonJson.contains("\"day\":\"$day\""))
        }
    }
}

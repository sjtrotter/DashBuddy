package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Outcome
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Refusal
import cloud.trotter.dashbuddy.domain.capture.CensusRecord
import cloud.trotter.dashbuddy.domain.capture.NoOpCensusEnvelopeSink
import cloud.trotter.dashbuddy.domain.capture.CensusEnvelopeSink
import cloud.trotter.dashbuddy.domain.capture.CensusSink
import cloud.trotter.census.contract.NotificationSkeletonSchema
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
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

    @Test fun `built unknown pairs the same capture and fingerprint after skeleton offer`() {
        for (queued in listOf(true, false)) {
            val sink = FakeSink(enabled = true)
            val stats = PipelineStats()
            val pairs = mutableListOf<Pair<String, String>>()
            val envelopes = object : CensusEnvelopeSink {
                override val isEnabled = true
                override suspend fun invalidate() = Unit
                override fun hold(captureId: String, platform: Platform, envelopeJson: String) = Unit
                override fun pair(captureId: String, fingerprint: String): Boolean {
                    assertEquals(fingerprint, sink.records.single().fingerprint)
                    pairs += captureId to fingerprint
                    return queued
                }
            }
            val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"), envelopes)
            publisher.publish(screen(captureId = "same-frame"), event(tree("Continue")))
            assertEquals(listOf("same-frame" to sink.records.single().fingerprint), pairs)
            assertTrue(stats.summary().contains(if (queued) "envelopesPaired=1" else "envelopesUnpaired=1"))
        }
    }

    @Test fun `no capture id refused skeleton and disabled envelope sink never pair`() {
        val sink = FakeSink(enabled = true)
        var pairs = 0
        var enabled = true
        val envelopes = object : CensusEnvelopeSink {
            override val isEnabled get() = enabled
            override suspend fun invalidate() = Unit
            override fun hold(captureId: String, platform: Platform, envelopeJson: String) = Unit
            override fun pair(captureId: String, fingerprint: String): Boolean { pairs++; return true }
        }
        val publisher = SkeletonPublisher(sink, PipelineStats(), ZoneId.of("UTC"), envelopes)
        publisher.publish(screen(), event(tree("Continue")))
        publisher.publish(screen(captureId = "refused"), event(tree("Transfer to bank")))
        enabled = false
        publisher.publish(screen(captureId = "disabled"), event(tree("Continue")))
        assertEquals(0, pairs)
    }

    @Test fun `skeleton sink refusal never pairs its envelope`() {
        val sink = FakeSink(enabled = true, accept = false)
        val stats = PipelineStats()
        var pairs = 0
        val envelopes = object : CensusEnvelopeSink {
            override val isEnabled = true
            override suspend fun invalidate() = Unit
            override fun hold(captureId: String, platform: Platform, envelopeJson: String) = Unit
            override fun pair(captureId: String, fingerprint: String): Boolean { pairs++; return true }
        }
        SkeletonPublisher(sink, stats, ZoneId.of("UTC"), envelopes)
            .publish(screen(captureId = "refused"), event(tree("Continue")))
        assertEquals(1, sink.records.size)
        assertEquals(1L, stats.censusSinkRefusedCount())
        assertEquals(0, pairs)
    }

    @Test fun `envelope pairing failure is contained after skeleton publication`() {
        val sink = FakeSink(enabled = true)
        val stats = PipelineStats()
        val envelopes = object : CensusEnvelopeSink {
            override val isEnabled = true
            override suspend fun invalidate() = Unit
            override fun hold(captureId: String, platform: Platform, envelopeJson: String) = Unit
            override fun pair(captureId: String, fingerprint: String): Boolean = throw AssertionError("test_failure")
        }
        SkeletonPublisher(sink, stats, ZoneId.of("UTC"), envelopes)
            .publish(screen(captureId = "same-frame"), event(tree("Continue")))
        assertEquals(1, sink.records.size)
        assertEquals(1L, stats.censusPublishFailureCount())
    }

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
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"), NoOpCensusEnvelopeSink)

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
            val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"), NoOpCensusEnvelopeSink)

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
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"), NoOpCensusEnvelopeSink)

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
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"), NoOpCensusEnvelopeSink)

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
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"), NoOpCensusEnvelopeSink)

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
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"), NoOpCensusEnvelopeSink)
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
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"), NoOpCensusEnvelopeSink)
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
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"), NoOpCensusEnvelopeSink)

        publisher.publish(screen(), event(tree("Continue")))

        assertEquals(1L, stats.censusPublishFailureCount())
        assertEquals(1L, stats.censusSkeletonCount())
        assertEquals(0L, stats.censusSinkRefusedCount())
    }

    @Test
    fun `a sink refusal counts separately from a build refusal`() {
        val sink = FakeSink(enabled = true, accept = false)
        val stats = PipelineStats()
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"), NoOpCensusEnvelopeSink)

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
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"), NoOpCensusEnvelopeSink)

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
            val publisher = SkeletonPublisher(sink, PipelineStats(), ZoneId.of(zone), NoOpCensusEnvelopeSink)

            // The event keeps the fixture timestamp; only the observation supplies the day.
            publisher.publish(screen(timestamp = timestamp), event(tree("Continue")))

            assertTrue(sink.records.single().skeletonJson.contains("\"day\":\"$day\""))
        }
    }
    private fun notification(target: String = UNKNOWN_TARGET, captureId: String? = "capture", time: Long = timestamp) =
        Observation.Notification(time, captureId, null, meta, null, null, ParsedFields.None, target)

    private fun rawNotification() = RawNotificationData("Continue", null, null, null,
        packageName = Platform.DoorDash.packageName!!, postTime = 0, isClearable = true, channelId = "synthetic")

    @Test fun `notifications and screens share sink and counts but notifications never pair envelopes`() {
        val sink = FakeSink(true)
        val stats = PipelineStats()
        val envelopes = object : CensusEnvelopeSink {
            override val isEnabled: Boolean get() = error("notification must not even read envelope enablement")
            override suspend fun invalidate() = Unit
            override fun hold(captureId: String, platform: Platform, envelopeJson: String) = error("unexpected hold")
            override fun pair(captureId: String, fingerprint: String): Boolean = error("unexpected pair")
        }
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("America/Chicago"), envelopes)
        val time = Instant.parse("2026-10-01T03:30:00Z").toEpochMilli()
        publisher.publish(notification(time = time), rawNotification())
        publisher.publish(notification(captureId = null, time = time), rawNotification().copy(actionLabels = listOf("Next")))
        val first = sink.records.first()
        val item = NotificationSkeletonSchema.deserialize(first.skeletonJson)
        assertEquals("2026-09-30", item.day)
        assertEquals(meta.engineVersion, item.engineVersion)
        assertEquals(Platform.DoorDash, first.platform)
        assertEquals("capture", first.captureId)
        assertEquals(first.fingerprint, sink.records.last().fingerprint)
        assertEquals(2L, stats.censusNotificationCount())
        assertEquals(0L, stats.censusPublishFailureCount())
        // No capture id on screen: it too enters the same sink without needing the envelope sink.
        publisher.publish(screen(), event(tree("Continue")))
        assertEquals(3, sink.records.size)
        assertEquals(3L, stats.censusSkeletonCount())
    }

    @Test fun `notification known and disabled paths build nothing including invalid raw input`() {
        for (enabled in listOf(true, false)) {
            val sink = FakeSink(enabled)
            val stats = PipelineStats()
            val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"), NoOpCensusEnvelopeSink)
            publisher.publish(notification(target = "known"), rawNotification().copy(channelId = null))
            if (!enabled) publisher.publish(notification(), rawNotification().copy(actionLabels = listOf("Transfer out")))
            assertNoCensus(stats)
            assertEquals(0L, stats.censusNotificationCount())
            NotificationSkeletonBuilder.Refusal.entries.forEach { assertEquals(0L, stats.censusNotificationRefusedCount(it)) }
        }
    }

    @Test fun `notification refusals and unknown packages are counted without offers`() {
        val sink = FakeSink(true)
        val stats = PipelineStats()
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"), NoOpCensusEnvelopeSink)
        publisher.publish(notification(), rawNotification().copy(packageName = "unregistered"))
        publisher.publish(notification(), rawNotification().copy(actionLabels = listOf("Transfer out")))
        assertEquals(1L, stats.censusUnattributedPlatformCount())
        assertEquals(1L, stats.censusNotificationRefusedCount(NotificationSkeletonBuilder.Refusal.SENSITIVE_NOTIFICATION))
        assertTrue(sink.records.isEmpty())
    }

    @Test fun `notification enablement and offer failures fail open while cancellation escapes`() {
        for (getter in listOf(false, true)) {
            for (cancel in listOf(false, true)) {
                val failure = if (cancel) CancellationException("cancelled") else IllegalStateException("private payload")
                val sink = object : CensusSink {
                    override val isEnabled: Boolean get() = if (getter) throw failure else true
                    override fun offer(record: CensusRecord): Boolean = throw failure
                }
                val stats = PipelineStats()
                val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"), NoOpCensusEnvelopeSink)
                if (cancel) {
                    try { publisher.publish(notification(), rawNotification()); fail("cancellation must escape") }
                    catch (e: CancellationException) { assertSame(failure, e) }
                } else publisher.publish(notification(), rawNotification())
                assertEquals(if (cancel) 0L else 1L, stats.censusPublishFailureCount())
            }
        }
        val stats = PipelineStats()
        SkeletonPublisher(FakeSink(true, accept = false), stats, ZoneId.of("UTC"), NoOpCensusEnvelopeSink)
            .publish(notification(), rawNotification())
        assertEquals(1L, stats.censusSinkRefusedCount())
        assertEquals(1L, stats.censusNotificationCount())
    }

    @Test fun `one class-only warning is shared across notification and screen failures`() {
        val warnings = mutableListOf<String>()
        val logTree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                warnings += message
                assertEquals("Census", tag)
                assertEquals(5, priority)
                assertEquals(null, t)
            }
        }
        val sink = object : CensusSink {
            override val isEnabled: Boolean get() = throw IllegalStateException("PRIVATE_SOURCE_TEXT")
            override fun offer(record: CensusRecord) = false
        }
        val stats = PipelineStats()
        val publisher = SkeletonPublisher(sink, stats, ZoneId.of("UTC"), NoOpCensusEnvelopeSink)
        timber.log.Timber.plant(logTree)
        try {
            publisher.publish(notification(), rawNotification())
            publisher.publish(screen(), event(tree("Continue")))
            publisher.publish(notification(), rawNotification())
        } finally {
            timber.log.Timber.uproot(logTree)
        }
        assertEquals(listOf("census publisher failed (IllegalStateException); frame unaffected"), warnings)
        assertEquals(3L, stats.censusPublishFailureCount())
    }

}

package cloud.trotter.log.gig.census

import cloud.trotter.census.contract.SkeletonVersion
import cloud.trotter.log.gig.core.data.census.CensusDailySample
import cloud.trotter.log.gig.core.data.census.CensusSpool
import cloud.trotter.log.gig.core.data.census.HttpCensusSink
import cloud.trotter.log.gig.core.pipeline.census.SkeletonBuilder
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.census.CensusChoices
import cloud.trotter.log.gig.domain.census.CensusUploadPreferences
import cloud.trotter.log.gig.domain.census.CensusUploadStats
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.log.gig.core.pipeline.census.NotificationSkeletonBuilder
import cloud.trotter.log.gig.domain.model.notification.RawNotificationData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate

/**
 * #1620 (ADR-0011 §8, §21): end to end through the real skeleton builders and sink, two frames of one screen that
 * differ only in the customer's name and the pay, or two pushes that differ only in a name, are one sample: the census keeps one skeleton per layout and format a
 * UTC day, never one per customer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CensusSamplingEndToEndTest {

    private val meta = ReplayMetadata(engineVersion = 1)
    private val day = LocalDate.parse("2026-11-13")

    /** An UNKNOWN-looking card with a customer's name in an id-less line and a pay figure. */
    private fun frame(name: String, pay: String) = UiNode(
        className = "android.widget.LinearLayout", viewIdResourceName = "com.doordash.driverapp:id/card",
        children = listOf(
            UiNode(text = "New order", className = "android.widget.TextView"),
            UiNode(text = name, className = "android.widget.TextView"),
            UiNode(text = pay, className = "android.widget.TextView"),
        ),
    )

    private fun record(name: String, pay: String): CensusRecord {
        val built = SkeletonBuilder.outcome(frame(name, pay), null, meta, Platform.DoorDash, day, SkeletonVersion.V2)
            as SkeletonBuilder.Outcome.Built
        return CensusRecord(Platform.DoorDash, built.skeleton.fingerprint, built.json, built.itemBytes, null)
    }

    /** A push with [body] in its text field, or in its big-text field when [bigText]. */
    private fun push(title: String, body: String, bigText: Boolean = false): CensusRecord {
        val raw = RawNotificationData(
            title, body.takeUnless { bigText }, null, body.takeIf { bigText },
            packageName = requireNotNull(Platform.DoorDash.packageName),
            postTime = 0, isClearable = true, channelId = "offers",
        )
        val built = NotificationSkeletonBuilder.outcome(raw, meta, Platform.DoorDash, day, SkeletonVersion.V2)
            as NotificationSkeletonBuilder.Outcome.Built
        return CensusRecord(Platform.DoorDash, built.skeleton.fingerprint, built.json, built.itemBytes, null, built.presentFields)
    }

    @Test fun `two frames that differ only in a customer's name and the pay are one sample`() = runTest {
        oneSample(record("Ann Lee", "$7.50"), record("Maria Elena Ruiz", "$12.25"))
    }

    @Test fun `two notifications that differ only in a customer's name are one sample`() = runTest {
        val ann = push("Order from Ann", "Pickup ready")
        val maria = push("Order from Maria", "Pickup ready")
        assertNotEquals("the fingerprints differ, so the test is not vacuous", ann.fingerprint, maria.fingerprint)
        oneSample(ann, maria)
    }

    @Test fun `a push whose body moved to another field is a new variant`() = runTest {
        assertEquals(2, samples(push("Order from Ann", "Pickup ready"), push("Order from Ann", "Pickup ready", bigText = true)))
    }

    private fun kotlinx.coroutines.test.TestScope.oneSample(ann: CensusRecord, maria: CensusRecord) {
        assertNotEquals("the skeletons differ, so the test is not vacuous", ann.skeletonJson, maria.skeletonJson)
        assertEquals(1, samples(ann, maria))
    }

    /** How many of [records] the sink queues on one UTC day. */
    private fun kotlinx.coroutines.test.TestScope.samples(vararg records: CensusRecord): Int {
        val io = StandardTestDispatcher(testScheduler)
        val context = RuntimeEnvironment.getApplication()
        val stats = CensusUploadStats()
        val spool = CensusSpool(context, stats, io)
        val prefs = object : CensusUploadPreferences {
            override val enabled = MutableStateFlow(true)
            override val baseUrl = flowOf("https://census.example")
            override val acceptedSchemaIds = MutableStateFlow(CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS.toSet())
            override val choices = MutableStateFlow(CensusChoices(null, wifiOnly = true))
        }
        val sink = HttpCensusSink(prefs, spool, CensusDailySample(context, io), stats, backgroundScope, io, readsUnboundPlatforms = false)
        runCurrent()
        records.forEach { sink.offer(it) }
        runCurrent()
        assertEquals(records.size - spool.count(), stats.sampledRepeat.get().toInt())
        return spool.count()
    }
}

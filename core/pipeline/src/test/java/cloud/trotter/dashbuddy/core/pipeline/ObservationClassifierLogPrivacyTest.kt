package cloud.trotter.dashbuddy.core.pipeline

import android.util.Log
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import timber.log.Timber

/** #1131: diagnostic logs must not persist unknown notification or click payloads. */
class ObservationClassifierLogPrivacyTest {
    private data class Entry(val priority: Int, val tag: String?, val message: String)

    private val entries = mutableListOf<Entry>()
    private val recorder = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            entries += Entry(priority, tag, message)
        }
    }
    private val classifier = ObservationClassifier(
        mock<JsonRuleInterpreter>(),
        mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
        PlatformAppVersions.NONE,
    )

    @Before fun plant() = Timber.plant(recorder)
    @After fun uproot() = Timber.uproot(recorder)

    @Test
    fun `unknown payloads stay in observations but never enter logs at any priority`() {
        val amount = "\$9876.54"
        val bankingText = "Synthetic bank transfer $amount"
        val customer = "Synthetic Customer"
        val address = "987 Example Lane"
        val nodeId = "synthetic_customer_address_node"
        val nodeText = "$customer, $address"
        val raw = RawNotificationData(
            title = "Synthetic banking notice",
            text = bankingText,
            bigText = null,
            tickerText = null,
            packageName = "com.doordash.driverapp",
            postTime = 123L,
            isClearable = true,
        )
        val notification: Observation.Notification =
            classifier.classify(PipelineEvent.Notification(raw.postTime, raw))
        val click: Observation.Click = classifier.classify(
            PipelineEvent.Click(124L, UiNode(viewIdResourceName = nodeId, text = nodeText)),
        )

        val notificationFields = notification.parsed as ParsedFields.NotificationFields
        assertEquals("unknown", notificationFields.intent)
        assertEquals(raw.toFullString(), notificationFields.rawText)
        val clickFields = click.parsed as ParsedFields.ClickFields
        assertEquals("unknown", clickFields.intent)
        assertEquals(nodeId, clickFields.nodeId)
        assertEquals(nodeText, clickFields.nodeText)
        assertEquals(
            listOf(
                Entry(Log.DEBUG, "Classifier", "UNKNOWN notification"),
                Entry(Log.DEBUG, "Classifier", "UNKNOWN click"),
            ),
            entries,
        )
        listOf(amount, bankingText, raw.title!!, customer, address, nodeId, nodeText).forEach { payload ->
            entries.forEach { entry ->
                assertFalse("Payload logged at priority ${entry.priority}", payload in entry.message)
                assertFalse("Payload logged in tag", payload in entry.tag.orEmpty())
            }
        }
    }
}

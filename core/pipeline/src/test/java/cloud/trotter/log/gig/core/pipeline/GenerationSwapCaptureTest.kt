package cloud.trotter.log.gig.core.pipeline

import android.content.Context
import cloud.trotter.log.gig.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.log.gig.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.log.gig.core.pipeline.rules.TestBundledFloor
import cloud.trotter.log.gig.core.pipeline.rules.TestRulesetSigning
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import cloud.trotter.log.gig.domain.capture.CaptureBus
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.capture.ReplayMetadataProvider
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.model.notification.RawNotificationData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * #1568: a rules generation that goes live between classification and capture must not mask the
 * frame. The frame is masked by the generation that classified it, on the screen path, the click
 * path (the screen context) and the notification path.
 *
 * Generation A redacts the note node and the push title; generation B carries the same rule ids
 * with no redact, so a capture that read the live rules after the swap would ship both raw.
 */
class GenerationSwapCaptureTest {

    private class RecordingBus : CaptureBus {
        val envelopes = mutableListOf<String>()
        override fun offer(
            captureId: String, source: String, classification: String?, platform: String,
            envelopeJson: String, contentHash: Int?,
        ): String? { envelopes += envelopeJson; return captureId }
    }

    private val signingKey = TestRulesetSigning.keyPair()
    private val interpreter = JsonRuleInterpreter(mock<Context>(), mock<RuleCapabilityGrants>())
    private val classifier = ObservationClassifier(
        interpreter,
        object : ReplayMetadataProvider { override fun current() = ReplayMetadata.EMPTY },
        PlatformAppVersions.NONE,
    )
    private val bus = RecordingBus()
    private val writer = CaptureWriter(bus, PipelineStats())

    private fun generation(withRedact: Boolean): String {
        val screenRedact = if (withRedact) """, "redact": [ { "find": { "hasIdSuffix": "order_note" } } ]""" else ""
        val notifRedact = if (withRedact) """, "redact": { "title": {} }""" else ""
        return """
        {
          "format_version": 2,
          "platform_id": "doordash.driver",
          "screens": [
            { "id": "doordash.screen.sensitive.known", "priority": 0, "overrideable": false,
              "parse": { "as": "sensitive" }, "require": { "exists": { "hasText": "Available Balance" } } },
            { "id": "doordash.screen.order_card", "priority": 10,
              "require": { "exists": { "hasIdSuffix": "order_note" } }$screenRedact }
          ],
          "clicks": [
            { "id": "doordash.click.order_note", "priority": 10, "intent": "order_note_tap",
              "require": { "hasIdSuffix": "order_note" } }
          ],
          "notifications": [
            { "id": "doordash.notification.promo", "priority": 10, "intent": "promo_message",
              "require": { "anyFieldContains": "promo" }$notifRedact }
          ]
        }
        """.trimIndent()
    }

    private suspend fun goLive(withRedact: Boolean) {
        if (!interpreter.isLoaded) interpreter.loadBundled(listOf(TestBundledFloor.uber))
        interpreter.load(TestRulesetSigning.verified(generation(withRedact), "test:rules", signingKey))
    }

    private val note = UiNode(viewIdResourceName = "com.doordash.driverapp:id/order_note", text = "Zebra42")
    private val tree = UiNode(children = listOf(UiNode(text = "Order"), note)).restoreParents()
    private val screenEvent = PipelineEvent.Screen(
        timestamp = 1_000L, tree = tree,
        snapshot = TreeSnapshot(tree = tree, packageName = "com.doordash.driverapp"),
        packageName = "com.doordash.driverapp",
    )

    @Test
    fun `a screen is masked by the generation that classified it`() = runTest {
        goLive(withRedact = true)
        val classified = classifier.classifyFrame(screenEvent)
        assertEquals("doordash.screen.order_card", classified.observation.ruleId)

        val before = interpreter.generation
        goLive(withRedact = false)
        assertNotSame("the swap happened", before, interpreter.generation)

        writer.captureScreen(classified.observation, screenEvent, classified.redaction)
        assertFalse(bus.envelopes.single(), bus.envelopes.single().contains("Zebra42"))
    }

    @Test
    fun `a click is masked by the generation that classified its screen`() = runTest {
        goLive(withRedact = true)
        classifier.classifyFrame(screenEvent)
        goLive(withRedact = false)

        val clickEvent = PipelineEvent.Click(timestamp = 2_000L, node = note, packageName = "com.doordash.driverapp")
        val classified = classifier.classifyFrame(clickEvent)
        val click = classified.observation
        assertEquals("doordash.screen.order_card", click.screenRuleId)

        writer.captureClick(click, clickEvent, click.screenTarget, click.screenRuleId, classified.redaction)
        assertFalse(bus.envelopes.single(), bus.envelopes.single().contains("Zebra42"))
    }

    @Test
    fun `a notification is masked by the generation that classified it`() = runTest {
        val raw = RawNotificationData(
            title = "Quokka99", text = "Weekend promo", tickerText = null, bigText = null,
            packageName = "com.doordash.driverapp", postTime = 1_000L, isClearable = true,
        )
        goLive(withRedact = true)
        val classified = classifier.classifyFrame(PipelineEvent.Notification(raw.postTime, raw))
        assertEquals("doordash.notification.promo", classified.observation.ruleId)
        goLive(withRedact = false)

        writer.captureNotification(classified.observation, raw, classified.redaction)
        val envelope = bus.envelopes.single()
        assertFalse(envelope, envelope.contains("Quokka99"))
        assertTrue("the unredacted field stays", envelope.contains("Weekend promo"))
    }

    @Test
    fun `without the swap the newer generation's rules apply`() = runTest {
        goLive(withRedact = false)
        val classified = classifier.classifyFrame(screenEvent)
        writer.captureScreen(classified.observation, screenEvent, classified.redaction)
        assertTrue("generation B declares no redact", bus.envelopes.single().contains("Zebra42"))
    }
}

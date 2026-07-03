package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledNotifRedact
import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedact
import cloud.trotter.dashbuddy.core.pipeline.rules.RuleCompiler
import cloud.trotter.dashbuddy.core.pipeline.rules.RuleContext
import cloud.trotter.dashbuddy.core.pipeline.rules.ScreenRedactionSource
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * #620 — rule-declared notification-envelope redaction. A recognized notification
 * whose rule declares a `redact` block must have customer PII masked in the
 * serialized envelope offered to the bus, while recognition/parse ran on the
 * ORIGINAL notification and the dedup contentHash is preserved (VET V7).
 */
class NotificationRedactionTest {

    private val captureBus: CaptureBus = mock()
    private val stats = PipelineStats()

    private fun compileNotifRedact(json: String): CompiledNotifRedact =
        RuleCompiler.compileRules<RawNotificationData>(
            Json.parseToJsonElement(json).jsonArray, RuleContext.NOTIFICATION,
        ).single().notifRedact

    private fun sourceFor(ruleId: String, redact: CompiledNotifRedact) = object : ScreenRedactionSource {
        override fun redactFor(id: String): CompiledRedact? = null
        override fun notifRedactFor(id: String): CompiledNotifRedact? = redact.takeIf { id == ruleId }
    }

    private fun writer(source: ScreenRedactionSource) = CaptureWriter(captureBus, stats, source)

    private fun raw(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        tickerText: String? = null,
        channelId: String? = null,
    ) = RawNotificationData(
        title = title, text = text, bigText = bigText, tickerText = tickerText,
        packageName = "com.doordash.driverapp", postTime = 0L, isClearable = true,
        channelId = channelId,
    )

    private fun obs(ruleId: String?, target: String?) = Observation.Notification(
        timestamp = 1_000L,
        captureId = null,
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.None,
        target = target,
    )

    private fun capturedEnvelope(): String {
        val cap = argumentCaptor<String>()
        org.mockito.kotlin.verify(captureBus).offer(any(), any(), anyOrNull(), any(), cap.capture(), anyOrNull())
        return cap.lastValue
    }

    private val customerMessageRedact = compileNotifRedact(
        """[{
            "id": "doordash.notification.customer_message",
            "priority": 22,
            "require": { "channelIdContains": "chat" },
            "redact": {
                "title": { "keepPrefix": [ "Message from " ] },
                "text": {},
                "bigText": {},
                "tickerText": {}
            }
        }]""",
    )

    private val orderReadyRedact = compileNotifRedact(
        """[{
            "id": "doordash.notification.order_ready",
            "priority": 24,
            "require": { "anyFieldContains": "ready for pickup" },
            "redact": {
                "text": { "match": "^(.+?)'s order is ready for pickup at ", "maskGroup": 1 }
            }
        }]""",
    )

    @Test
    fun `customer_message envelope masks the name and body, keeps the marker`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())).thenReturn("cap-1")
        val message = raw(
            title = "Message from Jennifer",
            text = "The gate code is 4412, leave at door",
            tickerText = "The gate code is 4412, leave at door",
            channelId = "dasher-notification-channel-inapp-chat",
        )
        writer(sourceFor("doordash.notification.customer_message", customerMessageRedact))
            .captureNotification(obs("doordash.notification.customer_message", "customer_message"), message)

        val json = capturedEnvelope()
        assertFalse("sender name gone", json.contains("Jennifer"))
        assertFalse("body gone", json.contains("gate code is 4412"))
        assertTrue("marker kept, name masked", Regex("""Message from \[redacted:[0-9a-f]{4}\]""").containsMatchIn(json))
    }

    @Test
    fun `notification redaction preserves the ORIGINAL contentHash (VET V7)`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())).thenReturn("cap-1")
        val message = raw(
            title = "Message from Jennifer",
            text = "secret body",
            channelId = "dasher-notification-channel-inapp-chat",
        )
        val originalHash = message.contentHash
        writer(sourceFor("doordash.notification.customer_message", customerMessageRedact))
            .captureNotification(obs("doordash.notification.customer_message", "customer_message"), message)

        val hashCap = argumentCaptor<Int?>()
        org.mockito.kotlin.verify(captureBus).offer(any(), any(), anyOrNull(), any(), any(), hashCap.capture())
        assertEquals("dedup identity must be the ORIGINAL raw hash, not the masked copy's", originalHash, hashCap.lastValue)
    }

    @Test
    fun `order_ready masks the customer name but keeps the store`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())).thenReturn("cap-1")
        val ready = raw(
            title = "Delivery Update",
            text = "Adam's order is ready for pickup at 7-Eleven.",
            channelId = "dasher-notification-channel-delivery-update",
        )
        writer(sourceFor("doordash.notification.order_ready", orderReadyRedact))
            .captureNotification(obs("doordash.notification.order_ready", "order_ready_for_pickup"), ready)

        val json = capturedEnvelope()
        assertFalse("customer name gone", json.contains("Adam"))
        assertTrue("store kept", json.contains("7-Eleven"))
        assertTrue("title (non-PII) kept", json.contains("Delivery Update"))
    }

    @Test
    fun `a benign notification with no redact block is unchanged`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())).thenReturn("cap-1")
        val newOrder = raw(title = "You have a new order!", text = "Tap to view", channelId = "new-order")
        // notifRedactFor returns null for this rule id.
        writer(sourceFor("doordash.notification.customer_message", customerMessageRedact))
            .captureNotification(obs("doordash.notification.new_order", "new_order"), newOrder)

        val json = capturedEnvelope()
        assertTrue(json.contains("You have a new order!"))
        assertTrue(json.contains("Tap to view"))
    }

    @Test
    fun `UNKNOWN notification never consults the notif redaction source`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())).thenReturn("cap-1")
        val benign = raw(title = "Promo", text = "50% off", channelId = "marketing")
        val throwing = object : ScreenRedactionSource {
            override fun redactFor(ruleId: String): CompiledRedact? = null
            override fun notifRedactFor(ruleId: String): CompiledNotifRedact =
                throw AssertionError("UNKNOWN path must not consult redaction source")
        }
        writer(throwing).captureNotification(obs(null, UNKNOWN_TARGET), benign)
        assertTrue(capturedEnvelope().contains("50% off"))
    }
}

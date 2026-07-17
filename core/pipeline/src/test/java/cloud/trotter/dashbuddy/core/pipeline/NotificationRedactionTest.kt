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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * #620 — rule-declared notification-envelope redaction. A recognized notification
 * whose rule declares a `redact` block must have customer PII masked in the
 * serialized envelope offered to the bus, while recognition/parse ran on the
 * ORIGINAL notification and the dedup contentHash is preserved (VET V7).
 */
class NotificationRedactionTest {

    private val captureBus: CaptureBus = mock { on { isEnabled } doReturn true }
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
        actionLabels: List<String> = emptyList(),
    ) = RawNotificationData(
        title = title, text = text, bigText = bigText, tickerText = tickerText,
        packageName = "com.doordash.driverapp", postTime = 0L, isClearable = true,
        channelId = channelId, actionLabels = actionLabels,
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

    // --- #632 rules-independent notification customer-PII backstop ------------

    /** notifRedactFor never matches -> simulates a recognized rule that FORGOT redact. */
    private val noRedactSource = object : ScreenRedactionSource {
        override fun redactFor(ruleId: String): CompiledRedact? = null
        override fun notifRedactFor(ruleId: String): CompiledNotifRedact? = null
    }

    @Test
    fun `#632 backstop scrubs a recognized notification whose rule forgot to redact`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())).thenReturn("cap-1")
        // Uber trip_at_dropoff push with the customer ADDRESS after the lead-in, but the
        // rule shipped NO redact block (noRedactSource) — the backstop is the only net.
        val leak = raw(title = "Leave the order at 123 Main St, Apt 4")
        writer(noRedactSource)
            .captureNotification(obs("uber.notification.trip_at_dropoff", "trip_at_dropoff"), leak)

        val json = capturedEnvelope()
        assertFalse("leaked address gone", json.contains("123 Main St"))
        assertTrue("field scrubbed whole", json.contains("[redacted]"))
        assertEquals(1L, stats.notifRedactBackstopScrubCount)
    }

    @Test
    fun `#632 backstop scrubs a forgotten DoorDash chat name`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())).thenReturn("cap-1")
        val leak = raw(title = "Message from Jennifer", text = "The gate code is 4412")
        writer(noRedactSource)
            .captureNotification(obs("doordash.notification.customer_message", "customer_message"), leak)

        val json = capturedEnvelope()
        assertFalse("customer name gone", json.contains("Jennifer"))
        assertEquals(1L, stats.notifRedactBackstopScrubCount)
    }

    @Test
    fun `#632 backstop preserves the ORIGINAL contentHash on a scrub`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())).thenReturn("cap-1")
        val leak = raw(title = "Leave the order at 123 Main St")
        val originalHash = leak.contentHash
        writer(noRedactSource)
            .captureNotification(obs("uber.notification.trip_at_dropoff", "trip_at_dropoff"), leak)

        val hashCap = argumentCaptor<Int?>()
        org.mockito.kotlin.verify(captureBus).offer(any(), any(), anyOrNull(), any(), any(), hashCap.capture())
        assertEquals("dedup identity stays the ORIGINAL raw hash", originalHash, hashCap.lastValue)
    }

    @Test
    fun `#632 backstop leaves a properly rule-redacted notification untouched (no double-scrub)`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())).thenReturn("cap-1")
        val message = raw(
            title = "Message from Jennifer",
            text = "secret body",
            channelId = "dasher-notification-channel-inapp-chat",
        )
        writer(sourceFor("doordash.notification.customer_message", customerMessageRedact))
            .captureNotification(obs("doordash.notification.customer_message", "customer_message"), message)

        // The rule redacted; the backstop found nothing to scrub.
        assertEquals(0L, stats.notifRedactBackstopScrubCount)
        assertTrue(
            "the rule's own keepPrefix mask stays, not double-scrubbed to whole-field [redacted]",
            Regex("""Message from \[redacted:[0-9a-f]{4}\]""").containsMatchIn(capturedEnvelope()),
        )
    }

    @Test
    fun `#632 backstop does not scrub store-only text`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())).thenReturn("cap-1")
        val storeOnly = raw(title = "Order confirmed", text = "Your delivery from H-E-B")
        writer(noRedactSource)
            .captureNotification(obs("doordash.notification.some_promo", "some_promo"), storeOnly)

        assertTrue("store kept", capturedEnvelope().contains("H-E-B"))
        assertEquals(0L, stats.notifRedactBackstopScrubCount)
    }

    @Test
    fun `#632 backstop cannot catch an unmarked address (residual - rule redact owns it)`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())).thenReturn("cap-1")
        // An address with no scannable marker. (The real trip_en_route_dropoff title is
        // "Going to <addr>" — "Going to " is deliberately NOT a marker because it also
        // prefixes STORES on trip_en_route_pickup; either way the prefix scan cannot fire
        // and the rule's own `redact` is the primary control. Synthetic bare-address shape.)
        val residual = raw(title = "123 Main Street, Austin")
        writer(noRedactSource)
            .captureNotification(obs("uber.notification.trip_en_route_dropoff", "trip_en_route_dropoff"), residual)

        // Honest residual: the prefix backstop does NOT fire here; the rule's
        // `redact { title {} }` is the primary control for this shape.
        assertEquals(0L, stats.notifRedactBackstopScrubCount)
    }

    // --- actionLabels (#666 item 2b) -------------------------------------------
    // actionLabels was serialized into every notif envelope but excluded from the
    // #632 backstop scan entirely. A recognized notification whose rule forgot to
    // redact a customer-PII marker living in an action label must still be caught.

    @Test
    fun `#632 backstop scrubs a marker-bearing action label`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())).thenReturn("cap-1")
        val leak = raw(title = "New message", actionLabels = listOf("Message from Jennifer", "Dismiss"))
        writer(noRedactSource)
            .captureNotification(obs("doordash.notification.customer_message", "customer_message"), leak)

        val json = capturedEnvelope()
        assertFalse("leaked name gone", json.contains("Jennifer"))
        assertTrue("action label scrubbed whole", json.contains("[redacted]"))
        assertTrue("clean sibling label kept", json.contains("Dismiss"))
        assertEquals(1L, stats.notifRedactBackstopScrubCount)
    }

    @Test
    fun `#632 backstop leaves clean action labels untouched`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())).thenReturn("cap-1")
        val benign = raw(title = "You have a new order!", actionLabels = listOf("Accept", "Decline"))
        writer(noRedactSource)
            .captureNotification(obs("doordash.notification.new_order", "new_order"), benign)

        val json = capturedEnvelope()
        assertTrue(json.contains("Accept"))
        assertTrue(json.contains("Decline"))
        assertEquals(0L, stats.notifRedactBackstopScrubCount)
    }

    @Test
    fun `#632 backstop action-label scrub preserves the ORIGINAL contentHash`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())).thenReturn("cap-1")
        val leak = raw(title = "New message", actionLabels = listOf("Message from Jennifer"))
        val originalHash = leak.contentHash
        writer(noRedactSource)
            .captureNotification(obs("doordash.notification.customer_message", "customer_message"), leak)

        val hashCap = argumentCaptor<Int?>()
        org.mockito.kotlin.verify(captureBus).offer(any(), any(), anyOrNull(), any(), any(), hashCap.capture())
        // actionLabels are NOT part of toFullString()/contentHash — scrubbing one
        // must not perturb the dedup identity.
        assertEquals("dedup identity stays the ORIGINAL raw hash", originalHash, hashCap.lastValue)
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

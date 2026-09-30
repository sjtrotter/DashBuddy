package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.rules.NoRedaction
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.dto.UiNodeDto
import cloud.trotter.dashbuddy.domain.capture.toDomain
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * #1147 review X2 — the customer text-marker backstop runs on EVERY click envelope, recognized
 * included. "A rule-matched click is an app button whose labels carry no PII" held for text/desc,
 * not for the #1147 fields: a Compose button's click-action label / hint / tooltip is arbitrary app
 * text. A recognized click with no marker is unchanged.
 */
class RecognizedClickCustomerScrubTest {

    private val offered = mutableListOf<String>()
    private val bus = object : CaptureBus {
        override fun offer(
            captureId: String, source: String, classification: String?, platform: String,
            envelopeJson: String, contentHash: Int?,
        ): String { offered += envelopeJson; return captureId }
    }
    private val stats = PipelineStats()
    private val writer = CaptureWriter(bus, stats, NoRedaction)

    private fun recognizedClick(node: UiNode): String = recognizedClickOrNull(node)!!

    private fun recognizedClickOrNull(node: UiNode): String? {
        offered.clear()
        writer.captureClick(
            Observation.Click(
                timestamp = 1_000L, captureId = null, ruleId = "doordash.click.accept_offer",
                metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null,
                parsed = ParsedFields.None, target = "accept_offer",
            ),
            PipelineEvent.Click(timestamp = 1_000L, node = node, packageName = "com.doordash.driverapp"),
            screenTarget = "offer_popup",
            screenRuleId = "doordash.screen.offer_popup",
        )
        return offered.singleOrNull()
    }

    private fun payloadNode(json: String): UiNode {
        val node = Json.parseToJsonElement(json).jsonObject["payload"]!!.jsonObject["node"]!!
        return Json { ignoreUnknownKeys = true }.decodeFromJsonElement(UiNodeDto.serializer(), node).toDomain()
    }

    private fun accept(field: UiNodeTextField?, value: String): UiNode {
        val base = UiNode(viewIdResourceName = "com.doordash.driverapp:id/accept_button", text = "Accept", isClickable = true)
        return when (field) {
            null -> base
            UiNodeTextField.PANE_TITLE -> base.copy(paneTitle = value)
            UiNodeTextField.ROLE_DESCRIPTION -> base.copy(roleDescription = value)
            UiNodeTextField.HINT_TEXT -> base.copy(hintText = value)
            UiNodeTextField.TOOLTIP_TEXT -> base.copy(tooltipText = value)
            UiNodeTextField.ERROR_TEXT -> base.copy(errorText = value)
            UiNodeTextField.CLICK_ACTION_LABEL -> base.copy(clickActionLabel = value)
            UiNodeTextField.UNIQUE_ID -> base.copy(uniqueId = value)
            else -> error("legacy field $field not under test here")
        }
    }

    @Test
    fun `a customer marker in any new field of a recognized click is scrubbed`() {
        val newFields = UiNodeTextField.entries.filter { it !in UiNodeTextField.LEGACY_SCAN_ORDER }
        for ((i, field) in newFields.withIndex()) {
            val json = recognizedClick(accept(field, "Deliver to Jane Q. Doe"))
            assertFalse("$field: the customer name must not persist", json.contains("Jane Q. Doe"))
            assertEquals("$field: the button label survives", "Accept", payloadNode(json).text)
            assertEquals("counted as a backstop scrub", (i + 1).toLong(), stats.redactBackstopScrubCount)
        }
    }

    @Test
    fun `a recognized click with no marker is unchanged`() {
        val node = accept(UiNodeTextField.CLICK_ACTION_LABEL, "Accept offer")
        val json = recognizedClick(node)
        assertEquals(node, payloadNode(json))
        assertEquals(0L, stats.redactBackstopScrubCount)
        assertEquals("no banking marker, no drop", 0L, stats.scrubbedUnknownCaptureCount)
        assertEquals(0L, stats.unknownCustomerScrubCount)
    }

    /**
     * #1147 review Z1: the dasher-BANKING drop runs on every click too. A click rule can classify a
     * tap on a DasherDirect sheet during the #1104 stale-screen window, and a Compose button's action
     * label / hint / tooltip is arbitrary app text — so a banking marker there must suppress the
     * envelope (the observation still flows; envelope-only).
     */
    @Test
    fun `a recognized click whose only banking marker sits in a new field produces no envelope`() {
        val base = UiNode(viewIdResourceName = "com.doordash.driverapp:id/continue", text = "Continue", isClickable = true)
        for ((i, node) in listOf(
            base.copy(clickActionLabel = "Transfer \$45.66 to bank"),
            base.copy(hintText = "Routing Number"),
            base.copy(tooltipText = "Available Balance"),
        ).withIndex()) {
            assertEquals("no envelope for $node", null, recognizedClickOrNull(node))
            assertEquals((i + 1).toLong(), stats.scrubbedUnknownCaptureCount)
        }
    }
}

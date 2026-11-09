package cloud.trotter.log.gig.notice

import android.content.res.Resources
import cloud.trotter.log.gig.core.pipeline.rules.ParsedFieldsFactory
import cloud.trotter.log.gig.domain.pipeline.ParseRotCause
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncident
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #1560 — the one owner of the recognition incident copy, shared by the notice and Home's card.
 *
 * Pins the en wording, that no percentage, digit, wire or rule id reaches the dasher, that every
 * shape-required shape and field has a label (a new one without a label fails here), and that the
 * es / fr formats resolve with a capitalized title.
 */
@RunWith(RobolectricTestRunner::class)
class RecognitionNoticeCopyTest {

    private val res: Resources get() = RuntimeEnvironment.getApplication().resources

    private fun rot(wire: String, shape: String, vararg fields: String) = ParseRotCause(
        ruleId = "$wire.screen.$shape",
        shape = shape,
        nullFields = fields.toList(),
        trippedAt = 1_000L,
    )

    private fun incident(wire: String = "doordash", unknown: Boolean = false, causes: List<ParseRotCause> = emptyList()) =
        RecognitionIncident(platformWire = wire, openedAt = 1_000L, screensUnrecognized = unknown, parseRot = causes)

    private fun offer(wire: String = "doordash") = incident(wire, causes = listOf(rot(wire, "offer", "payAmount", "distance")))
    private fun receipt() = incident(causes = listOf(rot("doordash", "post_task", "totalPay")))
    private fun summary() = incident(causes = listOf(rot("doordash", "session_ended", "totalEarnings")))
    private fun unrecognized(wire: String = "doordash") = incident(wire, unknown = true)
    private fun several(wire: String = "doordash") =
        incident(wire, unknown = true, causes = listOf(rot(wire, "offer", "payAmount")))

    private fun all() = listOf(offer(), receipt(), summary(), unrecognized(), several())

    // 1
    @Test
    fun `the en copy reads as pinned`() {
        val still = "Other screens may still record."

        assertEquals("DoorDash offers: pay and distance not read", RecognitionNoticeCopy.title(res, offer()))
        assertEquals(still, RecognitionNoticeCopy.text(res, offer()))

        assertEquals("DoorDash delivery receipts: pay not read", RecognitionNoticeCopy.title(res, receipt()))
        assertEquals(still, RecognitionNoticeCopy.text(res, receipt()))

        assertEquals("DoorDash shift summaries: total not read", RecognitionNoticeCopy.title(res, summary()))
        assertEquals(still, RecognitionNoticeCopy.text(res, summary()))

        assertEquals("DoorDash screens not recognized", RecognitionNoticeCopy.title(res, unrecognized()))
        assertEquals(still, RecognitionNoticeCopy.text(res, unrecognized()))

        assertEquals("DoorDash: some screens not read", RecognitionNoticeCopy.title(res, several()))
        assertEquals(
            "DoorDash screens not recognized\nDoorDash offers: pay not read\n$still",
            RecognitionNoticeCopy.text(res, several()),
        )
    }

    // 2
    @Test
    fun `an unknown wire reads the generic name and never the wire or a rule id`() {
        val incident = offer("some_future_platform")
        val title = RecognitionNoticeCopy.title(res, incident)
        val text = RecognitionNoticeCopy.text(res, incident)

        assertTrue(title, title.startsWith("Delivery app offers:"))
        for (copy in listOf(title, text)) {
            assertFalse(copy, copy.contains("some_future_platform"))
            assertFalse(copy, copy.contains(".screen."))
        }
    }

    // 3
    @Test
    fun `no percentage, digit, update or fix reaches the dasher`() {
        for (incident in all()) {
            for (copy in listOf(RecognitionNoticeCopy.title(res, incident), RecognitionNoticeCopy.text(res, incident))) {
                assertFalse(copy, copy.any { it.isDigit() })
                assertFalse(copy, copy.contains('%'))
                assertFalse(copy, copy.contains("update", ignoreCase = true))
                assertFalse(copy, copy.contains("fix", ignoreCase = true))
                assertFalse(copy, copy.contains("doordash"))
            }
        }
    }

    // 4
    @Test
    fun `every shape-required shape and field has a label`() {
        assertEquals(ParsedFieldsFactory.REQUIRED_FIELDS_BY_SHAPE.keys, RecognitionNoticeCopy.SHAPE_LABELS.keys)
        val missing = ParsedFieldsFactory.REQUIRED_FIELDS_BY_SHAPE.values.flatten()
            .filterNot { it in RecognitionNoticeCopy.FIELD_LABELS }
        assertEquals("shape-required fields without a label", emptyList<String>(), missing)
    }

    // 5
    @Test
    @Config(qualifiers = "es")
    fun `the es titles resolve, name the platform and start capitalized`() {
        assertLocalizedTitles()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the fr titles resolve, name the platform and start capitalized`() {
        assertLocalizedTitles()
    }

    private fun assertLocalizedTitles() {
        for (incident in listOf(offer(), several())) {
            val title = RecognitionNoticeCopy.title(res, incident)
            assertTrue(title, title.contains("DoorDash"))
            assertFalse("still the en copy: $title", title.startsWith("DoorDash"))
        }
        for (incident in listOf(offer("some_future_platform"), several("some_future_platform"), unrecognized("some_future_platform"))) {
            val title = RecognitionNoticeCopy.title(res, incident)
            assertTrue(title, title.first().isUpperCase())
        }
    }
}

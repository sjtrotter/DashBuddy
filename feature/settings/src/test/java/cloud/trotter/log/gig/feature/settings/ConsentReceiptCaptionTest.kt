package cloud.trotter.log.gig.feature.settings

import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1457 — the status line: the decision's label, then the receipt's details only when there is one. */
class ConsentReceiptCaptionTest {
    private val receipt = ConsentReceipt(
        Instant.parse("2026-10-06T00:30:00Z").toEpochMilli(), "0.231.0", 1, true,
    )
    private val format = "%1\$s, %2\$s, app %3\$s, privacy notice %4\$d"

    @Test
    fun `allowed status includes date version and disclosure revision`() {
        assertEquals(
            "Allowed, 6 Oct 2026, app 0.231.0, privacy notice 1",
            formatConsentStatus("Allowed", receipt, format, ZoneId.of("UTC"), Locale.UK),
        )
    }

    @Test
    fun `the label comes from the decision, and the date is the local calendar date`() {
        assertEquals(
            "Not allowed, 5 Oct 2026, app 0.231.0, privacy notice 1",
            formatConsentStatus("Not allowed", receipt, format, ZoneId.of("America/Chicago"), Locale.UK),
        )
    }

    @Test
    fun `no receipt shows the decision alone`() {
        assertEquals("Not allowed", formatConsentStatus("Not allowed", null, format))
        assertEquals("Not decided", formatConsentStatus("Not decided", null, format))
    }

    @Test
    fun `status honors translated labels positional format and locale`() {
        assertEquals(
            "r2 / vnext / Oct 6, 2026 / Yes",
            formatConsentStatus(
                "Yes",
                receipt.copy(appVersion = "next", disclosureRevision = 2),
                "r%4\$d / v%3\$s / %2\$s / %1\$s", ZoneId.of("UTC"), Locale.US,
            ),
        )
    }
}

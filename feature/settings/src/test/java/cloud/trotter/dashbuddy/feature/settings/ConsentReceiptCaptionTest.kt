package cloud.trotter.dashbuddy.feature.settings

import cloud.trotter.dashbuddy.domain.capability.ConsentReceipt
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsentReceiptCaptionTest {
    private val receipt = ConsentReceipt(
        Instant.parse("2026-10-06T00:30:00Z").toEpochMilli(), "0.231.0", 1, true,
    )
    private val format = "%1\$s · %2\$s · v%3\$s · disclosure r%4\$d"

    @Test
    fun `allowed caption includes date version and disclosure revision`() {
        assertEquals(
            "Allowed · 6 Oct 2026 · v0.231.0 · disclosure r1",
            formatConsentReceipt(receipt, format, "Allowed", "Not allowed", ZoneId.of("UTC"), Locale.UK),
        )
    }

    @Test
    fun `denied caption uses receipt decision and the local calendar date`() {
        assertEquals(
            "Not allowed · 5 Oct 2026 · v0.231.0 · disclosure r1",
            formatConsentReceipt(
                receipt.copy(granted = false), format, "Allowed", "Not allowed",
                ZoneId.of("America/Chicago"), Locale.UK,
            ),
        )
    }

    @Test
    fun `caption honors translated labels positional format and locale`() {
        assertEquals(
            "r2 / vnext / Oct 6, 2026 / Yes",
            formatConsentReceipt(
                receipt.copy(appVersion = "next", disclosureRevision = 2),
                "r%4\$d / v%3\$s / %2\$s / %1\$s", "Yes", "No", ZoneId.of("UTC"), Locale.US,
            ),
        )
    }
}

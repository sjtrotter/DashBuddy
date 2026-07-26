package cloud.trotter.dashbuddy.core.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #862 — the log-safe marker id must satisfy two properties, both pinned here against the LIVE
 * marker SSOTs (so a marker added later is covered without touching this test):
 *
 *  1. **Scan-clean** — an id never trips [SensitiveTextMarkers.findMarker] (the exact function the
 *     shareable-log sink is bound to) nor reads as a [CustomerTextMarkers] marker. This is what
 *     lets our own scrub/backstop WARN survive the sink verbatim while the sink stays zero-trust.
 *  2. **Unambiguous** — ids are collision-free within each SSOT, so a desk reader can map an id in
 *     `shareable.log` back to exactly one marker.
 */
class MarkerLogIdTest {

    /**
     * The `shape:` marker names [SensitiveTextMarkers] synthesizes for its regex shapes — derived
     * from the LIVE patterns via the production name formatter, never hand-copied, so a shape
     * added to the SSOT is covered here automatically.
     */
    private val shapeMarkers = SensitiveTextMarkers.SHAPE_PATTERNS.map { SensitiveTextMarkers.shapeMarkerName(it) }

    /**
     * The fail-closed sentinel the scanner returns instead of a real marker.
     *
     * The sink's OWN `scrubber-error` sentinel (`LogRepository`, `:core:data`) is deliberately NOT
     * covered here: it is never passed through [MarkerLogId] — the sink writes it straight into the
     * `[scrubbed:…]` placeholder that REPLACES the line, and it is never re-scanned. Pinning a
     * cross-module copy of it would be exactly the hand-maintained duplicate this test avoids.
     */
    private val sentinels = listOf(SensitiveTextMarkers.NORMALIZE_FAILED)

    @Test
    fun `id is the first two alphanumerics plus the marker length`() {
        assertEquals("Tr12", MarkerLogId.of("Transfer out"))
        assertEquals("De11", MarkerLogId.of("Deliver to "))
        assertEquals("CV3", MarkerLogId.of("CVV"))
    }

    @Test
    fun `a marker with no alphanumerics still yields a usable id`() {
        assertEquals("?3", MarkerLogId.of("---"))
    }

    @Test
    fun `no sensitive-marker id trips the sink scan`() {
        for (marker in SensitiveTextMarkers.KEYWORDS + shapeMarkers + sentinels) {
            val id = MarkerLogId.of(marker)
            assertNull(
                "id '$id' for marker '$marker' would self-scrub at the shareable-log sink",
                SensitiveTextMarkers.findMarker(id),
            )
        }
    }

    @Test
    fun `no customer-marker id reads as a customer marker, nor trips the sink scan`() {
        for (marker in CustomerTextMarkers.MARKERS) {
            val id = MarkerLogId.of(marker)
            assertNull("id '$id' still reads as customer marker '$marker'", CustomerTextMarkers.unredactedMarker(id))
            assertNull("id '$id' trips the sensitive scan", SensitiveTextMarkers.findMarker(id))
        }
    }

    @Test
    fun `an id never contains its own marker`() {
        for (marker in SensitiveTextMarkers.KEYWORDS + CustomerTextMarkers.MARKERS + shapeMarkers) {
            val id = MarkerLogId.of(marker)
            assertTrue(
                "id '$id' still carries marker '$marker' verbatim",
                !id.contains(marker, ignoreCase = true),
            )
        }
    }

    @Test
    fun `ids are collision-free within each marker SSOT, and across both`() {
        val ssots = listOf(SensitiveTextMarkers.KEYWORDS, CustomerTextMarkers.MARKERS)
        // Per-SSOT is the load-bearing property (a diagnostic names which SSOT it read); the
        // union check is the stronger one — an id decodes to one marker, full stop.
        for (ssot in ssots + listOf(ssots.flatten())) {
            val byId = ssot.groupBy { MarkerLogId.of(it) }.filterValues { it.size > 1 }
            assertTrue("ambiguous marker ids: $byId", byId.isEmpty())
        }
    }
}

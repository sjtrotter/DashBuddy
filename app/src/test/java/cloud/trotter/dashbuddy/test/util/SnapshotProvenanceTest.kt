package cloud.trotter.dashbuddy.test.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotProvenanceTest {
    @Test
    fun `provenance scrub preserves formatting and unrelated values at every depth`() {
        val input = """
            {
              "metadata": {"deviceFingerprint" : "fixture-device", "captureId":"fixture-capture"},
              "nested": [{"deviceFingerprint": "escaped\"device", "captureId" : "nested-capture"}],
              "other": "fixture-device",
              "otherText": "\"captureId\": \"leave this alone\"",
              "deviceFingerprintExtra": "leave this alone",
              "nonStrings": {"captureId": null, "deviceFingerprint": 123}
            }
        """.trimIndent()
        val expected = """
            {
              "metadata": {"deviceFingerprint" : "${SnapshotRedactor.DEVICE_FINGERPRINT_PLACEHOLDER}", "captureId":"${SnapshotRedactor.CAPTURE_ID_PLACEHOLDER}"},
              "nested": [{"deviceFingerprint": "${SnapshotRedactor.DEVICE_FINGERPRINT_PLACEHOLDER}", "captureId" : "${SnapshotRedactor.CAPTURE_ID_PLACEHOLDER}"}],
              "other": "fixture-device",
              "otherText": "\"captureId\": \"leave this alone\"",
              "deviceFingerprintExtra": "leave this alone",
              "nonStrings": {"captureId": null, "deviceFingerprint": 123}
            }
        """.trimIndent()
        assertEquals(expected, SnapshotRedactor.sanitizeProvenance(input))
        assertEquals(expected, SnapshotRedactor.sanitizeProvenance(expected))
        assertEquals("intake applies provenance sanitizing", expected, SnapshotRedactor.redact(input))
    }
}

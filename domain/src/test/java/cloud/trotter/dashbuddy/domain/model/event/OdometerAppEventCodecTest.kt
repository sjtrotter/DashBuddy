package cloud.trotter.dashbuddy.domain.model.event

import cloud.trotter.dashbuddy.domain.model.event.payload.OdometerReadingPayload
import org.junit.Assert.*
import org.junit.Test

class OdometerAppEventCodecTest {
    @Test fun `odometer reading payload round trips`() {
        val payload = OdometerReadingPayload("civic", 52_388, 123.5)
        assertEquals(payload, AppEventCodec.decodePayload(AppEventType.ODOMETER_READING, AppEventCodec.encodePayload(payload)))
    }
    @Test fun `decodes with gps total absent`() {
        assertEquals(OdometerReadingPayload("civic", 52_388), AppEventCodec.decodePayload(AppEventType.ODOMETER_READING,
            """{"vehicleId":"civic","readingMiles":52388}"""))
    }
    @Test fun `malformed json throws`() {
        assertThrows(Exception::class.java) { AppEventCodec.decodePayload(AppEventType.ODOMETER_READING, "{broken") }
    }
}

package cloud.trotter.dashbuddy.domain.model.event

import cloud.trotter.dashbuddy.domain.model.event.payload.DashVehiclePayload
import org.junit.Assert.*
import org.junit.Test

class DashVehicleAppEventCodecTest {
    @Test fun `dash vehicle round trips including null vehicle null gps and forward kind strings`() {
        for (kind in listOf("START", "SWAP", "FUTURE")) for (gps in listOf(null, 123.5)) for (vehicle in listOf(null, "civic")) {
            val payload = DashVehiclePayload("dash", vehicle, gps, kind)
            assertEquals(payload, AppEventCodec.decodePayload(AppEventType.DASH_VEHICLE, AppEventCodec.encodePayload(payload)))
        }
    }
    @Test fun `malformed json throws`() {
        assertThrows(Exception::class.java) { AppEventCodec.decodePayload(AppEventType.DASH_VEHICLE, "{broken") }
    }
}

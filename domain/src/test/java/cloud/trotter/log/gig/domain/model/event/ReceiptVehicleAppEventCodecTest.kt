package cloud.trotter.log.gig.domain.model.event

import cloud.trotter.log.gig.domain.analytics.ExpenseCategory
import cloud.trotter.log.gig.domain.model.event.payload.ExpenseLoggedPayload
import cloud.trotter.log.gig.domain.model.event.payload.FuelFillupPayload
import org.junit.Assert.*
import org.junit.Test

class ReceiptVehicleAppEventCodecTest {
    @Test fun `older receipt JSON decodes without a vehicle`() {
        val expense = AppEventCodec.decodePayload(AppEventType.EXPENSE_LOGGED,
            """{"sessionId":"S","category":"CAR_CARE","amount":18.4,"note":"wipers"}""") as ExpenseLoggedPayload
        val fuel = AppEventCodec.decodePayload(AppEventType.FUEL_FILLUP,
            """{"sessionId":"S","totalAmount":42.0,"gallons":11.125,"odometerMiles":1234.5}""") as FuelFillupPayload
        assertNull(expense.vehicleId)
        assertNull(fuel.vehicleId)
    }

    @Test fun `receipt payloads round trip their entry vehicle`() {
        val expense = ExpenseLoggedPayload("S", ExpenseCategory.CAR_CARE, 18.4, "wipers", "other-car")
        val fuel = FuelFillupPayload("S", 42.0, 11.125, 1234.5, "other-car")
        assertEquals(expense, AppEventCodec.decodePayload(AppEventType.EXPENSE_LOGGED, AppEventCodec.encodePayload(expense)))
        assertEquals(fuel, AppEventCodec.decodePayload(AppEventType.FUEL_FILLUP, AppEventCodec.encodePayload(fuel)))
    }
}

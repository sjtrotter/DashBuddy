package cloud.trotter.dashbuddy.domain.model.vehicle

import org.junit.Assert.*
import org.junit.Test

class OdometerReadingLimitTest {
    @Test fun `valid readings are one to the cap`() {
        assertFalse(OdometerReadingLimit.isValid(Int.MIN_VALUE))
        assertFalse(OdometerReadingLimit.isValid(-1))
        assertFalse(OdometerReadingLimit.isValid(0))
        assertTrue(OdometerReadingLimit.isValid(1))
        assertTrue(OdometerReadingLimit.isValid(52_388))
        assertTrue(OdometerReadingLimit.isValid(OdometerReadingLimit.MAX_MILES))
        assertFalse(OdometerReadingLimit.isValid(OdometerReadingLimit.MAX_MILES + 1))
        assertFalse(OdometerReadingLimit.isValid(Int.MAX_VALUE))
    }
}

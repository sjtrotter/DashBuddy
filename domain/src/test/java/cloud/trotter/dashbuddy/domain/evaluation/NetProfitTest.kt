package cloud.trotter.dashbuddy.domain.evaluation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the True-Net SSOT (#5). These are the exact arithmetic the offer verdict
 * and the live home glance both route through, so a change here is a change to both.
 */
class NetProfitTest {

    @Test
    fun `net subtracts miles times cost-per-mile from gross`() {
        // $20 gross, 8 mi at $0.25/mi operating cost → $18 net.
        assertEquals(18.0, NetProfit.net(grossPay = 20.0, miles = 8.0, costPerMile = 0.25), 1e-9)
    }

    @Test
    fun `net can go negative when costs exceed pay`() {
        assertEquals(-5.0, NetProfit.net(grossPay = 5.0, miles = 40.0, costPerMile = 0.25), 1e-9)
    }

    @Test
    fun `net with zero cost-per-mile is the gross`() {
        assertEquals(12.5, NetProfit.net(grossPay = 12.5, miles = 30.0, costPerMile = 0.0), 1e-9)
    }

    @Test
    fun `perHour divides net by hours`() {
        // $18 net over 0.5 h → $36/hr.
        assertEquals(36.0, NetProfit.perHour(net = 18.0, hours = 0.5)!!, 1e-9)
    }

    @Test
    fun `perHour is null for zero or negative hours`() {
        assertNull(NetProfit.perHour(net = 18.0, hours = 0.0))
        assertNull(NetProfit.perHour(net = 18.0, hours = -1.0))
    }

    @Test
    fun `perMile divides net by miles`() {
        // $18 net over 8 mi → $2.25/mi.
        assertEquals(2.25, NetProfit.perMile(net = 18.0, miles = 8.0)!!, 1e-9)
    }

    @Test
    fun `perMile is null for zero miles`() {
        assertNull(NetProfit.perMile(net = 18.0, miles = 0.0))
    }
}

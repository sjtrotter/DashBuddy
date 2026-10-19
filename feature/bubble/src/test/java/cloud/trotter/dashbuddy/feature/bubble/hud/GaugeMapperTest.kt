package cloud.trotter.dashbuddy.feature.bubble.hud

import org.junit.Assert.*
import org.junit.Test

import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE

class GaugeMapperTest {
    private val locale = java.util.Locale.getDefault()
    @org.junit.Before fun setup() { java.util.Locale.setDefault(java.util.Locale.US) }
    @org.junit.After fun cleanup() { java.util.Locale.setDefault(locale) }
    private fun map(rate: Double? = 16.0, target: Double? = 18.0, kind: GaugeKind = GaugeKind.HOUR,
        previous: Double? = null, denominator: Double? = 31.0, delta: Double? = null) =
        GaugeMapper.map(kind, denominator, rate, target, previous, delta)
    @Test fun `rate above target fills good`() {
        val m = map(20.0, 18.0, GaugeKind.HOUR)
        assertEquals(GaugeTone.GOOD, m.tone)
        assertEquals(true, m.aboveTarget)
    }
    @Test fun `rate equal to target is good`() {
        val m = map(18.0, 18.0, GaugeKind.HOUR)
        assertEquals(GaugeTone.GOOD, m.tone)
        assertEquals(true, m.aboveTarget)
    }
    @Test fun `rate one cent under the per mile target is warn`() {
        val m = map(1.49, 1.5, GaugeKind.MILE)
        assertEquals(GaugeTone.WARN, m.tone)
        assertEquals(false, m.aboveTarget)
    }
    @Test fun `hourly that renders as the target is good`() {
        val m = map(17.6, 18.0, GaugeKind.HOUR)
        assertEquals(GaugeTone.GOOD, m.tone)
        assertEquals(true, m.aboveTarget)
    }
    @Test fun `hourly one dollar under is warn`() {
        val m = map(17.0, 18.0, GaugeKind.HOUR)
        assertEquals(GaugeTone.WARN, m.tone)
        assertEquals(false, m.aboveTarget)
    }
    @Test fun `no target draws no tick and a neutral fill`() {
        val m = map(target = null)
        assertNull(m.tickAt)
        assertEquals(GaugeTone.NEUTRAL, m.tone)
        assertTrue(m.fill > 0)
    }
    @Test fun `scale is the larger of rate and target with headroom`() {
        val m = map()
        assertEquals(0.740741f, m.fill, 0.00001f)
        assertEquals(0.833333f, m.tickAt!!, 0.00001f)
    }
    @Test fun `previous rate places the hollow marker`() {
        val m = map(24.6, previous = 16.0)
        assertEquals((16.0 / (24.6 * 1.2)).toFloat(), m.ghostAt!!, 0.00001f)
        assertEquals(0.833333f, m.fill, 0.00001f)
    }
    @Test fun `negative rate clamps the fill and is warn`() {
        val m = map(-2.0, target = null)
        assertEquals(0f, m.fill)
        assertEquals(GaugeTone.WARN, m.tone)
    }
    @Test fun `unknown rate shows the em dash and no fill`() {
        val m = map(null, previous = 16.0)
        assertEquals(EMPTY_VALUE, m.rateText)
        assertEquals(0f, m.fill)
        assertNull(m.tickAt)
        assertNull(m.ghostAt)
    }
    @Test fun `hourly rate reads whole dollars`() {
        assertEquals("$16", map(16.4, kind = GaugeKind.HOUR).rateText)
    }
    @Test fun `per mile reads dollars and cents`() {
        assertEquals("$1.30", map(1.3, kind = GaugeKind.MILE).rateText)
    }
    @Test fun `per item under a dollar reads cents`() {
        assertEquals("69¢", map(0.69, kind = GaugeKind.ITEM).rateText)
    }
    @Test fun `per item from a dollar reads dollars`() {
        assertEquals("$1.20", map(1.2, kind = GaugeKind.ITEM).rateText)
    }
    @Test fun `per item that rounds to a dollar reads dollars`() {
        assertEquals("$1.00", map(0.999, kind = GaugeKind.ITEM).rateText)
    }
    @Test fun `minutes denominator rounds to whole minutes`() {
        assertEquals("31", map(denominator = 30.6).denominatorText)
    }
    @Test fun `miles denominator has one decimal`() {
        assertEquals("6.4", map(kind = GaugeKind.MILE, denominator = 6.42).denominatorText)
    }
    @Test fun `delta reads with a plus sign`() {
        assertEquals("+12", map(delta = 12.0).deltaText)
        assertEquals("+2.1", map(kind = GaugeKind.MILE, delta = 2.1).deltaText)
        assertEquals("+3", map(kind = GaugeKind.ITEM, delta = 3.0).deltaText)
    }
}

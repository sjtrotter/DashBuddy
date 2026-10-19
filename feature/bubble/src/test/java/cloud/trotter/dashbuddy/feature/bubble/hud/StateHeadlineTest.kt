package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import org.junit.Assert.*
import org.junit.Test

class StateHeadlineTest {
    @Test fun `dwell before the deadline is minutes and seconds`() {
        assertEquals(Headline.Clock(239_000), StateHeadline.parked(0, 240_000, 239_000))
    }
    @Test fun `dwell one millisecond before the deadline keeps seconds`() {
        assertEquals(Headline.Clock(239_999), StateHeadline.parked(0, 240_000, 239_999))
    }
    @Test fun `dwell at the deadline flips to whole minutes`() {
        assertEquals(Headline.Minutes(4), StateHeadline.parked(0, 240_000, 240_000))
    }
    @Test fun `late dwell floors to whole minutes`() {
        assertEquals(Headline.Minutes(9), StateHeadline.parked(0, 240_000, 599_999))
    }
    @Test fun `no deadline never flips and is never late`() {
        assertEquals(Headline.Clock(599_999), StateHeadline.parked(0, null, 599_999))
        assertNull(StateHeadline.lateMinutes(null, 599_999))
    }
    @Test fun `lateness is whole minutes and at least one`() {
        assertNull(StateHeadline.lateMinutes(240_000, 239_999))
        assertEquals(1, StateHeadline.lateMinutes(240_000, 240_000))
        assertEquals(1, StateHeadline.lateMinutes(240_000, 359_999))
        assertEquals(2, StateHeadline.lateMinutes(240_000, 360_000))
    }
    @Test fun `minutes left round up`() {
        assertEquals(Headline.Minutes(2), StateHeadline.moving(0, 120_001, 60_000))
        assertEquals(Headline.Minutes(1), StateHeadline.moving(0, 120_000, 60_000))
        assertEquals(Headline.Minutes(1), StateHeadline.moving(0, 120_000, 119_999))
    }
    @Test fun `navigation past the deadline follows the late form`() {
        assertEquals(Headline.Minutes(23), StateHeadline.moving(0, 1_320_000, 1_439_999))
        assertEquals(Headline.Minutes(22), StateHeadline.moving(0, 1_320_000, 1_320_000))
    }
    @Test fun `navigation without a deadline is the elapsed clock`() {
        assertEquals(Headline.Clock(252_000), StateHeadline.moving(10_000, null, 262_000))
        assertEquals(Headline.Clock(0), StateHeadline.moving(100_000, null, 90_000))
    }
    @Test fun `headline text renders clock minutes and unknown`() {
        assertEquals("4:12", headlineText(Headline.Clock(252_000)))
        assertEquals("1:04:12", headlineText(Headline.Clock(3_852_000)))
        assertEquals("23", headlineText(Headline.Minutes(23)))
        assertEquals(EMPTY_VALUE, headlineText(Headline.Unknown))
    }
    @Test fun `waiting is a clock from the anchor`() {
        assertEquals(Headline.Clock(252_000), StateHeadline.waiting(10_000, 262_000))
        assertEquals(Headline.Unknown, StateHeadline.waiting(null, 262_000))
    }
    @Test fun `negative spans clamp to zero`() {
        assertEquals(Headline.Clock(0), StateHeadline.waiting(100_000, 90_000))
        assertEquals(Headline.Clock(0), StateHeadline.parked(100_000, null, 90_000))
        assertEquals(Headline.Minutes(0), StateHeadline.parked(100_000, 80_000, 90_000))
        assertEquals(Headline.Minutes(0), StateHeadline.moving(100_000, 80_000, 90_000))
    }
}

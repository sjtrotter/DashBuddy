package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.analytics.SessionDetail
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1443: which shift the hero describes, and when its figure and cash may show. */
class DisplayedShiftsTest {
    private val ended = lastDash() // startedAt 1_000, endedAt 7_201_000
    private fun row(id: String = ended.sessionId, endedAt: Long? = ended.endedAt, platform: Platform = Platform.DoorDash) =
        ended.copy(sessionId = id, endedAt = endedAt, platform = platform)

    private fun region(session: Session? = null, lastEnd: Long? = null) =
        PlatformRegion(Platform.DoorDash, if (session != null) Mode.Online else Mode.Offline, session, lastEnd)

    private fun detail(session: SessionRecord, vararg pays: Pair<Double?, String>, cash: Double? = null) = SessionDetail(
        session,
        pays.mapIndexed { i, (pay, basis) ->
            DeliveryRecord(i.toLong(), session.sessionId, session.platform, "job-$i", "task-$i", null, i.toLong(), pay,
                basis, null, null, null, null, null, cashTip = cash.takeIf { i == 0 })
        },
    )

    private fun target(region: PlatformRegion?, read: LatestShiftRead) =
        DisplayedShifts.target(Platform.DoorDash, region, read)

    @Test
    fun `a region holding a session is live even when a row read is loading`() {
        val live = Session("live", 100, runningEarnings = 12.5)
        val target = target(region(live), LatestShiftRead.Loading)
        assertEquals("live", target.sessionId)
        val shift = DisplayedShifts.resolve(target, ShiftDetailRead("live", null))
        assertTrue(shift.live)
        assertEquals(12.5, shift.earnings!!, 0.0)
    }

    @Test
    fun `live earnings are the larger of the running total and recorded pay`() {
        val live = Session("live", 100, runningEarnings = 12.5)
        val open = row("live", endedAt = null)
        val target = target(region(live), LatestShiftRead.Loaded(open))
        val ahead = DisplayedShifts.resolve(target, ShiftDetailRead("live", detail(open, 9.0 to PayBasis.DROP_SHARE,
            8.5 to PayBasis.OFFER_PAY, cash = 4.0)))
        assertEquals(17.5, ahead.earnings!!, 0.0)
        assertEquals(4.0, ahead.cashTips!!, 0.0)
        assertEquals(open, ahead.record)
        val behind = DisplayedShifts.resolve(target(region(live.copy(runningEarnings = 30.0)), LatestShiftRead.Loading),
            ShiftDetailRead("live", detail(open, 9.0 to PayBasis.DROP_SHARE)))
        assertEquals(30.0, behind.earnings!!, 0.0)
    }

    @Test
    fun `a detail read for another id lends neither figure nor cash`() {
        val target = target(region(Session("b", 100, runningEarnings = 6.0)), LatestShiftRead.Loading)
        val foreign = DisplayedShifts.resolve(target, ShiftDetailRead("a", detail(row("a"), 40.0 to PayBasis.DROP_SHARE, cash = 9.0)))
        assertNull(foreign.earnings)
        assertNull(foreign.cashTips)
        assertNull(foreign.record)
        assertEquals("b", foreign.sessionId)
    }

    @Test
    fun `nothing priced shows the em dash and a priced zero or an offer-pay row shows its figure`() {
        val noReport = row().copy(reportedEarnings = null)
        fun rested(vararg pays: Pair<Double?, String>) = DisplayedShifts.resolve(
            target(null, LatestShiftRead.Loaded(noReport)), ShiftDetailRead(noReport.sessionId, detail(noReport, *pays))).earnings
        assertNull(rested())
        assertNull(rested(null to PayBasis.DROP_SHARE, null to PayBasis.OFFER_PAY))
        assertEquals(0.0, rested(0.0 to PayBasis.DROP_SHARE)!!, 0.0)
        assertEquals(7.0, rested(7.0 to PayBasis.OFFER_PAY, null to PayBasis.DROP_SHARE)!!, 0.0)
        val open = row("live", endedAt = null)
        fun live(vararg pays: Pair<Double?, String>) = DisplayedShifts.resolve(
            target(region(Session("live", 100)), LatestShiftRead.Loading), ShiftDetailRead("live", detail(open, *pays))).earnings
        assertNull(live())
        assertNull(live(null to PayBasis.DROP_SHARE))
        assertEquals(0.0, live(0.0 to PayBasis.DROP_SHARE)!!, 0.0)
        assertEquals(7.0, live(7.0 to PayBasis.OFFER_PAY)!!, 0.0)
    }

    @Test
    fun `at rest the effective report wins and recorded pay is the fallback`() {
        val noReport = row().copy(reportedEarnings = null)
        val shift = DisplayedShifts.resolve(target(null, LatestShiftRead.Loaded(noReport)),
            ShiftDetailRead(ended.sessionId, detail(noReport, 18.0 to PayBasis.DROP_SHARE)))
        assertFalse(shift.live)
        assertEquals(18.0, shift.earnings!!, 0.0)
        assertEquals(noReport, shift.record)
    }

    @Test
    fun `a row read for another platform is still loading`() {
        val target = target(null, LatestShiftRead.Loaded(row(platform = Platform.Uber)))
        assertNull(target.sessionId)
        assertNull(DisplayedShifts.resolve(target, ShiftDetailRead(null, null)).earnings)
        assertNull(target(null, LatestShiftRead.Loading).sessionId)
    }

    @Test
    fun `an open row that is not in state is not shown, never an older shift`() {
        assertNull(target(region(lastEnd = 9_000_000), LatestShiftRead.Loaded(row("b", endedAt = null))).sessionId)
        assertNull(target(null, LatestShiftRead.Loaded(row("b", endedAt = null))).sessionId)
    }

    @Test
    fun `an ended row older than the region's last end belongs to an older shift`() {
        val anchor = ended.endedAt!!
        assertNull(target(region(lastEnd = anchor + 1), LatestShiftRead.Loaded(row())).sessionId)
        assertEquals(ended.sessionId, target(region(lastEnd = anchor), LatestShiftRead.Loaded(row())).sessionId)
        assertEquals(ended.sessionId, target(region(lastEnd = null), LatestShiftRead.Loaded(row())).sessionId)
        assertEquals(ended.sessionId, target(null, LatestShiftRead.Loaded(row())).sessionId)
        assertNull(target(null, LatestShiftRead.Loaded(null)).sessionId)
    }
}

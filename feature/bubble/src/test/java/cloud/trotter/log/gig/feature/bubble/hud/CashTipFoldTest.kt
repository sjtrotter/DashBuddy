package cloud.trotter.log.gig.feature.bubble.hud

import org.junit.Assert.*
import org.junit.Test

class CashTipFoldTest {
    @Test fun `latest adjustment per delivery sets the line time`() {
        val events = listOf(logAdjustment(at = 20_000), logAdjustment(at = 10_000), logAdjustment(11, 50_000))
        val tips = CashTipFold.fold(events, listOf(logRecord(), logRecord(11, task = "other")))
        assertEquals(listOf(20_000L, 50_000L), tips.map { it.at })
    }
    @Test fun `projection amount is authoritative`() {
        val tip = CashTipFold.fold(listOf(logAdjustment(amount = 9.0)), listOf(logRecord(cash = 3.0))).single()
        assertEquals(3.0, tip.amount, 0.0)
    }
    @Test fun `cleared cash tip yields no line`() {
        for (cash in listOf(null, 0.0)) assertTrue(CashTipFold.fold(listOf(logAdjustment()), listOf(logRecord(cash = cash))).isEmpty())
    }
    @Test fun `adjustment for an unknown delivery waits for the projection`() {
        assertTrue(CashTipFold.fold(listOf(logAdjustment()), emptyList()).isEmpty())
    }
    @Test fun `cash tip without an adjustment event uses the completion time`() {
        assertEquals(9_000L, CashTipFold.fold(emptyList(), listOf(logRecord())).single().at)
    }
    @Test fun `non cash adjustment does not move the tip time`() {
        assertEquals(9_000L, CashTipFold.fold(listOf(logAdjustment(amount = null)), listOf(logRecord())).single().at)
    }
}

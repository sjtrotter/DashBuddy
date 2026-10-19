package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import org.junit.Assert.*
import org.junit.Test

class LogMapperTest {
    private fun map(stack: CardStack = logStack(), data: LogData = LogData.Empty) =
        LogMapper.map(stack, data, emptyList(), logLive())
    private fun job(items: List<LogItem>) = items.filterIsInstance<JobCardModel>().single()

    @Test fun `accepted offer pickup and delivery of one job group into one card`() {
        val items = map()
        val card = job(items)
        assertEquals(1, items.size)
        assertEquals("Panera Bread", card.store)
        assertEquals(81, card.score)
        assertEquals(ScoreTone.GOOD, card.scoreTone)
        assertEquals(10.6, card.pay!!, 0.0)
        assertEquals(4.2, card.miles!!, 0.0)
        assertEquals(listOf(JobLine.Accepted::class, JobLine.PickedUp::class, JobLine.Delivered::class), card.lines.map { it::class })
        assertEquals(10.6, (card.lines.last() as JobLine.Delivered).paid!!, 0.0)
    }
    @Test fun `job lines run in time order inside the card`() {
        val stack = logStack().copy(completed = logStack().completed.reversed())
        val lines = job(map(stack)).lines
        assertEquals(lines.map { it.at }.sorted(), lines.map { it.at })
    }
    @Test fun `declined offer is a dim thin row with pay and miles`() {
        val row = map(CardStack(listOf(logOffer(outcome = AppEventType.OFFER_DECLINED)))).single() as OfferRow
        assertTrue(row.dimmed)
        assertEquals(10.6, row.pay!!, 0.0)
        assertEquals(4.2, row.miles!!, 0.0)
    }
    @Test fun `timed out offer is a dim thin row`() {
        val row = map(CardStack(listOf(logOffer(outcome = AppEventType.OFFER_TIMEOUT)))).single() as OfferRow
        assertTrue(row.dimmed)
        assertEquals(AppEventType.OFFER_TIMEOUT, row.outcome)
    }
    @Test fun `expense logged mid job sits above the live job card`() {
        val items = map(logStack(true), LogData(expenses = listOf(logExpense())))
        assertTrue(items.first() is ExpenseRow)
        assertTrue(items.last() is JobCardModel)
    }
    @Test fun `live job card is always last`() {
        val items = LogMapper.map(logStack(true), LogData(expenses = listOf(logExpense(at = 99_000))),
            listOf(logChat("Dash Paused!", at = 100_000)), logLive())
        assertTrue((items.last() as JobCardModel).live)
    }
    @Test fun `job whose latest event is its completion freezes at its completion time`() {
        val before = job(map())
        val after = job(map(data = LogData(expenses = listOf(logExpense(at = 50_000)))))
        assertEquals(9_000L, before.at)
        assertEquals(before, after)
    }
    @Test fun `two jobs in one dash are two cards ordered by their latest event`() {
        val second = listOf(logOffer("second", 20_000), logPickup("job2", "pickup2", 21_000),
            logDelivery("job2", "drop2", 23_000), logPost("job2", "drop2", 28_000))
        val items = map(CardStack(logStack().completed + second))
        assertEquals(listOf("job", "job2"), items.filterIsInstance<JobCardModel>().map { it.jobId })
        assertEquals(listOf(9_000L, 28_000L), items.map { it.at })
    }
    @Test fun `cash tip is a line inside its delivery's card`() {
        val items = map(data = LogData(deliveries = listOf(logRecord())))
        assertEquals(1, items.size)
        assertEquals(5.0, job(items).lines.filterIsInstance<JobLine.CashTip>().single().amount, 0.0)
    }
    @Test fun `cash tip added after completion moves the card to the tip time`() {
        val items = map(data = LogData(events = listOf(logAdjustment(at = 20_000)),
            deliveries = listOf(logRecord()), expenses = listOf(logExpense(at = 15_000))))
        assertTrue(items.first() is ExpenseRow)
        assertEquals(20_000L, job(items).at)
    }
    @Test fun `expense is never inside a job card`() {
        val baseline = job(map())
        val items = map(data = LogData(expenses = listOf(logExpense())))
        assertEquals(baseline, job(items))
        assertEquals(11.4, items.filterIsInstance<ExpenseRow>().single().amount, 0.0)
    }
    @Test fun `voided expense is dropped`() {
        assertTrue(map(CardStack.Empty, LogData(expenses = listOf(logExpense().copy(voidedAt = 7_000)))).isEmpty())
    }
    @Test fun `fuel fill-up is a quiet gas price row and never a minus`() {
        val row = map(CardStack.Empty, LogData(fuel = listOf(logFuel()))).single() as FuelRow
        assertEquals(3.19, row.pricePerGallon!!, 0.00001)
        assertTrue(row.total > 0)
    }
    @Test fun `fuel without gallons shows the total`() {
        val row = map(CardStack.Empty, LogData(fuel = listOf(logFuel(gallons = null)))).single() as FuelRow
        assertNull(row.pricePerGallon)
        assertEquals(31.9, row.total, 0.0)
    }
    @Test fun `Now line is last while the job is live and absent after completion`() {
        assertTrue(job(map(logStack(true))).lines.last() is JobLine.Now)
        assertFalse(job(map()).lines.any { it is JobLine.Now })
    }
    @Test fun `unassigned job ends with an unassigned line`() {
        val stack = CardStack(listOf(logOffer(), logPickup().copy(confirmedAt = null)))
        val card = job(map(stack, LogData(events = listOf(logUnassigned()))))
        assertEquals(JobLine.Unassigned(10_000), card.lines.last())
        assertEquals(10_000L, card.at)
    }
    @Test fun `pending cash tip shows pending and carries its undo target`() {
        val window = UndoWindow(UndoTarget.CashTip(null, "drop"), 5.0, 30_000)
        val items = LogMapper.map(logStack(true), LogData.Empty, emptyList(), logLive(),
            listOf(PendingCashTip("job", "drop", 5.0, 10_000)), window)
        val tip = job(items).lines.filterIsInstance<JobLine.CashTip>().single()
        assertTrue(tip.pending)
        assertEquals(window, tip.undo)
        assertTrue(job(items).lines.last() is JobLine.Now)
    }
    @Test fun `committed cash tip replaces the pending one for the same task`() {
        val window = UndoWindow(UndoTarget.CashTip(10, "drop"), 7.0, 30_000)
        val items = LogMapper.map(logStack(), LogData(deliveries = listOf(logRecord(cash = 7.0))), emptyList(), null,
            listOf(PendingCashTip("job", "drop", 5.0, 10_000)), window)
        val tip = job(items).lines.filterIsInstance<JobLine.CashTip>().single()
        assertFalse(tip.pending)
        assertEquals(7.0, tip.amount, 0.0)
        assertEquals(window, tip.undo)
    }
    @Test fun `rows sharing a timestamp keep a stable order`() {
        val data = LogData(expenses = listOf(logExpense(22, 9_000), logExpense(21, 9_000)), fuel = listOf(logFuel(at = 9_000)))
        val items = map(data = data)
        assertEquals(listOf("job:job", "expense:21", "expense:22", "fuel:30"), items.map { it.key })
        assertEquals(items, map(data = data.copy(expenses = data.expenses.reversed())))
    }
    @Test fun `item keys are unique`() {
        val items = LogMapper.map(logStack(), LogData(expenses = listOf(logExpense(), logExpense()),
            fuel = listOf(logFuel(), logFuel())), listOf(logChat("Dash Paused!"), logChat("Dash Paused!")), null)
        assertEquals(items.size, items.map { it.key }.toSet().size)
        assertEquals(4, items.size)
    }
    @Test fun `unknown dispatcher lines survive but customer personas do not`() {
        val items = LogMapper.map(CardStack.Empty, LogData.Empty,
            listOf(logChat("New dispatch status"), logChat("Store", ChatPersona.Customer(""), "private")), null)
        assertEquals(listOf("chat:line"), items.map { it.key })
    }
    @Test fun `voided fuel is dropped`() {
        assertTrue(map(CardStack.Empty, LogData(fuel = listOf(logFuel().copy(voidedAt = 7_000)))).isEmpty())
    }
    @Test fun `frozen and live snapshots of one task create one Now line`() {
        val stack = CardStack(listOf(logOffer(), logPickup(), logDelivery()), logDelivery().copy(phaseEndedAt = null))
        val card = job(map(stack))
        assertEquals(1, card.lines.filterIsInstance<JobLine.Now>().size)
        assertTrue(card.lines.filterIsInstance<JobLine.Delivered>().isEmpty())
    }
    @Test fun `stacked drop receipts remain separate lines inside one card`() {
        val stack = CardStack(logStack().completed + logPost(task = "second-drop", at = 11_000))
        val card = job(map(stack))
        assertEquals(listOf(9_000L, 11_000L), card.lines.filterIsInstance<JobLine.Delivered>().map { it.at })
        assertEquals(11_000L, card.at)
    }

}

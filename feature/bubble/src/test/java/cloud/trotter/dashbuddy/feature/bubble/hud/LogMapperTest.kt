package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.model.event.payload.TaskUnassignedPayload
import cloud.trotter.dashbuddy.feature.bubble.cards.FlowCardMapper
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import org.junit.Assert.*
import org.junit.Test

class LogMapperTest {
    private fun map(stack: CardStack = logStack(), data: LogData = LogData.Empty) =
        // The standard fixture's accepted offer has an explicit pickup link.
        LogMapper.map(stack, data.copy(events = listOf(logLink()) + data.events), emptyList(), logLive())
    private fun job(items: List<LogItem>) = items.filterIsInstance<JobCardModel>().single()

    @Test fun `a figure that prefixes another is styled on its own token`() {
        val rates = logTextParts("%1\$s/hr %2\$s/mi", logFigure("\$15"), logFigure("\$1"))
        val parts = logTextParts("%1\$s, %2\$s", listOf(LogTextPart("accepted")), rates)
        val numberStyle = SpanStyle(fontWeight = FontWeight.SemiBold)
        val text = buildLogText(parts, numberStyle)
        assertEquals("accepted, \$15/hr \$1/mi", text.text)
        assertEquals(listOf(10 to 13, 17 to 19), text.spanStyles.map { it.start to it.end })
        assertEquals(listOf(numberStyle, numberStyle), text.spanStyles.map { it.item })
    }

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
    @Test fun `an unlinked accept is its own thin row`() {
        val items = LogMapper.map(logStack(), LogData.Empty, emptyList(), null)
        val row = items.filterIsInstance<OfferRow>().single()
        assertEquals(logOffer().id, row.key)
        assertEquals(AppEventType.OFFER_ACCEPTED, row.outcome)
        assertFalse(row.dimmed)
        assertEquals("Panera Bread", row.store)
        assertEquals(10.6, row.pay!!, 0.0)
        assertEquals(4.2, row.miles!!, 0.0)
        assertTrue(job(items).lines.none { it is JobLine.Accepted })
    }
    @Test fun `live links show the accepted line before pickup confirmation`() {
        val stack = CardStack(listOf(logOffer()), logPickup().copy(phaseEndedAt = null, confirmedAt = null))
        val items = LogMapper.map(stack, LogData.Empty, emptyList(), logLive(),
            liveLinks = mapOf("job" to setOf("offer")))
        assertEquals(1, items.size)
        assertEquals(JobLine.Accepted(1_000, 16.0, 1.93, false), job(items).lines.first())
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
        val items = map(CardStack(logStack().completed + second), LogData(events = listOf(
            logLink(), logLink("job2", listOf("second"), 21_000))))
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
    @Test fun `expense mapping preserves category and note for every category`() {
        cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory.entries.forEach { category ->
            val record = logExpense().copy(category = category, note = "wipers")
            val row = map(CardStack.Empty, LogData(expenses = listOf(record))).single() as ExpenseRow
            assertEquals(category, row.category)
            assertEquals("wipers", row.note)
        }
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
        val items = LogMapper.map(logStack(), LogData(events = listOf(logLink()), expenses = listOf(logExpense(), logExpense()),
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
    @Test fun `a dispatcher row carries the text the guard checked`() {
        val msg = logChat(" <b>Panera     Bread</b>&nbsp;&amp;\tstatus ")
        val row = LogMapper.map(CardStack.Empty, LogData.Empty, listOf(msg), null).single() as DispatcherRow
        assertEquals("Panera Bread & status", row.text)
        assertEquals(LogChatFilter.kept(msg), row.text)
    }
    @Test fun `an arrived but not completed drop has no delivered line`() {
        val events = stackedEvents().take(4)
        val stack = CardStack.of(FlowCardMapper.fold(events), null)
        assertEquals(7_000L, stack.completed.filterIsInstance<FlowCardSnapshot.Delivery>().single().phaseEndedAt)
        val card = job(LogMapper.map(stack, LogData(events = events), emptyList(), null))
        assertTrue(card.lines.filterIsInstance<JobLine.Delivered>().isEmpty())
    }
    @Test fun `a delivered line lands at the completion event`() {
        val events = stackedEvents().take(6)
        val stack = CardStack.of(FlowCardMapper.fold(events), null)
        val card = job(LogMapper.map(stack, LogData(events = events,
            deliveries = listOf(logRecord(task = "drop1", cash = null).copy(realizedPay = 6.25))), emptyList(), null))
        assertEquals(listOf(JobLine.Delivered(8_500, 6.25)), card.lines.filterIsInstance<JobLine.Delivered>())
        assertEquals(8_500L, card.at)
    }
    @Test fun `delivery payload completion time wins and missing time uses the event`() {
        val original = stackedEvents().take(6)
        listOf(8_250L, null).forEach { completedAt ->
            val event = original.last()
            val events = original.dropLast(1) + event.copy(
                payload = (event.payload as DeliveryPayload).copy(completedAt = completedAt))
            val stack = CardStack.of(FlowCardMapper.fold(events), null)
            assertEquals(completedAt ?: event.occurredAt,
                job(map(stack, LogData(events = events))).lines.filterIsInstance<JobLine.Delivered>().single().at)
        }
    }
    @Test fun `a stacked job shows one delivered line per drop through the production fold`() {
        val events = stackedEvents()
        val stack = CardStack.of(FlowCardMapper.fold(events), null)
        assertEquals(2, stack.completed.filterIsInstance<FlowCardSnapshot.Delivery>().size)
        assertEquals(1, stack.completed.filterIsInstance<FlowCardSnapshot.PostTask>().size)
        val records = listOf(
            logRecord(task = "drop1", cash = 3.0).copy(realizedPay = 6.25, tip = 2.0, basePay = 4.25),
            logRecord(seq = 11, task = "drop2", cash = 4.0).copy(realizedPay = 9.75, tip = 5.0, basePay = 4.75),
        )
        val card = job(map(stack, LogData(events = events, deliveries = records)))
        assertEquals(listOf(JobLine.Delivered(8_500, 6.25), JobLine.Delivered(12_500, 9.75)),
            card.lines.filterIsInstance<JobLine.Delivered>())
        assertEquals(12_500L, card.at)
    }
    @Test fun `a completion without a projection row shows no amount`() {
        // Both drops have started, but only the first has completed and the projection has not folded.
        val events = stackedEvents().take(7)
        val stack = CardStack.of(FlowCardMapper.fold(events), null)
        assertEquals(16.0, stack.completed.filterIsInstance<FlowCardSnapshot.PostTask>().single().totalPay!!, 0.0)
        assertEquals(listOf(JobLine.Delivered(8_500, null)),
            job(map(stack, LogData(events = events))).lines.filterIsInstance<JobLine.Delivered>())
    }
    @Test fun `a double fired completion is one delivered line`() {
        val original = stackedEvents()
        val completion = original[5]
        val duplicate = completion.copy(occurredAt = 8_750,
            payload = (completion.payload as DeliveryPayload).copy(completedAt = 8_750))
        // Put the later fire first to prove that input order does not choose the retained event.
        val events = listOf(duplicate) + original
        val stack = CardStack.of(FlowCardMapper.fold(events), null)
        val records = listOf(logRecord(task = "drop1", cash = null).copy(realizedPay = 6.25),
            logRecord(seq = 11, task = "drop2", cash = null).copy(realizedPay = 9.75))
        assertEquals(listOf(JobLine.Delivered(8_500, 6.25), JobLine.Delivered(12_500, 9.75)),
            job(map(stack, LogData(events = events, deliveries = records))).lines.filterIsInstance<JobLine.Delivered>())
    }
    @Test fun `a projection with unknown pay does not fall back to the receipt`() {
        val events = stackedEvents().take(6)
        val stack = CardStack.of(FlowCardMapper.fold(events), null)
        val data = LogData(events = events, deliveries = listOf(logRecord(task = "drop1").copy(realizedPay = null)))
        assertNull(job(map(stack, data)).lines.filterIsInstance<JobLine.Delivered>().single().paid)
    }
    @Test fun `a receipt without a matching delivery still shows a delivered line`() {
        val card = job(map(CardStack(listOf(logPost()))))
        assertEquals(listOf(JobLine.Delivered(9_000, 10.6)), card.lines)
    }
    @Test fun `receipt only recovery prefers the projection's pay`() {
        val stack = CardStack(listOf(logPost()))
        val data = LogData(deliveries = listOf(logRecord(cash = null).copy(realizedPay = 12.6)))
        assertEquals(listOf(JobLine.Delivered(9_000, 12.6)), job(map(stack, data)).lines)
        assertEquals(listOf(JobLine.Delivered(9_000, 10.6)), job(map(stack)).lines)
        assertNull(job(map(stack, data.copy(deliveries = listOf(logRecord(cash = null).copy(realizedPay = null)))))
            .lines.filterIsInstance<JobLine.Delivered>().single().paid)
    }
    @Test fun `receipt only recovery never prices a multi drop job`() {
        val events = stackedEvents().filter { it.type != AppEventType.DELIVERY_COMPLETED }
        val stack = CardStack(listOf(logPost().copy(totalPay = 16.0)))
        assertEquals(listOf(JobLine.Delivered(9_000, null)),
            job(map(stack, LogData(events = events))).lines)
    }
    @Test fun `receipt only recovery can price one distinct delivery task across its events`() {
        val events = stackedEvents().take(5)
        val stack = CardStack(listOf(logPost(task = "drop1").copy(totalPay = 16.0)))
        assertEquals(listOf(JobLine.Delivered(9_000, 16.0)),
            job(map(stack, LogData(events = events))).lines)
    }
    @Test fun `a receipt for another task adds no recovery line when the job has a completion event`() {
        val events = stackedEvents().take(6)
        val folded = CardStack.of(FlowCardMapper.fold(events), null)
        val stack = folded.copy(completed = folded.completed + logPost(task = "recovered"))
        assertEquals(listOf(JobLine.Delivered(8_500, null)),
            job(map(stack, LogData(events = events))).lines.filterIsInstance<JobLine.Delivered>())
    }
    @Test fun `an unassigned delivery never shows a delivered line`() {
        val event = AppEvent(AppEventType.TASK_UNASSIGNED, 8_000, "dash",
            TaskUnassignedPayload("job", "drop1", TaskPhase.DROPOFF, startedAt = 4_000, unassignedAt = 8_000))
        val events = stackedEvents().take(6) + event
        val stack = CardStack.of(FlowCardMapper.fold(events), null)
        val card = job(map(stack, LogData(events = events)))
        assertTrue(card.lines.filterIsInstance<JobLine.Delivered>().isEmpty())
        assertEquals(JobLine.Unassigned(8_000), card.lines.last())
        val receiptOnly = CardStack(listOf(logPost(task = "drop1")))
        assertTrue(job(map(receiptOnly, LogData(events = listOf(event))))
            .lines.filterIsInstance<JobLine.Delivered>().isEmpty())
    }

    private fun stackedEvents(): List<AppEvent> {
        val first = DeliveryPayload("job", "drop1", phaseStartedAt = 4_000, totalPay = 16.0,
            dropRealizedPay = 100.0)
        val second = first.copy(taskId = "drop2", phaseStartedAt = 9_000, dropRealizedPay = 200.0)
        return listOf(
            logAccepted(), logLink(),
            AppEvent(AppEventType.DELIVERY_NAV_STARTED, 4_000, "dash", first),
            AppEvent(AppEventType.DELIVERY_ARRIVED, 7_000, "dash", first.copy(arrivedAt = 7_000)),
            AppEvent(AppEventType.DELIVERY_CONFIRMED, 8_000, "dash", first.copy(arrivedAt = 7_000)),
            AppEvent(AppEventType.DELIVERY_COMPLETED, 8_500, "dash", first.copy(completedAt = 8_500)),
            AppEvent(AppEventType.DELIVERY_NAV_STARTED, 9_000, "dash", second),
            AppEvent(AppEventType.DELIVERY_ARRIVED, 11_000, "dash", second.copy(arrivedAt = 11_000)),
            AppEvent(AppEventType.DELIVERY_CONFIRMED, 12_000, "dash", second.copy(arrivedAt = 11_000)),
            AppEvent(AppEventType.DELIVERY_COMPLETED, 12_500, "dash", second.copy(completedAt = 12_500)),
        )
    }
}

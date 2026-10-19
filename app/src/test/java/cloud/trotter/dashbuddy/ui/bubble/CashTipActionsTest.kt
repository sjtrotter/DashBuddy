package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.analytics.CorrectionRepository
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryAdjustmentPayload
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.feature.bubble.hud.cashtip.CashTipSheetModel
import cloud.trotter.dashbuddy.feature.bubble.hud.cashtip.CashTipSign
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class CashTipActionsTest {
    // adjustDelivery returns the appended sequence id; the mock answers a fresh one per append.
    private val appended = java.util.concurrent.atomic.AtomicLong()
    private val events: AppEventRepo = mock { on { appendUserEvent(any(), anyOrNull()) } doAnswer { appended.incrementAndGet() } }
    private val clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)
    private fun row(cash: Double? = null) = DeliveryRecord(9, "dash", Platform.DoorDash, "job", "task", "Panera Bread",
        50, 8.0, "OFFER_PAY", 6.0, null, null, null, null, cashTip = cash)
    private fun actions(scope: CoroutineScope) = CashTipActions(scope, CorrectionRepository(events), clock)
    private suspend fun adjustments(): List<DeliveryAdjustmentPayload> {
        val captor = argumentCaptor<AppEvent>()
        verify(events, atLeastOnce()).appendUserEvent(captor.capture(), anyOrNull())
        assertTrue(captor.allValues.all { it.type == AppEventType.DELIVERY_ADJUSTMENT })
        return captor.allValues.map { it.payload as DeliveryAdjustmentPayload }
    }

    @Test fun `commit appends previous plus entered and exposes durable id`() = runTest {
        whenever(events.appendUserEvent(any(), anyOrNull())).thenReturn(71)
        val actions = actions(backgroundScope)
        actions.commit(row(2.0), 700, entryId = 12)
        runCurrent()
        assertEquals(9.0, adjustments().single().newCashTip!!, 0.0)
        assertEquals(9L, adjustments().single().targetEventSequenceId)
        val model = actions.committed.replayCache.single()
        assertEquals(71L, model.strip.committedSequenceId)
        assertEquals(10_100L, model.strip.deadline)
        assertEquals(700L, model.strip.amountCents)
        assertEquals(900L, model.expectedCashTipCents)
        assertEquals(12L, model.entryId)
        assertFalse(actions.failed.value)
    }
    @Test fun `undo restores previous cash and suppresses double tap synchronously`() = runTest {
        val actions = actions(backgroundScope)
        actions.commit(row(2.0), 700); runCurrent()
        val model = actions.undo.value!!
        doSuspendableAnswer { delay(1); 72L }.whenever(events).appendUserEvent(any(), anyOrNull())
        actions.undo(model); actions.undo(model)
        assertTrue(actions.undo.value!!.strip.busy)
        advanceTimeBy(1); runCurrent()
        assertNull(actions.undo.value)
        assertEquals(listOf(9.0, 2.0), adjustments().map { it.newCashTip })
    }
    @Test fun `undo without previous cash writes zero`() = runTest {
        val actions = actions(backgroundScope)
        actions.commit(row(), 700); runCurrent()
        actions.undo(actions.undo.value!!); runCurrent()
        assertEquals(listOf(7.0, 0.0), adjustments().map { it.newCashTip })
    }
    @Test fun `minus removes only entered cash without cash locked guard`() = runTest {
        val actions = actions(backgroundScope)
        actions.commit(row(9.0), -200); runCurrent()
        assertEquals(7.0, adjustments().single().newCashTip!!, 0.0)
        actions.undo(actions.undo.value!!); runCurrent()
        assertEquals(9.0, adjustments().last().newCashTip!!, 0.0)
    }
    @Test fun `zero and a removal below zero append nothing`() = runTest {
        val actions = actions(backgroundScope)
        actions.commit(row(), 0); actions.commit(row(1.0), -200); runCurrent()
        verifyNoInteractions(events)
    }
    @Test fun `failed write marks failure emits nothing and retry succeeds`() = runTest {
        whenever(events.appendUserEvent(any(), anyOrNull())).thenThrow(IllegalStateException("private amount and store"))
            .thenReturn(71)
        val actions = actions(backgroundScope)
        actions.commit(row(), 700); runCurrent()
        assertTrue(actions.failed.value)
        assertFalse(actions.busy.value)
        assertTrue(actions.committed.replayCache.isEmpty())
        assertTrue(actions.pending.value.isEmpty())
        assertNull(actions.undo.value)
        actions.commit(row(), 700); runCurrent()
        assertFalse(actions.failed.value)
        assertEquals(71L, actions.committed.replayCache.single().strip.committedSequenceId)
    }
    @Test fun `late collector receives one pending commit until consumed`() = runTest {
        val actions = actions(backgroundScope)
        actions.commit(row(), 700); runCurrent()
        val seen = mutableListOf<Long>()
        val collector = backgroundScope.launch {
            actions.committed.collect { seen += it.deliveryId; actions.committedHandled() }
        }
        runCurrent()
        assertEquals(listOf(9L), seen)
        collector.cancel()
        backgroundScope.launch { actions.committed.collect { seen += it.deliveryId } }
        runCurrent()
        assertEquals(listOf(9L), seen)
        assertTrue(actions.committed.replayCache.isEmpty())
    }
    @Test fun `projection lag retains committed value for another tip`() = runTest {
        val actions = actions(backgroundScope)
        actions.commit(row(), 500); runCurrent()
        actions.acknowledgeThrough(0)
        assertEquals(500L, actions.previous(row()))
        actions.commit(row(), 200); runCurrent()
        assertEquals(listOf(5.0, 7.0), adjustments().map { it.newCashTip })
        actions.acknowledgeThrough(1)
        assertTrue(actions.pending.value.isNotEmpty())
        actions.acknowledgeThrough(2)
        assertTrue(actions.pending.value.isEmpty())
        assertNotNull(actions.undo.value)
    }
    @Test fun `busy suppresses a repeated commit and cancellation still lands the append`() = runTest {
        doSuspendableAnswer { delay(1_000); 71L }.whenever(events).appendUserEvent(any(), anyOrNull())
        val scope = CoroutineScope(coroutineContext + Job())
        val actions = actions(scope)
        actions.commit(row(), 700)
        assertTrue(actions.busy.value)
        actions.commit(row(), 700)
        scope.cancel()
        advanceUntilIdle()
        assertEquals(7.0, adjustments().single().newCashTip!!, 0.0)
        assertFalse(actions.busy.value)
        assertNotNull(actions.undo.value)
    }
    @Test fun `a failed undo keeps the strip and retries`() = runTest {
        whenever(events.appendUserEvent(any(), anyOrNull())).thenReturn(71)
            .thenThrow(IllegalStateException()).thenReturn(72)
        val actions = actions(backgroundScope)
        actions.commit(row(), 700); runCurrent()
        actions.committedHandled()
        actions.undo(actions.undo.value!!); runCurrent()
        val failedUndo = actions.undo.value!!
        assertTrue(failedUndo.strip.failed)
        assertFalse(failedUndo.strip.busy)
        assertTrue(actions.failed.value)
        assertTrue(actions.committed.replayCache.isEmpty())
        assertEquals(700L, actions.previous(row()))
        actions.reopen()
        assertTrue(actions.failed.value)
        assertSame(failedUndo, actions.undo.value)
        actions.undoExpired(failedUndo)
        assertSame(failedUndo, actions.undo.value)
        actions.undo(failedUndo); actions.undo(failedUndo); runCurrent()
        assertNull(actions.undo.value)
        assertFalse(actions.failed.value)
        assertEquals(listOf(7.0, 0.0, 0.0), adjustments().map { it.newCashTip })
        assertEquals(setOf(72L), actions.pending.value.keys)
        assertEquals(0L, actions.previous(row()))
    }
    @Test fun `existing cash is rounded to cents before append and undo`() = runTest {
        val actions = actions(backgroundScope)
        actions.commit(row(2.346), 700); runCurrent()
        assertEquals(9.35, adjustments().single().newCashTip!!, 0.0)
        actions.undo(actions.undo.value!!); runCurrent()
        assertEquals(2.35, adjustments().last().newCashTip!!, 0.0)
    }
    @Test fun `another undo failure re-arms retry even after the original deadline`() = runTest {
        var now = 100L
        val tickingClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(now)
        }
        whenever(events.appendUserEvent(any(), anyOrNull())).thenReturn(71)
            .thenThrow(IllegalStateException()).thenThrow(IllegalStateException()).thenReturn(72)
        val actions = CashTipActions(backgroundScope, CorrectionRepository(events), tickingClock)
        actions.commit(row(), 700); runCurrent()
        actions.undo(actions.undo.value!!); runCurrent()
        val firstFailure = actions.undo.value!!
        now = firstFailure.strip.deadline + 1
        actions.undo(firstFailure); runCurrent()
        val secondFailure = actions.undo.value!!
        assertTrue(secondFailure.strip.failed)
        assertNotEquals(firstFailure.strip, secondFailure.strip)
        actions.undo(secondFailure); runCurrent()
        assertNull(actions.undo.value)
        assertFalse(actions.failed.value)
        assertEquals(0.0, adjustments().last().newCashTip!!, 0.0)
    }
    @Test fun `add undo add writes two dollars`() = runTest {
        whenever(events.appendUserEvent(any(), anyOrNull())).thenReturn(10, 11, 12)
        val actions = actions(backgroundScope)
        actions.commit(row(), 500); runCurrent()
        assertEquals(setOf(10L), actions.pending.value.keys)
        assertEquals(500L, actions.previous(row()))
        actions.undo(actions.undo.value!!); runCurrent()
        val reversal = actions.pending.value.values.single()
        assertEquals(setOf(11L), actions.pending.value.keys)
        // Even the old pre-add zero row cannot acknowledge the reversal.
        assertEquals(0L, actions.previous(row()))
        actions.acknowledgeThrough(10)
        assertEquals(0L, actions.previous(row(5.0)))
        assertEquals(reversal, actions.pending.value.values.single())
        actions.commit(row(5.0), 200); runCurrent()
        assertEquals(listOf(5.0, 0.0, 2.0), adjustments().map { it.newCashTip })
        actions.acknowledgeThrough(11)
        assertEquals(setOf(12L), actions.pending.value.keys)
        assertEquals(200L, actions.previous(row(5.0)))
        actions.acknowledgeThrough(12)
        assertTrue(actions.pending.value.isEmpty())
    }
    @Test fun `the undo clears at its watermark before another add`() = runTest {
        whenever(events.appendUserEvent(any(), anyOrNull())).thenReturn(10, 11)
        val actions = actions(backgroundScope)
        actions.commit(row(), 500); runCurrent()
        actions.undo(actions.undo.value!!); runCurrent()
        actions.acknowledgeThrough(10)
        assertEquals(setOf(11L), actions.pending.value.keys)
        actions.acknowledgeThrough(11)
        assertTrue(actions.pending.value.isEmpty())
    }
    @Test fun `ten and seventy cents remove as eighty cents`() = runTest {
        val actions = actions(backgroundScope)
        actions.commit(row(), 10); runCurrent()
        actions.commit(row(), 70); runCurrent()
        assertEquals(80L, actions.previous(row()))
        assertEquals(0.80, adjustments().last().newCashTip!!, 0.0)
        val model = CashTipSheetModel(listOf(row(0.10 + 0.70)), 9L, totalCents = 80,
            sign = CashTipSign.MINUS)
        assertTrue(model.canCommit)
        assertTrue(model.copy(deliveries = listOf(row()), pendingCashTipCents = mapOf(9L to 80L)).canCommit)
        assertFalse(model.copy(totalCents = 81).canCommit)
        actions.commit(row(), -80); runCurrent()
        assertEquals(0.0, adjustments().last().newCashTip!!, 0.0)
        actions.acknowledgeThrough(appended.get())
        actions.commit(row(2.0), 80); runCurrent()
        assertEquals(2.80, adjustments().last().newCashTip!!, 0.0)
    }
    @Test fun `a watermark short of the commit leaves the entry pending`() = runTest {
        whenever(events.appendUserEvent(any(), anyOrNull())).thenReturn(71)
        val actions = actions(backgroundScope)
        actions.commit(row(), 700); runCurrent()
        actions.acknowledgeThrough(70)
        assertEquals(setOf(71L), actions.pending.value.keys)
        assertEquals(700L, actions.pending.value.values.single().expectedCashTipCents)
        assertEquals(700L, actions.previous(row(2.0)))
        actions.acknowledgeThrough(71)
        assertTrue(actions.pending.value.isEmpty())
        assertEquals(200L, actions.previous(row(2.0)))
    }
    @Test fun `the foot shows the logged amount with a prior tip`() = runTest {
        val actions = actions(backgroundScope)
        actions.commit(row(2.0), 700); runCurrent()
        val pending = actions.pending.value.values.single()
        assertEquals(900L, pending.expectedCashTipCents)
        assertEquals(700L, CashTipPending.footAmountCents(pending))
        assertNull(CashTipPending.footAmountCents(null))
    }
    @Test fun `failure logs only the exception class under CashTip`() = runTest {
        val tree = cloud.trotter.dashbuddy.test.util.RecordingTree()
        timber.log.Timber.plant(tree)
        try {
            whenever(events.appendUserEvent(any(), anyOrNull())).thenThrow(IllegalStateException("Panera Bread $7.00"))
            val actions = actions(backgroundScope)
            actions.commit(row(), 700); runCurrent()
            val warning = tree.records.single { it.tag == "CashTip" }
            assertEquals(android.util.Log.WARN, warning.priority)
            assertEquals("IllegalStateException", warning.message)
        } finally { timber.log.Timber.uproot(tree) }
    }
    @Test fun `undo deadline is enforced even if an old callback is retained`() = runTest {
        var now = 100L
        val tickingClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(now)
        }
        val actions = CashTipActions(backgroundScope, CorrectionRepository(events), tickingClock)
        actions.commit(row(), 700); runCurrent()
        val model = actions.undo.value!!
        now = model.strip.deadline
        actions.undo(model); runCurrent()
        assertEquals(1, adjustments().size)
    }

    @Test fun `expiring a strip keeps projection pending and cannot clear a newer strip`() = runTest {
        val actions = actions(backgroundScope)
        actions.commit(row(), 500); runCurrent()
        val first = actions.undo.value!!
        actions.commit(row(), 200); runCurrent()
        val second = actions.undo.value!!
        actions.undoExpired(first)
        assertSame(second, actions.undo.value)
        actions.undoExpired(second)
        assertNull(actions.undo.value)
        assertEquals(700L, actions.pending.value.values.single().expectedCashTipCents)
    }

}

package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.analytics.CorrectionRepository
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryAdjustmentPayload
import cloud.trotter.dashbuddy.domain.state.Platform
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
        assertEquals(12L, model.entryId)
        assertFalse(actions.failed.value)
    }
    @Test fun `undo restores previous cash and consumes double tap synchronously`() = runTest {
        val actions = actions(backgroundScope)
        actions.commit(row(2.0), 700); runCurrent()
        val model = actions.undo.value!!
        actions.undo(model); actions.undo(model)
        assertNull(actions.undo.value)
        runCurrent()
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
        actions.projected(listOf(row()))
        assertEquals(5.0, actions.previous(row()), 0.0)
        actions.commit(row(), 200); runCurrent()
        assertEquals(listOf(5.0, 7.0), adjustments().map { it.newCashTip })
        actions.projected(listOf(row(5.0)))
        assertTrue(actions.pending.value.isNotEmpty())
        actions.projected(listOf(row(7.0)))
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
    @Test fun `failed undo clears strip at once and reports failure`() = runTest {
        whenever(events.appendUserEvent(any(), anyOrNull())).thenReturn(71).thenThrow(IllegalStateException())
        val actions = actions(backgroundScope)
        actions.commit(row(), 700); runCurrent()
        actions.committedHandled()
        actions.undo(actions.undo.value!!); runCurrent()
        assertNull(actions.undo.value)
        assertTrue(actions.failed.value)
        assertTrue(actions.committed.replayCache.isEmpty())
    }
    @Test fun `existing cash value is preserved rather than rounded on append`() = runTest {
        val actions = actions(backgroundScope)
        actions.commit(row(2.345), 700); runCurrent()
        assertEquals(9.345, adjustments().single().newCashTip!!, 0.000001)
        actions.undo(actions.undo.value!!); runCurrent()
        assertEquals(2.345, adjustments().last().newCashTip!!, 0.0)
    }
    @Test fun `a missing row does not clear pending and another target cannot acknowledge it`() = runTest {
        val actions = actions(backgroundScope)
        actions.commit(row(), 700); runCurrent()
        actions.projected(emptyList())
        actions.projected(listOf(row(7.0).copy(eventSequenceId = 10)))
        assertEquals(7.0, actions.pending.value[9L]!!.expectedCashTip, 0.0)
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
        assertEquals(7.0, actions.pending.value[9L]!!.expectedCashTip, 0.0)
    }

}

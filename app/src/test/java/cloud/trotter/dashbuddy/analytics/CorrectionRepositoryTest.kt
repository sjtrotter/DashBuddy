package cloud.trotter.dashbuddy.analytics

import cloud.trotter.dashbuddy.core.data.analytics.CorrectionRepository
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * #705 F4d — the write-side [CorrectionRepository] `require` boundaries (previously ZERO coverage).
 * Every money/miles field must be **finite** (a non-finite value would reach `AppEventCodec` and throw
 * a `SerializationException` deep in the append), pay finite > 0, tip/cash/miles finite ≥ 0, and
 * `adjustDelivery` needs at least one changed field or a note. A rejected input must throw
 * `IllegalArgumentException` BEFORE any event is appended; a valid one appends exactly once.
 */
class CorrectionRepositoryTest {

    private val appEventRepo: AppEventRepo = mock()
    private val repo = CorrectionRepository(appEventRepo)

    private fun rejects(block: suspend CorrectionRepository.() -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.block() } }
    }

    // ── addManualDelivery ───────────────────────────────────────────────

    @Test fun `addManualDelivery rejects a zero pay`() = rejects { manual(pay = 0.0) }
    @Test fun `addManualDelivery rejects a negative pay`() = rejects { manual(pay = -1.0) }
    @Test fun `addManualDelivery rejects a NaN pay`() = rejects { manual(pay = Double.NaN) }
    @Test fun `addManualDelivery rejects an infinite pay`() = rejects { manual(pay = Double.POSITIVE_INFINITY) }
    @Test fun `addManualDelivery rejects a negative tip`() = rejects { manual(pay = 5.0, tip = -0.01) }
    @Test fun `addManualDelivery rejects an infinite cash tip`() = rejects { manual(pay = 5.0, cashTip = Double.POSITIVE_INFINITY) }
    @Test fun `addManualDelivery rejects a NaN cash tip`() = rejects { manual(pay = 5.0, cashTip = Double.NaN) }
    @Test fun `addManualDelivery rejects a negative miles`() = rejects { manual(pay = 5.0, miles = -0.5) }
    @Test fun `addManualDelivery rejects an infinite miles`() = rejects { manual(pay = 5.0, miles = Double.NEGATIVE_INFINITY) }

    @Test
    fun `addManualDelivery accepts a finite positive pay with zero and null optionals`() = runTest {
        repo.manual(pay = 5.0, tip = 0.0, cashTip = 0.0, miles = 0.0)
        repo.manual(pay = 5.0, tip = null, cashTip = null, miles = null)
        verify(appEventRepo, times(2)).appendUserEvent(any(), anyOrNull())
    }

    // ── adjustDelivery ──────────────────────────────────────────────────

    @Test
    fun `adjustDelivery rejects an empty edit with no note`() = rejects { adjustDelivery(targetEventSequenceId = 1L, sessionId = "S1") }

    @Test fun `adjustDelivery rejects a zero newPay`() = rejects { adjust(newPay = 0.0) }
    @Test fun `adjustDelivery rejects a negative newPay`() = rejects { adjust(newPay = -1.0) }
    @Test fun `adjustDelivery rejects a NaN newPay`() = rejects { adjust(newPay = Double.NaN) }
    @Test fun `adjustDelivery rejects an infinite newPay`() = rejects { adjust(newPay = Double.POSITIVE_INFINITY) }
    @Test fun `adjustDelivery rejects a negative newTip`() = rejects { adjust(newTip = -1.0) }
    @Test fun `adjustDelivery rejects an infinite newCashTip`() = rejects { adjust(newCashTip = Double.NEGATIVE_INFINITY) }
    @Test fun `adjustDelivery rejects a negative newMiles`() = rejects { adjust(newMiles = -0.5) }
    @Test fun `adjustDelivery rejects a NaN newMiles`() = rejects { adjust(newMiles = Double.NaN) }

    @Test
    fun `adjustDelivery accepts a note-only edit and a finite positive pay edit`() = runTest {
        repo.adjustDelivery(targetEventSequenceId = 1L, sessionId = "S1", note = "just a note")
        repo.adjust(newPay = 12.0, newTip = 0.0, newCashTip = 0.0, newMiles = 0.0)
        verify(appEventRepo, times(2)).appendUserEvent(any(), anyOrNull())
    }

    @Test
    fun `a rejected addManualDelivery appends nothing`() = runTest {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.manual(pay = 0.0) } }
        verify(appEventRepo, never()).appendUserEvent(any(), anyOrNull())
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private suspend fun CorrectionRepository.manual(
        pay: Double,
        tip: Double? = null,
        cashTip: Double? = null,
        miles: Double? = null,
    ) = addManualDelivery(
        sessionId = "S1", storeName = "StoreX", pay = pay, tip = tip, cashTip = cashTip,
        completedAt = 1_000L, miles = miles, note = null,
    )

    private suspend fun CorrectionRepository.adjust(
        newPay: Double? = null,
        newTip: Double? = null,
        newCashTip: Double? = null,
        newMiles: Double? = null,
    ) = adjustDelivery(
        targetEventSequenceId = 1L, sessionId = "S1",
        newPay = newPay, newTip = newTip, newCashTip = newCashTip, newMiles = newMiles,
    )
}

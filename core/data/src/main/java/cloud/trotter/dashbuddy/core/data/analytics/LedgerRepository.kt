package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.database.analytics.LedgerDao
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import cloud.trotter.dashbuddy.domain.analytics.ExpenseRecord
import cloud.trotter.dashbuddy.domain.analytics.FuelRecord
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.ExpenseLoggedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.FuelFillupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.LedgerEntryVoidedPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** #1333: append-only writes and projected reads for the dasher's ledger. */
@Singleton
class LedgerRepository @Inject constructor(
    private val appEventRepo: AppEventRepo,
    private val ledgerDao: LedgerDao,
) {
    suspend fun logExpense(
        sessionId: String?,
        category: ExpenseCategory,
        amount: Double,
        note: String?,
        occurredAt: Long,
    ) {
        require(amount.isFinite() && amount > 0) { "amount must be finite and positive" }
        appEventRepo.appendUserEvent(
            AppEvent(
                type = AppEventType.EXPENSE_LOGGED,
                occurredAt = occurredAt,
                sessionId = sessionId,
                payload = ExpenseLoggedPayload(sessionId, category, amount, note),
            ),
            metadataJson = null,
        )
    }

    suspend fun logFuel(
        sessionId: String?,
        totalAmount: Double,
        gallons: Double?,
        odometerMiles: Double?,
        occurredAt: Long,
    ) {
        require(totalAmount.isFinite() && totalAmount > 0) { "totalAmount must be finite and positive" }
        require(gallons == null || (gallons.isFinite() && gallons > 0)) { "gallons must be finite and positive" }
        require(odometerMiles == null || (odometerMiles.isFinite() && odometerMiles >= 0)) {
            "odometerMiles must be finite and non-negative"
        }
        appEventRepo.appendUserEvent(
            AppEvent(
                type = AppEventType.FUEL_FILLUP,
                occurredAt = occurredAt,
                sessionId = sessionId,
                payload = FuelFillupPayload(sessionId, totalAmount, gallons, odometerMiles),
            ),
            metadataJson = null,
        )
    }

    suspend fun void(targetEventSequenceId: Long, occurredAt: Long) {
        appEventRepo.appendUserEvent(
            AppEvent(
                type = AppEventType.LEDGER_ENTRY_VOIDED,
                occurredAt = occurredAt,
                sessionId = null,
                payload = LedgerEntryVoidedPayload(targetEventSequenceId),
            ),
            metadataJson = null,
        )
    }

    fun expensesForSession(sessionId: String): Flow<List<ExpenseRecord>> =
        ledgerDao.expensesForSession(sessionId).map { rows -> rows.map { it.toDomain() } }

    fun fuelForSession(sessionId: String): Flow<List<FuelRecord>> =
        ledgerDao.fuelForSession(sessionId).map { rows -> rows.map { it.toDomain() } }
}

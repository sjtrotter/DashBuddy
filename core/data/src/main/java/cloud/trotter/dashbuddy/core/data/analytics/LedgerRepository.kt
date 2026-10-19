package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.LedgerDao
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import cloud.trotter.dashbuddy.domain.analytics.ExpenseRecord
import cloud.trotter.dashbuddy.domain.analytics.FuelRecord
import cloud.trotter.dashbuddy.domain.analytics.SessionNet
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.ExpenseLoggedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.FuelFillupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.LedgerEntryVoidedPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** #1333: append-only writes and projected reads for the dasher's ledger. */
@Singleton
class LedgerRepository @Inject constructor(
    private val appEventRepo: AppEventRepo,
    private val ledgerDao: LedgerDao,
    private val analyticsRepository: AnalyticsRepository,
) {
    /** @return the appended event's sequence id; the target of a later `void` */
    suspend fun logExpense(
        sessionId: String?,
        category: ExpenseCategory,
        amount: Double,
        note: String?,
        occurredAt: Long,
    ): Long {
        require(amount.isFinite() && amount > 0) { "amount must be finite and positive" }
        return appEventRepo.appendUserEvent(
            AppEvent(
                type = AppEventType.EXPENSE_LOGGED,
                occurredAt = occurredAt,
                sessionId = sessionId,
                payload = ExpenseLoggedPayload(sessionId, category, amount, note),
            ),
            metadataJson = null,
        )
    }

    /** @return the appended event's sequence id; the target of a later `void` */
    suspend fun logFuel(
        sessionId: String?,
        totalAmount: Double,
        gallons: Double?,
        odometerMiles: Double?,
        occurredAt: Long,
    ): Long {
        require(totalAmount.isFinite() && totalAmount > 0) { "totalAmount must be finite and positive" }
        require(gallons == null || (gallons.isFinite() && gallons > 0)) { "gallons must be finite and positive" }
        require(odometerMiles == null || (odometerMiles.isFinite() && odometerMiles >= 0)) {
            "odometerMiles must be finite and non-negative"
        }
        return appEventRepo.appendUserEvent(
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

    /** Raw projection rows, including voids and stored attribution, for CSV export. */
    /** Every expense row, oldest first; the export reads this. Domain records: a feature module never sees an entity. */
    suspend fun allExpenses(): List<ExpenseRecord> = ledgerDao.allExpenses().map { it.toDomain() }

    suspend fun allFuel(): List<FuelRecord> = ledgerDao.allFuel().map { it.toDomain() }

    fun recentLabels(category: ExpenseCategory, limit: Int = 6): Flow<List<String>> =
        ledgerDao.recentNotes(category.name, limit)

    fun expensesForSession(sessionId: String): Flow<List<ExpenseRecord>> =
        ledgerDao.expensesForSession(sessionId).map { rows -> rows.map { it.toDomain() } }

    fun fuelForSession(sessionId: String): Flow<List<FuelRecord>> =
        ledgerDao.fuelForSession(sessionId).map { rows -> rows.map { it.toDomain() } }

    /** Unassigned ledger rows belong in the HUD log on their own local day, including voids. */
    fun expensesUnassignedOn(date: LocalDate, zone: ZoneId): Flow<List<ExpenseRecord>> =
        ledgerDao.expensesUnassigned().map { rows ->
            rows.filter { PeriodBounds.localDate(it.occurredAt, zone) == date }
                .map { it.toDomain() }
        }

    fun fuelUnassignedOn(date: LocalDate, zone: ZoneId): Flow<List<FuelRecord>> =
        ledgerDao.fuelUnassigned().map { rows ->
            rows.filter { PeriodBounds.localDate(it.occurredAt, zone) == date }
                .map { it.toDomain() }
        }

    /**
     * #1333: the HUD's "kept" source; projector lag is accepted and disclosed on the surface.
     * Null without a session or any money term: no deliveries, cash tips, or unattributed pay.
     * Expenses alone do not make the dash's kept figure available.
     */
    fun sessionNet(sessionId: String): Flow<SessionNet?> =
        combine(
            analyticsRepository.sessionDetail(sessionId),
            ledgerDao.expenseTotalForSession(sessionId),
            ledgerDao.fuelTotalForSession(sessionId),
        ) { detail, expenses, fuel ->
            if (detail == null || (detail.deliveries.isEmpty() && detail.cashTips == 0.0 && detail.unattributedPay == 0.0)) {
                null
            } else {
                SessionNet(
                    sessionId = sessionId,
                    deliveredNet = detail.deliveries.sumOf { it.netProfit ?: 0.0 },
                    cashTips = detail.cashTips,
                    unattributedPay = detail.unattributedPay,
                    expenses = expenses,
                    fuel = fuel,
                )
            }
        }
}

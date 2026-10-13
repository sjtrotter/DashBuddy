package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.ExpenseRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.FuelRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.LedgerDao
import cloud.trotter.dashbuddy.domain.analytics.ExpenseLoggedFold
import cloud.trotter.dashbuddy.domain.analytics.FuelFillupFold
import cloud.trotter.dashbuddy.domain.analytics.LedgerVoidFold
import timber.log.Timber

private const val TAG = "Projector"

/** Append the expense projection inside the batch transaction. Returns false (applied). */
internal suspend fun LedgerDao.applyExpenseLogged(fold: ExpenseLoggedFold): Boolean {
    upsertExpense(ExpenseRecordEntity(
        fold.eventSequenceId, fold.sessionId, fold.occurredAt, fold.category.name,
        fold.amount, fold.note, voidedAt = null,
    ))
    return false
}

/** Append the fuel projection inside the batch transaction. Returns false (applied). */
internal suspend fun LedgerDao.applyFuelFillup(fold: FuelFillupFold): Boolean {
    upsertFuel(FuelRecordEntity(
        fold.eventSequenceId, fold.sessionId, fold.occurredAt, fold.totalAmount,
        fold.gallons, fold.odometerMiles, voidedAt = null,
    ))
    return false
}

/** Void a prior ledger row inside the batch transaction. Returns true for a counted skip. */
internal suspend fun LedgerDao.applyLedgerVoid(fold: LedgerVoidFold): Boolean {
    val skipped = voidEntry(fold.targetEventSequenceId, fold.occurredAt) == 0
    if (skipped) Timber.tag(TAG).w("LEDGER_ENTRY_VOIDED: target row %d not found or already voided", fold.targetEventSequenceId)
    return skipped
}

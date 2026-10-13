package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.ExpenseRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.FuelRecordEntity
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import cloud.trotter.dashbuddy.domain.analytics.ExpenseRecord
import cloud.trotter.dashbuddy.domain.analytics.FuelRecord

internal fun ExpenseRecordEntity.toDomain() = ExpenseRecord(
    eventSequenceId, sessionId, occurredAt, ExpenseCategory.fromStorage(category), amount, note, voidedAt,
)

internal fun FuelRecordEntity.toDomain() = FuelRecord(
    eventSequenceId, sessionId, occurredAt, totalAmount, gallons, odometerMiles, voidedAt,
)

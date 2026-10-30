package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.analytics.ExpenseRecordEntity
import cloud.trotter.log.gig.core.database.analytics.FuelRecordEntity
import cloud.trotter.log.gig.domain.analytics.ExpenseCategory
import cloud.trotter.log.gig.domain.analytics.ExpenseRecord
import cloud.trotter.log.gig.domain.analytics.FuelRecord

internal fun ExpenseRecordEntity.toDomain() = ExpenseRecord(
    eventSequenceId, sessionId, occurredAt, ExpenseCategory.fromStorage(category), amount, note, voidedAt, vehicleId,
)

internal fun FuelRecordEntity.toDomain() = FuelRecord(
    eventSequenceId, sessionId, occurredAt, totalAmount, gallons, odometerMiles, voidedAt, vehicleId,
)

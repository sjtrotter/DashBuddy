package cloud.trotter.dashbuddy.domain.analytics

data class ExpenseLoggedFold(
    val eventSequenceId: Long,
    val sessionId: String?,
    val occurredAt: Long,
    val category: ExpenseCategory,
    val amount: Double,
    val note: String?,
    val vehicleId: String? = null,
)

data class FuelFillupFold(
    val eventSequenceId: Long,
    val sessionId: String?,
    val occurredAt: Long,
    val totalAmount: Double,
    val gallons: Double?,
    val odometerMiles: Double?,
    val vehicleId: String? = null,
)

data class LedgerVoidFold(
    val targetEventSequenceId: Long,
    val occurredAt: Long,
)

package cloud.trotter.log.gig.domain.analytics

data class ExpenseRecord(
    val eventSequenceId: Long,
    val sessionId: String?,
    val occurredAt: Long,
    val category: ExpenseCategory,
    val amount: Double,
    val note: String?,
    val voidedAt: Long?,
    val vehicleId: String? = null,
)

data class FuelRecord(
    val eventSequenceId: Long,
    val sessionId: String?,
    val occurredAt: Long,
    val totalAmount: Double,
    val gallons: Double?,
    val odometerMiles: Double?,
    val voidedAt: Long?,
    val vehicleId: String? = null,
) {
    val pricePerGallon: Double? get() = gallons?.takeIf { it > 0 }?.let { totalAmount / it }
}

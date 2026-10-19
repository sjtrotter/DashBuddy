package cloud.trotter.dashbuddy.domain.analytics

data class ExpenseRecord(
    val eventSequenceId: Long,
    val sessionId: String?,
    val occurredAt: Long,
    val category: ExpenseCategory,
    val amount: Double,
    val note: String?,
    val voidedAt: Long?,
)

data class FuelRecord(
    val eventSequenceId: Long,
    val sessionId: String?,
    val occurredAt: Long,
    val totalAmount: Double,
    val gallons: Double?,
    val odometerMiles: Double?,
    val voidedAt: Long?,
) {
    val pricePerGallon: Double? get() = gallons?.takeIf { it > 0 }?.let { totalAmount / it }
}

/**
 * #1333: the HUD's kept figure. [deliveredNet] is the sum of the dash's delivery `netProfit`
 * with a null net as zero, the hub's per-dash net rule; [cashTips] and [unattributedPay] are
 * `SessionDetail`'s. Expenses and fuel are a separate ledger that never enters kept or the goal.
 */
data class SessionNet(
    val sessionId: String,
    val deliveredNet: Double,
    val cashTips: Double,
    val unattributedPay: Double,
) {
    fun kept(): Double = deliveredNet + cashTips + unattributedPay
}

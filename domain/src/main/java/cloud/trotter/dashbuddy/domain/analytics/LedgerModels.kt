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
 * #1333: the three money terms are exactly [SessionDetail]'s: deliveredPay using delivery net,
 * cashTips, and unattributedPay. [realExpenses] is the sum of non-voided expense amounts and
 * non-voided fuel totals for the dash. Expenses are deducted only when requested by the caller.
 */
data class SessionNet(
    val sessionId: String,
    val deliveredNet: Double,
    val cashTips: Double,
    val unattributedPay: Double,
    val realExpenses: Double,
) {
    fun kept(countExpenses: Boolean): Double =
        deliveredNet + cashTips + unattributedPay - (if (countExpenses) realExpenses else 0.0)
}

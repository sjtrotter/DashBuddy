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
 * `SessionDetail`'s. [expenses] and [fuel] are the dash's non-voided logged totals, kept apart
 * because fuel fill-ups are logs and never enter kept (design §8 ruling 3).
 */
data class SessionNet(
    val sessionId: String,
    val deliveredNet: Double,
    val cashTips: Double,
    val unattributedPay: Double,
    val expenses: Double,
    val fuel: Double,
) {
    fun kept(countExpenses: Boolean): Double =
        deliveredNet + cashTips + unattributedPay - (if (countExpenses) expenses else 0.0)
}

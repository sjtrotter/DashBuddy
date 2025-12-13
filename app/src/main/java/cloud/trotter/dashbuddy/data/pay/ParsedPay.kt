package cloud.trotter.dashbuddy.data.pay

/**
 * A container for the entire parsed pay breakdown from a delivery completion screen.
 * It separates the app-level pay components from the store/customer-specific tips.
 */
data class ParsedPay(
    val appPayComponents: List<ParsedPayItem>, // For "Base Pay", "Peak Pay", etc.
    val customerTips: List<ParsedPayItem>      // For tips, where .type is the store name
) {
    val total: Double
        get() = (appPayComponents.sumOf { it.amount } + customerTips.sumOf { it.amount })
}
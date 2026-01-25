package cloud.trotter.dashbuddy.model.config

enum class MetricType(val label: String, val unit: String, val isHigherBetter: Boolean) {
    // "Higher is Better" metrics
    PAYOUT("Min Payout", "$", true),
    DOLLAR_PER_MILE("Dollars / Mile", "$/mi", true),
    ACTIVE_HOURLY("Active Hourly Rate", "$/hr", true),

    // "Lower is Better" metrics
    MAX_DISTANCE("Max Distance", "mi", false),
    ITEM_COUNT("Max Items", "items", false)
}
package cloud.trotter.log.gig.domain.evaluation

enum class MetricType(val label: String, val unit: String, val isHigherBetter: Boolean) {
    // "Higher is Better" metrics
    PAYOUT("Minimum net", "$", true),
    DOLLAR_PER_MILE("Profit per mile", "$/mi", true),
    ACTIVE_HOURLY("Profit per delivery hour", "$/hr", true),

    // "Lower is Better" metrics
    MAX_DISTANCE("Max distance", "mi", false),
    ITEM_COUNT("Max items", "items", false)
}

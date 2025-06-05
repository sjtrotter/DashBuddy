package cloud.trotter.dashbuddy.data.order

/**
 * Represents various textual badges that apply to a specific order/leg within an offer,
 * such as equipment requirements or specific handling notes for that part of the delivery.
 */
enum class OrderBadge(
    val badgeText: String // The exact text to match on the screen (case-insensitive)
) {
    /** Indicates that a Red Card is required for this specific order (e.g., for payment at the store). */
    RED_CARD(badgeText = "Red Card required"),

    /** Indicates this specific order is a large catering order, often requiring more space or different handling. */
    LARGE_ORDER(badgeText = "Large Order - Catering"),

    /** Indicates a pizza bag is required for this specific order. */
    PIZZA_BAG(badgeText = "Pizza bag required"),

    /** Indicates this specific order contains alcohol and may require ID check at pickup or dropoff for this leg. */
    ALCOHOL(badgeText = "Alcohol");

    companion object {
        /**
         * Finds all OrderBadges present in a given line of text.
         *
         * @param text The line of text to scan for order-specific badges.
         * @return A list of all OrderBadge enums that match the text.
         */
        private fun findBadgesInText(text: String): List<OrderBadge> {
            val foundBadges = mutableListOf<OrderBadge>()
            for (badge in entries) {
                if (badge.badgeText.equals(text, ignoreCase = true)) {
                    foundBadges.add(badge)
                }
            }
            return foundBadges
        }

        /**
         * Finds all OrderBadges present in a list of texts representing a single order's details.
         *
         * @param orderBlockTexts The list of strings for a specific order block.
         * @return A set of all unique OrderBadge enums found in that block.
         */
        fun findAllBadgesInOrderBlock(orderBlockTexts: List<String>): Set<OrderBadge> {
            val allFoundBadges = mutableSetOf<OrderBadge>()
            orderBlockTexts.forEach { textLine ->
                allFoundBadges.addAll(findBadgesInText(textLine))
            }
            return allFoundBadges
        }
    }
}

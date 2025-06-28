package cloud.trotter.dashbuddy.data.offer

import cloud.trotter.dashbuddy.R

/**
 * Represents various textual badges or indicators that can appear on a DoorDash offer screen.
 * Each badge has a matching strategy: exact text, contains text, or a regex pattern.
 */
enum class OfferBadge(
    val displayName: String,
    val exactMatchText: String? = null, // For exact (ignore case) string matching
    val containsText: String? = null,   // For contains (ignore case) string matching
    val regexPattern: Regex? = null,    // For regex pattern matching
    val iconResId: Int? = null          // Optional icon resource ID
) {
    /** Indicates the offer is marked as high paying by DoorDash. */
    HIGH_PAYING(
        displayName = "High Paying",
        regexPattern = Regex("""High-paying (shopping )?offer!""", RegexOption.IGNORE_CASE),
        iconResId = R.drawable.ic_badge_dollar_plus
    ),

    /** Indicates priority access due to Dasher status (Platinum, Gold, Silver) or Pro Shopper ratings. */
    PRIORITY_ACCESS(
        displayName = "Priority Access",
        regexPattern = Regex(
            """your\s+(Platinum|Gold|Silver)\s+status|Pro Shopper ratings gave you priority""",
            RegexOption.IGNORE_CASE
        ),
        iconResId = R.drawable.ic_badge_priority_access
    ),

    /** Indicates all orders in a stacked offer are from a single store. */
    ALL_ORDERS_SAME_STORE(
        displayName = "All Orders Same Store",
        exactMatchText = "All orders are from the same store"
    ),

    /** Indicates both orders in a stacked offer are for the same customer. */
    BOTH_ORDERS_SAME_CUSTOMER(
        displayName = "Both Orders Same Customer",
        exactMatchText = "Both orders go to the same customer"
    ),

    /** Indicates customer can add items to a shopping order before checkout. */
    ITEMS_CAN_BE_ADDED(
        displayName = "Items Can Be Added",
        exactMatchText = "Items can be added before checkout"
    ),

    /** Indicates a Sharpie is recommended, often for Shop & Deliver orders. */
    SHARPIE_RECOMMENDED(
        displayName = "Sharpie Recommended",
        exactMatchText = "Black marker or Sharpie recommended"
    ),

    /** Indicates the order contains age-restricted or other restricted items. */
    CONTAINS_RESTRICTED_ITEMS(
        displayName = "Contains Restricted Items",
        exactMatchText = "Contains restricted items"
    ),

    /** Indicates the order requires the Dasher to be 18+. */
    AGE_RESTRICTED_18_PLUS(
        displayName = "Age Restricted 18+",
        exactMatchText = "Must be 18+ to accept order",
        iconResId = R.drawable.ic_badge_id_check
    ),

    /** Indicates the order requires the Dasher to be 21+ (often for alcohol). */
    AGE_RESTRICTED_21_PLUS(
        displayName = "Age Restricted 21+",
        exactMatchText = "Must be 21+ to accept order",
        iconResId = R.drawable.ic_badge_id_check
    ),

    /** Indicates the Dasher must check the recipient's ID. */
    CHECK_RECIPIENT_ID(
        displayName = "Check Recipient ID",
        exactMatchText = "Check recipient's ID",
        iconResId = R.drawable.ic_badge_id_check
    ),

    /** Indicates the order includes alcohol (often part of 'Contains restricted items'). */
    INCLUDES_ALCOHOL(
        displayName = "Includes Alcohol",
        containsText = "including alcohol",
        iconResId = R.drawable.ic_badge_alcohol
    ),

    /** Indicates a cash on delivery (COD) order. */
    COLLECT_CASH(
        displayName = "Collect Cash",
        exactMatchText = "Collect cash from customer",
        iconResId = R.drawable.ic_badge_collect_cash
    ),

    /** Indicates the order might involve processing returns (often for alcohol or retail). */
    MAY_NEED_RETURNS(
        displayName = "May Need Returns",
        exactMatchText = "May need returns"
    );

    companion object {
        /**
         * Finds all OfferBadges present in a given line of text.
         * A single line of text could potentially match multiple badges.
         *
         * @param text The line of text to scan for badges.
         * @return A list of all OfferBadge enums that match the text.
         */
        private fun findBadgesInText(text: String): List<OfferBadge> {
            val foundBadges = mutableListOf<OfferBadge>()
            for (badge in entries) {
                if (badge.exactMatchText != null && badge.exactMatchText.equals(
                        text,
                        ignoreCase = true
                    )
                ) {
                    foundBadges.add(badge)
                } else if (badge.containsText != null && text.contains(
                        badge.containsText,
                        ignoreCase = true
                    )
                ) {
                    foundBadges.add(badge)
                } else if (badge.regexPattern != null && badge.regexPattern.containsMatchIn(text)) {
                    foundBadges.add(badge)
                }
            }
            return foundBadges.distinct()
        }

        /**
         * Finds all OfferBadges present in a list of screen texts.
         *
         * @param screenTexts The list of strings representing the screen content.
         * @return A set of all unique OfferBadge enums found across all screen texts.
         */
        fun findAllBadgesInScreen(screenTexts: List<String>): Set<OfferBadge> {
            val allFoundBadges = mutableSetOf<OfferBadge>()
            screenTexts.forEach { textLine ->
                allFoundBadges.addAll(findBadgesInText(textLine))
            }
            return allFoundBadges
        }
    }
}

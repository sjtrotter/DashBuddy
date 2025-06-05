package cloud.trotter.dashbuddy.data.base

import androidx.room.TypeConverter
import cloud.trotter.dashbuddy.data.offer.OfferBadge
import cloud.trotter.dashbuddy.data.order.OrderBadge

class DataTypeConverters {

    // For Set<OfferBadge>
    @TypeConverter
    fun fromOfferBadgeSet(badges: Set<OfferBadge>?): String { // Returns non-null String
        // If set is null or empty, store an empty string. Otherwise, join names.
        return badges?.takeIf { it.isNotEmpty() }
            ?.joinToString("|") { it.name }
            ?: "" // Default to empty string if set is null or empty
    }

    @TypeConverter
    fun toOfferBadgeSet(badgeString: String?): Set<OfferBadge> {
        // If string is null/blank, return emptySet. Otherwise, split, convert, and collect.
        return badgeString?.takeIf { it.isNotBlank() }
            ?.split('|')
            ?.mapNotNull { part ->
                try {
                    OfferBadge.valueOf(part.trim())
                } catch (e: IllegalArgumentException) {
                    null
                }
            }?.toSet() ?: emptySet()
    }

    // For Set<OrderBadge>
    @TypeConverter
    fun fromOrderBadgeSet(badges: Set<OrderBadge>?): String { // Returns non-null String
        return badges?.takeIf { it.isNotEmpty() }
            ?.joinToString("|") { it.name }
            ?: "" // Default to empty string if set is null or empty
    }

    @TypeConverter
    fun toOrderBadgeSet(badgeString: String?): Set<OrderBadge> {
        return badgeString?.takeIf { it.isNotBlank() }
            ?.split('|')
            ?.mapNotNull { part ->
                try {
                    OrderBadge.valueOf(part.trim())
                } catch (e: IllegalArgumentException) {
                    null
                }
            }?.toSet() ?: emptySet()
    }
}

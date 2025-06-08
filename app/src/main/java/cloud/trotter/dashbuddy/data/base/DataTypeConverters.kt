package cloud.trotter.dashbuddy.data.base

import androidx.room.TypeConverter
import cloud.trotter.dashbuddy.data.offer.OfferBadge
import cloud.trotter.dashbuddy.data.order.OrderBadge

class DataTypeConverters {

    @TypeConverter
    fun fromOfferBadgeSet(badges: Set<OfferBadge>?): String {
        return badges?.takeIf { it.isNotEmpty() }?.joinToString("|") { it.name } ?: ""
    }

    @TypeConverter
    fun toOfferBadgeSet(badgeString: String?): Set<OfferBadge> {
        return badgeString?.takeIf { it.isNotBlank() }
            ?.split('|')
            ?.mapNotNull { part ->
                try {
                    OfferBadge.valueOf(part.trim())
                } catch (e: Exception) {
                    null
                }
            }
            ?.toSet() ?: emptySet()
    }

    @TypeConverter
    fun fromOrderBadgeSet(badges: Set<OrderBadge>?): String {
        return badges?.takeIf { it.isNotEmpty() }?.joinToString("|") { it.name } ?: ""
    }

    @TypeConverter
    fun toOrderBadgeSet(badgeString: String?): Set<OrderBadge> {
        return badgeString?.takeIf { it.isNotBlank() }
            ?.split('|')
            ?.mapNotNull { part ->
                try {
                    OrderBadge.valueOf(part.trim())
                } catch (e: Exception) {
                    null
                }
            }
            ?.toSet() ?: emptySet()
    }

    @TypeConverter
    fun fromLongList(list: List<Long>?): String {
        // Converts a list of Longs into a single comma-separated string.
        // Returns an empty string if the list is null or empty.
        return list?.joinToString(",") ?: ""
    }

    @TypeConverter
    fun toLongList(data: String?): List<Long> {
        // Converts a comma-separated string back into a list of Longs.
        // Returns an empty list if the data is null, blank, or contains no valid numbers.
        return data?.takeIf { it.isNotBlank() }
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() } // Safely convert each part to Long, ignoring invalid parts
            ?: emptyList()
    }
}
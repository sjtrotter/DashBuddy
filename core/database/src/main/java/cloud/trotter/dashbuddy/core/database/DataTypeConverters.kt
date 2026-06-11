package cloud.trotter.dashbuddy.core.database

import androidx.room.TypeConverter
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.offer.OfferBadge
import cloud.trotter.dashbuddy.domain.model.order.OrderBadge
import timber.log.Timber

/** Class used by the Room database to convert Kotlin data types to and from SQL types. */
class DataTypeConverters {

    /** Converts a set of [cloud.trotter.dashbuddy.domain.model.offer.OfferBadge]s to a bar-separated string for storage in the database.
     * @return A bar-separated string representation of the set,
     * or an empty string if the set is null or empty.
     */
    @TypeConverter
    fun fromOfferBadgeSet(badges: Set<OfferBadge>?): String {
        return badges?.takeIf { it.isNotEmpty() }?.joinToString("|") { it.name } ?: ""
    }

    /** Converts a bar-separated string of offer badges back to a set of [cloud.trotter.dashbuddy.domain.model.offer.OfferBadge]s.
     * @return A set of [cloud.trotter.dashbuddy.domain.model.offer.OfferBadge]s, or an empty set if the string is blank or null.
     */
    @TypeConverter
    fun toOfferBadgeSet(badgeString: String?): Set<OfferBadge> {
        return badgeString?.takeIf { it.isNotBlank() }
            ?.split('|')
            ?.mapNotNull { part ->
                try {
                    OfferBadge.valueOf(part.trim())
                } catch (_: Exception) {
                    null
                }
            }
            ?.toSet() ?: emptySet()
    }

    /** Converts a set of [cloud.trotter.dashbuddy.domain.model.order.OrderBadge]s to a bar-separated string for storage in the database.
     * @return A bar-separated string representation of the set,
     * or an empty string if the set is null or empty.
     */
    @TypeConverter
    fun fromOrderBadgeSet(badges: Set<OrderBadge>?): String {
        return badges?.takeIf { it.isNotEmpty() }?.joinToString("|") { it.name } ?: ""
    }

    /** Converts a bar-separated string of offer badges back to a set of [cloud.trotter.dashbuddy.domain.model.offer.OfferBadge]s.
     * @return A set of [cloud.trotter.dashbuddy.domain.model.order.OrderBadge]s, or an empty set if the string is blank or null.
     */
    @TypeConverter
    fun toOrderBadgeSet(badgeString: String?): Set<OrderBadge> {
        return badgeString?.takeIf { it.isNotBlank() }
            ?.split('|')
            ?.mapNotNull { part ->
                try {
                    OrderBadge.valueOf(part.trim())
                } catch (_: Exception) {
                    null
                }
            }
            ?.toSet() ?: emptySet()
    }

    /** Converts a list of [Long]s to a comma-separated string for storage in the database.
     * @return A comma-separated string representation of the list,
     * or an empty string if the list is null or empty.
     */
    @TypeConverter
    fun fromLongList(list: List<Long>?): String {
        // Converts a list of Longs into a single comma-separated string.
        // Returns an empty string if the list is null or empty.
        return list?.joinToString(",") ?: ""
    }

    /** Converts a comma-separated string of Longs back to a list of [Long]s.
     * @return A list of [Long]s, or an empty list if the string is blank or null.
     */
    @TypeConverter
    fun toLongList(data: String?): List<Long> {
        // Converts a comma-separated string back into a list of Longs.
        // Returns an empty list if the data is null, blank, or contains no valid numbers.
        return data?.takeIf { it.isNotBlank() }
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() } // Safely convert each part to Long, ignoring invalid parts
            ?: emptyList()
    }

    @TypeConverter
    fun fromAppEventType(type: AppEventType): String {
        return type.name
    }

    @TypeConverter
    fun toAppEventType(value: String): AppEventType {
        return try {
            enumValueOf<AppEventType>(value)
        } catch (_: Exception) {
            // Fallback for version safety: If we remove an enum later,
            // old logs default to ERROR or a specific UNKNOWN type.
            Timber.w("Unknown AppEventType '%s' — coercing to ERROR_OCCURRED", value)
            AppEventType.ERROR_OCCURRED
        }
    }
}
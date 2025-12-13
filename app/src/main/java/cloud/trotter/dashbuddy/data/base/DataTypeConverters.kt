package cloud.trotter.dashbuddy.data.base

import androidx.room.TypeConverter
import cloud.trotter.dashbuddy.data.dash.DashType
import cloud.trotter.dashbuddy.data.event.status.DropoffStatus
import cloud.trotter.dashbuddy.data.event.status.PickupStatus
import cloud.trotter.dashbuddy.data.offer.OfferBadge
import cloud.trotter.dashbuddy.data.offer.OfferStatus
import cloud.trotter.dashbuddy.data.order.OrderBadge
import cloud.trotter.dashbuddy.data.order.OrderStatus

/** Class used by the Room database to convert Kotlin data types to and from SQL types. */
class DataTypeConverters {

    /** Converts a set of [OfferBadge]s to a bar-separated string for storage in the database.
     * @return A bar-separated string representation of the set,
     * or an empty string if the set is null or empty.
     */
    @TypeConverter
    fun fromOfferBadgeSet(badges: Set<OfferBadge>?): String {
        return badges?.takeIf { it.isNotEmpty() }?.joinToString("|") { it.name } ?: ""
    }

    /** Converts a bar-separated string of offer badges back to a set of [OfferBadge]s.
     * @return A set of [OfferBadge]s, or an empty set if the string is blank or null.
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

    /** Converts a set of [OrderBadge]s to a bar-separated string for storage in the database.
     * @return A bar-separated string representation of the set,
     * or an empty string if the set is null or empty.
     */
    @TypeConverter
    fun fromOrderBadgeSet(badges: Set<OrderBadge>?): String {
        return badges?.takeIf { it.isNotEmpty() }?.joinToString("|") { it.name } ?: ""
    }

    /** Converts a bar-separated string of offer badges back to a set of [OfferBadge]s.
     * @return A set of [OrderBadge]s, or an empty set if the string is blank or null.
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
    fun fromDashType(type: DashType?): String? {
        return type?.name // Converts EarningType.PER_OFFER to the string "PER_OFFER"
    }

    @TypeConverter
    fun toDashType(name: String?): DashType? {
        return name?.let { DashType.valueOf(it) }
    }

    /** Converts the [OfferStatus] to a string for storage in the database. */
    @TypeConverter
    fun fromOfferStatus(status: OfferStatus): String {
        return status.name
    }

    /** Converts the string from the database back to the [OfferStatus]. */
    @TypeConverter
    fun toOfferStatus(statusString: String): OfferStatus {
        // Converts the string "ACCEPTED" from the database back to OfferStatus.ACCEPTED.
        return enumValueOf<OfferStatus>(statusString)
    }

    /** Converts the [OrderStatus] to a string for storage in the database. */
    @TypeConverter
    fun fromOrderStatus(status: OrderStatus): String {
        // Converts OrderStatus.PICKUP_CONFIRMED to "PICKUP_CONFIRMED".
        return status.name
    }

    /** Converts the string from the database back to the [OrderStatus]. */
    @TypeConverter
    fun toOrderStatus(statusString: String): OrderStatus {
        // Converts "PICKUP_CONFIRMED" back to OrderStatus.PICKUP_CONFIRMED.
        return enumValueOf<OrderStatus>(statusString)
    }

    @TypeConverter
    fun fromPickupStatus(status: PickupStatus): String = status.name

    @TypeConverter
    fun toPickupStatus(value: String): PickupStatus = try {
        enumValueOf<PickupStatus>(value)
    } catch (_: Exception) {
        PickupStatus.UNKNOWN
    }

    @TypeConverter
    fun fromDropoffStatus(status: DropoffStatus): String = status.name

    @TypeConverter
    fun toDropoffStatus(value: String): DropoffStatus = try {
        enumValueOf<DropoffStatus>(value)
    } catch (_: Exception) {
        DropoffStatus.UNKNOWN
    }

    @TypeConverter
    fun fromAppEventType(type: cloud.trotter.dashbuddy.data.event.AppEventType): String {
        return type.name
    }

    @TypeConverter
    fun toAppEventType(value: String): cloud.trotter.dashbuddy.data.event.AppEventType {
        return try {
            enumValueOf<cloud.trotter.dashbuddy.data.event.AppEventType>(value)
        } catch (_: Exception) {
            // Fallback for version safety: If we remove an enum later,
            // old logs default to ERROR or a specific UNKNOWN type.
            cloud.trotter.dashbuddy.data.event.AppEventType.ERROR_OCCURRED
        }
    }
}
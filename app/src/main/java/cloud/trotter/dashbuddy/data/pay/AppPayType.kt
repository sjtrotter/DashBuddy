package cloud.trotter.dashbuddy.data.pay

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a type of pay from the application (e.g., "Base Pay", "Peak Pay").
 * This is a lookup table to normalize pay types, allowing new ones to be added dynamically.
 */
@Entity(
    tableName = "app_pay_types",
    indices = [Index(value = ["name"], unique = true)] // Each pay type name must be unique
)
data class AppPayType(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The name of the pay type, e.g., "Base Pay", "Peak Pay". */
    val name: String
)

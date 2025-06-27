package cloud.trotter.dashbuddy.data.customer

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "customers",
    indices = [Index(value = ["nameHash", "addressHash"], unique = true)]
)
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val nameHash: String? = null,
    val addressHash: String? = null,
    val lastSeen: Long = System.currentTimeMillis()
)

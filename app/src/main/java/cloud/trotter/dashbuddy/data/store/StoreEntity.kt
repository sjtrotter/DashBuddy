package cloud.trotter.dashbuddy.data.store

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stores",
    indices = [Index(value = ["storeName", "address"], unique = true)]
)
data class StoreEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val storeName: String,
    val address: String,
    val lastSeen: Long = System.currentTimeMillis()
)

package cloud.trotter.dashbuddy.data.customer

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "customers",
)
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val hash: String,
)

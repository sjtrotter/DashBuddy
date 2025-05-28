package cloud.trotter.dashbuddy.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "sessions")

data class Session (
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val start: Date? = Date(),
    val stop: Date? = null,
    val distance: Double = 0.0,
)
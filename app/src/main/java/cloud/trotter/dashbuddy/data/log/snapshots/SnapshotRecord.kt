package cloud.trotter.dashbuddy.data.log.snapshots

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "snapshots")
data class SnapshotRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    @ColumnInfo(index = true) // Index for fast lookups
    val screenType: String,   // e.g. "OFFER", "MAP"

    val structuralHash: Int,  // From UiNode
    val contentHash: Int,     // From UiNode

    val filePath: String,
    val timestamp: Long
)
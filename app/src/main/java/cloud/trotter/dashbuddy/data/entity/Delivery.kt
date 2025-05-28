package cloud.trotter.dashbuddy.data.entity

import androidx.room.PrimaryKey

data class Delivery (
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    )
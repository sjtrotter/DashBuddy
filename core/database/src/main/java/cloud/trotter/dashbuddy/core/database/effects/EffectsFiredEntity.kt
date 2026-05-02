package cloud.trotter.dashbuddy.core.database.effects

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks which keyed effects have already been fired, for idempotency
 * during crash-recovery replay.
 *
 * ADR-0005 Section 7.4.
 */
@Entity(
    tableName = "effects_fired",
    indices = [Index(value = ["effectKey"], unique = true)]
)
data class EffectsFiredEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val effectKey: String,
    val firedAt: Long,
    val correlationVersion: Long,
)

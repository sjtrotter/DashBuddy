package cloud.trotter.dashbuddy.core.database.snapshot

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Periodic snapshot of the full [AppState] across all regions.
 *
 * Written every N=5 observations and at major transitions
 * (session start/end, job start/end, healing events).
 * Old snapshots pruned after 24h.
 *
 * ADR-0005 Section 7.2.
 */
@Entity(tableName = "app_state_snapshots")
data class AppStateSnapshotEntity(
    @PrimaryKey val correlationVersion: Long,
    val capturedAt: Long,
    val sessionId: String?,
    val stateJson: String,
)

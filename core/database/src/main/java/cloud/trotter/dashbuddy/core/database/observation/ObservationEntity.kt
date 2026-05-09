package cloud.trotter.dashbuddy.core.database.observation

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only observation log. Every observation accepted by the state machine
 * is persisted here. This is the source-of-truth event stream for replay,
 * debugging, and crash recovery.
 *
 * ADR-0005 Section 7.1.
 */
@Entity(
    tableName = "observations",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["occurredAt"]),
        Index(value = ["correlationVersion"]),
    ]
)
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) val sequenceId: Long = 0,
    val occurredAt: Long,
    val sessionId: String?,
    val pipelineId: String,
    val ruleId: String?,
    val platform: String?,
    val flow: String?,
    val modeHint: String?,
    val parsedJson: String,
    val captureId: String?,
    val metadataJson: String,
    val correlationVersion: Long,
    val timeoutType: String? = null,
)

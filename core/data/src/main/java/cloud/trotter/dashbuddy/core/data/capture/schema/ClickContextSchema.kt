package cloud.trotter.dashbuddy.core.data.capture.schema

import cloud.trotter.dashbuddy.core.database.log.mapper.toDto
import cloud.trotter.dashbuddy.core.database.log.mapper.toDomain
import cloud.trotter.dashbuddy.core.database.log.dto.UiNodeDto
import cloud.trotter.dashbuddy.domain.capture.CaptureSchema
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Capture schema for click events with screen context.
 * Wraps the clicked [UiNode] with the active screen classification
 * so click captures are self-contained for analysis and rule writing.
 */
object ClickContextSchema : CaptureSchema<ClickCapturePayload> {

    override val schemaId: String = "click_context.v1"

    private val json = Json {
        prettyPrint = false
        encodeDefaults = false
    }

    override fun serialize(payload: ClickCapturePayload): String =
        json.encodeToString(payload.toDto())

    override fun deserialize(json: String): ClickCapturePayload =
        this.json.decodeFromString<ClickCaptureDto>(json).toDomain()
}

/** Domain payload for click captures. */
data class ClickCapturePayload(
    val node: UiNode,
    val screenTarget: String?,
)

@Serializable
internal data class ClickCaptureDto(
    val node: UiNodeDto,
    val screenTarget: String? = null,
)

private fun ClickCapturePayload.toDto() = ClickCaptureDto(
    node = node.toDto(),
    screenTarget = screenTarget,
)

private fun ClickCaptureDto.toDomain() = ClickCapturePayload(
    node = node.toDomain(),
    screenTarget = screenTarget,
)

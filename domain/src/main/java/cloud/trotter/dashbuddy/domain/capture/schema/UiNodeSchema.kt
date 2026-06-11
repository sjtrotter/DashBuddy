package cloud.trotter.dashbuddy.domain.capture.schema

import cloud.trotter.dashbuddy.domain.capture.toDomain
import cloud.trotter.dashbuddy.domain.capture.toDto
import cloud.trotter.dashbuddy.domain.capture.dto.UiNodeDto
import cloud.trotter.dashbuddy.domain.capture.CaptureSchema
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Capture schema for accessibility UI node trees.
 * Serializes via the existing [UiNodeDto] ↔ [UiNode] mapper pair.
 */
object UiNodeSchema : CaptureSchema<UiNode> {

    override val schemaId: String = "uinode.v1"

    private val json = Json {
        prettyPrint = false
        encodeDefaults = false
    }

    override fun serialize(payload: UiNode): String =
        json.encodeToString(payload.toDto())

    override fun deserialize(json: String): UiNode =
        this.json.decodeFromString<UiNodeDto>(json).toDomain()
}

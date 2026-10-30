package cloud.trotter.log.gig.domain.capture.schema

import cloud.trotter.log.gig.domain.capture.toDomain
import cloud.trotter.log.gig.domain.capture.toDto
import cloud.trotter.log.gig.domain.capture.dto.UiNodeDto
import cloud.trotter.log.gig.domain.capture.CaptureSchema
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
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

package cloud.trotter.dashbuddy.domain.capture

import cloud.trotter.dashbuddy.domain.capture.dto.BoundingBoxDto
import cloud.trotter.dashbuddy.domain.capture.dto.UiNodeDto
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

// --- Bounding Box Mappers ---
fun BoundingBox.toDto() = BoundingBoxDto(left, top, right, bottom)
fun BoundingBoxDto.toDomain() = BoundingBox(left, top, right, bottom)

// --- Domain -> JSON DTO (Saving to disk) ---
fun UiNode.toDto(): UiNodeDto {
    return UiNodeDto(
        text = this.text,
        contentDescription = this.contentDescription,
        stateDescription = this.stateDescription,
        viewIdResourceName = this.viewIdResourceName,
        className = this.className,
        isClickable = this.isClickable,
        isEnabled = this.isEnabled,
        isChecked = this.isChecked,
        boundsInScreen = this.boundsInScreen.toDto(),
        children = this.children.map { it.toDto() }
    )
}

// --- JSON DTO -> Domain (Reading from disk) ---
// Bottom-up construction into the immutable tree (#363); parents are wired
// once at the root via restoreParents().
fun UiNodeDto.toDomain(): UiNode = toDomainNode().restoreParents()

private fun UiNodeDto.toDomainNode(): UiNode = UiNode(
    text = this.text,
    contentDescription = this.contentDescription,
    stateDescription = this.stateDescription,
    viewIdResourceName = this.viewIdResourceName,
    className = this.className,
    isClickable = this.isClickable,
    isEnabled = this.isEnabled,
    isChecked = this.isChecked,
    boundsInScreen = this.boundsInScreen.toDomain(),
    children = this.children.map { it.toDomainNode() },
)
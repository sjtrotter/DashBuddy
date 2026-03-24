package cloud.trotter.dashbuddy.core.database.log.mapper

import cloud.trotter.dashbuddy.core.database.log.dto.BoundingBoxDto
import cloud.trotter.dashbuddy.core.database.log.dto.UiNodeDto
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
fun UiNodeDto.toDomain(parentUiNode: UiNode? = null): UiNode {
    val domainNode = UiNode(
        text = this.text,
        contentDescription = this.contentDescription,
        stateDescription = this.stateDescription,
        viewIdResourceName = this.viewIdResourceName,
        className = this.className,
        isClickable = this.isClickable,
        isEnabled = this.isEnabled,
        isChecked = this.isChecked,
        boundsInScreen = this.boundsInScreen.toDomain(),
        parent = parentUiNode,
        children = mutableListOf()
    )

    // Recursively build children, passing the current node as the new parent
    domainNode.children.addAll(
        this.children.map { childDto -> childDto.toDomain(parentUiNode = domainNode) }
    )

    return domainNode
}
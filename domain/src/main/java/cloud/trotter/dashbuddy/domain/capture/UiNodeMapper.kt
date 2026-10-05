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
        paneTitle = this.paneTitle,
        roleDescription = this.roleDescription,
        hintText = this.hintText,
        tooltipText = this.tooltipText,
        errorText = this.errorText,
        clickActionLabel = this.clickActionLabel,
        uniqueId = this.uniqueId,
        viewIdResourceName = this.viewIdResourceName,
        className = this.className,
        isClickable = this.isClickable,
        isEnabled = this.isEnabled,
        isChecked = this.isChecked,
        hasClickAction = this.hasClickAction,
        isEditable = this.isEditable,
        foreignPackage = this.foreignPackage,
        unreadableChildren = this.unreadableChildren,
        isVisibleToUser = this.isVisibleToUser,
        isFocusable = this.isFocusable,
        isScreenReaderFocusable = this.isScreenReaderFocusable,
        isCheckable = this.isCheckable,
        isSelected = this.isSelected,
        isHeading = this.isHeading,
        liveRegion = this.liveRegion,
        collectionRows = this.collectionRows,
        collectionCols = this.collectionCols,
        itemRow = this.itemRow,
        itemCol = this.itemCol,
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
    paneTitle = this.paneTitle,
    roleDescription = this.roleDescription,
    hintText = this.hintText,
    tooltipText = this.tooltipText,
    errorText = this.errorText,
    clickActionLabel = this.clickActionLabel,
    uniqueId = this.uniqueId,
    viewIdResourceName = this.viewIdResourceName,
    className = this.className,
    isClickable = this.isClickable,
    isEnabled = this.isEnabled,
    isChecked = this.isChecked,
    hasClickAction = this.hasClickAction,
    isEditable = this.isEditable,
    foreignPackage = this.foreignPackage,
    unreadableChildren = this.unreadableChildren,
    isVisibleToUser = this.isVisibleToUser,
    isFocusable = this.isFocusable,
    isScreenReaderFocusable = this.isScreenReaderFocusable,
    isCheckable = this.isCheckable,
    isSelected = this.isSelected,
    isHeading = this.isHeading,
    liveRegion = this.liveRegion,
    collectionRows = this.collectionRows,
    collectionCols = this.collectionCols,
    itemRow = this.itemRow,
    itemCol = this.itemCol,
    boundsInScreen = this.boundsInScreen.toDomain(),
    children = this.children.map { it.toDomainNode() },
)

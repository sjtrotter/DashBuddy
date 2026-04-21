package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenDiffer @Inject constructor() {

    private var lastStructure: Int? = null

    /**
     * Returns TRUE if the screen structure has changed since the last check.
     * Only compares [UiNode.structuralHash] (className + viewIdResourceName hierarchy) —
     * text/content changes within a stable layout do not constitute a new screen.
     */
    fun hasChanged(node: UiNode): Boolean {
        val newStructure = node.structuralHash
        if (newStructure == lastStructure) return false
        lastStructure = newStructure
        return true
    }

    fun reset() {
        lastStructure = null
    }
}
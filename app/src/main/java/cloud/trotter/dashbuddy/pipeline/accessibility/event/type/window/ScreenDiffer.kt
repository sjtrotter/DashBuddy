package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window

import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenDiffer @Inject constructor() {

    private var lastStructure: Int? = null
    private var lastContent: Int? = null

    /**
     * Returns TRUE if the screen is different from the last check.
     */
    fun hasChanged(node: UiNode): Boolean {
        // Accessing the properties triggers the lazy computation automatically
        val newStructure = node.structuralHash
        val newContent = node.contentHash

        if (newStructure == lastStructure && newContent == lastContent) {
            return false // Screen is identical
        }

        lastStructure = newStructure
        lastContent = newContent
        return true
    }

    fun reset() {
        lastStructure = null
        lastContent = null
    }
}
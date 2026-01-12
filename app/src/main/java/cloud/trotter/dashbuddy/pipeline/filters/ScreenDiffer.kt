package cloud.trotter.dashbuddy.pipeline.filters

import cloud.trotter.dashbuddy.services.accessibility.UiNode

class ScreenDiffer {
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
package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode

/**
 * Stores context across multiple test executions.
 * Maps a structural fingerprint -> The Filename of the first occurrence.
 */
object SnapshotSession {
    private val analyzedFingerprints = mutableMapOf<Int, String>()

    fun reset() {
        analyzedFingerprints.clear()
    }

    sealed class VariantResult {
        object New : VariantResult()
        data class Duplicate(val originalFilename: String) : VariantResult()
    }

    fun checkVariant(node: UiNode, currentFilename: String): VariantResult {
        // Fingerprint logic: Sort all IDs on screen.
        val ids = node.findNodes { !it.viewIdResourceName.isNullOrBlank() }
            .map { it.viewIdResourceName!! }
            .sorted()
            .joinToString("|")

        // Fallback to structural hash if no IDs exist
        val fingerprint = if (ids.isNotBlank()) ids.hashCode() else node.structuralHash

        val existingFilename = analyzedFingerprints[fingerprint]

        return if (existingFilename != null) {
            VariantResult.Duplicate(existingFilename)
        } else {
            analyzedFingerprints[fingerprint] = currentFilename
            VariantResult.New
        }
    }
}
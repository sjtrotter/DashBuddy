package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode

/**
 * Automagically generates a ScreenMatcher class skeleton based on a UiNode snapshot.
 */
object SnapshotMatcherGenerator {

    fun generateSkeleton(root: UiNode): String {
        // 1. Find best candidates for matching
        val candidates = collectCandidates(root)

        // Prioritize: IDs > Specific Text > Content Descriptions
        val ids = candidates.filter { !it.viewIdResourceName.isNullOrBlank() }
            .mapNotNull { it.viewIdResourceName }
            .distinct()
            .take(3) // Pick top 3 IDs

        val texts = candidates.filter { !it.text.isNullOrBlank() && it.text!!.length > 5 }
            .mapNotNull { it.text }
            .distinct()
            .take(2) // Pick top 2 Texts

        // 2. Build the Code String
        val sb = StringBuilder()
        sb.append("     🤖 AUTO-GENERATED MATCHER SKELETON:\n")
        sb.append("     (Copy/Paste this into a new file to start)\n")
        sb.append("     ===============================================================\n")
        sb.append("     // TODO 1: Add a new entry to Screen.kt enum (e.g. MY_NEW_SCREEN)\n")
        sb.append("     // TODO 2: Copy this class to 'pipeline/recognition/matchers/'\n")
        sb.append("     // TODO 3: Add to TestMatcherFactory.createAllMatchers()\n")
        sb.append("     // TODO 4: Add to PipelineModule.kt (Production DI)\n")
        sb.append("     ===============================================================\n\n")

        sb.append("class UnknownScreenMatcher @Inject constructor() : ScreenMatcher {\n")
        sb.append("    override val targetScreen: Screen = Screen.UNKNOWN\n")
        sb.append("    override val priority = 10\n\n")
        sb.append("    override fun matches(node: UiNode): ScreenInfo? {\n")

        // Generate ID checks
        if (ids.isNotEmpty()) {
            sb.append("        // 1. Check for unique IDs\n")
            ids.forEach { id ->
                val shortId = id.substringAfter("id/")
                sb.append("        val has$shortId = node.findNode { it.hasId(\"$shortId\") } != null\n")
            }
            sb.append("\n")
        }

        // Generate Text checks
        if (texts.isNotEmpty()) {
            sb.append("        // 2. Check for specific text\n")
            texts.forEach { text ->
                // Sanitize variable name
                val varName = text.filter { it.isLetterOrDigit() }.take(15).capitalize()
                sb.append("        val has$varName = node.findNode { it.text == \"$text\" } != null\n")
            }
            sb.append("\n")
        }

        // Return logic
        sb.append("        // TODO: Combine checks (&&) for accuracy\n")
        sb.append("        return if (")

        val allChecks = ids.map { "has${it.substringAfter("id/")}" } +
                texts.map { "has${it.filter { c -> c.isLetterOrDigit() }.take(15).capitalize()}" }

        sb.append(allChecks.joinToString(" && "))
        sb.append(") ScreenInfo.Simple(targetScreen) else null\n")

        sb.append("    }\n")
        sb.append("}")

        return sb.toString()
    }

    // Tiny helper to capitalize first letter (Kotlin stdlib varies by version)
    private fun String.capitalize(): String {
        return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun collectCandidates(root: UiNode): List<UiNode> {
        val results = mutableListOf<UiNode>()
        fun walk(node: UiNode) {
            if (!node.text.isNullOrBlank() || !node.viewIdResourceName.isNullOrBlank()) {
                results.add(node)
            }
            node.children.forEach { walk(it) }
        }
        walk(root)
        return results
    }
}
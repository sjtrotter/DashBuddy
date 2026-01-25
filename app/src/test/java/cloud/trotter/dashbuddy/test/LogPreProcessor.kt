package cloud.trotter.dashbuddy.test

object LogPreProcessor {

    /**
     * Takes a raw single-line log dump and expands it into a multi-line string
     * compatible with LogToUiNodeParser.
     */
    fun process(rawLogLine: String): String? {
        // 1. Find the start of the UI Tree
        val startIndex = rawLogLine.indexOf("UiNode(")
        if (startIndex == -1) return null

        // 2. Extract the tree part
        // The logs use a literal backslash+n ("\n") sequence to separate nodes.
        // We replace that with a real newline character so your parser reads it as lines.
        val treeContent = rawLogLine.substring(startIndex)
            .replace("\\n", "\n")

        return treeContent
    }
}
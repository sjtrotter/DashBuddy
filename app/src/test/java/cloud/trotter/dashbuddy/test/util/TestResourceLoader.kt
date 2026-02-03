package cloud.trotter.dashbuddy.test.util

import android.graphics.Rect
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

object TestResourceLoader {

    /**
     * Loads all .json files from a specific folder under src/test/resources.
     * @param pathFromResources e.g. "snapshots/MAIN_MAP_IDLE"
     * @return List of pairs: (Filename, UiNode)
     */
    fun loadSnapshots(pathFromResources: String): List<Pair<String, UiNode>> {
        // Look for src/test/resources explicitly.
        val resourceDir = File("src/test/resources/$pathFromResources")

        if (!resourceDir.exists() || !resourceDir.isDirectory) {
            throw IllegalArgumentException("Could not find directory: ${resourceDir.absolutePath}")
        }

        return resourceDir.listFiles { _, name -> name.endsWith(".json") }
            ?.sorted()
            ?.map { file ->
                val jsonString = file.readText()
                val uiNode = parseJsonToUiNode(jsonString)
                file.name to uiNode
            }
            ?: emptyList()
    }

    /**
     * Reconstructs a UiNode tree from the JSON string using Gson.
     */
    fun parseJsonToUiNode(jsonString: String): UiNode {
        // Parse the raw string into a Gson JsonObject
        val jsonObject = JsonParser.parseString(jsonString).asJsonObject
        return parseRecursive(jsonObject, null)
    }

    private fun parseRecursive(json: JsonObject, parent: UiNode?): UiNode {
        // 1. Parse Bounds "[left,top][right,bottom]"
        // Gson: Check if member exists, then get string. Default if missing.
        val boundsStr = if (json.has("bounds")) json.get("bounds").asString else "[0,0][0,0]"
        val rect = parseBounds(boundsStr)

        // 2. Helper to safely get nullable strings
        fun getStringOrNull(key: String): String? {
            return if (json.has(key) && !json.get(key).isJsonNull) {
                val str = json.get(key).asString
                str.ifEmpty { null }
            } else null
        }

        // 3. Create the Node
        val node = UiNode(
            text = getStringOrNull("text"),
            contentDescription = getStringOrNull("desc"),
            // Re-add "id/" prefix if missing, matching how we likely use it in matchers
            viewIdResourceName = getStringOrNull("id")?.let { if (it.startsWith("id/")) it else "id/$it" },
            className = getStringOrNull("class"),

            // Boolean/Int helpers
            isClickable = if (json.has("clickable")) json.get("clickable").asBoolean else false,
            isEnabled = if (json.has("enabled")) json.get("enabled").asBoolean else false,
            isChecked = if (json.has("checked")) json.get("checked").asInt else 0,

            boundsInScreen = rect,
            parent = parent,
            children = mutableListOf()
        )

        // 4. Parse Children (Recursion)
        if (json.has("children")) {
            val childrenArray = json.getAsJsonArray("children")
            for (element in childrenArray) {
                val childObj = element.asJsonObject
                val childNode = parseRecursive(childObj, node) // Pass 'node' as parent
                node.children.add(childNode)
            }
        }

        return node
    }

    private fun parseBounds(boundsStr: String): Rect {
        try {
            // Remove brackets and split by comma
            // Input: "[0,1712][1080,2203]" -> "0,1712,1080,2203"
            val cleaned = boundsStr.replace("[", "").replace("]", ",")
            val parts = cleaned.split(",").filter { it.isNotBlank() }

            if (parts.size >= 4) {
                return Rect(
                    parts[0].toInt(),
                    parts[1].toInt(),
                    parts[2].toInt(),
                    parts[3].toInt()
                )
            }
        } catch (_: Exception) {
            // Fallback to empty rect
        }
        return Rect()
    }

    /**
     * Helper for JUnit 4 Parameterized Tests.
     * formatting the data as Collection<Array<Any>> [Filename, UiNode]
     */
    fun loadForParameterized(folderName: String): Collection<Array<Any>> {
        val path = "snapshots/$folderName"
        return loadSnapshots(path).map { (filename, node) ->
            arrayOf(filename, node)
        }
    }
}
package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.data.log.snapshots.SnapshotWrapper
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File

object TestResourceLoader {

    private val jsonParser = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Loads snapshots and returns Triple: (Filename, Node, Breadcrumbs)
     */
    fun loadSnapshots(pathFromResources: String): List<Triple<String, UiNode, List<String>>> {
        val resourceDir = File("src/test/resources/$pathFromResources")

        if (!resourceDir.exists() || !resourceDir.isDirectory) return emptyList()

        return resourceDir.listFiles { _, name -> name.endsWith(".json") }
            ?.sorted()
            ?.map { file ->
                val (node, breadcrumbs) = parseFlexibleJson(file.readText())
                Triple(file.name, node, breadcrumbs)
            }
            ?: emptyList()
    }

    fun isGolden(file: File): Boolean {
        return try {
            val content = file.readText()
            if (content.contains("\"isGolden\": true")) return true
            val wrapper = jsonParser.decodeFromString<SnapshotWrapper>(content)
            wrapper.isGolden
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns Pair(Node, Breadcrumbs)
     */
    private fun parseFlexibleJson(jsonString: String): Pair<UiNode, List<String>> {
        val (rootNode, breadcrumbs) = try {
            val jsonElement = jsonParser.parseToJsonElement(jsonString)
            val isWrapper = jsonElement.jsonObject.containsKey("root")

            if (isWrapper) {
                val wrapper = jsonParser.decodeFromString<SnapshotWrapper>(jsonString)
                wrapper.root to wrapper.breadcrumbs
            } else {
                val node = jsonParser.decodeFromString<UiNode>(jsonString)
                node to emptyList()
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse JSON", e)
        }

        rootNode.restoreParents()
        return rootNode to breadcrumbs
    }

    /**
     * UPDATED: Returns [Filename, Node, Breadcrumbs] for the test constructor
     */
    fun loadForParameterized(folderName: String): Collection<Array<Any>> {
        val path = "snapshots/$folderName"
        return loadSnapshots(path).map { (filename, node, breadcrumbs) ->
            arrayOf(filename, node, breadcrumbs)
        }
    }
}
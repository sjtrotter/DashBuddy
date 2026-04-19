package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.core.database.log.dto.SnapshotWrapperDto
import cloud.trotter.dashbuddy.core.database.log.dto.UiNodeDto
import cloud.trotter.dashbuddy.core.database.log.mapper.toDomain
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
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
            ?.mapNotNull { file ->
                try {
                    val (node, breadcrumbs) = parseFlexibleJson(file.readText())
                    Triple(file.name, node, breadcrumbs)
                } catch (_: Exception) {
                    println("⚠️  Skipping unparseable file: ${file.name}")
                    null
                }
            }
            ?: emptyList()
    }

    fun isGolden(file: File): Boolean {
        return try {
            val content = file.readText()
            if (content.contains("\"isGolden\": true")) return true
            val wrapperDto = jsonParser.decodeFromString<SnapshotWrapperDto>(content)
            wrapperDto.isGolden
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
                val wrapperDto = jsonParser.decodeFromString<SnapshotWrapperDto>(jsonString)
                val domainNode = wrapperDto.root.toDomain()
                domainNode to wrapperDto.breadcrumbs
            } else {
                val nodeDto = jsonParser.decodeFromString<UiNodeDto>(jsonString)
                val domainNode = nodeDto.toDomain()
                domainNode to emptyList()
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse JSON", e)
        }

        rootNode.restoreParents()
        return rootNode to breadcrumbs
    }

    /**
     * Loads a single file into a UiNode. Used by SnapshotLibrarian for content fingerprinting.
     */
    fun loadNode(file: File): UiNode = parseFlexibleJson(file.readText()).first

    /**
     * LEGACY SUPPORT: Returns [Filename, Node] for older tests (2-argument constructor)
     */
    fun loadForParameterized(folderName: String): Collection<Array<Any>> {
        val path = "snapshots/$folderName"
        return loadSnapshots(path).map { (filename, node, _) ->
            arrayOf(filename, node)
        }
    }

    /**
     * NEW SUPPORT: Returns [Filename, Node, Breadcrumbs] for tests like InboxProcessorTest (3-argument constructor)
     */
    fun loadForParameterizedWithBreadcrumbs(folderName: String): Collection<Array<Any>> {
        val path = "snapshots/$folderName"
        return loadSnapshots(path).map { (filename, node, breadcrumbs) ->
            arrayOf(filename, node, breadcrumbs)
        }
    }
}
package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.domain.capture.dto.UiNodeDto
import cloud.trotter.dashbuddy.domain.capture.toDomain
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File

object TestResourceLoader {

    private val jsonParser = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Directory names that hold **staging** captures rather than committed corpus: raw,
     * unsorted device pulls a human is triaging. A malformed file there is expected traffic
     * (a truncated pull, a click envelope pasted in by hand), so it is skipped with a warning
     * — the triage tool's whole job is to survive the batch and report.
     *
     * Everything else is COMMITTED corpus, where the same skip is a silently lost regression
     * guard (#941, review §4.4 item 5): the file stays in git looking like coverage while no
     * test ever reads it. There, a parse failure is a test failure.
     *
     * Note `snapshots/UNKNOWN/negative/` is committed and therefore strict — the leaf
     * directory name is what decides, and it is `negative`, not `UNKNOWN`.
     */
    private val TOLERANT_STAGING_DIRS = setOf("INBOX", "UNKNOWN")

    /**
     * Loads snapshots and returns Triple: (Filename, Node, Breadcrumbs)
     *
     * Fails LOUD on an unparseable file under committed corpus; tolerates one under a
     * staging directory (see [TOLERANT_STAGING_DIRS]).
     */
    fun loadSnapshots(pathFromResources: String): List<Triple<String, UiNode, List<String>>> {
        val resourceDir = File("src/test/resources/$pathFromResources")

        if (!resourceDir.exists() || !resourceDir.isDirectory) return emptyList()

        val staging = resourceDir.name in TOLERANT_STAGING_DIRS

        return resourceDir.listFiles { _, name -> name.endsWith(".json") }
            ?.sorted()
            ?.mapNotNull { file ->
                try {
                    val (node, breadcrumbs) = parseFlexibleJson(file.readText())
                    Triple(file.name, node, breadcrumbs)
                } catch (e: Exception) {
                    if (!staging) {
                        throw IllegalStateException(
                            "Unparseable committed corpus file $pathFromResources/${file.name} " +
                                "— it is silently contributing NO coverage while sitting in git " +
                                "looking like it does (#941). Fix or delete it.",
                            e,
                        )
                    }
                    println("⚠️  Skipping unparseable staging file: ${file.name}")
                    null
                }
            }
            ?: emptyList()
    }

    fun isGolden(file: File): Boolean {
        return try {
            val content = file.readText()
            if (content.contains("\"isGolden\": true")) return true
            val wrapperDto = jsonParser.decodeFromString<SnapshotWrapperLegacy>(content)
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
            val rootObj = jsonElement.jsonObject

            when {
                // Live device capture: CaptureEnvelope — the UiNode tree lives under "payload".
                // BoundingBoxDto already deserializes the envelope's object bounds, and the
                // payload node shares UiNodeDto's serial names, so we just decode the payload.
                rootObj.containsKey("payload") -> {
                    val nodeDto = jsonParser.decodeFromJsonElement<UiNodeDto>(rootObj.getValue("payload"))
                    nodeDto.toDomain() to emptyList()
                }
                // Legacy wrapper written by the old DiskCaptureBus.
                rootObj.containsKey("root") -> {
                    val wrapperDto = jsonParser.decodeFromString<SnapshotWrapperLegacy>(jsonString)
                    wrapperDto.root.toDomain() to wrapperDto.breadcrumbs
                }
                // Bare UiNodeDto fixture (sorted test corpus).
                else -> {
                    val nodeDto = jsonParser.decodeFromString<UiNodeDto>(jsonString)
                    nodeDto.toDomain() to emptyList()
                }
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
     * Decode a [UiNode] from a [JsonElement] that IS a bare `UiNodeDto` — e.g. a CLICK capture's
     * `payload.node` (a click envelope's `payload` is `{node, screenTarget}`, NOT a bare node, so
     * [loadNode] / [parseFlexibleJson] would decode the wrong object). Used by SessionReplay's
     * click injection.
     */
    fun nodeFromElement(element: JsonElement): UiNode =
        jsonParser.decodeFromJsonElement<UiNodeDto>(element).toDomain().also { it.restoreParents() }

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

/**
 * Legacy wrapper DTO for backward-compatible loading of snapshot files
 * that were written with the old DiskCaptureBus (SnapshotWrapperDto format).
 * New captures use [CaptureEnvelope] format; this only exists for the test corpus.
 */
@Serializable
internal data class SnapshotWrapperLegacy(
    val timestamp: Long,
    val breadcrumbs: List<String> = emptyList(),
    val isGolden: Boolean = false,
    val root: UiNodeDto,
)
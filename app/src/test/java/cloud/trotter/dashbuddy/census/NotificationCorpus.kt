package cloud.trotter.dashbuddy.census

import cloud.trotter.dashbuddy.domain.capture.schema.RawNotificationSchema
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** Schema-aware discovery. Synthetic builder vectors are distinct from field notification coverage. */
internal object NotificationCorpus {
    const val SYNTHETIC_PREFIX = "notification_census/fixtures/"
    data class Fixture(val path: String, val raw: RawNotificationData, val platform: Platform) {
        val synthetic: Boolean get() = path.startsWith(SYNTHETIC_PREFIX)
        // Synthetic builder vectors and independent wire vectors have separate keys in this namespace.
        val exportPath: String get() = if (synthetic) "contract/notification/builder/${path.substringAfterLast('/')}" else path
    }

    val fixtures: List<Fixture> by lazy { discover(File("src/test/resources/snapshots")) }
    val fieldFixtures: List<Fixture> get() = fixtures.filterNot { it.synthetic }

    fun discover(directory: File): List<Fixture> = directory.walkTopDown()
        .onEnter { dir -> dir == directory || dir.name !in setOf("INBOX", "SENSITIVE") }
        .filter { it.isFile && it.extension == "json" }
        .filterNot { it.parentFile?.relativeTo(directory)?.invariantSeparatorsPath == "UNKNOWN" }
        .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
        .mapNotNull { file ->
            val path = file.relativeTo(directory).invariantSeparatorsPath
            // Never fall back to the permissive UiNode loader for notification envelopes.
            val root = try {
                Json.parseToJsonElement(file.readText()) as? JsonObject
            } catch (_: Exception) {
                error("Invalid corpus JSON: $path")
            }
            val schema = (root?.get("schemaId") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (schema != RawNotificationSchema.schemaId) {
                check(!path.startsWith(SYNTHETIC_PREFIX)) { "Synthetic notification schema missing: $path" }
                null
            } else {
                val raw = try {
                    RawNotificationSchema.deserialize(root.getValue("payload").toString())
                } catch (_: Exception) {
                    // No decoder exception/cause: it may quote notification values.
                    error("Invalid notification fixture: $path")
                }
                val platform = Platform.fromPackage(raw.packageName)
                check(platform != Platform.Unknown) { "Unattributed notification fixture: $path" }
                Fixture(path, raw, platform)
            }
        }.toList()
}

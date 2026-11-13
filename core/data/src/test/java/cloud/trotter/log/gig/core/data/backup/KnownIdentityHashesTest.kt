package cloud.trotter.log.gig.core.data.backup

import cloud.trotter.log.gig.core.database.GigLogDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #1615: a restore accepts a database only with Room's identity hash for its schema version. This test holds
 * [BackupFormat.KNOWN_IDENTITY_HASHES] to the exported schema JSON for every version from FIRST_BACKUP_DB_VERSION up
 * to the build's own, so a schema bump fails here until its hash is added.
 */
class KnownIdentityHashesTest {

    @Test
    fun `the known hashes are the exported schemas' hashes, one per restorable version`() {
        val schemas = File(repoRoot(), "core/database/schemas/${GigLogDatabase::class.java.name}")
        val expected = (BackupFormat.FIRST_BACKUP_DB_VERSION..GigLogDatabase.VERSION).associateWith { version ->
            val json = Json.parseToJsonElement(File(schemas, "$version.json").readText()).jsonObject
            json.getValue("database").jsonObject.getValue("identityHash").jsonPrimitive.content
        }
        assertEquals(expected, BackupFormat.KNOWN_IDENTITY_HASHES)
    }

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("no settings.gradle.kts above the test's working directory")
    }
}

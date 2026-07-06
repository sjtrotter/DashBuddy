package cloud.trotter.dashbuddy.core.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cheap **unit-level** guard (#690) — this one DOES gate PR CI (the `MigrationTestHelper`
 * migration-correctness tests are instrumented/androidTest and only run under
 * `:core:database:connectedAndroidTest`, which the PR workflow does not invoke).
 *
 * With the destructive fallback retired, a version bump that forgets to regenerate the exported
 * schema JSON (or bumps the schema without bumping the code) would ship a DB whose declared version
 * has no committed schema — an upgrade path that can only fail loudly at runtime on a device. This
 * test catches that class of mistake at build time by asserting:
 *
 *  1. an exported schema JSON exists for the version the code ships ([DashBuddyDatabase.VERSION],
 *     which is also the `@Database(version = …)` value — same SSOT const);
 *  2. [DashBuddyDatabase.VERSION] equals the **newest** committed schema file;
 *  3. every schema file's internal `database.version` matches its filename number (a mis-copied
 *     schema is caught);
 *  4. the committed schema versions are contiguous (no gap that would strand an upgrade path).
 */
class SchemaVersionGuardTest {

    private val schemaDir: File by lazy { locateSchemaDir() }

    private val schemaVersions: List<Int> by lazy {
        schemaDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            ?.sorted()
            ?: emptyList()
    }

    @Test
    fun exportedSchemaExistsForDeclaredVersion() {
        val schemaFile = File(schemaDir, "${DashBuddyDatabase.VERSION}.json")
        assertTrue(
            "No exported schema JSON for DB version ${DashBuddyDatabase.VERSION} " +
                "(expected ${schemaFile.path}). Bump the version → regenerate the schema " +
                "(exportSchema) → add the AutoMigration + its MigrationTestHelper case.",
            schemaFile.isFile,
        )
    }

    @Test
    fun declaredVersionMatchesNewestSchema() {
        assertTrue("No schema JSONs found under ${schemaDir.path}", schemaVersions.isNotEmpty())
        val newest = schemaVersions.max()
        assertEquals(
            "@Database version (DashBuddyDatabase.VERSION) must equal the newest committed schema. " +
                "If you bumped the schema, bump VERSION too; if you bumped VERSION, regenerate the schema.",
            newest,
            DashBuddyDatabase.VERSION,
        )
    }

    @Test
    fun eachSchemaFileDeclaresItsOwnFilenameVersion() {
        for (version in schemaVersions) {
            val file = File(schemaDir, "$version.json")
            val declared = Json.parseToJsonElement(file.readText())
                .jsonObject["database"]!!.jsonObject["version"]!!.jsonPrimitive.int
            assertEquals(
                "Schema ${file.name} declares database.version=$declared — a mis-copied/mis-named schema.",
                version,
                declared,
            )
        }
    }

    @Test
    fun committedSchemaVersionsAreContiguous() {
        assertTrue("No schema JSONs found under ${schemaDir.path}", schemaVersions.isNotEmpty())
        val expected = (schemaVersions.first()..schemaVersions.last()).toList()
        assertEquals(
            "Committed schema versions have a gap ($schemaVersions) — an upgrade path would be stranded.",
            expected,
            schemaVersions,
        )
    }

    /**
     * JVM unit tests run with the working directory at the module root (`core/database`), but be
     * robust to a repo-root cwd too. The exported schemas live under
     * `schemas/<db-class-fqcn>/` (see `room.schemaLocation` in build.gradle.kts).
     */
    private fun locateSchemaDir(): File {
        val rel = "schemas/${DashBuddyDatabase::class.java.name}"
        val candidates = listOf(
            File(rel),
            File("core/database", rel),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "Could not locate exported schema dir '$rel' (cwd=${File(".").absolutePath}). " +
                    "Tried: ${candidates.joinToString { it.path }}",
            )
    }
}

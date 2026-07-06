package cloud.trotter.dashbuddy.core.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Cheap **unit-level** guard (#690) — this one DOES gate PR CI (the `MigrationTestHelper`
 * migration-correctness tests are instrumented/androidTest and only run under
 * `:core:database:connectedAndroidTest`, which the PR workflow does not invoke).
 *
 * With the destructive fallback retired, a version bump that forgets its `AutoMigration` edge would
 * ship a DB whose declared version has no upgrade path — a loud runtime crash on a device. This test
 * catches that class of mistake at build time. The load-bearing check is
 * [migrationEdgesChainFromSupportedBaseToDeclaredVersion]: a **source scan** of the migration edges
 * (`@Database` is BINARY retention — see below — so the edges are read from source, not reflection),
 * which is exactly the forgotten-`AutoMigration` class the retired fallback used to mask.
 *
 * **What CI can and cannot catch here.** The schema-JSON checks (tests 1–3) run against files KSP
 * **regenerates during the build**, so in CI they can only ever see a freshly-exported, internally
 * consistent schema — they CANNOT catch a schema JSON that was never *committed*. That
 * commit-enforcement is a separate CI step (`Schemas committed (Room export drift)` in
 * `pr-check.yml`), not this test. These checks still earn their keep: they catch local-dev drift
 * (a VERSION bump run before an `exportSchema` regen) fast, without a device.
 */
class SchemaVersionGuardTest {

    /**
     * The oldest on-disk schema version this build supports upgrading FROM. On-disk versions below
     * this (and downgrades) are an intentional loud crash at startup (#690), with a fresh pre-crash
     * snapshot taken by `DatabaseBackup`. Bump this only when a floor is deliberately raised (dropping
     * an old migration edge), which is a data-migration decision, not a routine version bump.
     */
    private val supportedBase = 8

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
            "Committed schema *files* have a numbering gap ($schemaVersions). NB: this asserts the " +
                "exported-schema filenames are contiguous, NOT that a migration edge exists between " +
                "them — migrationEdgesChainFromSupportedBaseToDeclaredVersion() owns that claim.",
            expected,
            schemaVersions,
        )
    }

    /**
     * The load-bearing #690 guard: the committed **migration edges** must chain from [supportedBase]
     * to [DashBuddyDatabase.VERSION] with no gap. Source-scanned rather than reflected because
     * `@Database` (which carries `autoMigrations`) has **BINARY retention** — the `AutoMigration`
     * annotations are not visible at runtime. We scan the `@Database` source for `AutoMigration(from,
     * to)` and (if any) `DatabaseModule` for manual `Migration(from, to)` registrations, then walk
     * the edges from [supportedBase]: a forgotten edge (the exact mistake the retired destructive
     * fallback used to paper over) leaves a hole and fails here at build time, not loudly on a device.
     */
    @Test
    fun migrationEdgesChainFromSupportedBaseToDeclaredVersion() {
        val edgeRegex = Regex("""AutoMigration\s*\(\s*from\s*=\s*(\d+)\s*,\s*to\s*=\s*(\d+)""")
        val manualRegex = Regex("""\bMigration\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)""")

        val dbSource = locateSourceFile(
            "src/main/java/cloud/trotter/dashbuddy/core/database/DashBuddyDatabase.kt",
        ).readText()
        val moduleSource = locateSourceFile(
            "src/main/java/cloud/trotter/dashbuddy/core/database/di/DatabaseModule.kt",
        ).readText()

        val edges = buildList {
            edgeRegex.findAll(dbSource).forEach { m ->
                add(m.groupValues[1].toInt() to m.groupValues[2].toInt())
            }
            manualRegex.findAll(moduleSource).forEach { m ->
                add(m.groupValues[1].toInt() to m.groupValues[2].toInt())
            }
        }

        assertTrue(
            "No migration edges found by source scan. Expected AutoMigration(from,to) on @Database " +
                "chaining $supportedBase → ${DashBuddyDatabase.VERSION}.",
            edges.isNotEmpty(),
        )

        val edgesByFrom = edges.groupBy { it.first }
        var cur = supportedBase
        val visited = mutableSetOf<Int>()
        while (cur < DashBuddyDatabase.VERSION) {
            if (!visited.add(cur)) {
                fail("Migration edges form a cycle at v$cur (edges=$edges).")
                return
            }
            val next = edgesByFrom[cur]?.maxOfOrNull { it.second }
                ?: run {
                    fail(
                        "No migration edge from v$cur — the chain $supportedBase → " +
                            "${DashBuddyDatabase.VERSION} is broken (edges=$edges). Add the missing " +
                            "AutoMigration(from = $cur, to = ${cur + 1}).",
                    )
                    return
                }
            cur = next
        }
        assertEquals(
            "Migration edges overshoot the declared version (reached v$cur, edges=$edges).",
            DashBuddyDatabase.VERSION,
            cur,
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

    /** Locate a module source file, robust to a module-root or repo-root cwd (see [locateSchemaDir]). */
    private fun locateSourceFile(rel: String): File {
        val candidates = listOf(File(rel), File("core/database", rel))
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "Could not locate source file '$rel' (cwd=${File(".").absolutePath}). " +
                    "Tried: ${candidates.joinToString { it.path }}",
            )
    }
}

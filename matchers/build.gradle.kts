// #192 / N1 (#635) — the "matchers repo" build. Owns the JSON5 rule SOURCE
// (rules/*.json5) and canonicalizes it to the streamlined JSON the app consumes.
// In production this canonicalize step is the matchers repo's CI; here it is a
// Gradle task in an included build so the local dev loop needs NO publish step.
// See docs/adr/ADR-0009-rule-distribution-channels.md.
//
// kotlinx-serialization (the SAME JSON library the app compiles rules with, so
// there is ONE canonical parse/serialize definition, not a second copy) does the
// JSON5 parse + canonical re-serialize. It is loaded onto the build-script
// classpath below; the app's version catalog is not shared into an included build.
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File

buildscript {
    repositories { mavenCentral() }
    dependencies {
        // Kept in lockstep with the app's version catalog (kotlinSerialization = "1.10.0").
        classpath("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    }
}

plugins {
    base
}

// Coordinates the app build substitutes against when this build is included.
group = "cloud.trotter.matchers"
version = "0.0.0-local"

// ---------------------------------------------------------------------------
// JSON5 -> canonical JSON via kotlinx-serialization (D2).
//
// Parse leniently enough for JSON5 authoring affordances (comments + trailing
// commas) but nothing more (isLenient = false keeps unquoted keys / single
// quotes OUT — the source stays strict JSON plus comments/trailing-commas).
// Re-serialize deterministically: pretty, 2-space, insertion-order preserved.
//
// SEMANTIC INERTNESS: JsonElement parsing preserves object key insertion order
// (LinkedHashMap-backed), array element order (load-bearing for equal-priority
// rule match order), and numeric literals verbatim (JsonLiteral re-emits the
// source text). The only change vs. the hand-authored file is cosmetic
// whitespace (inlined arrays expand to one-element-per-line). The proof that
// this is behaviourally inert is ParseOutputGoldenTest staying green WITHOUT a
// regen flag (parse output derives from typed fields, not raw bytes).
// ---------------------------------------------------------------------------
@OptIn(ExperimentalSerializationApi::class)
fun canonicalizeJson5(src: String): String {
    val parser = Json {
        allowComments = true
        allowTrailingComma = true
        isLenient = false
    }
    val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }
    val element: JsonElement = parser.parseToJsonElement(src)
    // Trailing newline: POSIX-friendly and idempotent (parse ignores it, re-adds it).
    return pretty.encodeToString(JsonElement.serializer(), element) + "\n"
}

/** Cheap, schema-aware structural check (D3). Reads the top-level `required`
 * list from docs/rules.schema.json so it tracks the schema rather than a
 * hardcoded copy; full JSON-Schema validation is deferred to the matchers repo
 * CI (ADR-0009). Returns null if OK, else a human-readable reason. */
fun structuralReject(canonicalJson: String, schemaRequired: List<String>): String? {
    val obj = try {
        Json.parseToJsonElement(canonicalJson).jsonObject
    } catch (e: Exception) {
        return "not a JSON object: ${e.message}"
    }
    val missing = schemaRequired.filter { it !in obj.keys }
    if (missing.isNotEmpty()) return "missing required top-level key(s): $missing"
    return null
}

/** Top-level `required` array from docs/rules.schema.json (repo root). */
fun schemaRequiredKeys(): List<String> {
    val schemaFile = layout.projectDirectory.file("../docs/rules.schema.json").asFile
    if (!schemaFile.isFile) return listOf("format_version", "platform_id")
    val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject
    return schema["required"]?.let { req ->
        (req as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
    } ?: listOf("format_version", "platform_id")
}

val canonicalRulesDir: Provider<Directory> = layout.buildDirectory.dir("canonical/rules")

val canonicalizeRules = tasks.register("canonicalizeRules") {
    group = "matchers"
    description = "Canonicalize rules/*.json5 -> build/canonical/rules/*.json (streamlined JSON the app consumes)."
    val srcDir = layout.projectDirectory.dir("rules")
    val outDir = canonicalRulesDir
    inputs.dir(srcDir).withPropertyName("json5Source")
    outputs.dir(outDir).withPropertyName("canonicalOut")
    doLast {
        val out = outDir.get().asFile
        out.mkdirs()
        out.listFiles()?.forEach { it.delete() }
        val sources = srcDir.asFile.listFiles { f -> f.extension == "json5" }?.sortedBy { it.name } ?: emptyList()
        sources.forEach { f ->
            val canon = canonicalizeJson5(f.readText(Charsets.UTF_8))
            File(out, f.nameWithoutExtension + ".json").writeText(canon, Charsets.UTF_8)
            logger.lifecycle("canonicalized ${f.name} -> ${f.nameWithoutExtension}.json (${canon.toByteArray().size} bytes)")
        }
    }
}

// ---- Canonicalization PROOF (re-runnable): idempotency + structural schema check (D3).
// There is no committed canonical asset to byte-compare against anymore (the
// app consumes the GENERATED output — see #635). The audit anchors are instead:
// the committed JSON5 source + the deterministic committed canonicalizer + the
// app's ParseOutputGoldenTest. This task asserts the canonicalizer is a fixed
// point (canonicalize(canonicalize(x)) == canonicalize(x)) and each output has
// the schema's required top-level keys.
tasks.register("verifyMatchersCanonical") {
    group = "matchers"
    description = "PROOF: canonicalize is idempotent + each rules/*.json5 canonicalizes to schema-valid JSON."
    val srcDir = layout.projectDirectory.dir("rules")
    inputs.dir(srcDir).withPropertyName("json5Source")
    doLast {
        val required = schemaRequiredKeys()
        val sources = srcDir.asFile.listFiles { f -> f.extension == "json5" }?.sortedBy { it.name } ?: emptyList()
        if (sources.isEmpty()) throw GradleException("no rules/*.json5 sources found to verify")
        sources.forEach { f ->
            val once = canonicalizeJson5(f.readText(Charsets.UTF_8))
            val twice = canonicalizeJson5(once)
            if (once != twice) {
                throw GradleException(
                    "canonicalize is NOT idempotent for ${f.name} " +
                        "(${once.toByteArray().size} vs ${twice.toByteArray().size} bytes) — not a fixed point.",
                )
            }
            structuralReject(once, required)?.let { reason ->
                throw GradleException("canonical ${f.nameWithoutExtension}.json fails schema check: $reason")
            }
            logger.lifecycle("PROOF OK: ${f.name} canonicalizes idempotently to schema-valid JSON (${once.toByteArray().size} bytes).")
        }
    }
}

// Outgoing artifact for composite-build consumers (Option A dependency substitution).
val rulesElements: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, "matchers-rules"))
    }
}
artifacts {
    add(rulesElements.name, canonicalRulesDir) {
        builtBy(canonicalizeRules)
    }
}

tasks.named("assemble") { dependsOn(canonicalizeRules) }

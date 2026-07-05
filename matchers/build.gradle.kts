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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
//
// A platform source is EITHER a flat `<platform>.json5` file (e.g. uber) OR a
// `<platform>/` DIRECTORY of surface sub-files (e.g. doordash) that the merge
// step below folds into one combined element before this same serializer runs
// — so both paths emit ONE canonical `<platform>.json` and there is a single
// serializer definition (`canonicalizeElement`), #639.
// ---------------------------------------------------------------------------
@OptIn(ExperimentalSerializationApi::class)
fun canonicalParser(): Json = Json {
    allowComments = true
    allowTrailingComma = true
    isLenient = false
}

val canonicalPretty: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** Deterministic canonical serialization of an ALREADY-PARSED rules element.
 * The one serializer both the flat-file and merged-directory paths route
 * through. Trailing newline is POSIX-friendly and idempotent (parse ignores it,
 * re-adds it). */
fun canonicalizeElement(element: JsonElement): String =
    canonicalPretty.encodeToString(JsonElement.serializer(), element) + "\n"

/** Flat-file path: JSON5 source text -> canonical JSON (unchanged behaviour). */
fun canonicalizeJson5(src: String): String =
    canonicalizeElement(canonicalParser().parseToJsonElement(src))

/** Unknown top-level key reject for a flat `<platform>.json5` source (#647).
 * Kept out of `canonicalizeJson5` so that path stays a pure serializer (it is
 * also re-fed a merged canonical element during the idempotency proof). */
fun rejectUnknownFlatKeys(src: String, sourceLabel: String) {
    val obj = canonicalParser().parseToJsonElement(src).jsonObject
    val errs = unknownTopLevelKeyErrors(obj, FLAT_TOP_LEVEL_KEYS, sourceLabel)
    if (errs.isNotEmpty()) throw GradleException(errs.joinToString("; "))
}

/** Rule sections, in the fixed canonical key order that follows the metadata. */
val RULE_SECTIONS: List<String> = listOf("screens", "clicks", "notifications")

// ---------------------------------------------------------------------------
// Build-time fail-loud guards on the rule-source ingestion boundary (#647).
//
// These make two silent-drop failure modes LOUD at canonicalize time, in the
// spirit of the #639 dup-id reject. Neither is reachable on today's sources;
// both harden the multi-platform / untrusted-CDN-rules path (#192/M2).
//
// SSOT: the allowed top-level key set is derived once here from the metadata +
// section vocabulary (`RULE_SECTIONS` above), then composed per file-kind — no
// hand-maintained second copy per path.
// ---------------------------------------------------------------------------
/** Metadata keys carried by a flat file / a directory's `_manifest.json5`. */
val METADATA_KEYS: List<String> = listOf("format_version", "platform_id")

/** The editor `$schema` pointer is authoring metadata, allowed on every kind. */
val SCHEMA_KEY: String = "\$schema"

/** A flat `<platform>.json5` carries metadata + all sections (e.g. uber.json5). */
val FLAT_TOP_LEVEL_KEYS: List<String> = listOf(SCHEMA_KEY) + METADATA_KEYS + RULE_SECTIONS

/** A directory `_manifest.json5` is metadata ONLY — rules live in the surface
 * sub-files; any section array here would be silently ignored by the merge. */
val MANIFEST_TOP_LEVEL_KEYS: List<String> = listOf(SCHEMA_KEY) + METADATA_KEYS

/** A surface sub-file carries only sections — metadata is read from the
 * manifest, so a stray metadata key here would be silently ignored. */
val SURFACE_TOP_LEVEL_KEYS: List<String> = listOf(SCHEMA_KEY) + RULE_SECTIONS

/**
 * Unknown top-level key reject (#647, deliverable 1). Any key on a rule-source
 * object that is NOT in the allowed set for its file-kind is an author error —
 * most importantly a typo'd section key (`"screen":` for `"screens":`, or a
 * mis-named array), which today silently drops those rules from the merge (only
 * corpus-covered rules would surface via the goldens). Returns human-readable
 * error strings (empty when clean), naming the source and the offending key.
 */
fun unknownTopLevelKeyErrors(
    obj: JsonObject,
    allowed: List<String>,
    sourceLabel: String,
): List<String> =
    obj.keys.filter { it !in allowed }.map {
        "unknown top-level key '$it' in $sourceLabel (allowed: $allowed)"
    }

/**
 * Platform file/dir collision reject (#647, deliverable 2). If both a flat
 * `<platform>.json5` and a `<platform>/` directory exist for one platform, both
 * emit `<platform>.json` and the later-processed one silently clobbers the
 * other. Impossible today (each platform is one or the other). Takes
 * (platform, sourceLabel) pairs so it is pure/testable independent of the
 * filesystem; returns error strings (empty when clean).
 */
fun platformCollisionErrors(sources: List<Pair<String, String>>): List<String> {
    val byPlatform = LinkedHashMap<String, MutableList<String>>()
    sources.forEach { (platform, label) -> byPlatform.getOrPut(platform) { mutableListOf() } += label }
    return byPlatform.filterValues { it.size > 1 }.map { (platform, labels) ->
        "platform '$platform' has colliding sources (${labels.joinToString(", ")}): a platform must be " +
            "EITHER a flat <platform>.json5 file OR a <platform>/ directory, not both"
    }
}

/** Map the canonicalize entries (flat files + platform dirs) to (platform,
 * sourceLabel) pairs for the collision check. */
fun platformSources(entries: List<File>): List<Pair<String, String>> =
    entries.map { e ->
        if (e.isDirectory) e.name to "${e.name}/" else e.nameWithoutExtension to e.name
    }

/**
 * Directory path (#639): merge a platform DIRECTORY of surface sub-files into ONE
 * combined element. Reads `<dir>/_manifest.json5` for `format_version` +
 * `platform_id` ONLY (any rule arrays it carries are ignored — metadata lives in
 * the manifest, rules live in the surface files), then concatenates the
 * `screens`/`clicks`/`notifications` arrays of every OTHER `*.json5` sub-file in
 * sorted-name order (deterministic; mirrors the runtime loader's sorted read).
 * The combined object is built in fixed key order `{format_version, platform_id,
 * screens, clicks, notifications}` and handed to `canonicalizeElement`, so the
 * merged output is byte-identical to canonicalizing the equivalent flat file.
 *
 * Returns the merged element PLUS a list of duplicate-id errors (empty when
 * clean). A dup rule id across sub-files is an author error the caller fails the
 * build on: this is the canonicalize-time analog of the runtime #624/#633 rejects
 * — and strictly better, because a dup that reached the merged asset would make
 * the loader's #624 check SKIP the whole platform behind the #432 fail-closed
 * gate (a silent field outage), whereas failing here is loud at author time.
 */
@OptIn(ExperimentalSerializationApi::class)
fun mergeRuleDirectory(dir: File): Pair<JsonElement, List<String>> {
    val parser = canonicalParser()
    val manifestFile = File(dir, "_manifest.json5")
    if (!manifestFile.isFile) {
        throw GradleException(
            "rules directory '${dir.name}/' has no _manifest.json5 (needs format_version + platform_id)",
        )
    }
    val manifest = parser.parseToJsonElement(manifestFile.readText(Charsets.UTF_8)).jsonObject
    // Unknown-key reject (#647): the manifest is metadata ONLY — a section array
    // here would be silently ignored by the merge below.
    val keyErrors = unknownTopLevelKeyErrors(
        manifest, MANIFEST_TOP_LEVEL_KEYS, "${dir.name}/_manifest.json5",
    ).toMutableList()
    val metadata = linkedMapOf<String, JsonElement>(
        "format_version" to (manifest["format_version"]
            ?: throw GradleException("${dir.name}/_manifest.json5 missing format_version")),
        "platform_id" to (manifest["platform_id"]
            ?: throw GradleException("${dir.name}/_manifest.json5 missing platform_id")),
    )

    val subFiles = dir.listFiles { f -> f.extension == "json5" && f.name != "_manifest.json5" }
        ?.sortedBy { it.name } ?: emptyList()

    val sections: Map<String, MutableList<JsonElement>> = RULE_SECTIONS.associateWith { mutableListOf() }
    // id -> first sub-file that declared it, so a collision names both files.
    val idOwner = LinkedHashMap<String, String>()
    val dupErrors = mutableListOf<String>()

    subFiles.forEach { f ->
        val obj = parser.parseToJsonElement(f.readText(Charsets.UTF_8)).jsonObject
        // Unknown-key reject (#647): a surface sub-file carries only sections
        // (`$schema` aside) — a typo'd section key silently drops its rules.
        keyErrors += unknownTopLevelKeyErrors(obj, SURFACE_TOP_LEVEL_KEYS, "${dir.name}/${f.name}")
        RULE_SECTIONS.forEach { section ->
            val arr = obj[section] as? JsonArray ?: return@forEach
            arr.forEach { rule ->
                val id = (rule as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.content }
                if (id != null) {
                    val prev = idOwner[id]
                    if (prev != null) {
                        dupErrors += "duplicate rule id '$id' across ${dir.name}/ sub-files: $prev, ${f.name}"
                    } else {
                        idOwner[id] = f.name
                    }
                }
                sections.getValue(section) += rule
            }
        }
    }

    // Unknown top-level keys are structural author errors (like a missing
    // manifest) — fail loud immediately, listing every offending file+key.
    if (keyErrors.isNotEmpty()) throw GradleException(keyErrors.joinToString("; "))

    // metadata (format_version, platform_id) first, then the three sections in
    // fixed order — LinkedHashMap `plus` preserves insertion order.
    val merged = JsonObject(metadata + RULE_SECTIONS.associateWith { JsonArray(sections.getValue(it)) })
    return merged to dupErrors
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
    description = "Canonicalize rules/*.json5 files + rules/<platform>/ directories -> build/canonical/rules/*.json (streamlined JSON the app consumes)."
    val srcDir = layout.projectDirectory.dir("rules")
    val outDir = canonicalRulesDir
    // inputs.dir tracks the whole tree recursively, so sub-files under a
    // <platform>/ directory participate in up-to-date checks.
    inputs.dir(srcDir).withPropertyName("json5Source")
    outputs.dir(outDir).withPropertyName("canonicalOut")
    doLast {
        val out = outDir.get().asFile
        out.mkdirs()
        out.listFiles()?.forEach { it.delete() }
        // Each entry is a platform: a flat `<platform>.json5` file OR a
        // `<platform>/` directory of surface sub-files (#639). Both emit one
        // canonical `<platform>.json`.
        val entries = srcDir.asFile.listFiles { f -> f.isDirectory || f.extension == "json5" }
            ?.sortedBy { it.name } ?: emptyList()
        if (entries.isEmpty()) {
            throw GradleException("no rules sources (rules/*.json5 files or rules/<platform>/ directories) found")
        }
        // Platform file/dir collision reject (#647): fail before any output is
        // written, so a colliding flat file can't silently clobber a merged dir.
        platformCollisionErrors(platformSources(entries)).let {
            if (it.isNotEmpty()) throw GradleException(it.joinToString("; "))
        }
        entries.forEach { entry ->
            val platform: String
            val canon: String
            if (entry.isDirectory) {
                val (merged, dupErrors) = mergeRuleDirectory(entry)
                if (dupErrors.isNotEmpty()) throw GradleException(dupErrors.joinToString("; "))
                platform = entry.name
                canon = canonicalizeElement(merged)
            } else {
                platform = entry.nameWithoutExtension
                val text = entry.readText(Charsets.UTF_8)
                rejectUnknownFlatKeys(text, entry.name) // #647: parity with the dir path
                canon = canonicalizeJson5(text)
            }
            File(out, "$platform.json").writeText(canon, Charsets.UTF_8)
            logger.lifecycle("canonicalized ${entry.name} -> $platform.json (${canon.toByteArray().size} bytes)")
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
    description = "PROOF: canonicalize is idempotent + each platform (flat file OR merged directory) canonicalizes to schema-valid, dup-id-free JSON."
    val srcDir = layout.projectDirectory.dir("rules")
    inputs.dir(srcDir).withPropertyName("json5Source")
    doLast {
        val required = schemaRequiredKeys()
        val entries = srcDir.asFile.listFiles { f -> f.isDirectory || f.extension == "json5" }
            ?.sortedBy { it.name } ?: emptyList()
        if (entries.isEmpty()) throw GradleException("no rules sources found to verify")
        // Platform file/dir collision reject (#647).
        platformCollisionErrors(platformSources(entries)).let {
            if (it.isNotEmpty()) throw GradleException(it.joinToString("; "))
        }
        entries.forEach { entry ->
            val platform: String
            // `once` is the canonical output for this platform (merged for a
            // directory, single-file for a flat file).
            val once: String
            if (entry.isDirectory) {
                val (merged, dupErrors) = mergeRuleDirectory(entry)
                if (dupErrors.isNotEmpty()) {
                    throw GradleException("duplicate rule id(s) in ${entry.name}/: ${dupErrors.joinToString("; ")}")
                }
                platform = entry.name
                once = canonicalizeElement(merged)
            } else {
                platform = entry.nameWithoutExtension
                val text = entry.readText(Charsets.UTF_8)
                rejectUnknownFlatKeys(text, entry.name) // #647
                once = canonicalizeJson5(text)
            }
            // Idempotency on the (possibly merged) canonical output: feeding it
            // back through the single-file canonicalizer must reproduce it exactly.
            val twice = canonicalizeJson5(once)
            if (once != twice) {
                throw GradleException(
                    "canonicalize is NOT idempotent for ${entry.name} " +
                        "(${once.toByteArray().size} vs ${twice.toByteArray().size} bytes) — not a fixed point.",
                )
            }
            structuralReject(once, required)?.let { reason ->
                throw GradleException("canonical $platform.json fails schema check: $reason")
            }
            logger.lifecycle("PROOF OK: ${entry.name} canonicalizes idempotently to schema-valid, dup-id-free JSON (${once.toByteArray().size} bytes).")
        }
    }
}

// ---- Hardening-guard PROOF (#647). The two fail-loud guards are unreachable on
// today's clean sources, so `canonicalizeRules`/`verifyMatchersCanonical` only
// prove they DON'T false-positive. This task drives the pure guard functions
// with SYNTHETIC bad + clean inputs to prove they actually FIRE — the honest
// negative test the build-script has no unit-test source set to host otherwise.
tasks.register("verifyMatchersHardening") {
    group = "matchers"
    description = "PROOF (#647): the unknown-top-level-key and platform file/dir collision guards fire on bad inputs and pass clean inputs."
    doLast {
        val parser = canonicalParser()
        fun require(cond: Boolean, msg: String) {
            if (!cond) throw GradleException("verifyMatchersHardening FAILED: $msg")
        }
        fun obj(json: String): JsonObject = parser.parseToJsonElement(json).jsonObject

        // Deliverable 1 — unknown top-level key.
        val typo = unknownTopLevelKeyErrors(
            obj("""{ "${SCHEMA_KEY}": "x", "screen": [] }"""), SURFACE_TOP_LEVEL_KEYS, "doordash/offer.json5",
        )
        require(
            typo.size == 1 && typo.single().contains("'screen'") && typo.single().contains("offer.json5"),
            "typo'd surface section key not rejected: $typo",
        )
        require(
            unknownTopLevelKeyErrors(
                obj("""{ "${SCHEMA_KEY}": "x", "screens": [], "clicks": [], "notifications": [] }"""),
                SURFACE_TOP_LEVEL_KEYS, "doordash/offer.json5",
            ).isEmpty(),
            "clean surface sub-file wrongly flagged",
        )
        val manifestBad = unknownTopLevelKeyErrors(
            obj("""{ "format_version": 2, "platform_id": "p", "screens": [] }"""),
            MANIFEST_TOP_LEVEL_KEYS, "doordash/_manifest.json5",
        )
        require(
            manifestBad.size == 1 && manifestBad.single().contains("'screens'"),
            "manifest section key (silently ignored by merge) not rejected: $manifestBad",
        )
        require(
            unknownTopLevelKeyErrors(
                obj("""{ "${SCHEMA_KEY}": "x", "format_version": 2, "platform_id": "p" }"""),
                MANIFEST_TOP_LEVEL_KEYS, "doordash/_manifest.json5",
            ).isEmpty(),
            "clean manifest wrongly flagged",
        )
        require(
            unknownTopLevelKeyErrors(
                obj("""{ "${SCHEMA_KEY}": "x", "format_version": 2, "platform_id": "p", "screens": [], "clicks": [], "notifications": [] }"""),
                FLAT_TOP_LEVEL_KEYS, "uber.json5",
            ).isEmpty(),
            "clean flat file wrongly flagged",
        )

        // Deliverable 2 — platform file/dir collision.
        val collide = platformCollisionErrors(
            listOf("uber" to "uber.json5", "uber" to "uber/", "doordash" to "doordash/"),
        )
        require(
            collide.size == 1 && collide.single().contains("'uber'") &&
                collide.single().contains("uber.json5") && collide.single().contains("uber/"),
            "flat/dir collision for one platform not detected: $collide",
        )
        require(
            platformCollisionErrors(listOf("uber" to "uber.json5", "doordash" to "doordash/")).isEmpty(),
            "distinct platforms wrongly flagged as colliding",
        )

        logger.lifecycle("PROOF OK (#647): unknown-key + collision guards fire on bad inputs and pass clean inputs.")
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

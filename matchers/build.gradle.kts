// SPIKE (#192) — the "matchers repo" build. Owns the JSON5 rule SOURCE
// (rules/*.json5) and canonicalizes it to the streamlined JSON the app consumes.
// In production this canonicalize step is the matchers repo's CI; here it is a
// Gradle task so the local dev loop needs NO publish step.
plugins {
    base
}

// Coordinates the app build substitutes against when this build is included.
group = "cloud.trotter.matchers"
version = "0.0.0-local"

// ---------------------------------------------------------------------------
// Dependency-free JSON5 -> canonical stripper.
//
// SPIKE-GRADE: this is a text-preserving comment/trailing-comma remover, NOT a
// JSON5 parser. It is string-aware (won't touch // or , inside string values)
// and preserves all other bytes, which is exactly what makes canonicalization
// byte-identical to the committed pretty-printed rule file. Production wants a
// real JSON5 parser + JSON-Schema validation + a canonical re-serializer
// (see ADR-0009). Kept intentionally minimal for the spike.
// ---------------------------------------------------------------------------
fun stripComments(src: String): String {
    val sb = StringBuilder()
    var i = 0; val n = src.length; var inStr = false
    while (i < n) {
        val c = src[i]
        if (inStr) {
            sb.append(c)
            if (c == '\\' && i + 1 < n) { sb.append(src[i + 1]); i += 2; continue }
            if (c == '"') inStr = false
            i++; continue
        }
        if (c == '"') { inStr = true; sb.append(c); i++; continue }
        if (c == '/' && i + 1 < n && src[i + 1] == '/') {
            var j = i + 2
            while (j < n && src[j] != '\n') j++
            while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\t')) sb.deleteCharAt(sb.length - 1)
            val lineBlank = sb.isEmpty() || sb.last() == '\n'
            i = j
            if (lineBlank && i < n && src[i] == '\n') i++ // whole-line comment: drop its newline too
            continue
        }
        if (c == '/' && i + 1 < n && src[i + 1] == '*') {
            var j = i + 2
            while (j + 1 < n && !(src[j] == '*' && src[j + 1] == '/')) j++
            i = j + 2; continue
        }
        sb.append(c); i++
    }
    return sb.toString()
}

fun stripTrailingCommas(src: String): String {
    val sb = StringBuilder()
    var i = 0; val n = src.length; var inStr = false
    while (i < n) {
        val c = src[i]
        if (inStr) {
            sb.append(c)
            if (c == '\\' && i + 1 < n) { sb.append(src[i + 1]); i += 2; continue }
            if (c == '"') inStr = false
            i++; continue
        }
        if (c == '"') { inStr = true; sb.append(c); i++; continue }
        if (c == ',') {
            var j = i + 1
            while (j < n && src[j].isWhitespace()) j++
            if (j < n && (src[j] == '}' || src[j] == ']')) { i++; continue } // drop trailing comma, keep whitespace
        }
        sb.append(c); i++
    }
    return sb.toString()
}

fun canonicalizeJson5(src: String): String = stripTrailingCommas(stripComments(src))

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
            logger.lifecycle("canonicalized ${f.name} -> ${f.nameWithoutExtension}.json (${canon.length} bytes)")
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

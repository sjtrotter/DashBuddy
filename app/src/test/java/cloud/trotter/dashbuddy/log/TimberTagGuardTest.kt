package cloud.trotter.dashbuddy.log

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-scan guard for CLAUDE.md Principle 7 (semantic, PII-safe logging): every
 * `Timber.i(`/`Timber.w(`/`Timber.e(` call site in MAIN source must be **tagged** —
 * `Timber.tag("...")` immediately ahead of the call, on the same expression — never the
 * catch-all default tag (`App`). Three manual cleanup passes (#679/#692/#763) already chased
 * this drift down by hand; this test is the permanent backstop (#764) so it can't creep back.
 * `Timber.d(`/`Timber.v(` (DEBUG/VERBOSE) are out of scope — Principle 7 only requires a stable
 * tag from INFO up, since that's the exported "shareable" stream a user can send as a bug
 * report.
 *
 * Follows the same repo-root **source scan** shape as `SchemaVersionGuardTest`
 * (`:core:database`) — read `.kt` files off disk rather than reflecting over compiled code,
 * because this is a textual/style property (which token sits immediately before the call),
 * not something the Kotlin type system encodes.
 *
 * **Detection is a plain regex, not a real Kotlin parse — by design** (the spec for #764 is
 * explicit: keep the scanner simple and predictable, don't build clever heuristics). A call
 * site is "bare" when `Timber` is followed by nothing but whitespace before the `.i(`/`.w(`/
 * `.e(`:
 * ```
 * Timber.i("...")            // bare — FLAGGED
 * Timber
 *     .w("...")               // bare — FLAGGED (whitespace-only gap, still multiline)
 * Timber.tag("X").i("...")   // tagged — not flagged (`.tag(...)` sits in the gap)
 * Timber.tag("X")
 *     .e(e, "...")            // tagged — not flagged, multiline chain
 * ```
 * A receiver that legitimately re-tags — e.g. `private val log = Timber.tag(TAG)` then
 * `log.i(...)` elsewhere — is not textually `Timber.i(` at all, so it's invisible to this
 * scanner by construction. That is an accepted blind spot per the #764 spec ("wrappers/
 * receivers that legitimately re-tag are handled or allowlisted explicitly — do not build
 * clever heuristics"), not a gap this test claims to close; today's tree has no such receiver
 * pattern in main source (verified by hand at the time this guard was written).
 *
 * **Ratchet**: current offenders are frozen in [allowlistFile] as `<path> <count>` lines — one
 * per offending file, repo-relative, count = bare-site count at freeze time. This is a
 * **strict** ratchet in both directions (SSOT for "how much debt is left", never allowed to go
 * stale):
 * - [everyUntaggedInfoPlusSiteIsAccountedFor] — a file not on the list may have zero untagged
 *   sites; a listed file's actual count may not exceed its allowlisted number (a new bare call
 *   added to an already-listed file is a regression, not free debt).
 * - [allowlistHasNoStaleEntries] — a listed file's actual count may not fall *below* its
 *   allowlisted number either: tagging a call means lowering (or deleting, at 0) that file's
 *   line in the same change, so the list is always an exact mirror of live debt, not a ceiling
 *   nobody revisits.
 */
class TimberTagGuardTest {

    /** Module `src/main` roots to scan — every module that can carry `Timber` calls. */
    private val roots = listOf(
        "app/src/main",
        "core/database/src/main",
        "core/data/src/main",
        "core/datastore/src/main",
        "core/designsystem/src/main",
        "core/location/src/main",
        "core/network/src/main",
        "core/pipeline/src/main",
        "core/state/src/main",
        "domain/src/main",
    )

    /**
     * Matches `Timber` immediately followed by (only whitespace, then) `.i(`/`.w(`/`.e(` — i.e.
     * a bare, untagged call. `Timber.tag("X").i(` does NOT match: the `.tag("X").` text between
     * `Timber` and `.i(` is not whitespace, so the whitespace-only gap this pattern requires is
     * broken. See the class doc for the multiline case.
     */
    private val bareCallRegex = Regex("""Timber\s*\.\s*(?:i|w|e)\s*\(""")

    private val repoRoot: File by lazy { locateRepoRoot() }

    private val allowlistFile: File by lazy {
        File(repoRoot, "app/src/test/resources/timber-tag-guard-allowlist.txt")
    }

    private val allowlistRelPath: String by lazy {
        allowlistFile.relativeTo(repoRoot).path.replace(File.separatorChar, '/')
    }

    /** repo-relative path -> allowlisted (frozen) bare-call count. */
    private val allowlist: Map<String, Int> by lazy {
        allowlistFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val parts = line.split(Regex("\\s+"))
                require(parts.size == 2) {
                    "Malformed $allowlistRelPath line: '$line' (expected '<path> <count>')"
                }
                val count = parts[1].toIntOrNull()
                    ?: error("Malformed count in $allowlistRelPath line: '$line'")
                parts[0] to count
            }
    }

    /** repo-relative path -> live count of bare (untagged) Timber.i/w/e( sites found today. */
    private val liveOffendersByFile: Map<String, Int> by lazy {
        val result = mutableMapOf<String, Int>()
        for (root in roots) {
            val rootDir = File(repoRoot, root)
            if (!rootDir.isDirectory) continue
            rootDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val count = bareCallRegex.findAll(file.readText()).count()
                    if (count > 0) {
                        val relPath = file.relativeTo(repoRoot).path.replace(File.separatorChar, '/')
                        result[relPath] = count
                    }
                }
        }
        result
    }

    @Test
    fun everyUntaggedInfoPlusSiteIsAccountedFor() {
        val problems = mutableListOf<String>()
        for ((path, liveCount) in liveOffendersByFile) {
            val allowedCount = allowlist[path]
            when {
                allowedCount == null -> problems += "$path: $liveCount untagged " +
                    "Timber.i/w/e( site(s), file is not on the allowlist at all"

                liveCount > allowedCount -> problems += "$path: $liveCount untagged " +
                    "Timber.i/w/e( site(s), exceeds the allowlisted $allowedCount " +
                    "(a NEW bare call was added to an already-allowlisted file)"
            }
        }
        assertTrue(
            "Untagged Timber.i(/w(/e( call site(s) found beyond the frozen allowlist " +
                "(CLAUDE.md Principle 7 — every INFO+ log call needs a stable " +
                "Timber.tag(\"YourTag\") immediately ahead of it, never the catch-all default " +
                "tag). Fix by adding Timber.tag(\"YourTag\") before the call " +
                "(Timber.tag(\"X\").i(...) and the multiline Timber.tag(\"X\")\\n    .i(...) " +
                "both satisfy the guard); if this is accepted new debt instead, add/raise the " +
                "file's line in $allowlistRelPath. Problems:\n" +
                problems.sorted().joinToString("\n"),
            problems.isEmpty(),
        )
    }

    @Test
    fun allowlistHasNoStaleEntries() {
        val problems = mutableListOf<String>()
        for ((path, allowedCount) in allowlist) {
            val liveCount = liveOffendersByFile[path] ?: 0
            if (liveCount < allowedCount) {
                problems += "$path: allowlisted for $allowedCount but only $liveCount " +
                    "untagged site(s) remain — lower the count in $allowlistRelPath " +
                    "(or delete the line once it reaches 0)"
            }
        }
        assertTrue(
            "Allowlist entries no longer match live debt — this is a STRICT ratchet, so the " +
                "list must shrink as call sites get tagged, never sit stale. Problems:\n" +
                problems.sorted().joinToString("\n"),
            problems.isEmpty(),
        )
    }

    /**
     * JVM unit tests run with the working directory at the `:app` module root, but be robust to
     * a repo-root (or any nested) cwd too — walk up until `settings.gradle.kts` is found.
     */
    private fun locateRepoRoot(): File {
        var dir = File(".").absoluteFile.normalize()
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
                ?: error(
                    "Could not locate repo root (settings.gradle.kts) walking up from " +
                        File(".").absoluteFile.normalize().path,
                )
        }
    }
}

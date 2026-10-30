package cloud.trotter.log.gig.core.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1427: installation owns minting and every pending deadline write. */
class WakeIdInstallGuardTest {
    private val statePath = "core/state/src/main/java/cloud/trotter/log/gig/core/state/"
    private val domainPath = "domain/src/main/java/cloud/trotter/log/gig/domain/state/"
    private val installers = setOf("withWakeIdIfDeadlineMoved", "modeResumeWindow", "sessionPayWindow")
    private val constructors = mapOf(
        "PendingDestructive" to "withWakeIdIfDeadlineMoved",
        "PendingModeResume" to "modeResumeWindow",
        "PendingSessionPay" to "sessionPayWindow",
        "PendingWake" to "pauseSafetyWindow",
    )

    @Test
    fun `main code mints only in the installer and installs every pending deadline`() {
        val root = locateRepoRoot()
        val files = root.walkTopDown()
            .onEnter { it.name !in setOf(".git", ".gradle", "build", ".idea") }
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
            .toList()
        val paths = files.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        assertTrue("No WakeInstall.kt scanned", statePath + "WakeInstall.kt" in paths)
        assertTrue("No PlatformRegion.kt scanned", domainPath + "PlatformRegion.kt" in paths)
        assertTrue("No stepper scanned", statePath + "PlatformRegionStepper.kt" in paths)
        val problems = mutableListOf<String>()
        files.forEach { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            val source = codeOnly(file.readText())
            if (Regex("\\bmintWakeId\\s*\\(").containsMatchIn(source) &&
                path !in setOf(domainPath + "PlatformRegion.kt", statePath + "WakeInstall.kt")
            ) problems += "$path: mintWakeId belongs to WakeInstall.kt"
            if (Regex("\\bWakeId\\s*\\(").containsMatchIn(source) &&
                path != domainPath + "PlatformRegion.kt"
            ) problems += "$path: WakeId construction belongs to PlatformRegion.mintWakeId"
            if (path.startsWith("core/state/src/main/") && path != statePath + "WakeInstall.kt") {
                problems += installationProblems(source).map { "$path:$it" }
            }
        }
        assertTrue(problems.sorted().joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `guard rejects a missing installer or inherited id on a deadline write`() {
        val invalid = listOf(
            "PendingWake(10L, old.wakeId)",
            "PendingDestructive(kind, since = 1L, deadline = 2L, wakeId = old.wakeId)",
            "old.copy(deadline = 2L)",
            "r.withWakeIdIfDeadlineMoved(old, start) { id -> old.copy(deadline = 2L, wakeId = old.wakeId) }",
            "r.sessionPayWindow { id -> old.copy(deadline = 2L) }; old.copy(deadline = 3L, wakeId = id)",
        )
        invalid.forEach { assertTrue(it, installationProblems(it).isNotEmpty()) }
        assertTrue(installationProblems(
            "r.withWakeIdIfDeadlineMoved(old, start) { id -> old.copy(deadline = minOf(2L, 3L), wakeId = id) }",
        ).isEmpty())
        assertTrue(installationProblems(
            "r.sessionPayWindow { id -> PendingSessionPay(value = 1.0, since = 1L, deadline = 2L, wakeId = id) }",
        ).isEmpty())
    }

    private data class Installer(val name: String, val start: Int, val end: Int, val id: String)

    private fun installationProblems(source: String): List<String> {
        val windows = Regex("\\b(${installers.joinToString("|")})\\s*(?=[({])").findAll(source).mapNotNull { match ->
            var at = match.range.last + 1
            if (source[at] == '(') at = closing(source, at) + 1
            while (at < source.length && source[at].isWhitespace()) at++
            if (source.getOrNull(at) != '{') return@mapNotNull null
            val parameter = Regex("\\{\\s*(\\w+)\\s*->").find(source, at)
                ?.takeIf { it.range.first == at } ?: return@mapNotNull null
            Installer(match.groupValues[1], at, closing(source, at), parameter.groupValues[1])
        }.toList()
        return Regex("\\b(PendingDestructive|PendingModeResume|PendingSessionPay|PendingWake|copy)\\s*\\(")
            .findAll(source).mapNotNull { call ->
                val name = call.groupValues[1]
                val open = call.range.last
                val close = closing(source, open)
                val args = directArguments(source.substring(open + 1, close))
                // All named deadline copies are guarded, conservatively including unknown receivers.
                if (name == "copy" && !Regex("\\bdeadline\\s*=(?!=)").containsMatchIn(args)) return@mapNotNull null
                val window = windows.lastOrNull { call.range.first > it.start && close < it.end }
                val correctInstaller = window != null && (name == "copy" || constructors[name] == window.name)
                val correctId = window != null && Regex("\\bwakeId\\s*=\\s*${window.id}\\s*(,|$)").containsMatchIn(args)
                if (correctInstaller && correctId) null
                else "${source.take(call.range.first).count { it == '\n' } + 1}: $name must take wakeId from its installer"
            }.toList()
    }

    /** Keep top-level named arguments; nested calls/lambdas cannot satisfy the id check. */
    private fun directArguments(source: String): String {
        var depth = 0
        return source.map { c ->
            when (c) {
                '(', '[', '{' -> { depth++; ' ' }
                ')', ']', '}' -> { depth--; ' ' }
                else -> if (depth == 0) c else ' '
            }
        }.joinToString("")
    }

    private fun closing(source: String, open: Int): Int {
        val end = when (source[open]) { '(' -> ')'; '{' -> '}'; else -> error("Not a delimiter") }
        var depth = 0
        for (i in open until source.length) {
            if (source[i] == source[open]) depth++
            if (source[i] == end) {
                depth--
                if (depth == 0) return i
            }
        }
        error("Unclosed delimiter at $open")
    }

    /** Mask strings and comments, retaining offsets and line numbers for useful failures. */
    private fun codeOnly(source: String): String =
        Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\n]*|/\\*[\\s\\S]*?\\*/")
            .replace(source) { match -> match.value.map { if (it == '\n') '\n' else ' ' }.joinToString("") }

    private fun locateRepoRoot(): File {
        var dir = File(".").absoluteFile.normalize()
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("Could not locate settings.gradle.kts")
        }
        return dir
    }
}

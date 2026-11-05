package cloud.trotter.log.gig.guard

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1444: the faintest grey is for decorative marks and disabled content only; words use `neutral`. */
class Text3GuardTest {
    private val repoRoot: File by lazy { RepoRoot.locate() }
    private val mainSourceSets = listOf("/src/main/", "/src/debug/", "/src/release/")
    private val sources: List<File> by lazy {
        listOf("app", "feature", "core").flatMap { module ->
            File(repoRoot, module).walkTopDown()
                .onEnter { it.name != "build" }
                .filter { file ->
                    val path = file.invariantSeparatorsPath
                    file.isFile && file.extension == "kt" && mainSourceSets.any { it in path } &&
                        !path.endsWith("/theme/AppColors.kt")
                }
                .toList()
        }
    }

    @Test
    fun everyText3LineIsMarked() {
        val violations = sources.flatMap { file ->
            val path = file.relativeTo(repoRoot).invariantSeparatorsPath
            file.readLines().mapIndexedNotNull { index, line ->
                if (isUnmarkedText3(line)) "$path:${index + 1}" else null
            }
        }
        assertTrue(
            "text3 outside a decorative or disabled mark (use neutral for words):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun matcherFlagsOnlyUnmarkedLines() {
        assertTrue(isUnmarkedText3("    color = colors.text3)"))
        assertFalse(isUnmarkedText3("    color = colors.text3) // text3: decorative"))
        assertFalse(isUnmarkedText3("    color = colors.text3) // text3: disabled"))
        assertFalse(isUnmarkedText3("    color = colors.neutral)"))
    }

    private fun isUnmarkedText3(line: String): Boolean =
        TEXT3.containsMatchIn(line) && MARKERS.none { it in line }

    private companion object {
        val TEXT3 = Regex("""\btext3\b""")
        val MARKERS = listOf("// text3: decorative", "// text3: disabled")
    }
}

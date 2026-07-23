package cloud.trotter.dashbuddy.res

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #428 Half B — every localized string must carry the SAME positional format args as its default
 * (`values/`) counterpart. A translated `%`-mismatch is the #719 HIGH-1 crash class
 * (`getString(id, args)` throws at runtime when the arg indices don't line up), so this guards it
 * at build time rather than on a device speaking Spanish. Focused on the spoken-offer (`tts_`)
 * strings this PR translates, but it checks EVERY name present in both files.
 *
 * Runs as a plain JVM test — the module working dir is `app/`, so the res files are read directly.
 */
class StringFormatArgConsistencyTest {

    // %1$s / %2$d / %3$f … — the positional format tokens Android's Resources.getString consumes.
    private val positionalArg = Regex("""%(\d+)\$[a-zA-Z]""")

    private data class Res(val name: String, val value: String)

    private fun parse(file: File): Map<String, String> {
        assertTrue("missing res file: ${file.absolutePath}", file.exists())
        val text = file.readText()
        // Grab <string name="x" ...>value</string>, tolerating extra attributes (translatable, etc).
        val stringTag = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return stringTag.findAll(text)
            .map { Res(it.groupValues[1], it.groupValues[2]) }
            .associate { it.name to it.value }
    }

    /** The multiset of positional arg indices in a value (e.g. "%1$s and %1$s, %2$d" → [1,1,2]). */
    private fun argIndices(value: String): List<Int> =
        positionalArg.findAll(value).map { it.groupValues[1].toInt() }.sorted().toList()

    @Test
    fun `es strings keep the default positional args`() {
        val default = parse(File("src/main/res/values/strings.xml"))
        val es = parse(File("src/main/res/values-es/strings.xml"))

        // The whole point of this PR's es file is the spoken-offer set — make sure it's actually here.
        assertTrue(
            "values-es should translate the spoken-offer template",
            es.containsKey("tts_offer_evaluation_template"),
        )

        val mismatches = es.keys
            .filter { it in default }
            .mapNotNull { name ->
                val d = argIndices(default.getValue(name))
                val t = argIndices(es.getValue(name))
                if (d != t) "  $name: default=$d es=$t" else null
            }
        assertEquals(
            "Positional format args drifted between values/ and values-es/ (crash risk, #719):\n" +
                mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    @Test
    fun `tts template still declares six args in the default locale`() {
        val default = parse(File("src/main/res/values/strings.xml"))
        val args = argIndices(default.getValue("tts_offer_evaluation_template"))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), args)
    }
}

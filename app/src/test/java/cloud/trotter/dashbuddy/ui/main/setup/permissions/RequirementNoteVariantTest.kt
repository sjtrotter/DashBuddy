package cloud.trotter.dashbuddy.ui.main.setup.permissions

import cloud.trotter.dashbuddy.guard.RepoRoot
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import cloud.trotter.dashbuddy.feature.settings.R as SettingsR

/**
 * #1151 RR1 — the requirement note is ONE key whose value differs by build variant: the debug
 * source set's override says "Required" (a decline disables the service and blocks the app), the
 * main (production) value says "Optional" (a decline keeps the app usable). Both name the real
 * settings screen. This unit test runs in the DEBUG variant, so Robolectric resolves the override;
 * the production value is pinned by reading the main source file.
 */
@RunWith(RobolectricTestRunner::class)
class RequirementNoteVariantTest {

    private val settingsPath = "Settings → Data &amp; Privacy → Automation &amp; Consent"

    private fun sourceValue(relPath: String): String {
        val xml = File(RepoRoot.locate(), relPath).readText()
        val m = Regex("""<string name="event_receipt_requirement_note">([^<]*)</string>""").find(xml)
        return checkNotNull(m) { "event_receipt_requirement_note missing from $relPath" }.groupValues[1]
    }

    @Test
    fun `the debug variant resolves the Required note`() {
        val note = RuntimeEnvironment.getApplication().getString(SettingsR.string.event_receipt_requirement_note)
        assertTrue(note, note.startsWith("Required"))
        assertTrue(note, note.contains("Settings → Data & Privacy → Automation & Consent"))
    }

    @Test
    fun `the production value says Optional, the debug override says Required, both name the screen`() {
        val main = sourceValue("feature/settings/src/main/res/values/strings.xml")
        val debug = sourceValue("feature/settings/src/debug/res/values/strings.xml")

        assertTrue(main, main.startsWith("Optional"))
        assertTrue(debug, debug.startsWith("Required"))
        assertNotEquals(main, debug)
        assertTrue(main, main.contains(settingsPath))
        assertTrue(debug, debug.contains(settingsPath))
    }
}

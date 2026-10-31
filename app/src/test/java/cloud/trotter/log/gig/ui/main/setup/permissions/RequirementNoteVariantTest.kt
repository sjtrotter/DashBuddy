package cloud.trotter.log.gig.ui.main.setup.permissions

import cloud.trotter.log.gig.guard.RepoRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import cloud.trotter.log.gig.feature.settings.R as SettingsR

/**
 * #1151 RR1 — the requirement note is ONE key whose value differs by build variant: the debug
 * source set's override says "Required" (a decline disables the service and blocks the app), the
 * main (production) value says "Optional" (a decline keeps the app usable). This unit test runs in
 * the DEBUG variant, so Robolectric resolves the override; the production value is pinned by reading
 * the main source file.
 *
 * #1457: the note no longer names the settings screen; the separate one-variant
 * `event_receipt_change_later_format` does, through `event_receipt_settings_path`.
 */
@RunWith(RobolectricTestRunner::class)
class RequirementNoteVariantTest {

    private val settingsPath = "Settings > Data & privacy > Automation & consent"

    private fun sourceValue(relPath: String, name: String): String? {
        val xml = File(RepoRoot.locate(), relPath).readText()
        return Regex("""<string name="$name">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
    }

    @Test
    fun `the debug variant resolves the Required note`() {
        val note = RuntimeEnvironment.getApplication().getString(SettingsR.string.event_receipt_requirement_note)
        assertTrue(note, note.startsWith("Required"))
    }

    @Test
    fun `the production value says Optional, the debug override says Required`() {
        val main = checkNotNull(sourceValue(MAIN, NOTE))
        val debug = checkNotNull(sourceValue(DEBUG, NOTE))

        assertTrue(main, main.startsWith("Optional"))
        assertTrue(debug, debug.startsWith("Required"))
        assertNotEquals(main, debug)
    }

    @Test
    fun `the change-later line names the Automation and consent screen in both variants`() {
        val app = RuntimeEnvironment.getApplication()
        val line = app.getString(
            SettingsR.string.event_receipt_change_later_format,
            app.getString(SettingsR.string.event_receipt_settings_path),
        )
        assertEquals("Change this later in $settingsPath.", line)
        assertEquals(
            "one value for both variants: the debug source set does not override it",
            null,
            sourceValue(DEBUG, "event_receipt_change_later_format"),
        )
    }

    private companion object {
        const val NOTE = "event_receipt_requirement_note"
        const val MAIN = "feature/settings/src/main/res/values/strings.xml"
        const val DEBUG = "feature/settings/src/debug/res/values/strings.xml"
    }
}

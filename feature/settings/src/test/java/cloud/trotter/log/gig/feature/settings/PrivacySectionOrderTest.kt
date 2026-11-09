package cloud.trotter.log.gig.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #1582: the web privacy policy (tools/site/build_site.py) shows the `privacy_*_title` / `_body` pairs in the order
 * strings.xml lists them; the in-app Privacy page shows [PRIVACY_SECTIONS]. Both must be the same sections in the same
 * order, so the web page and the app say the same thing.
 */
class PrivacySectionOrderTest {

    @Test
    fun `the app page and the web policy show the same sections in the same order`() {
        val xml = File("src/main/res/values/strings.xml").readText()
        val fromFile = Regex("""<string name="(privacy_[a-z_]+)_title">""").findAll(xml)
            .map { it.groupValues[1] }
            .filter { it != "privacy_test_builds" }
            .map { "${it}_title" to "${it}_body" }
            .toList()
        val fromScreen = PRIVACY_SECTIONS.map { (title, body) -> name(title) to name(body) }
        assertEquals(fromFile, fromScreen)
    }

    private fun name(id: Int): String = R.string::class.java.fields.first { it.getInt(null) == id }.name
}

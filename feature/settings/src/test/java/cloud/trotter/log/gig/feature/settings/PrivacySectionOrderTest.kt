package cloud.trotter.log.gig.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * #1582: the web privacy policy (tools/site/build_site.py) shows the `privacy_*_title` / `_body` pairs in the order
 * strings.xml lists them; the in-app Privacy page shows [PRIVACY_SECTIONS]. Both must be the same sections in the same
 * order, so the web page and the app say the same thing.
 */
class PrivacySectionOrderTest {

    @Test
    fun `the app page and the web policy show the same sections in the same order`() {
        // The generator's rule: every <string> named privacy_*_title, in file order, except the Test builds section.
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val strings = doc.getElementsByTagName("string")
        val fromFile = (0 until strings.length)
            .map { (strings.item(it) as Element).getAttribute("name") }
            .filter { Regex("privacy_[a-z_]+_title").matches(it) && it != "privacy_test_builds_title" }
            .map { it to it.removeSuffix("_title") + "_body" }
        val fromScreen = PRIVACY_SECTIONS.map { (title, body) -> name(title) to name(body) }
        assertEquals(fromFile, fromScreen)
    }

    private fun name(id: Int): String = R.string::class.java.fields.first { it.getInt(null) == id }.name
}

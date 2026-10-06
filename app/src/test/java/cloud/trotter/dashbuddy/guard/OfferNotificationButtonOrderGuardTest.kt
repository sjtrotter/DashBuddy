package cloud.trotter.dashbuddy.guard

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** #1254: both notification shapes keep Accept physically left and Decline right. */
class OfferNotificationButtonOrderGuardTest {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test
    fun `compact offer actions keep accept left and decline right`() {
        checkButtonRow("notif_offer_compact.xml")
    }

    @Test
    fun `expanded offer actions keep accept left and decline right`() {
        checkButtonRow("notif_offer_expanded.xml")
    }

    private fun checkButtonRow(name: String) {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(RepoRoot.locate(), "app/src/main/res/layout/$name"))
        val nodes = document.getElementsByTagName("*")
        val elements = (0 until nodes.length).map { nodes.item(it) as Element }
        val message = "#1254 $name"
        val accepts = elements.filter { it.getAttributeNS(androidNamespace, "id") == "@+id/notif_btn_accept" }
        val declines = elements.filter { it.getAttributeNS(androidNamespace, "id") == "@+id/notif_btn_decline" }
        assertEquals("$message: Accept id must be unique", 1, accepts.size)
        assertEquals("$message: Decline id must be unique", 1, declines.size)
        val accept = accepts.single()
        val decline = declines.single()
        assertSame("$message: actions must share a parent", accept.parentNode, decline.parentNode)
        val row = accept.parentNode as Element
        assertEquals("$message: actions must be in a LinearLayout", "LinearLayout", row.tagName)
        assertEquals("$message: actions must be horizontal", "horizontal", row.getAttributeNS(androidNamespace, "orientation"))
        assertEquals("$message: actions must stay physically LTR", "ltr", row.getAttributeNS(androidNamespace, "layoutDirection"))
        val children = row.childNodes
        val siblings = (0 until children.length).map { children.item(it) }
        assertTrue("$message: Accept must precede Decline", siblings.indexOf(accept) < siblings.indexOf(decline))
        val margin = decline.getAttributeNS(androidNamespace, "layout_marginStart")
        val marginDp = margin.takeIf { it.endsWith("dp") }?.removeSuffix("dp")?.toFloatOrNull()
        assertTrue("$message: Decline start margin must be at least 16dp", marginDp != null && marginDp >= 16f)
    }
}

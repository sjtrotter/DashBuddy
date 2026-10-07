package cloud.trotter.dashbuddy.test.util

import android.app.Notification
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * #1271 scenario 6's fixed manifest. Only freshly loaded immutable trees are copied; the corpus
 * stays untouched. Canaries are deliberately fake ASCII, and every splice has a checked anchor.
 * Android objects are the input boundary: the production mappers still read every frame/push.
 */
object PrivacyExportInputs {
    const val PACKAGE = "com.doordash.driverapp"
    const val BENIGN = "FAKEBENIGNCANARY"
    const val AFTER = "FAKEAFTERCANARY"
    const val NAME = "FAKECUSTOMERCANARY"
    const val ADDRESS_ONE = "FAKEADDRESSONECANARY"
    const val ADDRESS_TWO = "FAKEADDRESSTWOCANARY"
    const val UNKNOWN_NAME = "FAKEUNKNOWNNAMECANARY"
    const val UNKNOWN_ADDRESS = "FAKEUNKNOWNADDRESSCANARY"
    const val LEAD_IN = "FAKELEADINCANARY"
    const val INPUT = "FAKEINPUTCANARY"
    const val BANK = "FAKEBANKCANARY"
    const val BANK_BACKSTOP = "FAKEBANKBACKSTOPCANARY"
    const val NOTIF_CUSTOMER = "FAKENOTIFCUSTOMERCANARY"
    const val NOTIF_BANK = "FAKENOTIFBANKCANARY"
    const val NOTIF_BLOCK = "FAKENOTIFBLOCKCONTROL"
    const val LOG = "FAKELOGSINKCANARY"
    const val DEBUG = "FAKEDEBUGONLYCANARY"
    val forbidden = listOf(NAME, ADDRESS_ONE, ADDRESS_TWO, UNKNOWN_NAME, UNKNOWN_ADDRESS,
        LEAD_IN, INPUT, BANK, BANK_BACKSTOP, NOTIF_CUSTOMER, NOTIF_BANK, NOTIF_BLOCK)

    /**
     * Each input's required classification. BANKING is claimed by `sensitive.known`'s STRUCTURAL
     * DasherDirect branch (#924: the nav host's own view id, present on every frame of that surface
     * before any text renders), which outranks the text-keyed `transfer_out` branch — the stronger,
     * locale-immune gate, so that is the one pinned here.
     */
    enum class Screen(val target: String) {
        BENIGN("waiting_for_offer"), CUSTOMER("dropoff_pre_arrival"),
        UNKNOWN("UNKNOWN"), CUSTOMER_UNKNOWN("UNKNOWN"), BANKING("sensitive.dasher_direct"), BANKING_UNKNOWN("UNKNOWN"),
    }
    enum class Push { BENIGN, CUSTOMER, BANKING }
    private const val NEGATIVE = "UNKNOWN/negative/2026-09-30_19-04-14-421__doordash__accessibility.window__UNKNOWN__9a63dd.json"
    private fun file(path: String) = File("src/test/resources/snapshots", path).also {
        assertTrue("manifest fixture exists: $path", it.isFile)
    }
    fun nodes(root: UiNode): List<UiNode> = listOf(root) + root.children.flatMap(::nodes)

    /** Restore parent relationships only after the complete immutable copy has been built. */
    fun screen(kind: Screen): UiNode {
        var root = TestResourceLoader.loadNode(file(when (kind) {
            Screen.BENIGN -> "waiting_for_offer/2026-08-23_17-35-24-870__doordash__accessibility.window__waiting_for_offer__65095d.json"
            Screen.CUSTOMER -> "dropoff_pre_arrival/2026-06-12__doordash__dropoff_pre_arrival_alcohol__f076f1.json"
            Screen.BANKING -> "SENSITIVE/2026-07-17_21-29-25__doordash__dasher_direct_transfer_out__aebcfc.json"
            else -> NEGATIVE
        }))
        val tokens = when (kind) {
            Screen.CUSTOMER -> {
                for ((id, text) in listOf("user_name" to "$NAME Q", "address_line_1" to ADDRESS_ONE, "address_line_2" to ADDRESS_TWO)) {
                    root = replace(root, text) { it.viewIdResourceName?.endsWith(id) == true }
                }
                listOf(NAME, ADDRESS_ONE, ADDRESS_TWO)
            }
            Screen.BANKING -> {
                root = replace(root, "$BANK available") { it.text == "[redacted] available" }
                assertTrue("banking anchor survives", nodes(root).any { it.text == "Transfer out" })
                listOf(BANK)
            }
            Screen.UNKNOWN -> { root = append(root, listOf(leaf(BENIGN))); listOf(BENIGN) }
            Screen.CUSTOMER_UNKNOWN -> {
                root = append(root, listOf(leaf(UNKNOWN_NAME, "order_cx_name"), leaf(UNKNOWN_ADDRESS, "address_line_1"),
                    leaf("Order for $LEAD_IN"), leaf(INPUT).copy(className = "android.widget.EditText", isEditable = true)))
                listOf(UNKNOWN_NAME, UNKNOWN_ADDRESS, LEAD_IN, INPUT)
            }
            Screen.BANKING_UNKNOWN -> {
                // Its own view id makes it a distinct STRUCTURE: FrameGate keys UNKNOWN suppression on
                // `stableHash` (class names + view ids, never text), so an id-less leaf here hashes like
                // the benign UNKNOWN input's and is suppressed as a repeat before the backstop runs.
                root = append(root, listOf(leaf(BANK_BACKSTOP, id = "fake_backstop_row").copy(stateDescription = "Transfer out")))
                listOf(BANK_BACKSTOP)
            }
            Screen.BENIGN -> emptyList()
        }
        if (kind in setOf(Screen.UNKNOWN, Screen.CUSTOMER_UNKNOWN, Screen.BANKING_UNKNOWN)) {
            assertTrue("negative fixture anchor survives", nodes(root).any { it.text == "Current dash" })
        }
        for (token in tokens) assertTrue("input contains $token", nodes(root).any { n -> n.scrubbableStrings().any { it.second?.contains(token) == true } })
        return root.restoreParents()
    }
    private fun leaf(text: String, id: String? = null) = UiNode(text = text, className = "android.widget.TextView",
        viewIdResourceName = id?.let { "$PACKAGE:id/$it" }, isEnabled = true)
    private fun append(root: UiNode, leaves: List<UiNode>) = root.copy(children = root.children + leaves)
    private fun replace(root: UiNode, text: String, predicate: (UiNode) -> Boolean): UiNode {
        assertEquals("one mutation site for $text", 1, nodes(root).count(predicate))
        fun copy(n: UiNode): UiNode = n.copy(text = if (predicate(n)) text else n.text, children = n.children.map(::copy))
        return copy(root)
    }

    /** A new structural leaf ensures a post-withdrawal frame cannot disappear into admission dedup. */
    fun distinctUnknown(): UiNode = append(screen(Screen.UNKNOWN), listOf(leaf(AFTER, "privacy_after")))
        .restoreParents().also { assertEquals(1, nodes(it).count { n -> n.text == AFTER }) }

    /** Mirror all fields carried by these fixtures, including the privacy-only state/input fields. */
    @Suppress("DEPRECATION") // SDK 35 uses boolean checked and the click-action bitmask.
    fun mirror(n: UiNode): AccessibilityNodeInfo {
        val node = mock<AccessibilityNodeInfo>()
        val children = n.children.map(::mirror)
        whenever(node.packageName).thenReturn(PACKAGE)
        whenever(node.className).thenReturn(n.className)
        whenever(node.text).thenReturn(n.text)
        whenever(node.contentDescription).thenReturn(n.contentDescription)
        whenever(node.stateDescription).thenReturn(n.stateDescription)
        whenever(node.viewIdResourceName).thenReturn(n.viewIdResourceName)
        whenever(node.isClickable).thenReturn(n.isClickable)
        whenever(node.isEnabled).thenReturn(n.isEnabled)
        whenever(node.isChecked).thenReturn(n.isChecked == 1)
        whenever(node.isVisibleToUser).thenReturn(n.isVisibleToUser)
        whenever(node.isEditable).thenReturn(n.isEditable)
        whenever(node.isFocusable).thenReturn(n.isFocusable)
        whenever(node.isScreenReaderFocusable).thenReturn(n.isScreenReaderFocusable)
        whenever(node.extras).thenReturn(Bundle())
        whenever(node.actions).thenReturn(if (n.hasClickAction) AccessibilityNodeInfo.ACTION_CLICK else 0)
        whenever(node.actionList).thenReturn(if (n.hasClickAction) listOf(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK) else emptyList())
        whenever(node.childCount).thenReturn(children.size)
        whenever(node.getChild(any())).thenAnswer { children.getOrNull(it.getArgument(0)) }
        children.forEach { whenever(it.parent).thenReturn(node) }
        whenever(node.getBoundsInScreen(any())).thenAnswer {
            n.boundsInScreen.let { b -> (it.arguments[0] as Rect).set(b.left, b.top, b.right, b.bottom) }
        }
        whenever(node.refresh()).thenReturn(true)
        return node
    }

    /** Reconstruct all five fields/channel/actions; historical fixture postTime is never replayed. */
    @Suppress("DEPRECATION") // Same framework StatusBarNotification constructor as the pipeline tests.
    fun notification(context: Context, kind: Push, now: Long): StatusBarNotification {
        val name = when (kind) { Push.BENIGN -> "chrome"; Push.CUSTOMER -> "customer-lead-in"; Push.BANKING -> "action-only-sensitive" }
        val p = privacyExportDecode("notification fixture/$name") {
            Json.parseToJsonElement(file("notification_census/fixtures/$name.json").readText()).jsonObject.getValue("payload").jsonObject
        }
        fun field(key: String) = p[key]?.jsonPrimitive?.contentOrNull
        val title = when (kind) {
            Push.BENIGN -> field("title").also { assertEquals("Continue", it) }
            Push.CUSTOMER -> { assertEquals("Deliver to Jordan", field("title")); "Deliver to $NOTIF_CUSTOMER" }
            Push.BANKING -> { assertEquals("Continue", field("title")); NOTIF_BLOCK }
        }
        val actions = p["actionLabels"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if (kind == Push.BANKING) assertEquals(listOf("Transfer out"), actions)
        val builder = Notification.Builder(context, field("channelId"))
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title)
            .setContentText(field("text")).setSubText(field("subText")).setTicker(field("tickerText"))
        (if (kind == Push.BANKING) listOf("Transfer out $NOTIF_BANK") else actions).forEach {
            builder.addAction(Notification.Action.Builder(null, it, null).build())
        }
        val notification = builder.build().apply { field("bigText")?.let { extras.putCharSequence("android.bigText", it) } }
        assertEquals(title, notification.extras.getCharSequence("android.title")?.toString())
        if (kind == Push.BANKING) assertEquals("Transfer out $NOTIF_BANK", notification.actions.single().title.toString())
        assertEquals(PACKAGE, field("packageName"))
        return StatusBarNotification(PACKAGE, PACKAGE, 1, null, 0, 0, 0, notification, Process.myUserHandle(), now)
    }
}

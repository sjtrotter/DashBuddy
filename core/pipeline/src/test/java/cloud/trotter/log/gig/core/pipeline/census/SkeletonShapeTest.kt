package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.census.contract.KindClassifier
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.contract.NotificationSkeletonSchema
import cloud.trotter.census.contract.ShapeTemplate
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.SkeletonVersion
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.log.gig.core.pipeline.census.NotificationSkeletonBuilder.Outcome
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.model.notification.RawNotificationData
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0011 §13 (#1619): shape slots, click/visibility flags and notification actions, and the v1 projection. */
class SkeletonShapeTest : SkeletonBuilderTestBase() {

    private fun v2Slot(value: String, id: String? = null): TextSlot? =
        SkeletonBuilder.build(
            UiNode(className = "android.widget.TextView", viewIdResourceName = id, text = value),
            null, meta, platform, day, SkeletonVersion.V2,
        )!!.root.text["text"]

    @Test
    fun `a money, time, distance or count slot goes out as its template in v2 and as mixed in v1`() {
        mapOf(
            "\$45.66" to ShapeTemplate.MONEY,
            "8:42 PM" to ShapeTemplate.CLOCK_12H,
            "8:42 AM" to ShapeTemplate.CLOCK_12H,
            "3.4 mi" to ShapeTemplate.MILES_1,
            "12 min" to ShapeTemplate.MINUTES_SHORT,
            "3 items" to ShapeTemplate.ITEMS,
            "12 min" to ShapeTemplate.MINUTES_SHORT,
        ).forEach { (value, template) ->
            assertEquals(value, TextSlot.ofShape(template), v2Slot(value))
            assertEquals(value, TextSlot(kind = KindClassifier.MIXED), slot(value))
        }
    }

    @Test
    fun `a value outside the list keeps its v1 kind, and a caught value stays withheld`() {
        listOf("\$7.50 Guaranteed", "Deliver by 8:42 PM", "#1234", "\$12,345.00").forEach {
            assertEquals(it, TextSlot(kind = KindClassifier.MIXED), v2Slot(it))
        }
        assertEquals(TextSlot(kind = KindClassifier.DIGITS), v2Slot("4821"))
        assertNotNull(v2Slot("Accept")!!.h)
        // A PII step fires before the template list is consulted.
        listOf("Apt 12", "pin 4821", "210-555-0100", "1425 Sample Ridge Dr").forEach {
            assertEquals(it, TextSlot.WITHHELD, v2Slot(it))
        }
        // The node's own PII id withholds a value that would otherwise fit a template.
        assertEquals(TextSlot.WITHHELD, v2Slot("12 min", id = "com.doordash.driverapp:id/customer_name"))
    }

    @Test
    fun `v2 carries the click and visibility flags, v1 is the v2 build projected`() {
        val tree = UiNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                UiNode(className = "android.view.View", text = "Accept", hasClickAction = true),
                UiNode(className = "android.widget.TextView", text = "\$7.50", isVisibleToUser = false),
                UiNode(className = "android.widget.Button", text = "Decline", isClickable = true),
            ),
        ).restoreParents()
        val v2 = SkeletonBuilder.build(tree, "8:42 PM", meta, platform, day, SkeletonVersion.V2)!!
        val v1 = SkeletonBuilder.build(tree, "8:42 PM", meta, platform, day)!!
        assertEquals(SkeletonSchema.SCHEMA_ID_V2, v2.schemaId)
        assertEquals(SkeletonSchema.SCHEMA_ID, v1.schemaId)
        val (accept, money, decline) = v2.root.children
        assertTrue(accept.hasClickAction && !accept.isClickable)
        assertFalse(money.isVisibleToUser)
        assertTrue(decline.isClickable && !decline.hasClickAction && decline.isVisibleToUser)
        assertEquals(TextSlot.ofShape(ShapeTemplate.CLOCK_12H), v2.windowTitle)
        assertEquals(v2.toV1(), v1)
        assertEquals(v2.fingerprint, v1.fingerprint)
        val v1Json = SkeletonSchema.serialize(v1)
        listOf("hasClickAction", "isVisibleToUser", "shape").forEach { assertFalse(v1Json, v1Json.contains(it)) }
    }

    private val raw = RawNotificationData(
        "Your order", null, null, null,
        packageName = Platform.DoorDash.packageName!!, postTime = 1, isClearable = true, channelId = "synthetic-updates",
    )

    private fun notification(value: RawNotificationData, version: SkeletonVersion = SkeletonVersion.V2): NotificationSkeletonDto =
        (NotificationSkeletonBuilder.outcome(value, meta, platform, day, version) as Outcome.Built).skeleton

    @Test
    fun `notification action labels travel as filtered slots, never in clear`() {
        val labels = listOf("Accept", "Call Jordan T.", "Deliver to Riley", "", "Mark as read", "\$7.50")
        val built = NotificationSkeletonBuilder.outcome(raw.copy(actionLabels = labels), meta, platform, day, SkeletonVersion.V2)
            as Outcome.Built
        val actions = built.skeleton.actions
        // The blank label is omitted; the rest keep their order.
        assertEquals(5, actions.size)
        assertNotNull(actions[0].h)
        assertEquals(TextSlot.WITHHELD, actions[1])
        assertEquals(TextSlot.WITHHELD, actions[2])
        assertNotNull(actions[3].h)
        assertEquals(TextSlot.ofShape(ShapeTemplate.MONEY), actions[4])
        labels.filter { it.isNotBlank() }.forEach { label ->
            label.split(' ').filter { it.length >= 3 }.forEach { assertFalse("'$it' in clear: ${built.json}", built.json.contains(it)) }
        }
        assertEquals(built.skeleton, NotificationSkeletonSchema.deserialize(built.json))
        // v1 drops them; the fingerprint is the same.
        val v1 = notification(raw.copy(actionLabels = labels), SkeletonVersion.V1)
        assertTrue(v1.actions.isEmpty())
        assertEquals(built.skeleton.toV1(), v1)
        assertEquals(built.skeleton.fingerprint, v1.fingerprint)
        assertFalse(NotificationSkeletonSchema.serialize(v1).contains("actions"))
    }

    @Test
    fun `the action list is cut at the contract cap`() {
        val labels = List(NotificationSkeletonDto.MAX_ACTIONS + 3) { "Option" }
        assertEquals(NotificationSkeletonDto.MAX_ACTIONS, notification(raw.copy(actionLabels = labels)).actions.size)
        assertNull(notification(raw).actions.firstOrNull())
    }
}

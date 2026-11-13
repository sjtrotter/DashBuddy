/*
 * Copyright 2026 Stephen Trotter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Census wire contract (ADR-0011, #1173). This Apache-2.0 included build depends
 * on nothing but the JDK and kotlinx-serialization.
 */
package cloud.trotter.census.contract

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0011 §13 (#1619): the v2 wire fields, the v1 projection and what each version refuses. */
class SkeletonV2ContractTest {

    private val money = TextSlot.ofShape(ShapeTemplate.MONEY)
    private val word = TextSlot("0123456789abcdef", "words:1")

    private fun screen(version: SkeletonVersion, root: UiSkeletonNodeDto = UiSkeletonNodeDto(), title: TextSlot? = null) = UiSkeletonDto(
        schemaId = version.screenSchemaId, hashDomain = 1, filterRev = 1, fingerprint = "0".repeat(64),
        platform = "doordash", engineVersion = 1, day = "2026-11-13", windowTitle = title, root = root,
    )

    private fun notification(version: SkeletonVersion, actions: List<TextSlot> = emptyList(), title: TextSlot = TextSlot.WITHHELD) =
        NotificationSkeletonContractTest.fixture().copy(
            schemaId = version.notificationSchemaId,
            slots = NotifTextField.entries.associateWith { if (it == NotifTextField.TITLE) title else TextSlot.WITHHELD },
            actions = actions,
        )

    private fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test
    fun `a shape slot carries a template of the closed list and nothing else`() {
        assertEquals("""{"kind":"shape","shape":"${'$'}#.##"}""", SkeletonSchema.json.encodeToString(TextSlot.serializer(), money))
        rejects { TextSlot(kind = KindClassifier.SHAPE) }
        rejects { TextSlot(kind = KindClassifier.SHAPE, shape = "\$7.50") }
        rejects { TextSlot(kind = KindClassifier.SHAPE, shape = "#:## PM") }
        rejects { TextSlot(kind = KindClassifier.SHAPE, shape = "") }
        rejects { TextSlot(h = "0123456789abcdef", kind = KindClassifier.SHAPE, shape = ShapeTemplate.MONEY.wire) }
        rejects { TextSlot(kind = KindClassifier.MIXED, shape = ShapeTemplate.MONEY.wire) }
        rejects { TextSlot(h = "0123456789abcdef", kind = "words:1", shape = ShapeTemplate.MONEY.wire) }
        assertTrue(KindClassifier.isWireKind(KindClassifier.SHAPE))
        assertFalse(KindClassifier.isHashableKind(KindClassifier.SHAPE))
    }

    @Test
    fun `v2 screens carry shapes and the click and visibility flags, and round-trip canonically`() {
        val root = UiSkeletonNodeDto(
            className = "android.widget.Button", isClickable = true, hasClickAction = true, isVisibleToUser = false,
            text = mapOf("text" to money, "desc" to word),
        )
        val item = screen(SkeletonVersion.V2, root, title = TextSlot.ofShape(ShapeTemplate.CLOCK_12H))
        val wire = SkeletonSchema.serialize(item)
        assertTrue(wire, wire.contains("\"schemaId\":\"uinode.skeleton.v2\""))
        assertTrue(wire, wire.contains("\"isClickable\":true,\"hasClickAction\":true,\"isVisibleToUser\":false,\"text\""))
        assertEquals(item, SkeletonSchema.deserialize(wire))
        assertEquals(item, CensusSkeletonSchema.deserialize(wire))
        assertEquals(SkeletonVersion.V2, item.version)
        // The common values stay off the wire.
        assertEquals("{}", SkeletonSchema.json.encodeToString(UiSkeletonNodeDto.serializer(), UiSkeletonNodeDto()))
    }

    @Test
    fun `a v1 item refuses every v2 field, at construction and at decode`() {
        listOf(
            UiSkeletonNodeDto(hasClickAction = true),
            UiSkeletonNodeDto(isVisibleToUser = false),
            UiSkeletonNodeDto(text = mapOf("text" to money)),
            UiSkeletonNodeDto(children = listOf(UiSkeletonNodeDto(hasClickAction = true))),
        ).forEach { root ->
            rejects { screen(SkeletonVersion.V1, root) }
            val v2Wire = SkeletonSchema.serialize(screen(SkeletonVersion.V2, root))
            rejects { CensusSkeletonSchema.deserialize(v2Wire.replace("uinode.skeleton.v2", "uinode.skeleton.v1")) }
        }
        rejects { screen(SkeletonVersion.V1, title = money) }
        rejects { notification(SkeletonVersion.V1, actions = listOf(word)) }
        rejects { notification(SkeletonVersion.V1, title = money) }
        val v2Notification = NotificationSkeletonSchema.serialize(notification(SkeletonVersion.V2, actions = listOf(word)))
        rejects { CensusSkeletonSchema.deserialize(v2Notification.replace("notification.skeleton.v2", "notification.skeleton.v1")) }
    }

    @Test
    fun `an unknown shape on the wire is refused`() {
        val wire = SkeletonSchema.serialize(screen(SkeletonVersion.V2, UiSkeletonNodeDto(text = mapOf("text" to money))))
        listOf("\$7.50", "#:## PM", "anything").forEach { bad ->
            rejects { CensusSkeletonSchema.deserialize(wire.replace("\"${'$'}#.##\"", JsonPrimitive(bad).toString())) }
        }
        assertEquals("bad_shape", SkeletonRejectionReason.BAD_SHAPE.wire)
    }

    @Test
    fun `the v1 projection is what v1 emitted, and the screen fingerprint is unchanged`() {
        val v2 = screen(
            SkeletonVersion.V2,
            UiSkeletonNodeDto(
                className = "android.widget.FrameLayout", hasClickAction = true, isVisibleToUser = false,
                text = mapOf("text" to money),
                children = listOf(UiSkeletonNodeDto(id = "com.x:id/eta", text = mapOf("text" to TextSlot.ofShape(ShapeTemplate.MINUTES_SHORT), "desc" to word))),
            ),
            title = TextSlot.ofShape(ShapeTemplate.ITEMS),
        )
        val v1 = v2.toV1()
        assertEquals(SkeletonSchema.SCHEMA_ID, v1.schemaId)
        assertEquals(
            screen(
                SkeletonVersion.V1,
                UiSkeletonNodeDto(
                    className = "android.widget.FrameLayout",
                    text = mapOf("text" to TextSlot(kind = "mixed")),
                    children = listOf(UiSkeletonNodeDto(id = "com.x:id/eta", text = mapOf("text" to TextSlot(kind = "mixed"), "desc" to word))),
                ),
                title = TextSlot(kind = "mixed"),
            ),
            v1,
        )
        assertEquals(CensusFingerprint.of(v2.root), CensusFingerprint.of(v1.root))
        assertSame(v1, v1.toV1())
    }

    @Test
    fun `notification actions are slots, capped, outside the fingerprint, and the v1 projection drops them`() {
        val bare = notification(SkeletonVersion.V2)
        val withActions = notification(SkeletonVersion.V2, actions = listOf(word, TextSlot.WITHHELD, money), title = money)
        val wire = NotificationSkeletonSchema.serialize(withActions)
        assertEquals(withActions, CensusSkeletonSchema.deserialize(wire))
        val actions = SkeletonSchema.json.parseToJsonElement(wire).jsonObject.getValue("actions") as JsonArray
        assertEquals(3, actions.size)
        actions.forEach { assertTrue(setOf("h", "kind", "shape").containsAll((it as JsonObject).keys)) }
        assertFalse("no actions key when there are none", NotificationSkeletonSchema.serialize(bare).contains("actions"))
        assertEquals(
            NotifTextField.entries.map { withActions.slots.getValue(it) } + withActions.actions,
            CensusSkeletonSchema.slots(withActions),
        )
        rejects { notification(SkeletonVersion.V2, actions = List(NotificationSkeletonDto.MAX_ACTIONS + 1) { TextSlot.WITHHELD }) }
        // A shape writes `mixed` into the fingerprint, and actions are not identity.
        val mixedTitle = notification(SkeletonVersion.V1, title = TextSlot(kind = "mixed"))
        assertEquals(CensusFingerprint.of(mixedTitle), CensusFingerprint.of(withActions))
        val v1 = withActions.toV1()
        assertEquals(mixedTitle, v1)
        assertEquals(NotificationSkeletonSchema.SCHEMA_ID, v1.schemaId)
    }

    @Test
    fun `the newest accepted version is chosen per kind`() {
        val all = CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS
        assertEquals(
            listOf("uinode.skeleton.v1", "notification.skeleton.v1", "uinode.skeleton.v2", "notification.skeleton.v2"),
            all,
        )
        assertEquals(SkeletonVersion.V2, SkeletonVersion.newestScreen(all))
        assertEquals(SkeletonVersion.V2, SkeletonVersion.newestNotification(all))
        assertEquals(SkeletonVersion.V1, SkeletonVersion.newestScreen(listOf(SkeletonSchema.SCHEMA_ID)))
        assertNull(SkeletonVersion.newestNotification(listOf(SkeletonSchema.SCHEMA_ID, SkeletonSchema.SCHEMA_ID_V2)))
        assertEquals(SkeletonVersion.V2, SkeletonVersion.newestScreen(listOf(SkeletonSchema.SCHEMA_ID_V2)))
        assertNull(SkeletonVersion.newestScreen(emptyList()))
    }
}

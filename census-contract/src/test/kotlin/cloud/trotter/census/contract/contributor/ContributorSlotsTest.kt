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
 */
package cloud.trotter.census.contract.contributor

import cloud.trotter.census.contract.NotifTextField
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.contract.NotificationSkeletonSchema
import cloud.trotter.census.contract.SkeletonKind
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The slot ids and layout id match the census server's `SlotWalk` byte for byte (gig.log#1626, census#80/#81). The
 * expected digests are a shared vector: computed by an independent port of the server's `SlotWalk.layout` (the
 * byte form in `docs/API.md` and `SlotWalk.kt` of dashbuddy-census), never by this implementation.
 */
class ContributorSlotsTest {
    private val hashed = TextSlot(h = "0123456789abcdef", kind = "words:2")

    private fun screen(root: UiSkeletonNodeDto, title: TextSlot? = hashed) = UiSkeletonDto(
        schemaId = SkeletonSchema.SCHEMA_ID_V2,
        hashDomain = 1,
        filterRev = 1,
        fingerprint = "a".repeat(64),
        platform = "doordash",
        engineVersion = 1,
        day = "2026-11-13",
        windowTitle = title,
        root = root,
    )

    private val children = listOf(
        UiSkeletonNodeDto(className = "android.widget.TextView", id = "com.doordash.driverapp:id/title", text = mapOf("text" to hashed)),
        UiSkeletonNodeDto(
            className = "android.widget.LinearLayout",
            children = listOf(
                // Inserted text order differs from sorted order: ids follow the sorted keys.
                UiSkeletonNodeDto(className = "android.widget.Button", text = linkedMapOf("text" to hashed, "desc" to TextSlot.WITHHELD)),
                UiSkeletonNodeDto(id = "com.doordash.driverapp:id/pay", text = mapOf("text" to TextSlot(kind = "digits"))),
            ),
        ),
    )

    private val fixture = screen(
        UiSkeletonNodeDto(className = "android.widget.FrameLayout", text = mapOf("desc" to TextSlot.WITHHELD), children = children),
    )

    @Test fun `slot ids are the window title then the pre-order tree path and sorted text keys`() {
        assertEquals(
            listOf("windowTitle", "root/desc", "root/0/text", "root/1/0/desc", "root/1/0/text", "root/1/1/text"),
            ContributorSlots.slots(fixture).map { it.id },
        )
        val button = ContributorSlots.slots(fixture).single { it.id == "root/1/0/text" }
        assertEquals("android.widget.Button", button.className)
        assertEquals("text", button.key)
        assertEquals(hashed, button.slot)
    }

    @Test fun `the layout id matches the shared vector`() {
        assertEquals("a2ad02af7b812ccdf3edf67965f23a0a899de615823579e5124d6ee81488e969", ContributorSlots.layout(fixture))
    }

    @Test fun `a wrapper's text keys are part of its layout, the values never`() {
        val bare = screen(UiSkeletonNodeDto(className = "android.widget.FrameLayout", children = children))
        assertEquals("993ffc5bb8279080b4445e87ee2146d6a85eecb3f590a99952bd27dc394ae117", ContributorSlots.layout(bare))
        val otherValues = screen(
            UiSkeletonNodeDto(
                className = "android.widget.FrameLayout",
                text = mapOf("desc" to TextSlot(h = "fedcba9876543210", kind = "words:1")),
                children = children,
            ),
            title = null,
        )
        assertEquals(ContributorSlots.layout(fixture), ContributorSlots.layout(otherValues))
        assertNotEquals(ContributorSlots.layout(fixture), ContributorSlots.layout(bare))
    }

    @Test fun `a notification's slots are its field names then its actions, and its layout counts the actions`() {
        val item = NotificationSkeletonDto(
            kind = SkeletonKind.NOTIFICATION,
            schemaId = NotificationSkeletonSchema.SCHEMA_ID_V2,
            hashDomain = 1,
            filterRev = 1,
            fingerprint = "b".repeat(64),
            platform = "doordash",
            engineVersion = 1,
            day = "2026-11-13",
            channelId = "synthetic-updates",
            slots = NotifTextField.entries.associateWith { TextSlot.WITHHELD },
            actions = listOf(hashed, TextSlot.WITHHELD),
        )
        assertEquals(NotifTextField.entries.map { it.wire } + listOf("action/0", "action/1"), ContributorSlots.slots(item).map { it.id })
        assertEquals("0f6491346da26d55ad0a578e44fc47cccdbeb3ffd978e16650c3b9ae53728f80", ContributorSlots.layout(item))
    }

    @Test fun `the variant carries the layout and unknown for a missing app version`() {
        val variant = requireNotNull(ContributorVariant.of(fixture))
        assertEquals(
            "{\"kind\":\"screen\",\"fingerprint\":\"${"a".repeat(64)}\",\"platformAppVersion\":\"unknown\"," +
                "\"hashDomain\":1,\"filterRev\":1,\"layout\":\"a2ad02af7b812ccdf3edf67965f23a0a899de615823579e5124d6ee81488e969\"}",
            variant.json(),
        )
        assertEquals(SkeletonKind.SCREEN, variant.kind)
    }

    @Test fun `the class list reads anything off the list as unknown`() {
        assertEquals(SlotClass.CONSTANT, SlotClass.fromWire("constant"))
        assertEquals(SlotClass.UNKNOWN, SlotClass.fromWire("Constant"))
        assertEquals(SlotClass.UNKNOWN, SlotClass.fromWire(null))
        assertEquals(
            listOf("total_pay", "tip", "base_pay", "deadline", "distance", "item_count", "customer_info", "store_name", "other"),
            ContributorRole.entries.map { it.wire },
        )
    }
}

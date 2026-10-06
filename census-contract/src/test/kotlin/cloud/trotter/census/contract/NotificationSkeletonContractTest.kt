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

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotificationSkeletonContractTest {
    @Test
    fun channelGrammarIsExact() {
        listOf("a", "A0_.-", "a".repeat(64)).forEach { channel ->
            val item = fixture().copy(channelId = channel)
            assertEquals(item, NotificationSkeletonSchema.deserialize(NotificationSkeletonSchema.serialize(item)))
        }
        listOf("", "a".repeat(65), " updates", "updates ", "updates\n", "a/b", "é", "a\u0000b").forEach { channel ->
            rejects { fixture().copy(channelId = channel) }
            rejects { NotificationSkeletonSchema.deserialize(changed("channelId", JsonPrimitive(channel))) }
        }
        rejects { NotificationSkeletonSchema.deserialize(changed("channelId", JsonNull)) }
        rejects { NotificationSkeletonSchema.deserialize(without("channelId")) }
    }

    @Test
    fun requiresExactlyFiveSlots() {
        NotifTextField.entries.forEach { field ->
            rejects { fixture().copy(slots = fixture().slots - field) }
            val slots = wire().getValue("slots").jsonObject - field.wire
            rejects { NotificationSkeletonSchema.deserialize(changed("slots", JsonObject(slots))) }
        }
        rejects { fixture().copy(slots = emptyMap()) }
        rejects { NotificationSkeletonSchema.deserialize(without("slots")) }
        rejects { NotificationSkeletonSchema.deserialize(changed("slots", JsonNull)) }
        val slots = wire().getValue("slots").jsonObject
        listOf("actionLabels", "TITLE", "unknown").forEach { key ->
            rejects { NotificationSkeletonSchema.deserialize(changed("slots", JsonObject(slots + (key to slots.getValue("title"))))) }
        }
    }

    @Test
    fun rejectsPlaintextActionsAndUnknownFields() {
        listOf("actionLabels", "root", "windowTitle", "packageName", "postTime", "category", "isOngoing", "plaintext").forEach { key ->
            rejects { NotificationSkeletonSchema.deserialize(changed(key, JsonPrimitive("forbidden"))) }
        }
        val slots = wire().getValue("slots").jsonObject
        listOf("text", "actionLabels", "value").forEach { key ->
            val title = slots.getValue("title").jsonObject + (key to JsonPrimitive("forbidden"))
            rejects { NotificationSkeletonSchema.deserialize(changed("slots", JsonObject(slots + ("title" to JsonObject(title))))) }
        }
        listOf(
            "{\"kind\":\"empty\"}", "{\"kind\":\"expired\"}", "{\"kind\":\"words:1\"}",
            "{\"kind\":\"withheld\",\"h\":\"0123456789abcdef\"}",
            "{\"kind\":\"words:1\",\"h\":\"0123456789ABCDEF\"}",
        ).forEach { badSlot ->
            val title = SkeletonSchema.json.parseToJsonElement(badSlot)
            rejects { NotificationSkeletonSchema.deserialize(changed("slots", JsonObject(slots + ("title" to title)))) }
        }
    }

    @Test
    fun requiresNotificationKindAndSchema() {
        rejects { fixture().copy(kind = SkeletonKind.SCREEN) }
        rejects { fixture().copy(schemaId = SkeletonSchema.SCHEMA_ID) }
        rejects { NotificationSkeletonSchema.deserialize(without("kind")) }
        rejects { NotificationSkeletonSchema.deserialize(without("schemaId")) }
        listOf("screen", "NOTIFICATION", "unknown", "").forEach { kind ->
            rejects { NotificationSkeletonSchema.deserialize(changed("kind", JsonPrimitive(kind))) }
        }
        rejects { NotificationSkeletonSchema.deserialize(changed("kind", JsonNull)) }
        rejects { NotificationSkeletonSchema.deserialize(changed("schemaId", JsonPrimitive(SkeletonSchema.SCHEMA_ID))) }
        assertEquals(SkeletonKind.NOTIFICATION, SkeletonKind.fromWire("notification"))
        assertEquals(null, SkeletonKind.fromWire("NOTIFICATION"))
    }

    @Test
    fun commonEnvelopeChecksApplyAtConstructionAndDecode() {
        listOf<() -> Unit>(
            { fixture().copy(hashDomain = 2) }, { fixture().copy(filterRev = 0) },
            { fixture().copy(fingerprint = "A".repeat(64)) }, { fixture().copy(platform = "DoorDash") },
            { fixture().copy(day = "2026-02-31") }, { fixture().copy(appVersion = "a".repeat(65)) },
            { fixture().copy(platformAppVersion = "a\u0000b") }, { fixture().copy(rulesetReleaseTag = "\uD800") },
        ).forEach { rejects(it) }
        mapOf(
            "hashDomain" to JsonPrimitive(2), "filterRev" to JsonPrimitive(0),
            "fingerprint" to JsonPrimitive("A".repeat(64)), "platform" to JsonPrimitive("DoorDash"),
            "day" to JsonPrimitive("2026-02-31"), "appVersion" to JsonPrimitive("a".repeat(65)),
            "platformAppVersion" to JsonPrimitive("a\u0000b"), "rulesetReleaseTag" to JsonPrimitive("\uD800"),
        ).forEach { (key, value) -> rejects { NotificationSkeletonSchema.deserialize(changed(key, value)) } }
    }

    @Test
    fun roundTripIsCanonical() {
        val item = fixture()
        val canonical = NotificationSkeletonSchema.serialize(item)
        val reversed = item.copy(slots = item.slots.entries.reversed().associate { it.toPair() })
        assertEquals(canonical, NotificationSkeletonSchema.serialize(reversed))
        assertEquals(item, NotificationSkeletonSchema.deserialize(canonical))
        assertEquals(NotifTextField.entries.map { it.wire }, wire().getValue("slots").jsonObject.keys.toList())
        assertEquals("notification", wire().getValue("kind").let { (it as JsonPrimitive).content })
        val unicode = item.copy(appVersion = "é")
        val measured = NotificationSkeletonSchema.measure(unicode)
        assertEquals(NotificationSkeletonSchema.serialize(unicode), measured.json)
        assertEquals(measured.json.toByteArray(Charsets.UTF_8).size, measured.bytes)
    }

    private fun wire() = SkeletonSchema.json.parseToJsonElement(NotificationSkeletonSchema.serialize(fixture())).jsonObject
    private fun changed(key: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(wire() + (key to value)).toString()
    private fun without(key: String) = JsonObject(wire() - key).toString()
    private fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    companion object {
        internal fun fixture() = NotificationSkeletonDto(
            kind = SkeletonKind.NOTIFICATION, schemaId = NotificationSkeletonSchema.SCHEMA_ID,
            hashDomain = 1, filterRev = 1,
            fingerprint = "2e65f1960194a414de332d15ae0e10d367d3ec194ca7292920be5d623fa11524",
            platform = "doordash", engineVersion = 1, day = "2026-10-06", channelId = "updates",
            slots = NotifTextField.entries.associateWith { TextSlot.WITHHELD },
        )
    }
}

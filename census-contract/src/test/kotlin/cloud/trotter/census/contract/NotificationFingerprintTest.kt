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

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class NotificationFingerprintTest {
    @Test
    fun fixedCanonicalBytesAndDigests() {
        val item = NotificationSkeletonContractTest.fixture()
        // Independently encoded with Python LP framing + hashlib.sha256, never the Kotlin encoder.
        val bytes = "N112\u0000notification8\u0000doordash7\u0000updates" +
            "5\u0000title8\u0000withheldN4\u0000text8\u0000withheldN7\u0000bigText8\u0000withheldN" +
            "10\u0000tickerText8\u0000withheldN7\u0000subText8\u0000withheldN"
        assertArrayEquals(bytes.toByteArray(Charsets.UTF_8), CensusFingerprint.canonicalBytes(item.platform, item.channelId, item.slots))
        assertEquals("2e65f1960194a414de332d15ae0e10d367d3ec194ca7292920be5d623fa11524", CensusFingerprint.of(item))
        val hashes = listOf("f2383d33be3d7562", "5de9e0e5193cc247", "3fe870a97b3881aa", "3233db710a9d06a4", "bb4fe4ecd1a4d91d")
        val slots = NotifTextField.entries.mapIndexed { i, field -> field to TextSlot(hashes[i], "words:${i + 1}") }.toMap()
        val hashedBytes = "N112\u0000notification8\u0000doordash7\u0000updates" +
            "5\u0000title7\u0000words:1H16\u0000f2383d33be3d7562" +
            "4\u0000text7\u0000words:2H16\u00005de9e0e5193cc247" +
            "7\u0000bigText7\u0000words:3H16\u00003fe870a97b3881aa" +
            "10\u0000tickerText7\u0000words:4H16\u00003233db710a9d06a4" +
            "7\u0000subText7\u0000words:5H16\u0000bb4fe4ecd1a4d91d"
        assertArrayEquals(hashedBytes.toByteArray(Charsets.UTF_8), CensusFingerprint.canonicalBytes(item.platform, item.channelId, slots))
        assertEquals("da9b07ea841d3400f9ad85d9cde91a529ac2b9cccf7b45bea43ef64fba660f5e", CensusFingerprint.of(item.platform, item.channelId, slots))
    }

    @Test
    fun everySyntheticWireVectorMatchesIndependentPins() {
        val pins = mapOf(
            "channel-case.json" to "a436e052e7f47de0034bc2ad34151d97afb55b56ae463a47e6118680636b8aac",
            "coarse-kinds.json" to "6452892e590ed89191e17779335ff6231502870e81a00925b56c2bddb74f5efb",
            "metadata-only.json" to "2e65f1960194a414de332d15ae0e10d367d3ec194ca7292920be5d623fa11524",
            "withheld-doordash.json" to "2e65f1960194a414de332d15ae0e10d367d3ec194ca7292920be5d623fa11524",
            "withheld-uber.json" to "6197b5d28a9a41d45bf0eb0d43b432f35bbeb9a577d8528aa13c4b4e3803b5b4",
            "words-1-to-5.json" to "da9b07ea841d3400f9ad85d9cde91a529ac2b9cccf7b45bea43ef64fba660f5e",
            "words-6-to-8.json" to "067ed0073e35d66ff6e36cb4b6986a28c418d023999f28d723f7628f2582f8f1",
        )
        // Included-build and repository-root invocations both resolve the same checked-in vectors.
        val vectors = sequenceOf(File("conformance/notification-vectors.jsonl"), File("census-contract/conformance/notification-vectors.jsonl"))
            .first { it.isFile }.readLines()
        assertEquals(pins.size, vectors.size)
        val seen = mutableSetOf<String>()
        vectors.forEach { line ->
            val row = SkeletonSchema.json.parseToJsonElement(line).jsonObject
            val file = row.getValue("file").jsonPrimitive.content.removePrefix("contract/notification/")
            check(seen.add(file))
            val item = CensusSkeletonSchema.deserialize(row.getValue("skeleton").toString())
            assertEquals(pins.getValue(file), item.fingerprint)
            assertEquals(pins.getValue(file), row.getValue("fingerprint").jsonPrimitive.content)
            assertEquals(pins.getValue(file), CensusFingerprint.of(item))
            assertEquals(row.getValue("skeleton").toString(), CensusSkeletonSchema.serialize(item))
        }
    }

    @Test
    fun mapOrderIsIrrelevantButPlatformChannelAndEverySlotSeparate() {
        val item = NotificationSkeletonContractTest.fixture()
        val original = CensusFingerprint.of(item)
        assertEquals(original, CensusFingerprint.of(item.copy(slots = item.slots.entries.reversed().associate { it.toPair() })))
        assertNotEquals(original, CensusFingerprint.of(item.copy(platform = "uber")))
        assertNotEquals(original, CensusFingerprint.of(item.copy(channelId = "Updates")))
        NotifTextField.entries.forEach { field ->
            val digits = item.copy(slots = item.slots + (field to TextSlot(kind = "digits")))
            assertNotEquals(original, CensusFingerprint.of(digits))
            val hashed = item.copy(slots = item.slots + (field to TextSlot("0123456789abcdef", "words:1")))
            val newHash = hashed.copy(slots = hashed.slots + (field to TextSlot("1123456789abcdef", "words:1")))
            val newKind = hashed.copy(slots = hashed.slots + (field to TextSlot("0123456789abcdef", "words:2")))
            assertNotEquals(CensusFingerprint.of(hashed), CensusFingerprint.of(newHash))
            assertNotEquals(CensusFingerprint.of(hashed), CensusFingerprint.of(newKind))
        }
    }

    @Test
    fun metadataIsIrrelevantAndScreenEncodingIsDisjoint() {
        val item = NotificationSkeletonContractTest.fixture()
        assertEquals(CensusFingerprint.of(item), CensusFingerprint.of(item.copy(
            filterRev = 2, fingerprint = "f".repeat(64), day = "2026-10-05", platformAppVersion = "2.0",
            appVersion = "test", rulesetReleaseTag = "synthetic", engineVersion = 2, rulesetFormatVersion = 2,
        )))
        val screen = CensusFingerprint.canonicalBytes(UiSkeletonNodeDto())
        val notification = CensusFingerprint.canonicalBytes(item.platform, item.channelId, item.slots)
        assertEquals('C'.code.toByte(), screen.first())
        assertEquals('N'.code.toByte(), notification.first())
        assertFalse(screen.contentEquals(notification))
        assertNotEquals(CensusFingerprint.of(UiSkeletonNodeDto()), CensusFingerprint.of(item))
    }
}

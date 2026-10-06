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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CensusSkeletonSchemaTest {
    @Test
    fun byteIdenticalLegacyScreenSerialization() {
        val item = screen()
        val expected = """{"schemaId":"uinode.skeleton.v1","hashDomain":1,"filterRev":1,"fingerprint":"${"0".repeat(64)}","platform":"doordash","engineVersion":1,"day":"2026-10-06","root":{}}"""
        assertEquals(expected, SkeletonSchema.serialize(item))
        assertEquals(expected, CensusSkeletonSchema.serialize(item))
        assertEquals(item, CensusSkeletonSchema.deserialize(expected))
        assertEquals(SkeletonKind.SCREEN, item.kind)
        assertFalse(expected.contains("kind"))
        assertEquals(SkeletonSchema.measure(item), CensusSkeletonSchema.measure(item))
        assertEquals(CensusFingerprint.of(item.root), CensusFingerprint.of(item))
    }

    @Test
    fun strictDispatch() {
        val notification = NotificationSkeletonContractTest.fixture()
        val wire = CensusSkeletonSchema.serialize(notification)
        assertEquals(notification, CensusSkeletonSchema.deserialize(wire))
        assertEquals(NotificationSkeletonSchema.measure(notification), CensusSkeletonSchema.measure(notification))
        assertEquals(listOf(SkeletonSchema.SCHEMA_ID, NotificationSkeletonSchema.SCHEMA_ID), CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS)
        listOf("{}", "[]", "null", "{\"schemaId\":1}", wire.replace("notification.skeleton.v1", "unknown"),
            wire.replace("\"kind\":\"notification\",", ""), wire.replace("\"kind\":\"notification\"", "\"kind\":\"screen\""),
            SkeletonSchema.serialize(screen()).replaceFirst("{", "{\"kind\":\"screen\","),
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { CensusSkeletonSchema.deserialize(invalid) } }
    }

    @Test
    fun slotsTraverseBothSupportedShapes() {
        val title = TextSlot(kind = "digits")
        val text = TextSlot(kind = "mixed")
        val child = TextSlot("0123456789abcdef", "words:1")
        val item = screen().copy(windowTitle = title, root = UiSkeletonNodeDto(
            text = mapOf("text" to text), children = listOf(UiSkeletonNodeDto(text = mapOf("desc" to child))),
        ))
        assertEquals(listOf(title, text, child), CensusSkeletonSchema.slots(item))
        val notification = NotificationSkeletonContractTest.fixture()
        assertEquals(NotifTextField.entries.map { notification.slots.getValue(it) }, CensusSkeletonSchema.slots(notification))
    }

    private fun screen() = UiSkeletonDto(
        schemaId = SkeletonSchema.SCHEMA_ID, hashDomain = 1, filterRev = 1, fingerprint = "0".repeat(64),
        platform = "doordash", engineVersion = 1, day = "2026-10-06", root = UiSkeletonNodeDto(),
    )
}

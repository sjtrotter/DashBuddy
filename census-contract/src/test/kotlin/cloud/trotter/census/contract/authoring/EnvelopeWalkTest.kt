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
package cloud.trotter.census.contract.authoring

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeWalkTest {
    @Test
    fun `preorder preserves paths typed slots immediate sibling and click ancestry`() {
        val payload = Json.parseToJsonElement("""{
            "class":" android.widget.FrameLayout ", "isClickable":true,
            "bounds":{"left":1,"top":2,"right":3,"bottom":4},
            "children":[
                {"class":"TextView","text":" Label ","visible":false},
                {"class":"TextView","id":" app:id/value ","desc":" Value ","clickAction":true},
                {"class":"TextView","hint":" Hint ","pane":" Pane ","children":[{"text":"child"}]}
            ]
        }""").jsonObject
        val nodes = EnvelopeWalk.walk(payload)
        assertEquals(listOf(emptyList(), listOf(0), listOf(1), listOf(2), listOf(2, 0)), nodes.map { it.path })
        assertEquals("FrameLayout", nodes.first().simpleClass)
        assertEquals(Bounds(1, 2, 3, 4), nodes.first().bounds)
        val value = requireNotNull(nodes.at(listOf(1)))
        assertEquals("app:id/value", value.viewId)
        assertEquals("value", value.idSuffix)
        assertEquals("Value", value.desc)
        assertEquals("Label", value.precedingSiblingText)
        assertTrue(value.hasClickAction)
        assertTrue(value.clickableAncestor)
        assertTrue(value.visible)
        assertFalse(requireNotNull(nodes.at(listOf(0))).visible)
        assertNull(requireNotNull(nodes.at(listOf(2))).precedingSiblingText)
        assertEquals("Hint", nodes.at(listOf(2))?.hint)
        assertEquals("Pane", nodes.at(listOf(2))?.pane)
        assertNull(nodes.at(listOf(7)))
        assertFalse(nodes.first().clickableAncestor)
    }

    @Test
    fun `malformed children consume budget and retain original indices`() {
        val payload = Json.parseToJsonElement("""{"class":"Root","children":[
            42, null, {"text":false}, {}, {"class":"TextView","text":"ok"},
            {"class":"TextView","visible":"false"}, {"class":"TextView"}
        ]}""").jsonObject
        val nodes = EnvelopeWalk.walk(payload)
        assertEquals(listOf(emptyList(), listOf(4), listOf(6)), nodes.map { it.path })
        assertNull(nodes.last().precedingSiblingText)
        assertEquals(1, EnvelopeWalk.walk(payload, maxNodes = 5).size)
    }

    @Test
    fun `node and depth bounds include root at depth zero`() {
        var payload: JsonObject = buildJsonObject { put("class", "Leaf") }
        repeat(100) {
            val child = payload
            payload = buildJsonObject { put("class", "Branch"); put("children", JsonArray(listOf(child))) }
        }
        assertEquals(65, EnvelopeWalk.walk(payload).size)
        assertEquals(4, EnvelopeWalk.walk(payload, maxNodes = 4).size)
        assertEquals(3, EnvelopeWalk.walk(payload, maxDepth = 2).size)
        assertEquals(1, EnvelopeWalk.walk(payload, maxDepth = 0).size)
        assertTrue(EnvelopeWalk.walk(payload, maxNodes = 0).isEmpty())
        assertTrue(EnvelopeWalk.walk(payload, maxDepth = -1).isEmpty())
    }
}

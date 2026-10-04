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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleDraftTest {
    private val root = NodeRef(emptyList())
    private val child = NodeRef(listOf(0))
    private val base = Selections("idle", "idle", "drafted", 50, anchors = listOf(root))

    private fun node(
        id: String? = "chrome",
        text: String? = null,
        desc: String? = null,
        clickable: Boolean = false,
        children: List<JsonObject> = emptyList(),
        className: String = "android.widget.TextView",
    ): JsonObject = buildJsonObject {
        put("class", className)
        id?.let { put("id", "example:id/$it") }
        text?.let { put("text", it) }
        desc?.let { put("desc", it) }
        put("isClickable", clickable)
        put("children", JsonArray(children))
    }

    private fun generate(selections: Selections = base, payload: JsonObject = node()): DraftResult =
        RuleDraft.generate(buildJsonObject { put("payload", payload) }, selections, "example", null, "2026-10-04")

    private fun ok(selections: Selections = base, payload: JsonObject = node()): DraftResult.Ok {
        val result = generate(selections, payload)
        assertTrue(result.toString(), result is DraftResult.Ok)
        return result as DraftResult.Ok
    }

    private fun errors(selections: Selections = base, payload: JsonObject = node()): List<String> {
        val result = generate(selections, payload)
        assertTrue(result.toString(), result is DraftResult.Refused)
        return (result as DraftResult.Refused).errors
    }

    private fun DraftResult.Ok.rule(): JsonObject = fragment.getValue("screens").jsonArray.single().jsonObject
    private fun DraftResult.Ok.fields(): JsonObject = rule().getValue("parse").jsonObject.getValue("fields").jsonObject

    @Test
    fun `validation collects all independent selection errors`() {
        val result = errors(base.copy(
            screenClass = "bad", shape = "bad", intent = "Bad!", priority = 999,
            modeHint = "none", offerSurface = "bad", anchors = emptyList(),
        ))
        assertEquals(listOf(
            "unknown screenClass", "unknown shape", "invalid intent", "priority must be in 1..998",
            "unknown modeHint", "unknown offerSurface", "offerSurface requires offer:presented",
            "at least one anchor is required",
        ), result)
        for (priority in listOf(0, 999, -1)) assertTrue(errors(base.copy(priority = priority)).contains("priority must be in 1..998"))
        for (intent in listOf("", "a".repeat(49), "has.dot", "has-dash", "1first")) {
            assertTrue(errors(base.copy(intent = intent)).contains("invalid intent"))
        }
        ok(base.copy(intent = "a".repeat(48), priority = 998, modeHint = "paused"))
    }

    @Test
    fun `every node role must resolve within the bounded walk`() {
        val missing = NodeRef(listOf(9))
        val result = errors(base.copy(
            anchors = listOf(missing), fields = listOf(FieldAssignment(missing, "zoneName")),
            binds = listOf(BindAssignment(missing, "expandButton")), redacts = listOf(missing),
        ))
        assertEquals(List(4) { "node path does not resolve: [9]" }, result)
        assertEquals(
            listOf("envelope has no payload object", "node path does not resolve: []"),
            (RuleDraft.generate(JsonObject(emptyMap()), base, "example", null, "day") as DraftResult.Refused).errors,
        )
    }

    @Test
    fun `field transform and bind vocabulary are enforced`() {
        val result = errors(base.copy(
            fields = listOf(FieldAssignment(root, "custom", listOf("bogus", "normalizeCustomerName", "sha256"))),
            binds = listOf(BindAssignment(root, "tapAnything")),
        ), node(text = "value"))
        assertEquals(listOf(
            "unknown field custom for shape idle", "unknown transform bogus",
            "normalizeCustomerName requires a customer-name hash field", "sha256 requires a hash field",
            "unknown bind target tapAnything", "bind tapAnything is not clickable and has no clickable ancestor",
        ), result)
        assertTrue(errors(base.copy(fields = listOf(FieldAssignment(root, "zoneName", listOf("sha256")))), node(text = "x"))
            .contains("sha256 requires a hash field"))
        assertTrue(errors(base.copy(shape = "task", fields = listOf(
            FieldAssignment(root, "customerAddressHash", listOf("normalizeCustomerName")),
        )), node(text = "x")).contains("normalizeCustomerName requires a customer-name hash field"))
    }

    @Test
    fun `required and one-of fields count typed constants`() {
        val offer = base.copy(screenClass = "offer:presented", shape = "offer", offerSurface = "card")
        assertEquals(listOf("missing required field payAmount", "missing required field distance",
            "missing one of: deliveryTimeText, timeToCompleteMinutes"), errors(offer))
        val complete = offer.copy(constants = listOf(
            Constant("payAmount", JsonPrimitive(8.5)), Constant("distance", JsonPrimitive(2.1)),
            Constant("timeToCompleteMinutes", JsonPrimitive(12)),
        ))
        assertEquals(listOf("offer draft has no orders[] — store names will be absent"), ok(complete).warnings)
        assertEquals(listOf("missing required field totalPay"), errors(base.copy(shape = "post_task")))
        assertEquals(listOf("missing required field totalEarnings"), errors(base.copy(shape = "session_ended")))
    }

    @Test
    fun `constants cannot bypass field types hash handling or overwrite assignments`() {
        assertEquals(listOf("unknown constant custom for shape idle"), errors(base.copy(constants = listOf(Constant("custom", JsonPrimitive(true))))))
        assertEquals(listOf("invalid constant type for startingSession"), errors(base.copy(constants = listOf(Constant("startingSession", JsonPrimitive("true"))))))
        assertEquals(listOf("hash field customerNameHash requires a node assignment"), errors(base.copy(
            shape = "task", constants = listOf(Constant("customerNameHash", JsonPrimitive("plaintext"))),
        )))
        assertTrue(errors(base.copy(fields = listOf(FieldAssignment(root, "zoneName")),
            constants = listOf(Constant("zoneName", JsonPrimitive("zone")))), node(text = "zone")).contains("duplicate field zoneName"))
        assertTrue(errors(base.copy(binds = listOf(BindAssignment(root, "expandButton"), BindAssignment(root, "expandButton"))),
            node(clickable = true)).contains("duplicate bind target expandButton"))
    }

    @Test
    fun `class-only and unstable fields are refused including hint and pane only`() {
        assertEquals(listOf("class-only anchor refused at []"), errors(payload = node(id = null)))
        val payload = buildJsonObject { put("class", "TextView"); put("hint", "Stable label"); put("pane", "Pane") }
        assertEquals(listOf("class-only anchor refused at []"), errors(payload = payload))
        assertEquals(listOf("field has no stable anchor at [0]"), errors(
            base.copy(fields = listOf(FieldAssignment(child, "zoneName"))), node(children = listOf(node(id = null, text = "zone"))),
        ))
        assertEquals(listOf("field zoneName has no text/desc slot"), errors(base.copy(fields = listOf(FieldAssignment(root, "zoneName")))))
        assertEquals(listOf("hash field customerNameHash has no text/desc slot"), errors(
            base.copy(shape = "task", fields = listOf(FieldAssignment(root, "customerNameHash"))),
        ))
    }

    @Test
    fun `duplicate ids escalate anchor predicates but cannot silently parse another value`() {
        val payload = node(id = "same", text = "Ready", children = listOf(node(id = "same", text = "Other")))
        val predicate = ok(payload = payload).rule().getValue("require").jsonObject.getValue("exists")
        assertEquals(Json.parseToJsonElement("""{"all":[{"hasIdSuffix":"same"},{"hasClassNameEndsWith":"TextView"},{"hasText":"Ready"}]}"""), predicate)
        assertEquals(listOf("ambiguous id at []"), errors(payload = node(id = "same", children = listOf(node(id = "same")))))
        assertEquals(listOf("ambiguous id for field at [0]"), errors(
            base.copy(fields = listOf(FieldAssignment(child, "zoneName"))), payload,
        ))
        val differentClass = node(id = "same", text = "Ready", children = listOf(node(id = "same", text = "value", className = "Button")))
        assertEquals(2, ok(base.copy(fields = listOf(FieldAssignment(child, "zoneName"))), differentClass)
            .fields().getValue("zoneName").jsonObject.getValue("find").jsonObject.getValue("all").jsonArray.size)
    }

    @Test
    fun `description is a typed anchor and parse fallback`() {
        assertEquals(Json.parseToJsonElement("""{"exists":{"hasDesc":"Ready"}}"""),
            ok(payload = node(id = null, desc = " Ready ")).rule().getValue("require"))
        val result = ok(base.copy(fields = listOf(FieldAssignment(child, "zoneName"))),
            node(children = listOf(node(id = "value", desc = " Zone "))))
        assertEquals("contentDescription", result.fields().getValue("zoneName").jsonObject.getValue("read").jsonPrimitive.content)
        val duplicate = node(id = "same", desc = "Ready", children = listOf(node(id = "same", desc = "Other")))
        assertTrue(ok(payload = duplicate).json5.contains("hasDesc"))
    }

    @Test
    fun `idless fields use only immediate sibling text with class`() {
        val selection = base.copy(fields = listOf(FieldAssignment(NodeRef(listOf(1)), "zoneName")))
        val result = ok(selection, node(children = listOf(node(id = null, text = "Zone"), node(id = null, text = "North"))))
        assertEquals(Json.parseToJsonElement("""{"all":[{"hasClassNameEndsWith":"TextView"},{"hasPrecedingSiblingText":"Zone"}]}"""),
            result.fields().getValue("zoneName").jsonObject.getValue("find"))
        assertTrue(errors(selection, node(children = listOf(node(id = null, desc = "Zone"), node(id = null, text = "North"))))
            .contains("field has no stable anchor at [1]"))
    }

    @Test
    fun `all emitted label selectors use the literal guard and require a class when needed`() {
        val missingClass = buildJsonObject { put("text", "Ready") }
        assertEquals(listOf("node has no simple class: []"), errors(payload = missingClass))
        assertEquals(listOf("class-only anchor refused at []"), errors(payload = node(id = null, clickable = true)))
        val fields = base.copy(fields = listOf(FieldAssignment(NodeRef(listOf(1)), "zoneName")))
        assertEquals(listOf("unsafe anchor literal at [1]"), errors(fields,
            node(children = listOf(node(id = null, text = "Route 123"), node(id = null, text = "North")))))
        val repeated = node(children = listOf(
            node(id = null, text = "Zone"), node(id = null, text = "North"),
            node(id = null, text = "Zone"), node(id = null, text = "South"),
        ))
        assertEquals(listOf("ambiguous sibling anchor at [1]"), errors(fields, repeated))
        val binds = base.copy(binds = listOf(BindAssignment(child, "expandButton")))
        assertEquals(listOf("unsafe anchor literal at [0]"), errors(binds,
            node(children = listOf(node(id = null, desc = "Bank account", clickable = true)))))
        val result = ok(binds, node(children = listOf(node(id = null, desc = "Expand", clickable = true))))
        assertEquals(Json.parseToJsonElement("""{"hasDesc":"Expand"}"""),
            result.rule().getValue("bind").jsonObject.getValue("expandButton").jsonObject["find"])
    }

    @Test
    fun `hash transforms are forced and redact uses the same selector`() {
        val selection = base.copy(shape = "task", fields = listOf(
            FieldAssignment(child, "customerNameHash", emptyList()),
            FieldAssignment(NodeRef(listOf(1)), "customerAddressHash", listOf("trim")),
        ), redacts = listOf(root))
        val result = ok(selection, node(children = listOf(node(id = "name", text = "Sample Customer"), node(id = "address", desc = "Sample street"))))
        val name = result.fields().getValue("customerNameHash").jsonObject
        assertEquals(Json.parseToJsonElement("""["trim","normalizeCustomerName","sha256"]"""), name["transform"])
        val redacts = result.rule().getValue("redact").jsonArray
        assertEquals(name["find"], redacts[0].jsonObject["find"])
        assertEquals(JsonPrimitive("customerName"), redacts[0].jsonObject["normalize"])
        assertEquals(setOf("find"), redacts[1].jsonObject.keys)
        assertEquals(JsonPrimitive(true), redacts[2].jsonObject["plainMask"])
        assertFalse(result.json5.contains("Sample Customer"))
        assertFalse(result.json5.contains("Sample street"))
    }

    @Test
    fun `bind actions are sorted unique and ancestor reliance warns`() {
        val result = ok(base.copy(binds = listOf(
            BindAssignment(child, "declineButton"), BindAssignment(root, "acceptButton"),
        )), node(clickable = true, children = listOf(node(id = "label", text = "Decline"))))
        assertEquals(Json.parseToJsonElement("""["accept_offer","decline_offer"]"""), result.rule()["enables"])
        assertEquals(listOf("bind declineButton relies on a clickable ancestor"), result.warnings)
        assertEquals(JsonPrimitive(true), result.rule().getValue("bind").jsonObject.getValue("acceptButton").jsonObject["optional"])
        assertTrue(errors(base.copy(binds = listOf(BindAssignment(root, "acceptButton"))))
            .contains("bind acceptButton is not clickable and has no clickable ancestor"))
        val actionOnly = buildJsonObject { put("class", "Button"); put("id", "example:id/control"); put("clickAction", true) }
        assertTrue(errors(base.copy(binds = listOf(BindAssignment(root, "acceptButton"))), actionOnly)
            .contains("bind acceptButton is not clickable and has no clickable ancestor"))
    }

    @Test
    fun `unsafe literals never enter a successful draft`() {
        for (literal in listOf(" ", "x".repeat(81), "Pay $8", "Route 123", "Route ١٢٣", "[redacted:sample]", "Bank account")) {
            assertEquals(listOf("unsafe anchor literal at []"), errors(payload = node(id = null, text = literal)))
            assertEquals(listOf("unsafe anchor literal at []"), errors(payload = node(id = null, desc = literal)))
            assertTrue(errors(payload = node(id = "same", text = literal, children = listOf(node(id = "same"))))
                .contains("unsafe anchor literal at []"))
        }
        assertEquals(listOf("one anchor; verify against the negative corpus"), ok(payload = node(id = null, text = "Ready")).warnings)
        assertTrue(ok(payload = node(text = "$123.00")).warnings.isEmpty()) // The literal is never emitted for a unique id.
    }

    @Test
    fun `key order task defaults and transform cardinality are stable`() {
        val selection = base.copy(screenClass = "task:dropoff:navigation", shape = "task", comment = "Reviewed",
            constants = listOf(Constant("arrivalConfirmed", JsonPrimitive(false))),
            fields = listOf(FieldAssignment(child, "deadlineText"), FieldAssignment(root, "storeName", emptyList())),
            binds = listOf(BindAssignment(root, "expandButton")), redacts = listOf(child))
        val result = ok(selection, node(text = "Chrome", clickable = true, children = listOf(node(id = "deadline", text = "Deliver by 6:02 PM"))))
        assertEquals(listOf("$" + "schema", "screens"), result.fragment.keys.toList())
        assertEquals(listOf("id", "priority", "comment", "enables", "state", "bind", "redact", "require", "parse"), result.rule().keys.toList())
        assertEquals(listOf("phase", "subFlow", "arrivalConfirmed", "deadlineText", "storeName"), result.fields().keys.toList())
        assertEquals(JsonPrimitive("DROPOFF"), result.fields()["phase"])
        assertEquals(JsonPrimitive("NAVIGATION"), result.fields()["subFlow"])
        assertEquals(JsonPrimitive("stripDeadlinePrefix"), result.fields().getValue("deadlineText").jsonObject["transform"])
        assertFalse("transform" in result.fields().getValue("storeName").jsonObject)
        val array = ok(base.copy(fields = listOf(FieldAssignment(root, "zoneName", listOf("trim", "lower")))), node(text = "Zone"))
        assertEquals(Json.parseToJsonElement("""["trim","lower"]"""), array.fields().getValue("zoneName").jsonObject["transform"])
    }

    @Test
    fun `sensitive and noise omit state and forbid fields and binds`() {
        for (screenClass in listOf("sensitive", "noise")) {
            val selection = base.copy(screenClass = screenClass, shape = screenClass)
            val rule = ok(selection).rule()
            assertFalse("state" in rule)
            assertEquals(buildJsonObject { put("as", screenClass) }, rule["parse"])
            assertEquals(if (screenClass == "sensitive") JsonPrimitive(false) else null, rule["overrideable"])
            assertTrue(errors(selection.copy(fields = listOf(FieldAssignment(root, "zoneName")), binds = listOf(BindAssignment(root, "expandButton"))))
                .contains("sensitive/noise class cannot declare fields or binds"))
            assertTrue(errors(selection.copy(shape = "idle")).contains("$screenClass class and shape must agree"))
        }
        assertEquals(buildJsonObject { put("as", "none") }, ok(base.copy(shape = "none")).rule()["parse"])
    }

    @Test
    fun `output is byte stable two-space JSON with only explicit provenance`() {
        val envelope = buildJsonObject {
            put("payload", node()); put("captureId", "private-capture"); put("fingerprint", "private-fingerprint")
        }
        val first = RuleDraft.generate(envelope, base, "example", "1.2", "2026-10-04") as DraftResult.Ok
        assertEquals(first, RuleDraft.generate(envelope, base, "example", "1.2", "2026-10-04"))
        assertTrue(first.json5.startsWith("// Drafted from a census capture — example 1.2 · 2026-10-04\n{\n  \""))
        assertFalse(first.json5.contains("private-"))
        assertFalse(first.json5.contains("captureId"))
        assertFalse(first.json5.contains("fingerprint"))
        assertTrue(ok().json5.contains("unknown version"))
        val two = ok(base.copy(anchors = listOf(root, child)), node(children = listOf(node(id = "other"))))
        assertEquals(2, two.rule().getValue("require").jsonObject.getValue("all").jsonArray.size)
    }
}

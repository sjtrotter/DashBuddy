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
            (RuleDraft.generate(JsonObject(emptyMap()), base, "example", null, "2026-10-04") as DraftResult.Refused).errors,
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
        assertTrue(errors(base.copy(screenClass = "task:active", shape = "task", fields = listOf(
            FieldAssignment(root, "customerAddressHash", listOf("normalizeCustomerName")),
        )), node(text = "x")).contains("normalizeCustomerName requires a customer-name hash field"))
    }

    @Test
    fun `required fields cannot be supplied through dynamic constants`() {
        val offer = base.copy(screenClass = "offer:presented", shape = "offer", offerSurface = "card")
        assertEquals(listOf("missing required field payAmount", "missing required field distance",
            "missing one of: deliveryTimeText, timeToCompleteMinutes"), errors(offer))
        val complete = offer.copy(constants = listOf(
            Constant("payAmount", JsonPrimitive(8.5)), Constant("distance", JsonPrimitive(2.1)),
            Constant("timeToCompleteMinutes", JsonPrimitive(12)),
        ))
        assertEquals(listOf("payAmount", "distance", "timeToCompleteMinutes").map {
            "constant $it: only flags, counts and enum values may be constants"
        }, errors(complete))
        assertEquals(listOf("missing required field totalPay"), errors(base.copy(screenClass = "post:task", shape = "post_task")))
        assertEquals(listOf("missing required field totalEarnings"), errors(base.copy(screenClass = "session:ended", shape = "session_ended")))
    }

    @Test
    fun `constants cannot bypass field types hash handling or overwrite assignments`() {
        assertEquals(listOf("unknown constant custom for shape idle"), errors(base.copy(constants = listOf(Constant("custom", JsonPrimitive(true))))))
        assertEquals(listOf("invalid constant type for startingSession"), errors(base.copy(constants = listOf(Constant("startingSession", JsonPrimitive("true"))))))
        assertEquals(listOf("hash field customerNameHash requires a node assignment"), errors(base.copy(
            screenClass = "task:active", shape = "task", constants = listOf(Constant("customerNameHash", JsonPrimitive("plaintext"))),
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
            base.copy(screenClass = "task:active", shape = "task", fields = listOf(FieldAssignment(root, "customerNameHash"))),
        ))
    }

    @Test
    fun `duplicate ids escalate anchor predicates but cannot silently parse another value`() {
        val payload = node(id = "same", text = "Ready", children = listOf(node(id = "same", text = "Other")))
        val predicate = ok(payload = payload).rule().getValue("require").jsonObject.getValue("exists")
        assertEquals(Json.parseToJsonElement("""{"all":[{"hasIdSuffix":":id/same"},{"hasClassNameEndsWith":"TextView"},{"hasText":"Ready"}]}"""), predicate)
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
        assertEquals(Json.parseToJsonElement("""{"exists":{"hasDesc":" Ready "}}"""),
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

    }

    @Test
    fun `shared id fields require a unique full value shape from the resolved transform chain`() {
        val selection = base.copy(fields = listOf(FieldAssignment(child, "sessionPay", listOf("trim", "parseCurrency"))))
        val payload = node(children = listOf(node(id = "value", text = "$8.30"), node(id = "value", text = "3.8 mi")))
        val find = ok(selection, payload).fields().getValue("sessionPay").jsonObject.getValue("find")
        assertEquals(buildJsonObject { put("all", JsonArray(listOf(
            buildJsonObject { put("hasIdSuffix", ":id/value") },
            buildJsonObject { put("hasClassNameEndsWith", "TextView") },
            buildJsonObject { put("hasTextMatchesRegex", RuleAuthoringVocabulary.VALUE_SHAPES_BY_TRANSFORM.getValue("parseCurrency")) },
        ))) }, find)
        for (values in listOf(listOf("$8.30", "$9.00"), listOf("$8.30 Guaranteed", "$9.00 Guaranteed"))) {
            assertEquals(listOf("ambiguous id for field at [0]"), errors(selection,
                node(children = values.map { node(id = "value", text = it) })))
        }
        assertEquals(listOf("ambiguous id for field at [0]"), errors(selection.copy(
            fields = listOf(FieldAssignment(child, "sessionPay", emptyList())),
        ), payload))
        // ANCHOR still quotes the literal and therefore refuses currency, even with a shaped FIELD.
        assertTrue(errors(selection.copy(anchors = listOf(child)), payload).contains("unsafe anchor literal at [0]"))
    }

    @Test
    fun `idless fields cannot use global shape selection even for a unique amount`() {
        val selection = base.copy(fields = listOf(FieldAssignment(child, "sessionPay")))
        for (values in listOf(listOf("$8.30", "3.8 mi"), listOf("$8.30", "$9.00"), listOf("$8.30 Guaranteed", "3.8 mi"))) {
            assertEquals(listOf("field has no stable anchor at [0]"), errors(selection,
                node(children = values.map { node(id = null, text = it) })))
        }
    }

    @Test
    fun `idless shape refuses a collision under another parent because find is envelope wide`() {
        val selection = base.copy(fields = listOf(FieldAssignment(NodeRef(listOf(1, 0)), "sessionPay")))
        val payload = node(children = listOf(
            node(id = "first", children = listOf(node(id = null, text = "$9.00"))),
            node(id = "second", children = listOf(node(id = null, text = "$8.30"))),
        ))
        assertEquals(listOf("field has no stable anchor at [1, 0]"), errors(selection, payload))
    }

    @Test
    fun `desc sibling hash field still refuses without a stable redact predicate`() {
        val selection = base.copy(screenClass = "task:active", shape = "task", fields = listOf(FieldAssignment(NodeRef(listOf(1)), "customerNameHash")))
        assertEquals(listOf("hash field customerNameHash has no stable redact predicate (the parse selector was valid)"), errors(selection,
            node(children = listOf(node(id = null, desc = "Customer"), node(id = null, text = "Sample Customer")))))
    }

    @Test
    fun `desc only sibling emits siblingOf and refuses ambiguous or unsafe labels`() {
        val selection = base.copy(fields = listOf(FieldAssignment(NodeRef(listOf(1)), "sessionPay")))
        val children = listOf(node(id = null, desc = "Total"), node(id = null, text = "$8.30"))
        val field = ok(selection, node(children = children)).fields().getValue("sessionPay").jsonObject
        assertEquals(Json.parseToJsonElement("""{"siblingOf":{"hasDesc":"Total"},"offset":1,"read":"text","transform":"parseGlyphCurrency"}"""), field)
        for (label in listOf("Total", "TOTAL")) {
            assertEquals(listOf("ambiguous sibling label at [1]"), errors(selection,
                node(children = children + node(id = "other", desc = label))))
        }
        assertEquals(listOf("unsafe anchor literal at [1]"), errors(selection,
            node(children = listOf(node(id = null, desc = "Route 123"), children[1]))))
        val withText = node(children = listOf(node(id = null, text = "Pay", desc = "Total"), children[1]))
        assertTrue("find" in ok(selection, withText).fields().getValue("sessionPay").jsonObject)
    }

    @Test
    fun `stripPrefix precedes the forced hash chain and preserves chrome in redact`() {
        val prefix = "Recipient: "
        val selection = base.copy(screenClass = "task:active", shape = "task", fields = listOf(
            FieldAssignment(child, "customerNameHash", emptyList(), stripPrefix = prefix),
        ))
        val result = ok(selection, node(children = listOf(node(id = "name", text = prefix + "Sample Customer"))))
        val field = result.fields().getValue("customerNameHash").jsonObject
        assertEquals(Json.parseToJsonElement("""[{"stripPrefixes":["Recipient: "]},"trim","normalizeCustomerName","sha256"]"""), field["transform"])
        val redact = result.rule().getValue("redact").jsonArray.single().jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive(prefix))), redact["keepPrefix"])
        assertEquals(JsonPrimitive("customerName"), redact["normalize"])
        assertEquals(field["find"], redact["find"])
        assertFalse(result.json5.contains("Sample Customer"))
        assertEquals(listOf("stripPrefix is not a prefix of the value"), errors(selection,
            node(children = listOf(node(id = "name", text = "Sample Customer")))))
        for (unsafe in listOf("", "Route 123 ", "Bank account ", "$8 ")) {
            assertTrue(errors(selection.copy(fields = listOf(FieldAssignment(child, "customerNameHash", stripPrefix = unsafe))),
                node(children = listOf(node(id = "name", text = unsafe + "Sample Customer")))).contains("unsafe anchor literal at [0]"))
        }
        val plain = ok(base.copy(fields = listOf(FieldAssignment(child, "zoneName", listOf("trim", "lower"), "Zone: "))),
            node(children = listOf(node(id = "zone", text = "Zone: North"))))
        assertEquals(Json.parseToJsonElement("""[{"stripPrefixes":["Zone: "]},"trim","lower"]"""),
            plain.fields().getValue("zoneName").jsonObject["transform"])
        assertFalse("redact" in plain.rule())
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
        assertTrue(errors(binds, node(children = listOf(node(id = null, desc = "Bank account", clickable = true))))
            .contains("unsafe anchor literal at [0]"))
        val result = ok(binds, node(children = listOf(node(id = null, desc = "Expand", clickable = true))))
        assertEquals(Json.parseToJsonElement("""{"hasDesc":"Expand"}"""),
            result.rule().getValue("bind").jsonObject.getValue("expandButton").jsonObject["find"])
    }

    @Test
    fun `hash transforms are forced and redact uses the same selector`() {
        val selection = base.copy(screenClass = "task:active", shape = "task", fields = listOf(
            FieldAssignment(child, "customerNameHash", emptyList()),
            FieldAssignment(NodeRef(listOf(1)), "customerAddressHash", listOf("trim")),
        ), redacts = listOf(root))
        val result = ok(selection, node(children = listOf(node(id = "name", text = "Sample Customer"), node(id = "address", desc = "Sample street"))))
        assertTrue(result.warnings.containsAll(listOf(
            "transform on customerNameHash ignored: hash fields use their fixed chain",
            "transform on customerAddressHash ignored: hash fields use their fixed chain",
        )))
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
        for (literal in listOf("x".repeat(81), "Pay $8", "Route 123", "Route ١٢٣", "[redacted:sample]", "Bank account")) {
            assertTrue(errors(payload = node(id = null, text = literal)).contains("unsafe anchor literal at []"))
            assertTrue(errors(payload = node(id = null, desc = literal)).contains("unsafe anchor literal at []"))
            assertTrue(errors(payload = node(id = "same", text = literal, children = listOf(node(id = "same"))))
                .contains("unsafe anchor literal at []"))
        }
        assertEquals(listOf("class-only anchor refused at []"), errors(payload = node(id = null, text = " ")))
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
    @Test
    fun `resource boundaries and compiler suffix semantics define peers`() {
        val boundary = node(id = "container", children = listOf(node(id = "bottom_navigation_container")))
        // Round 3 refinement: :id/ makes this suffix collision impossible in the compiler too.
        assertEquals(Json.parseToJsonElement("""{"exists":{"hasIdSuffix":":id/container"}}"""), ok(payload = boundary).rule()["require"])
        val casePeers = node(id = "container", text = "Ready", className = "View",
            children = listOf(node(id = "CONTAINER", text = "Other", className = "TextView")))
        assertTrue(ok(payload = casePeers).json5.contains("hasClassNameEndsWith"))
        assertTrue(errors(base.copy(fields = listOf(FieldAssignment(root, "zoneName"))), casePeers)
            .contains("ambiguous id for field at []"))
        val labels = node(children = listOf(node(id = null, text = "Zone"), node(id = null, text = "North", className = "View"),
            node(id = null, text = "ZONE"), node(id = null, text = "South", className = "TextView")))
        assertEquals(listOf("ambiguous sibling anchor at [1]"), errors(base.copy(
            fields = listOf(FieldAssignment(NodeRef(listOf(1)), "zoneName"))), labels))
    }

    @Test
    fun `raw labels stay exact while blank text falls through to desc`() {
        assertEquals(Json.parseToJsonElement("""{"exists":{"all":[{"hasClassNameEndsWith":"TextView"},{"hasText":" Ready "}]}}"""),
            ok(payload = node(id = null, text = " Ready ")).rule()["require"])
        assertEquals(Json.parseToJsonElement("""{"exists":{"hasDesc":"Collapse"}}"""),
            ok(payload = node(id = null, text = "", desc = "Collapse")).rule()["require"])
        val selected = base.copy(fields = listOf(FieldAssignment(NodeRef(listOf(1)), "sessionPay")))
        assertTrue("siblingOf" in ok(selected, node(children = listOf(node(id = null, text = "", desc = "Collapse"),
            node(id = null, text = "$8.30")))).fields().getValue("sessionPay").jsonObject)
        assertEquals("contentDescription", ok(base.copy(fields = listOf(FieldAssignment(root, "zoneName"))),
            node(text = "", desc = "North")).fields().getValue("zoneName").jsonObject.getValue("read").jsonPrimitive.content)
    }

    @Test
    fun `truncated envelopes refuse uniqueness decisions`() {
        val wide = node(children = List(EnvelopeWalk.MAX_NODES) { node(id = "peer") })
        assertTrue(errors(payload = wide).contains("envelope exceeds the walk bounds"))
        var deep = node(id = "leaf")
        repeat(EnvelopeWalk.MAX_DEPTH + 1) { deep = node(id = "branch", children = listOf(deep)) }
        assertTrue(errors(payload = deep).contains("envelope exceeds the walk bounds"))
    }

    @Test
    fun `header metadata cannot inject comments`() {
        val envelope = buildJsonObject { put("payload", node()) }
        for (day in listOf("day", "2026-10-04\n", "2026-1-04")) {
            assertTrue(RuleDraft.generate(envelope, base, "example", null, day) is DraftResult.Refused)
        }
        for (version in listOf("", "x".repeat(41), "1\n//", "1 / 2")) {
            assertTrue(RuleDraft.generate(envelope, base, "example", version, "2026-10-04") is DraftResult.Refused)
        }
        for (platform in listOf("", "Upper", "a-b", "x".repeat(33), "a\n")) {
            assertTrue(RuleDraft.generate(envelope, base, platform, null, "2026-10-04") is DraftResult.Refused)
        }
    }

    @Test
    fun `customer and unicode value literals refuse across anchors labels and prefixes`() {
        for (text in listOf("Ready 1", "Ready ١", "Ready 𝟚", "Pay ＄", "Pay €", "Jane S", "Deliver to", "deliver to Jane")) {
            assertTrue(generate(payload = node(id = null, text = text)) is DraftResult.Refused)
            assertTrue(generate(payload = node(id = null, text = "", desc = text)) is DraftResult.Refused)
            assertTrue(generate(base.copy(fields = listOf(FieldAssignment(NodeRef(listOf(1)), "zoneName"))),
                node(children = listOf(node(id = null, text = text), node(id = null, text = "North")))) is DraftResult.Refused)
        }
        for (prefix in listOf("Zone:", "Route 1 ", "Pay ＄ ", "Jane S ")) {
            assertTrue(generate(base.copy(fields = listOf(FieldAssignment(child, "zoneName", stripPrefix = prefix))),
                node(children = listOf(node(id = "zone", text = prefix + "North")))) is DraftResult.Refused)
        }
        for (comment in listOf("x".repeat(201), "Jane S", "Deliver to", "＄", "Bank account")) {
            assertTrue(generate(base.copy(comment = comment)) is DraftResult.Refused)
        }
        ok(base.copy(comment = "123456"))
    }

    @Test
    fun `class shape legality enum constants and resource reads enforce field policy`() {
        for (shape in listOf("task", "offer", "post_task", "session_ended", "paused", "ratings", "timeline")) {
            assertTrue(errors(base.copy(shape = shape)).contains("shape $shape is not legal for class idle"))
        }
        for (value in RuleAuthoringVocabulary.SESSION_TYPES) ok(base.copy(constants = listOf(Constant("sessionType", JsonPrimitive(value)))))
        assertTrue(errors(base.copy(constants = listOf(Constant("sessionType", JsonPrimitive("bad")))))
            .contains("invalid constant type for sessionType"))
        for ((name, value) in listOf("phase" to "PICKUP", "subFlow" to "ARRIVED")) {
            ok(base.copy(screenClass = "task:active", shape = "task", constants = listOf(Constant(name, JsonPrimitive(value)))))
        }
        val post = base.copy(screenClass = "post:task", shape = "post_task", fields = listOf(
            FieldAssignment(root, "totalPay"), FieldAssignment(child, "expandButtonId")))
        val expanded = ok(post, node(text = "$8.30", children = listOf(node(id = "expand"))))
        assertEquals("viewIdResourceName", expanded.fields().getValue("expandButtonId").jsonObject.getValue("read").jsonPrimitive.content)
        assertFalse(RuleAuthoringVocabulary.FIELDS_BY_SHAPE.getValue("offer").any { it.name == "offerHash" })
        val counts = ok(base.copy(screenClass = "task:active", shape = "task", constants = listOf(Constant("itemsShopped", JsonPrimitive(3)))))
        assertEquals(JsonPrimitive(3), counts.fields()["itemsShopped"])
        assertTrue(errors(base.copy(screenClass = "task:active", shape = "task", constants = listOf(Constant("itemsShopped", JsonPrimitive(3.5)))))
            .contains("invalid constant type for itemsShopped"))
    }

    @Test
    fun `a lead-in stripPrefix is accepted while a whole-value name prefix is refused`() {
        // A stripPrefix IS a customer lead-in by design: the name-shape body ("Deliver t" inside "Deliver to ") must not
        // refuse it, and the emitted redact keeps the prefix while the name is masked. Only a prefix that is ITSELF a
        // name shape ("Jane S. ") is refused.
        for (prefix in listOf("Deliver to ", "Deliver to door of ")) {
            val selection = base.copy(screenClass = "task:active", shape = "task", fields = listOf(
                FieldAssignment(child, "customerNameHash", stripPrefix = prefix),
            ))
            val draft = ok(selection, node(children = listOf(node(id = "name", text = prefix + "Sample Customer"))))
            val rule = draft.fragment.getValue("screens").jsonArray.single().jsonObject
            val redact = rule.getValue("redact").jsonArray.single().jsonObject
            assertEquals(JsonArray(listOf(JsonPrimitive(prefix))), redact["keepPrefix"])
            assertEquals(JsonPrimitive("customerName"), redact["normalize"])
        }
        val named = base.copy(screenClass = "task:active", shape = "task", fields = listOf(
            FieldAssignment(child, "customerNameHash", stripPrefix = "Jane S. "),
        ))
        assertTrue(errors(named, node(children = listOf(node(id = "name", text = "Jane S. Sample Customer"))))
            .contains("stripPrefix looks like a customer name"))
    }

}

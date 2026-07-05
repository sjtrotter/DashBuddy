package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RuleCompiler] predicate compilation.
 *
 * Each test compiles a predicate from a JSON snippet and verifies it returns the
 * expected boolean for specific inputs. No JSON file on disk is loaded — inputs are
 * constructed inline.
 */
class RuleCompilerTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun raw(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        tickerText: String? = null,
    ) = RawNotificationData(
        title = title, text = text, bigText = bigText, tickerText = tickerText,
        packageName = "com.doordash.driverapp", postTime = 0L, isClearable = false,
    )

    private fun node(
        viewId: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        className: String? = null,
        isClickable: Boolean = false,
        isEnabled: Boolean = false,
        isChecked: Int = 0,
    ) = UiNode(
        viewIdResourceName = viewId,
        text = text,
        contentDescription = contentDescription,
        className = className,
        isClickable = isClickable,
        isEnabled = isEnabled,
        isChecked = isChecked,
    )

    private fun tree(vararg children: UiNode, text: String? = null): UiNode {
        val root = UiNode(text = text, children = children.toMutableList())
        root.restoreParents()
        return root
    }

    private fun json(vararg pairs: Pair<String, Any>) = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                is String -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is Int -> JsonPrimitive(v)
                else -> throw IllegalArgumentException("Unsupported type: ${v::class}")
            }
        }
    )

    private fun parseJson(s: String) = Json.parseToJsonElement(s)

    // =========================================================================
    // compileNodePred — ID predicates
    // =========================================================================

    @Test
    fun `hasIdSuffix matches by suffix`() {
        val pred = RuleCompiler.compileNodePred(json("hasIdSuffix" to "accept_button"))
        assertTrue(pred(node(viewId = "com.example:id/accept_button")))
        assertFalse(pred(node(viewId = "com.example:id/decline_button")))
    }

    @Test
    fun `hasIdSuffix is case insensitive`() {
        val pred = RuleCompiler.compileNodePred(json("hasIdSuffix" to "Accept_Button"))
        assertTrue(pred(node(viewId = "com.example:id/accept_button")))
    }

    @Test
    fun `hasIdSuffix returns false for null viewId`() {
        val pred = RuleCompiler.compileNodePred(json("hasIdSuffix" to "accept_button"))
        assertFalse(pred(node(viewId = null)))
    }

    @Test
    fun `hasIdExact requires exact match`() {
        val pred = RuleCompiler.compileNodePred(json("hasIdExact" to "com.example:id/btn"))
        assertTrue(pred(node(viewId = "com.example:id/btn")))
        assertFalse(pred(node(viewId = "com.example:id/btn_extra")))
        assertFalse(pred(node(viewId = null)))
    }

    @Test
    fun `hasIdContaining matches substring in viewId`() {
        val pred = RuleCompiler.compileNodePred(json("hasIdContaining" to "action"))
        assertTrue(pred(node(viewId = "com.example:id/primary_action_button")))
        assertFalse(pred(node(viewId = "com.example:id/accept_button")))
    }

    @Test
    fun `hasNoId matches null or blank viewId`() {
        val pred = RuleCompiler.compileNodePred(json("hasNoId" to "true"))
        assertTrue(pred(node(viewId = null)))
        assertTrue(pred(node(viewId = "")))
        assertFalse(pred(node(viewId = "some:id/btn")))
    }

    // =========================================================================
    // compileNodePred — text predicates
    // =========================================================================

    @Test
    fun `hasText matches exact text case-insensitively`() {
        val pred = RuleCompiler.compileNodePred(json("hasText" to "Accept"))
        assertTrue(pred(node(text = "Accept")))
        assertTrue(pred(node(text = "ACCEPT")))
        assertTrue(pred(node(text = "accept")))
        // hasText is equals (not contains), so partial match fails
        assertFalse(pred(node(text = "Accept offer")))
        assertFalse(pred(node(text = null)))
    }

    @Test
    fun `hasTextCaseSensitive is exact and case-sensitive`() {
        val pred = RuleCompiler.compileNodePred(json("hasTextCaseSensitive" to "Accept"))
        assertTrue(pred(node(text = "Accept")))
        assertFalse(pred(node(text = "ACCEPT")))
        assertFalse(pred(node(text = "accept")))
    }

    @Test
    fun `hasTextContaining matches substring case-insensitively`() {
        val pred = RuleCompiler.compileNodePred(json("hasTextContaining" to "Decline"))
        assertTrue(pred(node(text = "Decline offer")))
        assertTrue(pred(node(text = "decline offer")))
        assertFalse(pred(node(text = "Accept")))
        assertFalse(pred(node(text = null)))
    }

    @Test
    fun `hasTextStartsWith matches prefix case-insensitively`() {
        val pred = RuleCompiler.compileNodePred(json("hasTextStartsWith" to "Arrived"))
        assertTrue(pred(node(text = "Arrived at store")))
        assertTrue(pred(node(text = "arrived")))
        assertFalse(pred(node(text = "You Arrived")))
        assertFalse(pred(node(text = null)))
    }

    @Test
    fun `hasTextMatchesRegex uses compiled regex`() {
        val pred = RuleCompiler.compileNodePred(parseJson("""{"hasTextMatchesRegex": "\\d{1,2}/\\d{1,2}"}"""))
        assertTrue(pred(node(text = "Delivered at 4/26")))
        assertFalse(pred(node(text = "No date here")))
        assertFalse(pred(node(text = null)))
    }

    @Test
    fun `hasAnyText matches text on the node itself`() {
        val pred = RuleCompiler.compileNodePred(json("hasAnyText" to "Decline offer"))
        assertTrue(pred(node(text = "Decline offer")))
        assertTrue(pred(node(text = "decline offer")))
        assertFalse(pred(node(text = "Decline")))
    }

    @Test
    fun `hasAnyText matches text on a direct child`() {
        // The DoorDash confirm-decline regression: outer Button has no text,
        // child TextView carries "Decline offer". hasText fails; hasAnyText must succeed.
        val pred = RuleCompiler.compileNodePred(json("hasAnyText" to "Decline offer"))
        val confirmDeclineButton = tree(
            node(text = "Decline offer", className = "android.widget.TextView"),
        ).copy(isClickable = true, className = "android.widget.Button")
        assertTrue(pred(confirmDeclineButton))
    }

    @Test
    fun `hasAnyText matches text on a deeper descendant`() {
        val pred = RuleCompiler.compileNodePred(json("hasAnyText" to "Arrived at store"))
        val wrapper = tree(tree(node(text = "Arrived at store")))
        assertTrue(pred(wrapper))
    }

    @Test
    fun `hasAnyText matches contentDescription anywhere in the tree`() {
        val pred = RuleCompiler.compileNodePred(json("hasAnyText" to "Submit"))
        val root = tree(node(contentDescription = "Submit"))
        assertTrue(pred(root))
    }

    @Test
    fun `hasAnyText returns false when text is absent throughout the tree`() {
        val pred = RuleCompiler.compileNodePred(json("hasAnyText" to "Decline offer"))
        val root = tree(node(text = "Accept"), node(text = "Cancel"))
        assertFalse(pred(root))
        assertFalse(pred(node(text = null)))
    }

    // =========================================================================
    // compileNodePred — content description & class predicates
    // =========================================================================

    @Test
    fun `hasDesc matches content description case-insensitively`() {
        val pred = RuleCompiler.compileNodePred(json("hasDesc" to "close"))
        assertTrue(pred(node(contentDescription = "Close")))
        assertFalse(pred(node(contentDescription = "Open")))
    }

    @Test
    fun `hasClassNameEndsWith matches class suffix`() {
        val pred = RuleCompiler.compileNodePred(json("hasClassNameEndsWith" to "TextView"))
        assertTrue(pred(node(className = "android.widget.TextView")))
        assertFalse(pred(node(className = "android.widget.Button")))
    }

    // =========================================================================
    // compileNodePred — boolean flag predicates
    // =========================================================================

    @Test
    fun `isClickable passes for clickable nodes`() {
        val pred = RuleCompiler.compileNodePred(json("isClickable" to true))
        assertTrue(pred(node(isClickable = true)))
        assertFalse(pred(node(isClickable = false)))
    }

    @Test
    fun `isEnabled passes for enabled nodes`() {
        val pred = RuleCompiler.compileNodePred(json("isEnabled" to true))
        assertTrue(pred(node(isEnabled = true)))
        assertFalse(pred(node(isEnabled = false)))
    }

    @Test
    fun `isChecked passes for checked nodes`() {
        val pred = RuleCompiler.compileNodePred(json("isChecked" to true))
        assertTrue(pred(node(isChecked = 1)))
        assertFalse(pred(node(isChecked = 0)))
    }

    @Test
    fun `hasChildren passes for nodes with children`() {
        val pred = RuleCompiler.compileNodePred(json("hasChildren" to true))
        val withChild = UiNode(children = mutableListOf(UiNode(text = "child")))
        assertTrue(pred(withChild))
        assertFalse(pred(node()))
    }

    @Test
    fun `isLeaf passes for nodes without children`() {
        val pred = RuleCompiler.compileNodePred(json("isLeaf" to true))
        assertTrue(pred(node()))
        val withChild = UiNode(children = mutableListOf(UiNode(text = "child")))
        assertFalse(pred(withChild))
    }

    // =========================================================================
    // compileNodePred — logical combinators
    // =========================================================================

    @Test
    fun `all requires every predicate to pass`() {
        val pred = RuleCompiler.compileNodePred(
            parseJson("""{"all": [{"hasText": "Accept"}, {"isClickable": true}]}""")
        )
        assertTrue(pred(node(text = "Accept", isClickable = true)))
        assertFalse(pred(node(text = "Accept", isClickable = false)))
        assertFalse(pred(node(text = "Other", isClickable = true)))
    }

    @Test
    fun `any passes when at least one predicate passes`() {
        val pred = RuleCompiler.compileNodePred(
            parseJson("""{"any": [{"hasText": "Accept"}, {"hasText": "Decline"}]}""")
        )
        assertTrue(pred(node(text = "Accept")))
        assertTrue(pred(node(text = "Decline")))
        assertFalse(pred(node(text = "Other")))
    }

    @Test
    fun `not negates the inner predicate`() {
        val pred = RuleCompiler.compileNodePred(parseJson("""{"not": {"hasText": "Accept"}}"""))
        assertFalse(pred(node(text = "Accept")))
        assertTrue(pred(node(text = "Decline")))
        assertTrue(pred(node(text = null)))
    }

    // =========================================================================
    // compileTreePred
    // =========================================================================

    @Test
    fun `allTextContains passes when joined tree text contains substring`() {
        val pred = RuleCompiler.compileTreePred(json("allTextContains" to "accept"))
        val t = tree(node(text = "Accept"), text = "Other")
        assertTrue(pred(t))
    }

    @Test
    fun `allTextContains fails when substring not in tree`() {
        val pred = RuleCompiler.compileTreePred(json("allTextContains" to "missing"))
        assertFalse(pred(tree(node(text = "Accept"))))
    }

    @Test
    fun `allTextContainsAll requires all strings in tree text`() {
        val pred = RuleCompiler.compileTreePred(
            parseJson("""{"allTextContainsAll": ["accept", "decline"]}""")
        )
        val t = tree(node(text = "Accept"), node(text = "Decline"))
        assertTrue(pred(t))
        assertFalse(pred(tree(node(text = "Accept"))))
    }

    @Test
    fun `allTextContainsAny requires at least one string in tree text`() {
        val pred = RuleCompiler.compileTreePred(
            parseJson("""{"allTextContainsAny": ["accept", "confirm"]}""")
        )
        assertTrue(pred(tree(node(text = "Accept"))))
        assertTrue(pred(tree(node(text = "Confirm pickup"))))
        assertFalse(pred(tree(node(text = "Other"))))
    }

    @Test
    fun `exists passes when matching node found in tree`() {
        val pred = RuleCompiler.compileTreePred(parseJson("""{"exists": {"hasText": "Accept"}}"""))
        val t = tree(node(text = "Accept"), node(text = "Other"))
        assertTrue(pred(t))
    }

    @Test
    fun `exists fails when no matching node in tree`() {
        val pred = RuleCompiler.compileTreePred(parseJson("""{"exists": {"hasText": "Accept"}}"""))
        assertFalse(pred(tree(node(text = "Decline"))))
    }

    @Test
    fun `notExists passes when no matching node`() {
        val pred = RuleCompiler.compileTreePred(parseJson("""{"notExists": {"hasText": "Accept"}}"""))
        assertTrue(pred(tree(node(text = "Decline"))))
        assertFalse(pred(tree(node(text = "Accept"))))
    }

    @Test
    fun `tree all combinator`() {
        val pred = RuleCompiler.compileTreePred(
            parseJson("""{"all": [{"allTextContains": "foo"}, {"allTextContains": "bar"}]}""")
        )
        assertTrue(pred(tree(node(text = "foo"), node(text = "bar"))))
        assertFalse(pred(tree(node(text = "foo"))))
    }

    @Test
    fun `tree not combinator`() {
        val pred = RuleCompiler.compileTreePred(
            parseJson("""{"not": {"allTextContains": "sensitive"}}""")
        )
        assertTrue(pred(tree(node(text = "Normal screen"))))
        assertFalse(pred(tree(node(text = "sensitive content"))))
    }

    // =========================================================================
    // compileNotifPred
    // =========================================================================

    @Test
    fun `titleContains matches notification title case-insensitively`() {
        val pred = RuleCompiler.compileNotifPred(json("titleContains" to "New Order"))
        assertTrue(pred(raw(title = "New Order")))
        assertTrue(pred(raw(title = "NEW ORDER AVAILABLE")))
        assertFalse(pred(raw(title = "DoorDash")))
        assertFalse(pred(raw(title = null)))
    }

    @Test
    fun `textContains matches notification text`() {
        val pred = RuleCompiler.compileNotifPred(json("textContains" to "expired"))
        assertTrue(pred(raw(text = "Your scheduled dash has expired")))
        assertFalse(pred(raw(text = "Your dash is active")))
    }

    @Test
    fun `anyFieldMatchesRegex matches against full text`() {
        val pred = RuleCompiler.compileNotifPred(
            parseJson("""{"anyFieldMatchesRegex": "added \\$\\d+\\.\\d{2} tip"}""")
        )
        assertTrue(pred(raw(bigText = "added \$5.00 tip on a past H-E-B order delivered at 4/26, 3:15 PM")))
        assertFalse(pred(raw(text = "You received a tip")))
    }

    @Test
    fun `anyFieldContainsAll requires all substrings present across all fields`() {
        val pred = RuleCompiler.compileNotifPred(
            parseJson("""{"anyFieldContainsAll": ["scheduled", "expired"]}""")
        )
        assertTrue(pred(raw(text = "Your scheduled dash has expired")))
        assertFalse(pred(raw(text = "Your scheduled dash starts soon")))
        assertFalse(pred(raw(text = "Your promo has expired")))
    }

    @Test
    fun `anyFieldContainsAny passes when any substring matches`() {
        val pred = RuleCompiler.compileNotifPred(
            parseJson("""{"anyFieldContainsAny": ["new order", "order available"]}""")
        )
        assertTrue(pred(raw(title = "New Order")))
        assertTrue(pred(raw(text = "An order available for pickup")))
        assertFalse(pred(raw(title = "DoorDash", text = "Something else")))
    }

    @Test
    fun `isClearable matches clearable notifications`() {
        val pred = RuleCompiler.compileNotifPred(json("isClearable" to true))
        val clearable = RawNotificationData(
            title = null, text = null, bigText = null, tickerText = null,
            packageName = "com.doordash.driverapp", postTime = 0L, isClearable = true,
        )
        val notClearable = clearable.copy(isClearable = false)
        assertTrue(pred(clearable))
        assertFalse(pred(notClearable))
    }

    @Test
    fun `notif not combinator negates predicate`() {
        val pred = RuleCompiler.compileNotifPred(
            parseJson("""{"not": {"titleContains": "New Order"}}""")
        )
        assertFalse(pred(raw(title = "New Order")))
        assertTrue(pred(raw(title = "DoorDash")))
    }

    @Test
    fun `notif all combinator`() {
        val pred = RuleCompiler.compileNotifPred(
            parseJson("""{"all": [{"titleContains": "New"}, {"textContains": "order"}]}""")
        )
        assertTrue(pred(raw(title = "New Order", text = "An order is available")))
        assertFalse(pred(raw(title = "New Order", text = "Something else")))
        assertFalse(pred(raw(title = "DoorDash", text = "An order is available")))
    }

    // =========================================================================
    // Security caps
    // =========================================================================

    @Test(expected = RuleCompileException::class)
    fun `regex length exceeding MAX_REGEX_LENGTH throws`() {
        val pattern = "a".repeat(RuleCompiler.MAX_REGEX_LENGTH + 1)
        RuleCompiler.compileNodePred(parseJson("""{"hasTextMatchesRegex": "$pattern"}"""))
    }

    @Test(expected = RuleCompileException::class)
    fun `invalid regex throws RuleCompileException`() {
        RuleCompiler.compileNodePred(parseJson("""{"hasTextMatchesRegex": "[invalid("}"""))
    }

    @Test(expected = RuleCompileException::class)
    fun `unknown node predicate key throws`() {
        RuleCompiler.compileNodePred(parseJson("""{"unknownKey": "value"}"""))
    }

    @Test(expected = RuleCompileException::class)
    fun `unknown tree predicate key throws`() {
        RuleCompiler.compileTreePred(parseJson("""{"unknownKey": "value"}"""))
    }

    @Test(expected = RuleCompileException::class)
    fun `unknown notif predicate key throws`() {
        RuleCompiler.compileNotifPred(parseJson("""{"unknownKey": "value"}"""))
    }

    @Test(expected = RuleCompileException::class)
    fun `rule exceeding MAX_BRANCHES_PER_RULE throws (#419)`() {
        val branches = (0..RuleCompiler.MAX_BRANCHES_PER_RULE).joinToString(",") {
            """{ "require": { "exists": { "hasText": "x$it" } } }"""
        }
        val rule = """[{ "id": "dd.screen.fat", "priority": 7000, "branches": [ $branches ] }]"""
        RuleCompiler.compileRules<UiNode>(parseJson(rule).jsonArray, RuleContext.SCREEN)
    }

    @Test
    fun `rule at MAX_BRANCHES_PER_RULE compiles (#419 boundary)`() {
        val branches = (0 until RuleCompiler.MAX_BRANCHES_PER_RULE).joinToString(",") {
            """{ "require": { "exists": { "hasText": "x$it" } } }"""
        }
        val rule = """[{ "id": "dd.screen.atcap", "priority": 7001, "branches": [ $branches ] }]"""
        val compiled = RuleCompiler.compileRules<UiNode>(parseJson(rule).jsonArray, RuleContext.SCREEN)
        assertEquals(RuleCompiler.MAX_BRANCHES_PER_RULE, compiled.single().branches.size)
    }

    @Test(expected = RuleCompileException::class)
    fun `rule exceeding MAX_EFFECTS_PER_RULE throws (#419)`() {
        val effects = (0..RuleCompiler.MAX_EFFECTS_PER_RULE).joinToString(",") {
            """{ "log": { "type": "T$it" } }"""
        }
        val rule = """[{
            "id": "dd.screen.effecty", "priority": 7002,
            "require": { "exists": { "hasText": "x" } },
            "effects": [ $effects ]
        }]"""
        RuleCompiler.compileRules<UiNode>(parseJson(rule).jsonArray, RuleContext.SCREEN)
    }

    @Test
    fun `rule at MAX_EFFECTS_PER_RULE compiles (#419 boundary)`() {
        val effects = (0 until RuleCompiler.MAX_EFFECTS_PER_RULE).joinToString(",") {
            """{ "log": { "type": "T$it" } }"""
        }
        val rule = """[{
            "id": "dd.screen.effectcap", "priority": 7003,
            "require": { "exists": { "hasText": "x" } },
            "effects": [ $effects ]
        }]"""
        val compiled = RuleCompiler.compileRules<UiNode>(parseJson(rule).jsonArray, RuleContext.SCREEN)
        assertEquals(1, compiled.size)
    }

    @Test(expected = RuleCompileException::class)
    fun `node pred depth limit throws`() {
        var json = """{"hasText": "x"}"""
        repeat(RuleCompiler.MAX_DEPTH + 2) { json = """{"not": $json}""" }
        RuleCompiler.compileNodePred(Json.parseToJsonElement(json))
    }

    @Test(expected = RuleCompileException::class)
    fun `tree pred depth limit throws`() {
        var json = """{"allTextContains": "x"}"""
        repeat(RuleCompiler.MAX_DEPTH + 2) { json = """{"not": $json}""" }
        RuleCompiler.compileTreePred(Json.parseToJsonElement(json))
    }

    @Test(expected = RuleCompileException::class)
    fun `coalesce with a top-level transform throws - it would be silently dropped (#549)`() {
        // A coalesce returns the chosen branch verbatim; a top-level transform is NOT applied. For a
        // PII field (sha256 at the top instead of in each branch) that would leak the raw value, so
        // the compiler must reject it — the transform has to live inside each branch.
        val rule = """
            [{
              "id": "test.coalesce.toptransform",
              "priority": 9999,
              "state": { "flow": "task:dropoff:navigation" },
              "require": { "exists": { "hasText": "x" } },
              "parse": {
                "as": "task",
                "fields": {
                  "phase": "DROPOFF",
                  "customerAddressHash": {
                    "coalesce": [ { "find": { "hasIdSuffix": "address_line_1" }, "read": "text" } ],
                    "transform": "sha256"
                  }
                }
              }
            }]
        """.trimIndent()
        RuleCompiler.compileRules<UiNode>(parseJson(rule).jsonArray, RuleContext.SCREEN)
    }

    // =========================================================================
    // compileEffectEntry — verb validation
    // =========================================================================

    private val compiler = RuleCompiler

    @Test(expected = RuleCompileException::class)
    fun `compileEffectEntry rejects rule-declared click - actuation is app-owned`() {
        // #425: rules expose target bindings; they can never declare a tap.
        compiler.compileEffectEntry(
            parseJson("""{"click": "${'$'}acceptBtn"}""").jsonObject
        )
    }

    @Test(expected = RuleCompileException::class)
    fun `compileEffectEntry rejects future gesture wires too`() {
        compiler.compileEffectEntry(
            parseJson("""{"swipe": {"direction": "up"}}""").jsonObject
        )
    }

    @Test
    fun `compileEffectEntry accepts screenshot verb`() {
        val effect = compiler.compileEffectEntry(
            parseJson("""{"screenshot": {"prefix": "Offer"}}""").jsonObject
        )
        assertEquals(cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.SCREENSHOT, effect.verb)
        assertEquals("Offer", effect.args["prefix"])
    }

    @Test
    fun `compileEffectEntry accepts bubble verb with args`() {
        val effect = compiler.compileEffectEntry(
            parseJson("""{"bubble": {"text": "Hello", "persona": "dispatcher"}}""").jsonObject
        )
        assertEquals(cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.BUBBLE, effect.verb)
        assertEquals("Hello", effect.args["text"])
        assertEquals("dispatcher", effect.args["persona"])
    }

    @Test
    fun `compileEffectEntry accepts verb with no args`() {
        val effect = compiler.compileEffectEntry(
            parseJson("""{"evaluate_offer": {}}""").jsonObject
        )
        assertEquals(cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.EVALUATE_OFFER, effect.verb)
        assertTrue(effect.args.isEmpty())
    }

    @Test(expected = RuleCompileException::class)
    fun `compileEffectEntry rejects unknown verb`() {
        compiler.compileEffectEntry(
            parseJson("""{"explode": {}}""").jsonObject
        )
    }

    @Test(expected = RuleCompileException::class)
    fun `compileEffectEntry rejects missing verb`() {
        compiler.compileEffectEntry(
            parseJson("""{"onlyIf": {"fieldEquals": {"field": "x", "value": "y"}}}""").jsonObject
        )
    }

    // =========================================================================
    // compileEffectEntry — format enforcement
    // =========================================================================

    @Test(expected = RuleCompileException::class)
    fun `compileEffectEntry rejects multiple verb keys`() {
        compiler.compileEffectEntry(
            parseJson("""{"screenshot": {}, "bubble": {"text": "hi"}}""").jsonObject
        )
    }

    // =========================================================================
    // compileEffectEntry — arg key validation
    // =========================================================================

    @Test(expected = RuleCompileException::class)
    fun `compileEffectEntry rejects unknown arg key for screenshot`() {
        compiler.compileEffectEntry(
            parseJson("""{"screenshot": {"badKey": "value"}}""").jsonObject
        )
    }

    @Test(expected = RuleCompileException::class)
    fun `compileEffectEntry rejects unknown arg key for bubble`() {
        compiler.compileEffectEntry(
            parseJson("""{"bubble": {"text": "ok", "unknown": "bad"}}""").jsonObject
        )
    }

    @Test(expected = RuleCompileException::class)
    fun `compileEffectEntry rejects args on verb with no allowed args`() {
        compiler.compileEffectEntry(
            parseJson("""{"evaluate_offer": {"surprise": "bad"}}""").jsonObject
        )
    }

    @Test
    fun `compileEffectEntry preserves onlyIf gate`() {
        val effect = compiler.compileEffectEntry(
            parseJson("""{"screenshot": {"prefix": "Offer"}, "onlyIf": {"fieldEquals": {"field": "intent", "value": "accept"}}}""").jsonObject
        )
        assertTrue(effect.onlyIf is cloud.trotter.dashbuddy.domain.pipeline.ParsedFieldsGate.FieldEquals)
    }

    @Test
    fun `compileEffectEntry preserves dedupeKey and throttleMs`() {
        val effect = compiler.compileEffectEntry(
            parseJson("""{"screenshot": {"prefix": "Offer"}, "dedupeKey": "offer-ss", "throttleMs": 1000}""").jsonObject
        )
        assertEquals("offer-ss", effect.dedupeKey)
        assertEquals(1000L, effect.throttleMs)
    }

    // =========================================================================
    // compileTransitionOverrides — trigger validation
    // =========================================================================

    @Test
    fun `compileTransitionOverrides accepts known trigger keys`() {
        val overrides = compiler.compileTransitionOverrides(
            parseJson("""{
                "mode:online": [{"session_start": {"platformName": "Uber"}}],
                "mode:offline": [{"session_end": {}}, {"odometer_stop": {}}]
            }""").jsonObject
        )
        assertEquals(2, overrides.size)
        assertTrue(overrides.containsKey("mode:online"))
        assertTrue(overrides.containsKey("mode:offline"))
        assertEquals(1, overrides["mode:online"]!!.size)
        assertEquals(2, overrides["mode:offline"]!!.size)
    }

    @Test(expected = RuleCompileException::class)
    fun `compileTransitionOverrides rejects unknown trigger key`() {
        compiler.compileTransitionOverrides(
            parseJson("""{"mode:turbo": [{"log": {}}]}""").jsonObject
        )
    }

    @Test
    fun `compileTransitionOverrides validates effect verbs within overrides`() {
        // Should compile successfully — effects inside overrides go through compileEffectEntry
        val overrides = compiler.compileTransitionOverrides(
            parseJson("""{
                "task:start": [
                    {"odometer_resume": {}},
                    {"log": {"type": "TASK_START"}},
                    {"bubble": {"text": "Task started"}}
                ]
            }""").jsonObject
        )
        assertEquals(3, overrides["task:start"]!!.size)
        assertEquals(
            cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.ODOMETER_RESUME,
            overrides["task:start"]!![0].verb,
        )
    }

    @Test(expected = RuleCompileException::class)
    fun `compileTransitionOverrides rejects unknown verb inside override`() {
        compiler.compileTransitionOverrides(
            parseJson("""{"mode:online": [{"teleport": {}}]}""").jsonObject
        )
    }

    @Test
    fun `compileTransitionOverrides returns empty map for empty input`() {
        val overrides = compiler.compileTransitionOverrides(
            parseJson("""{}""").jsonObject
        )
        assertTrue(overrides.isEmpty())
    }

    // =========================================================================
    // Shape contract validation (M3)
    // =========================================================================

    @Test(expected = RuleCompileException::class)
    fun `offer without payAmount throws RuleCompileException`() {
        ParsedFieldsFactory.validateShapeFields(
            "offer", setOf("distance", "timeToCompleteMinutes"), "test.rule",
        )
    }

    @Test(expected = RuleCompileException::class)
    fun `offer without distance throws RuleCompileException`() {
        ParsedFieldsFactory.validateShapeFields(
            "offer", setOf("payAmount", "timeToCompleteMinutes"), "test.rule",
        )
    }

    @Test(expected = RuleCompileException::class)
    fun `offer without time field throws RuleCompileException`() {
        ParsedFieldsFactory.validateShapeFields(
            "offer", setOf("payAmount", "distance"), "test.rule",
        )
    }

    @Test
    fun `offer with deliveryTimeText passes validation`() {
        ParsedFieldsFactory.validateShapeFields(
            "offer", setOf("payAmount", "distance", "deliveryTimeText"), "test.rule",
        )
    }

    @Test
    fun `offer with timeToCompleteMinutes passes validation`() {
        ParsedFieldsFactory.validateShapeFields(
            "offer", setOf("payAmount", "distance", "timeToCompleteMinutes"), "test.rule",
        )
    }

    @Test(expected = RuleCompileException::class)
    fun `post_task without totalPay throws RuleCompileException`() {
        ParsedFieldsFactory.validateShapeFields(
            "post_task", setOf("appPay", "customerTips"), "test.rule",
        )
    }

    @Test
    fun `post_task with totalPay passes validation`() {
        ParsedFieldsFactory.validateShapeFields(
            "post_task", setOf("totalPay"), "test.rule",
        )
    }

    @Test(expected = RuleCompileException::class)
    fun `session_ended without totalEarnings throws RuleCompileException`() {
        ParsedFieldsFactory.validateShapeFields(
            "session_ended", setOf("sessionDurationMillis"), "test.rule",
        )
    }

    @Test
    fun `session_ended with totalEarnings passes validation`() {
        ParsedFieldsFactory.validateShapeFields(
            "session_ended", setOf("totalEarnings"), "test.rule",
        )
    }

    @Test
    fun `shape with no required fields passes validation`() {
        ParsedFieldsFactory.validateShapeFields("idle", setOf("anything"), "test.rule")
        ParsedFieldsFactory.validateShapeFields("task", emptySet(), "test.rule")
        ParsedFieldsFactory.validateShapeFields("noise", emptySet(), "test.rule")
    }

    @Test
    fun `unknown shape passes validation`() {
        ParsedFieldsFactory.validateShapeFields("future_shape", emptySet(), "test.rule")
    }

    @Test(expected = RuleCompileException::class)
    fun `compileRules rejects offer shape rule missing payAmount`() {
        val ruleJson = """[{
            "id": "test.screen.bad_offer",
            "priority": 10,
            "require": { "exists": { "hasText": "Accept" } },
            "parse": {
                "as": "offer",
                "fields": {
                    "distance": { "find": { "hasText": "5 mi" }, "read": "text" }
                }
            }
        }]"""
        RuleCompiler.compileRules<UiNode>(
            Json.parseToJsonElement(ruleJson).jsonArray, RuleContext.SCREEN,
        )
    }

    @Test
    fun `compileRules accepts offer shape rule with required fields`() {
        val ruleJson = """[{
            "id": "test.screen.good_offer",
            "priority": 10,
            "require": { "exists": { "hasText": "Accept" } },
            "parse": {
                "as": "offer",
                "fields": {
                    "payAmount": { "find": { "hasTextMatchesRegex": "\\$\\d" }, "read": "text" },
                    "distance": { "find": { "hasTextMatchesRegex": "\\d.*mi" }, "read": "text" },
                    "deliveryTimeText": { "find": { "hasTextMatchesRegex": "\\d+:\\d+" }, "read": "text" }
                }
            }
        }]"""
        val rules = RuleCompiler.compileRules<UiNode>(
            Json.parseToJsonElement(ruleJson).jsonArray, RuleContext.SCREEN,
        )
        assertEquals(1, rules.size)
    }

    // =========================================================================
    // redact block + sha256⇒redact enforcement (#598)
    // =========================================================================

    @Test(expected = RuleCompileException::class)
    fun `screen rule with sha256 transform but no redact block fails compile`() {
        val ruleJson = """[{
            "id": "doordash.screen.leaky",
            "priority": 10,
            "require": { "exists": { "hasTextStartsWith": "Deliver to" } },
            "parse": {
                "as": "task",
                "fields": {
                    "customerNameHash": {
                        "find": { "hasTextStartsWith": "Deliver to" },
                        "read": "text",
                        "transform": [ { "stripPrefixes": ["Deliver to "] }, "sha256" ]
                    }
                }
            }
        }]"""
        RuleCompiler.compileRules<UiNode>(
            Json.parseToJsonElement(ruleJson).jsonArray, RuleContext.SCREEN,
        )
    }

    @Test
    fun `screen rule with sha256 and a redact block compiles and carries the redact`() {
        val ruleJson = """[{
            "id": "doordash.screen.safe",
            "priority": 10,
            "require": { "exists": { "hasTextStartsWith": "Deliver to" } },
            "redact": [
                { "find": { "hasTextStartsWith": "Deliver to" }, "keepPrefix": ["Deliver to "] }
            ],
            "parse": {
                "as": "task",
                "fields": {
                    "customerNameHash": {
                        "find": { "hasTextStartsWith": "Deliver to" },
                        "read": "text",
                        "transform": [ { "stripPrefixes": ["Deliver to "] }, "sha256" ]
                    }
                }
            }
        }]"""
        val rules = RuleCompiler.compileRules<UiNode>(
            Json.parseToJsonElement(ruleJson).jsonArray, RuleContext.SCREEN,
        )
        assertEquals(1, rules.size)
        assertFalse(rules[0].redact.isEmpty())
    }

    @Test
    fun `redact masks matched node text keeping the declared prefix`() {
        val ruleJson = """[{
            "id": "doordash.screen.dropoff",
            "priority": 10,
            "require": { "exists": { "hasTextStartsWith": "Deliver to" } },
            "redact": [
                { "find": { "hasTextStartsWith": "Deliver to" }, "keepPrefix": ["Deliver to "] },
                { "find": { "hasIdSuffix": "address_line_1" } }
            ]
        }]"""
        val rule = RuleCompiler.compileRules<UiNode>(
            Json.parseToJsonElement(ruleJson).jsonArray, RuleContext.SCREEN,
        )[0]
        val tree = UiNode(
            children = listOf(
                UiNode(text = "Deliver to Jane Q. Doe"),
                UiNode(viewIdResourceName = "com.x:id/address_line_1", text = "123 Secret St"),
                UiNode(text = "Directions"),
            ),
        )
        val redacted = rule.redact.apply(tree)
        // #623: masked portion carries a `[redacted:<4hex>]` distinctness suffix.
        assertTrue(
            "marker kept, name masked with distinctness suffix",
            Regex("""^Deliver to \[redacted:[0-9a-f]{4}\]$""").matches(redacted.children[0].text!!),
        )
        assertTrue(
            "address masked whole with distinctness suffix",
            Regex("""^\[redacted:[0-9a-f]{4}\]$""").matches(redacted.children[1].text!!),
        )
        assertEquals("Directions", redacted.children[2].text)
        // Original tree is untouched.
        assertEquals("Deliver to Jane Q. Doe", tree.children[0].text)
    }

    @Test
    fun `notification rule with sha256 does not require a redact block`() {
        // The sha256->redact compile gate is SCREEN-scoped. Notification-envelope
        // redaction is opt-in `redact` vocabulary (#620), not compiler-enforced.
        val ruleJson = """[{
            "id": "doordash.notification.customer_message",
            "priority": 10,
            "require": { "channelIdContains": "message" },
            "parse": {
                "fields": {
                    "senderHash": { "from": "title", "transform": "sha256" }
                }
            }
        }]"""
        val rules = RuleCompiler.compileRules<RawNotificationData>(
            Json.parseToJsonElement(ruleJson).jsonArray, RuleContext.NOTIFICATION,
        )
        assertEquals(1, rules.size)
    }

    // =========================================================================
    // notification redact block (#620)
    // =========================================================================

    @Test
    fun `notification redact compiles both whole-field and regex-capture maskers`() {
        val ruleJson = """[{
            "id": "doordash.notification.customer_message",
            "priority": 10,
            "require": { "channelIdContains": "chat" },
            "redact": {
                "title": { "keepPrefix": [ "Message from " ] },
                "text": {},
                "bigText": { "match": "^(.+?)'s order is ready for pickup at ", "maskGroup": 1 }
            }
        }]"""
        val rule = RuleCompiler.compileRules<RawNotificationData>(
            Json.parseToJsonElement(ruleJson).jsonArray, RuleContext.NOTIFICATION,
        ).single()
        assertFalse("notif redact must compile onto the rule", rule.notifRedact.isEmpty())

        val masked = rule.notifRedact.apply(
            raw(title = "Message from Jennifer", text = "gate code is 4412", bigText = "Adam's order is ready for pickup at 7-Eleven"),
        )
        // Whole-field with keepPrefix: marker kept, name masked.
        assertTrue(Regex("""^Message from \[redacted:[0-9a-f]{4}\]$""").matches(masked.title!!))
        // Whole-field: body fully masked.
        assertTrue(Regex("""^\[redacted:[0-9a-f]{4}\]$""").matches(masked.text!!))
        // Regex-capture: name masked, store kept.
        assertFalse("customer name gone", masked.bigText!!.contains("Adam"))
        assertTrue("store kept", masked.bigText!!.contains("7-Eleven"))
        assertTrue(
            Regex("""^\[redacted:[0-9a-f]{4}\]'s order is ready for pickup at 7-Eleven$""")
                .matches(masked.bigText!!),
        )
    }

    @Test(expected = RuleCompileException::class)
    fun `notification redact rejects an unknown field`() {
        val ruleJson = """[{
            "id": "doordash.notification.bad_field",
            "priority": 11,
            "require": { "channelIdContains": "chat" },
            "redact": { "notAField": {} }
        }]"""
        RuleCompiler.compileRules<RawNotificationData>(
            Json.parseToJsonElement(ruleJson).jsonArray, RuleContext.NOTIFICATION,
        )
    }

    @Test
    fun `notification regex-capture mask fails CLOSED when the pattern does not match (F2b)`() {
        // The require gate admitted the notification but the capture regex drifted
        // and does not match — masking the group would ship the RAW field. It must
        // mask the WHOLE field instead.
        val ruleJson = """[{
            "id": "doordash.notification.order_ready",
            "priority": 10,
            "require": { "anyFieldContains": "ready" },
            "redact": { "text": { "match": "^(.+?)'s order is ready for pickup at ", "maskGroup": 1 } }
        }]"""
        val rule = RuleCompiler.compileRules<RawNotificationData>(
            Json.parseToJsonElement(ruleJson).jsonArray, RuleContext.NOTIFICATION,
        ).single()
        val masked = rule.notifRedact.apply(raw(text = "Your order is ready!"))
        assertEquals("whole field masked when the capture regex misses", "[redacted]", masked.text)
    }

    @Test(expected = RuleCompileException::class)
    fun `notification redact rejects an out-of-range maskGroup (F3)`() {
        // Pattern has 2 capturing groups; maskGroup 5 would throw
        // IndexOutOfBoundsException at capture — reject at COMPILE (fail closed).
        val ruleJson = """[{
            "id": "doordash.notification.bad_group",
            "priority": 12,
            "require": { "channelIdContains": "chat" },
            "redact": { "text": { "match": "(foo)(bar)", "maskGroup": 5 } }
        }]"""
        RuleCompiler.compileRules<RawNotificationData>(
            Json.parseToJsonElement(ruleJson).jsonArray, RuleContext.NOTIFICATION,
        )
    }

    @Test(expected = RuleCompileException::class)
    fun `a redact on a CLICK rule is rejected (F5)`() {
        // redact compiles only for SCREEN, notifRedact only for NOTIFICATION — a
        // CLICK-context redact silently vanishes, so reject it at compile.
        val ruleJson = """[{
            "id": "doordash.click.some_button",
            "priority": 99,
            "screenIs": "offer_popup",
            "require": { "hasText": "Accept" },
            "redact": [ { "find": { "hasTextStartsWith": "Deliver to " } } ]
        }]"""
        RuleCompiler.compileRules<UiNode>(
            Json.parseToJsonElement(ruleJson).jsonArray, RuleContext.CLICK,
        )
    }

    // =========================================================================
    // enumeratePermissions
    // =========================================================================

    @Test
    fun `enumeratePermissions returns tiers from effects and overrides`() {
        val rule = CompiledRule<UiNode>(
            id = "test.rule", priority = 10, overrideable = true,
            branches = listOf(
                CompiledBranch(
                    intent = "OFFER",
                    predicate = { true },
                    effects = listOf(
                        CompiledEffect(verb = cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.SCREENSHOT),
                        CompiledEffect(verb = cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.LOG),
                    ),
                    transitionOverrides = mapOf(
                        "mode:online" to listOf(
                            CompiledEffect(verb = cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.ODOMETER_START),
                        ),
                    ),
                ),
            ),
        )
        val tiers = compiler.enumeratePermissions(listOf(rule))
        // SCREENSHOT → ACCESSIBILITY, LOG → NONE (excluded), ODOMETER_START → LOCATION
        assertTrue(tiers.contains(cloud.trotter.dashbuddy.domain.pipeline.PermissionTier.ACCESSIBILITY))
        assertTrue(tiers.contains(cloud.trotter.dashbuddy.domain.pipeline.PermissionTier.LOCATION))
        assertFalse("NONE should be excluded", tiers.contains(cloud.trotter.dashbuddy.domain.pipeline.PermissionTier.NONE))
    }

    @Test
    fun `enumeratePermissions returns empty for rules with no effects`() {
        val rule = CompiledRule<UiNode>(
            id = "test.rule", priority = 10, overrideable = true,
            branches = listOf(
                CompiledBranch(intent = "IDLE", predicate = { true }),
            ),
        )
        assertTrue(compiler.enumeratePermissions(listOf(rule)).isEmpty())
    }

    // =========================================================================
    // effect meta keys
    // =========================================================================

    @Test
    fun `compileEffectEntry treats meta keys as meta - not as a second verb`() {
        // Regression guard: meta keys alongside a verb must not be flagged
        // as multiple verb keys.
        val obj = parseJson("""{"log": {"type": "X"}, "throttleMs": 1000, "dedupeKey": "k"}""").jsonObject
        val effect = RuleCompiler.compileEffectEntry(obj)
        assertEquals(1000L, effect.throttleMs)
        assertEquals("k", effect.dedupeKey)
    }

    // =========================================================================
    // redact block placement (#624 VET V3)
    // =========================================================================

    @Test(expected = RuleCompileException::class)
    fun `a redact block inside a branches entry is rejected (#624)`() {
        // compileBranch never reads `redact` — a branch-level redact would silently
        // no-op, so the author's PII would ship raw. Reject at compile time.
        val rule = """
            [{
              "id": "doordash.screen.branch_redact",
              "priority": 9001,
              "branches": [
                {
                  "require": { "exists": { "hasText": "x" } },
                  "redact": [ { "find": { "hasTextStartsWith": "Deliver to " }, "keepPrefix": [ "Deliver to " ] } ]
                }
              ]
            }]
        """.trimIndent()
        RuleCompiler.compileRules<UiNode>(parseJson(rule).jsonArray, RuleContext.SCREEN)
    }

    @Test
    fun `a top-level redact on a branched rule still compiles (#624)`() {
        // The reject is scoped to branches[] entries; a whole-rule top-level redact
        // is the #598 mechanism and must keep compiling.
        val rule = """
            [{
              "id": "doordash.screen.toplevel_redact",
              "priority": 9002,
              "redact": [ { "find": { "hasTextStartsWith": "Deliver to " }, "keepPrefix": [ "Deliver to " ] } ],
              "branches": [
                { "require": { "exists": { "hasText": "x" } } }
              ]
            }]
        """.trimIndent()
        val compiled = RuleCompiler.compileRules<UiNode>(parseJson(rule).jsonArray, RuleContext.SCREEN)
        assertFalse("top-level redact must compile onto the rule", compiled.single().redact.isEmpty())
    }
}

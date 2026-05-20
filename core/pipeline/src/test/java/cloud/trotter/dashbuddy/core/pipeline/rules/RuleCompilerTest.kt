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

    // =========================================================================
    // compileEffectEntry — verb validation
    // =========================================================================

    private val compiler = RuleCompiler

    @Test
    fun `compileEffectEntry accepts valid click verb with target`() {
        val effect = compiler.compileEffectEntry(
            parseJson("""{"click": "${'$'}acceptBtn"}""").jsonObject
        )
        assertEquals(cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.CLICK, effect.verb)
        assertEquals("acceptBtn", effect.targetBindName)
    }

    @Test
    fun `compileEffectEntry accepts screenshot verb`() {
        val effect = compiler.compileEffectEntry(
            parseJson("""{"screenshot": {"prefix": "Offer"}}""").jsonObject
        )
        assertEquals(cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.SCREENSHOT, effect.verb)
        assertNull(effect.targetBindName)
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

    @Test(expected = Exception::class)
    fun `compileEffectEntry rejects click with object value instead of target string`() {
        // click requires a string target, not an args object
        compiler.compileEffectEntry(
            parseJson("""{"click": {"bad": "value"}}""").jsonObject
        )
    }

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
            parseJson("""{"click": "${'$'}btn", "onlyIf": {"fieldEquals": {"field": "intent", "value": "accept"}}}""").jsonObject
        )
        assertTrue(effect.onlyIf is cloud.trotter.dashbuddy.domain.pipeline.ParsedFieldsGate.FieldEquals)
    }

    @Test
    fun `compileEffectEntry preserves dedupeKey and throttleMs`() {
        val effect = compiler.compileEffectEntry(
            parseJson("""{"click": "${'$'}btn", "dedupeKey": "accept-click", "throttleMs": 1000}""").jsonObject
        )
        assertEquals("accept-click", effect.dedupeKey)
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
    // delayMs on effects
    // =========================================================================

    @Test
    fun `compileEffectEntry accepts delayMs within cap`() {
        val obj = parseJson("""{"click": "${'$'}btn", "delayMs": 500}""").jsonObject
        val effect = RuleCompiler.compileEffectEntry(obj)
        assertEquals(500L, effect.delayMs)
    }

    @Test
    fun `compileEffectEntry omits delayMs when not specified`() {
        val obj = parseJson("""{"click": "${'$'}btn"}""").jsonObject
        val effect = RuleCompiler.compileEffectEntry(obj)
        assertNull(effect.delayMs)
    }

    @Test(expected = RuleCompileException::class)
    fun `compileEffectEntry rejects delayMs above 5000ms cap`() {
        val obj = parseJson("""{"click": "${'$'}btn", "delayMs": 6000}""").jsonObject
        RuleCompiler.compileEffectEntry(obj)
    }

    @Test
    fun `compileEffectEntry treats delayMs as meta-key not verb-key`() {
        // Regression guard: with delayMs alongside a verb, compileEffectEntry
        // must not flag it as a second verb.
        val obj = parseJson("""{"click": "${'$'}btn", "delayMs": 500, "throttleMs": 1000, "dedupeKey": "k"}""").jsonObject
        val effect = RuleCompiler.compileEffectEntry(obj)
        assertEquals(500L, effect.delayMs)
        assertEquals(1000L, effect.throttleMs)
        assertEquals("k", effect.dedupeKey)
    }
}

package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #624/#632 marker-SSOT unit tests for [CustomerTextMarkers] — the recognized-frame
 * customer-PII backstop's keyword set and the already-redacted skip rule (VET V1)
 * plus the deliberate "Heading to " exclusion (VET V2), across BOTH the screen
 * (UiNode tree) and notification (flat field) helpers.
 */
class CustomerTextMarkersTest {

    private fun notif(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        tickerText: String? = null,
        subText: String? = null,
    ) = RawNotificationData(
        title = title, text = text, bigText = bigText, tickerText = tickerText,
        subText = subText, packageName = "pkg", postTime = 0L, isClearable = true,
    )

    @Test
    fun `a customer marker prefix is detected`() {
        assertEquals("Deliver to ", CustomerTextMarkers.unredactedMarker("Deliver to Jane Q Doe"))
        assertEquals("Order for ", CustomerTextMarkers.unredactedMarker("Order for John Smith"))
        assertEquals("Verify items for ", CustomerTextMarkers.unredactedMarker("Verify items for Jane"))
        assertEquals("Delivery for ", CustomerTextMarkers.unredactedMarker("Delivery for John"))
    }

    @Test
    fun `the Pickup for task-detail marker is detected (#806)`() {
        // The DoorDash pickup / "Current task" bottom-sheet lead-in that leaked the
        // customer name to UNKNOWN captures; redacted on recognized captures already.
        assertEquals("Pickup for ", CustomerTextMarkers.unredactedMarker("Pickup for Jane D."))
        // Already-redacted form (VET V1) is skipped so a rule's own output can't re-trip.
        assertNull(CustomerTextMarkers.unredactedMarker("Pickup for [redacted:ab12]"))
    }

    @Test
    fun `an already-redacted node is skipped (VET V1)`() {
        // The distinctness suffix form and the plain form both contain "[redacted".
        assertNull(CustomerTextMarkers.unredactedMarker("Deliver to [redacted:ab12]"))
        assertNull(CustomerTextMarkers.unredactedMarker("Deliver to [redacted]"))
        // The rule's OWN dropoff_reminder output must not re-trip the backstop.
        assertNull(CustomerTextMarkers.unredactedMarker("Deliver to door of [redacted:ab12]"))
    }

    @Test
    fun `Heading to is NOT a marker - it prefixes store names (VET V2)`() {
        assertNull(CustomerTextMarkers.unredactedMarker("Heading to Chipotle"))
    }

    @Test
    fun `Deliver by is NOT a marker - it is a time`() {
        assertNull(CustomerTextMarkers.unredactedMarker("Deliver by 5:00 PM"))
    }

    @Test
    fun `null and blank are clean`() {
        assertNull(CustomerTextMarkers.unredactedMarker(null))
        assertNull(CustomerTextMarkers.unredactedMarker(""))
    }

    @Test
    fun `firstUnredactedMarker walks the whole tree and scrub masks only the offending node`() {
        val tree = UiNode(
            children = listOf(
                UiNode(text = "Directions"),
                UiNode(text = "Deliver to Jane Q Doe"),
                UiNode(text = "Got it"),
            ),
        )
        assertEquals("Deliver to ", CustomerTextMarkers.firstUnredactedMarker(tree))

        val scrubbed = CustomerTextMarkers.scrub(tree)
        assertEquals("Directions", scrubbed.children[0].text)
        assertEquals("[redacted]", scrubbed.children[1].text)
        assertEquals("Got it", scrubbed.children[2].text)
        // Clean after scrub.
        assertNull(CustomerTextMarkers.firstUnredactedMarker(scrubbed))
    }

    // --- Notification path (#632) --------------------------------------------

    @Test
    fun `cross-platform notif customer lead-ins are detected (#632)`() {
        // Uber notification vocabulary.
        assertEquals(
            "Leave the order at ",
            CustomerTextMarkers.unredactedMarker("Leave the order at 123 Main St, Apt 4"),
        )
        assertEquals(
            "Meet at door for ",
            CustomerTextMarkers.unredactedMarker("Meet at door for Jane Q Doe"),
        )
        // DoorDash chat push title.
        assertEquals("Message from ", CustomerTextMarkers.unredactedMarker("Message from Jennifer"))
    }

    @Test
    fun `firstUnredactedMarkerInNotif scans flat fields and scrubNotif masks the whole field`() {
        val raw = notif(
            title = "Leave the order at 123 Main St, Apt 4",
            text = "Your delivery from H-E-B", // store-only, kept
        )
        assertEquals("Leave the order at ", CustomerTextMarkers.firstUnredactedMarkerInNotif(raw))

        val scrubbed = CustomerTextMarkers.scrubNotif(raw)
        // Whole-field scrub (flat strings, not a tree).
        assertEquals("[redacted]", scrubbed.title)
        // Store-only field is untouched (merchants are not PII).
        assertEquals("Your delivery from H-E-B", scrubbed.text)
        // Clean after scrub.
        assertNull(CustomerTextMarkers.firstUnredactedMarkerInNotif(scrubbed))
    }

    @Test
    fun `an already-redacted notif field is skipped (VET V1)`() {
        val raw = notif(title = "Leave the order at [redacted:ab12]")
        assertNull(CustomerTextMarkers.firstUnredactedMarkerInNotif(raw))
    }

    @Test
    fun `store-only notif text is NOT a customer marker`() {
        // "Your delivery from <store>" prefixes a MERCHANT, not customer data.
        assertNull(CustomerTextMarkers.unredactedMarker("Your delivery from H-E-B"))
        assertNull(CustomerTextMarkers.firstUnredactedMarkerInNotif(notif(text = "Your delivery from H-E-B")))
    }

    // --- actionLabels (#666 item 2) -------------------------------------------

    @Test
    fun `a customer-marker action label is detected and scrubbed, a clean label is untouched`() {
        val raw = notif(title = "New order").copy(
            actionLabels = listOf("Message from Jane", "Dismiss"),
        )
        assertEquals("Message from ", CustomerTextMarkers.firstUnredactedMarkerInNotif(raw))

        val scrubbed = CustomerTextMarkers.scrubNotif(raw)
        assertEquals("[redacted]", scrubbed.actionLabels[0])
        assertEquals("Dismiss", scrubbed.actionLabels[1])
        // Text fields untouched (marker was only in the action label).
        assertEquals("New order", scrubbed.title)
        // Clean after scrub.
        assertNull(CustomerTextMarkers.firstUnredactedMarkerInNotif(scrubbed))
    }

    @Test
    fun `action labels with no marker are left untouched`() {
        val raw = notif(title = "New order").copy(actionLabels = listOf("Accept", "Decline"))
        assertNull(CustomerTextMarkers.firstUnredactedMarkerInNotif(raw))
        val scrubbed = CustomerTextMarkers.scrubNotif(raw)
        assertEquals(listOf("Accept", "Decline"), scrubbed.actionLabels)
    }

    @Test
    fun `firstUnredactedMarkerInNotif checks text fields before action labels`() {
        val raw = notif(title = "Deliver to Jane").copy(actionLabels = listOf("Message from Bob"))
        // Text-field marker found first (order doesn't affect correctness, but pins behavior).
        assertEquals("Deliver to ", CustomerTextMarkers.firstUnredactedMarkerInNotif(raw))
    }

    // --- The node-ID half, UNKNOWN envelopes only (#910) -----------------------

    private fun dd(id: String, text: String? = null, desc: String? = null) = UiNode(
        viewIdResourceName = "com.doordash.driverapp:id/$id",
        text = text,
        contentDescription = desc,
    )

    @Test
    fun `a customer-PII node id is detected even though its text carries no marker`() {
        // The whole point: these values are BARE — no lead-in for the prefix scan.
        assertEquals("user_name", CustomerTextMarkers.unredactedIdMarker(dd("user_name", "Jane Q")))
        assertEquals("customer_name", CustomerTextMarkers.unredactedIdMarker(dd("customer_name", "Jane Q")))
        assertEquals("address_line_1", CustomerTextMarkers.unredactedIdMarker(dd("address_line_1", "123 Main St")))
        assertEquals(
            "address_line_2",
            CustomerTextMarkers.unredactedIdMarker(dd("address_line_2", "Austin, TX 78701")),
        )
        // The prefix scan is blind to every one of them — that is the gap this closes.
        assertNull(CustomerTextMarkers.unredactedMarker("Jane Q"))
        assertNull(CustomerTextMarkers.unredactedMarker("123 Main St"))
    }

    @Test
    fun `the dropoff subpremise and instruction ids are customer PII by construction (#1058)`() {
        // Fielded 2026-08-28: the ALCOHOL variant of the drop-off arrival card renders no
        // customer lead-in at all (`alcohol_dropoff_instructions_title` sits where "Delivery for"
        // does on the ordinary card), so the prefix scan is blind to it — and until this rule
        // existed the frame fell UNKNOWN with both of these nodes raw.
        assertEquals(
            "address_subpremise_line",
            CustomerTextMarkers.unredactedIdMarker(dd("address_subpremise_line", "Apt/Suite: 4021")),
        )
        assertEquals(
            "dasher_instruction_content_collapsed",
            CustomerTextMarkers.unredactedIdMarker(
                dd("dasher_instruction_content_collapsed", "Hand it to me: gate code 4417, building B"),
            ),
        )
        // Both states of the same field, not just the one that fielded.
        assertEquals(
            "dasher_instruction_content_expanded",
            CustomerTextMarkers.unredactedIdMarker(
                dd("dasher_instruction_content_expanded", "Hand it to me: gate code 4417, building B"),
            ),
        )
        // The prefix scan is blind to every one of them — that is the gap this closes.
        assertNull(CustomerTextMarkers.unredactedMarker("Apt/Suite: 4021"))
        assertNull(CustomerTextMarkers.unredactedMarker("Hand it to me: gate code 4417, building B"))
        // The instruction LABEL sibling is app vocabulary and must survive for triage.
        assertNull(CustomerTextMarkers.unredactedIdMarker(dd("instructions_title", "Hand it to recipient")))
        // And the whole-node scrub reaches them through the UNKNOWN-path helper.
        val tree = UiNode(
            children = listOf(
                dd("instructions_title", "Hand it to recipient"),
                dd("address_subpremise_line", "Apt/Suite: 4021"),
                dd("dasher_instruction_content_collapsed", "Hand it to me: gate code 4417"),
            ),
        )
        val scrubbed = CustomerTextMarkers.scrubUnknown(tree)
        assertEquals("Hand it to recipient", scrubbed.children[0].text)
        assertEquals("[redacted]", scrubbed.children[1].text)
        assertEquals("[redacted]", scrubbed.children[2].text)
    }

    @Test
    fun `the id match is a SUFFIX match, like the rules' hasIdSuffix predicate`() {
        // The `com.<vendor>:id/` prefix varies by package, so only the tail is pinned.
        assertEquals(
            "customer_name",
            CustomerTextMarkers.unredactedIdMarker(
                UiNode(viewIdResourceName = "com.example.other:id/customer_name", text = "Jane Q"),
            ),
        )
        // A bare id with no package prefix still matches.
        assertEquals(
            "user_name",
            CustomerTextMarkers.unredactedIdMarker(UiNode(viewIdResourceName = "user_name", text = "Jane Q")),
        )
    }

    @Test
    fun `the LABEL siblings are app vocabulary and are not id markers`() {
        // "Delivery for" / "Order for" chrome must survive so a scrubbed UNKNOWN frame
        // keeps its shape for triage.
        assertNull(CustomerTextMarkers.unredactedIdMarker(dd("user_name_label", "Delivery for")))
        assertNull(CustomerTextMarkers.unredactedIdMarker(dd("customer_name_label", "Order for")))
        assertNull(CustomerTextMarkers.unredactedIdMarker(dd("merchant_name", "Pei Wei")))
    }

    @Test
    fun `an id-marked node whose values are already redacted is skipped`() {
        assertNull(CustomerTextMarkers.unredactedIdMarker(dd("user_name", "[redacted:ab12]")))
        assertNull(CustomerTextMarkers.unredactedIdMarker(dd("address_line_1", "[redacted]")))
        // An empty/valueless node has nothing to leak either.
        assertNull(CustomerTextMarkers.unredactedIdMarker(dd("user_name")))
        assertNull(CustomerTextMarkers.unredactedIdMarker(UiNode(text = "Jane Q")))
    }

    @Test
    fun `a customer-PII id carrying its value in the contentDescription is caught too`() {
        assertEquals("user_name", CustomerTextMarkers.unredactedIdMarker(dd("user_name", desc = "Jane Q")))
    }

    @Test
    fun `scrubUnknown masks BOTH the id-marked node and the text-marked node in one pass`() {
        // THE FIELDED SHAPE (07-28 and again 07-29, once per job): the lead-in reads
        // exactly "Delivery for" with NO trailing name, so the marker "Delivery for "
        // never matches — and the name lives in a bare sibling. Neither node is
        // reachable by the prefix scan alone.
        val tree = UiNode(
            children = listOf(
                dd("user_name_label", "Delivery for"),
                dd("user_name", "Jane Q"),
                dd("address_line_1", "123 Main St"),
                dd("address_line_2", "Austin, TX 78701"),
                UiNode(text = "Deliver to Jane Q"), // text-marker node, the #806 half
                UiNode(text = "Directions"), // over-match guard
            ),
        )
        assertNull("the label's exact text does not match the marker", CustomerTextMarkers.unredactedMarker("Delivery for"))
        assertEquals("user_name", CustomerTextMarkers.firstUnredactedIdMarker(tree))

        val scrubbed = CustomerTextMarkers.scrubUnknown(tree)
        assertEquals("Delivery for", scrubbed.children[0].text) // chrome kept
        assertEquals("[redacted]", scrubbed.children[1].text)
        assertEquals("[redacted]", scrubbed.children[2].text)
        assertEquals("[redacted]", scrubbed.children[3].text)
        assertEquals("[redacted]", scrubbed.children[4].text)
        assertEquals("Directions", scrubbed.children[5].text)
        // Clean under BOTH scans afterwards.
        assertNull(CustomerTextMarkers.firstUnredactedIdMarker(scrubbed))
        assertNull(CustomerTextMarkers.firstUnredactedMarker(scrubbed))
    }

    @Test
    fun `the id scan over-scrubs a reused user_name node - the documented UNKNOWN-only cost`() {
        // `user_name` is not exclusively a customer node: it renders the DASHER's own
        // name in some chrome, and on a pickup card it holds the MERCHANT ("Pickup from
        // <store>", which pickup_pre_arrival parses as storeName). On an UNKNOWN frame
        // the scan cannot tell them apart, so it masks them too — losing triage text,
        // leaking nothing. This is the accepted fail-toward-privacy cost, pinned here so
        // it is a DECISION rather than a surprise. The RECOGNIZED path is untouched, so
        // no rule's merchant-keeps-raw decision is affected.
        assertEquals(
            "user_name",
            CustomerTextMarkers.unredactedIdMarker(dd("user_name", "Pickup from Sample Pizza Co")),
        )
    }

    @Test
    fun `the id scan owns the 8-97-8 drop-off-steps instruction body (#1107)`() {
        // FIELDED 2026-09-13: DoorDash's "Drop off steps" wrapper renders the customer's own
        // free-text instruction — a gate code, on the frames that fielded — in a
        // `description_text_view` node with NO lead-in and no name shape, so neither text scan
        // can reach it. `doordash.screen.dropoff_step_instructions` is the primary control; this
        // is the rules-independent half, which is what covers an UNKNOWN render of the surface.
        val tree = UiNode(
            children = listOf(
                dd("title_text_view", "Leave it at the door"),
                dd("description_text_view", "Gate code is #1234, second building on the left"),
                dd("textView_navBar_title", "Drop off steps"),
            ),
        )
        assertEquals("description_text_view", CustomerTextMarkers.firstUnredactedIdMarker(tree))

        val scrubbed = CustomerTextMarkers.scrubUnknown(tree)
        assertEquals("DoorDash's handoff vocabulary is chrome", "Leave it at the door", scrubbed.children[0].text)
        assertEquals("the instruction body goes whole", "[redacted]", scrubbed.children[1].text)
        assertEquals("the nav title is chrome", "Drop off steps", scrubbed.children[2].text)
        assertNull(CustomerTextMarkers.firstUnredactedIdMarker(scrubbed))
    }

    @Test
    fun `the id scan over-scrubs a chrome description_text_view - the accepted UNKNOWN-only cost (#1107)`() {
        // `description_text_view` is GENERIC DoorDash id vocabulary: it carries app copy on the
        // Dasher Rewards board and the GoPuff pickup-steps blurb. On an UNKNOWN frame the scan
        // cannot tell those from a customer instruction, so it masks them too — losing triage
        // text, leaking nothing. Pinned so it is a DECISION rather than a surprise; the
        // RECOGNIZED path is untouched, so no rule's keep-raw decision is affected.
        assertEquals(
            "description_text_view",
            CustomerTextMarkers.unredactedIdMarker(dd("description_text_view", "Raise to 50%")),
        )
    }

    @Test
    fun `a clean tree yields no id marker`() {
        val tree = UiNode(
            children = listOf(dd("merchant_name", "Pei Wei"), UiNode(text = "Continue")),
        )
        assertNull(CustomerTextMarkers.firstUnredactedIdMarker(tree))
    }

    @Test
    fun `no-lead-in customer shapes are the documented residual the rule redact owns`() {
        // Uber trip_en_route_dropoff title is a WHOLE address with NO lead-in marker —
        // a prefix scan cannot catch it; the rule-declared `redact` is the control.
        assertNull(CustomerTextMarkers.unredactedMarker("123 Main Street, Austin"))
        // DoorDash order_ready puts the customer name at the START (no lead-in).
        assertNull(CustomerTextMarkers.unredactedMarker("Adam's order is ready for pickup at 7-Eleven"))
    }

    @Test
    fun `ID_MARKERS is the pinned suffix list - EE1 unchanged, NN2 TT1 ZZ1 deliberately added`() {
        assertEquals(
            listOf(
                "customer_name", "user_name", "address_line_1", "address_line_2", "arriving_at_title",
                "address_subpremise_line", "dasher_instruction_content_collapsed",
                "dasher_instruction_content_expanded", "description_text_view",
                // #1160 review NN2 — deliberately ADDED: the GoPuff per-order customer name, promoted
                // from the intake list so the runtime UNKNOWN scrub covers it too.
                "order_cx_name",
                // #1160 review ZZ1 — deliberately ADDED: the chat header, ALWAYS (fail closed; a value-shape
                // gate missed non-Latin and particle names).
                "tvTitle",
                // #1160 review TT1 — deliberately ADDED: the chat last-message preview is always customer text.
                "tvLastMessage",
                // #919 — deliberately ADDED: the chat compose box holds user-authored draft text.
                "message_input",
            ),
            CustomerTextMarkers.ID_MARKERS,
        )
        assertEquals(
            mapOf(
                "customer_name" to CustomerTextMarkers.IdentityKind.NAME,
                "user_name" to CustomerTextMarkers.IdentityKind.PERSON_OR_MERCHANT,
                "address_line_1" to CustomerTextMarkers.IdentityKind.ADDRESS,
                "address_line_2" to CustomerTextMarkers.IdentityKind.ADDRESS,
                "arriving_at_title" to CustomerTextMarkers.IdentityKind.ADDRESS,
                "address_subpremise_line" to CustomerTextMarkers.IdentityKind.ADDRESS,
                "dasher_instruction_content_collapsed" to CustomerTextMarkers.IdentityKind.CONTENT,
                "dasher_instruction_content_expanded" to CustomerTextMarkers.IdentityKind.CONTENT,
                "description_text_view" to CustomerTextMarkers.IdentityKind.CONTENT,
                "order_cx_name" to CustomerTextMarkers.IdentityKind.NAME,
                "tvTitle" to CustomerTextMarkers.IdentityKind.EXACT,
                "tvLastMessage" to CustomerTextMarkers.IdentityKind.EXACT,
                "message_input" to CustomerTextMarkers.IdentityKind.CONTENT,
            ),
            CustomerTextMarkers.ID_MARKER_TABLE.filter { it.runtimeScrub == CustomerTextMarkers.RuntimeScrub.ALWAYS }
                .associate { it.suffix to it.kind },
        )
    }

    @Test
    fun `the UNKNOWN-envelope scrub covers the NN2-promoted order_cx_name (review OO3)`() {
        val node = UiNode(viewIdResourceName = "com.doordash.driverapp:id/order_cx_name", text = "Morgan")
        assertEquals("order_cx_name", CustomerTextMarkers.firstUnredactedIdMarker(node))
        assertEquals("[redacted]", CustomerTextMarkers.scrubUnknown(node).text)
    }

    @Test
    fun `tvTitle and tvLastMessage always scrub at runtime (reviews SS9, TT1, ZZ1)`() {
        // AL3: every RUNTIME row is ALWAYS; the NEVER rows are exactly the intake-only CONTENT ids.
        assertEquals(CustomerTextMarkers.RuntimeScrub.ALWAYS, CustomerTextMarkers.ID_MARKER_TABLE.single { it.suffix == "tvTitle" }.runtimeScrub)
        assertEquals(CustomerTextMarkers.RuntimeScrub.ALWAYS, CustomerTextMarkers.ID_MARKER_TABLE.single { it.suffix == "tvLastMessage" }.runtimeScrub)
        CustomerTextMarkers.ID_MARKER_TABLE.filter { it.runtimeScrub == CustomerTextMarkers.RuntimeScrub.NEVER }.forEach {
            assertEquals(it.suffix, CustomerTextMarkers.IdentityKind.CONTENT, it.kind)
            assertTrue(it.suffix, !it.idProtect)
        }
        listOf("Riley", "李明", "محمد", "de la Cruz", "RILEY S", "Pick up order").forEach {
            val node = UiNode(viewIdResourceName = "com.x:id/tvTitle", text = it)
            assertEquals(it, "[redacted]", CustomerTextMarkers.scrubUnknown(node).text)
        }
        val message = UiNode(viewIdResourceName = "com.x:id/tvLastMessage", text = "My gate code is 2468")
        assertEquals("[redacted]", CustomerTextMarkers.scrubUnknown(message).text)
    }

    @Test
    fun `the runtime mode is applied before the first match, on the real table (reviews UU4, AL3)`() {
        // An intake-only NEVER row never scrubs at runtime … (#919 promoted `message_input` to ALWAYS, so
        // the example is its still-intake-only sibling)
        assertNull(CustomerTextMarkers.idMarkerSuffix("com.x:id/chat_input_text_field"))
        assertNull(CustomerTextMarkers.idMarkerSuffix("com.x:id/primaryManeuverText"))
        assertEquals("message_input", CustomerTextMarkers.idMarkerSuffix("com.x:id/message_input"))
        // … and never switches an overlapping ALWAYS row off.
        assertEquals("address_line_1", CustomerTextMarkers.idMarkerSuffix("com.x:id/bottom_sheet_address_line_1"))
        // The intake list IS the table (one list), and the census sees every row.
        assertEquals(CustomerTextMarkers.ID_MARKER_TABLE.map { it.suffix }.toSet(), CustomerTextMarkers.ID_MARKER_SUFFIXES)
        assertEquals(CustomerTextMarkers.IdentityKind.CONTENT, CustomerTextMarkers.idMarkerFor("com.x:id/step_description")?.kind)
    }

    @Test
    fun `idProtect is pinned - only rows whose value a test tag can embed (review AB4)`() {
        assertEquals(
            setOf("customer_name", "user_name", "order_cx_name", "tvTitle"),
            CustomerTextMarkers.ID_MARKER_TABLE.filter { it.idProtect }.map { it.suffix }.toSet(),
        )
    }

    @Test
    fun `what a kind seeds is pinned on the kind table (review AB1)`() {
        assertEquals(
            mapOf(
                CustomerTextMarkers.IdentityKind.NAME to listOf(true, Int.MAX_VALUE, 2, false, true),
                CustomerTextMarkers.IdentityKind.ADDRESS to listOf(true, 0, 0, false, false),
                CustomerTextMarkers.IdentityKind.CONTENT to listOf(false, 0, 0, false, false),
                CustomerTextMarkers.IdentityKind.EXACT to listOf(true, 0, 0, false, false),
                CustomerTextMarkers.IdentityKind.PERSON_OR_MERCHANT to listOf(true, 2, 3, true, false),
            ),
            CustomerTextMarkers.IdentityKind.entries.associateWith {
                listOf(it.seedsExactValue, it.maxRunSeedTokens, it.minRunLetters, it.personNameShapeOnly, it.runsGuardClasses)
            },
        )
        // Reviews AD2, AF1: a person-or-merchant value seeds runs only when it reads as a person's name.
        val pom = CustomerTextMarkers.IdentityKind.PERSON_OR_MERCHANT
        listOf("Riley", "Mary Jo", "O'Brien", "Wing Stop", "Riley S", "Riley S.", "Mary Jo S").forEach { assertTrue(it, pom.seedsRunsFrom(it)) }
        listOf("S", "Riley S T", "Mary Jo Anne", "In-N-Out Burger", "Sonic Drive-In", "7-Eleven", "The Home Depot", "Jack in the Box", "riley")
            .forEach { assertTrue(it, !pom.seedsRunsFrom(it)) }
        assertTrue(CustomerTextMarkers.IdentityKind.NAME.seedsRunsFrom("Mary Jo Anne Smith"))
        assertTrue(!CustomerTextMarkers.IdentityKind.EXACT.seedsRunsFrom("Riley"))
    }

    @Test
    fun `an EditText-class node on an UNKNOWN envelope is scrubbed whole (#919)`() {
        val tree = UiNode(
            className = "android.widget.EditText",
            text = "they only had one of the juice boxes in stock",
            hintText = "Type a message",
        )
        assertEquals("android.widget.EditText", CustomerTextMarkers.firstUnredactedInputNode(tree))
        val scrubbed = CustomerTextMarkers.scrubUnknown(tree)
        assertEquals("[redacted]", scrubbed.text)
        assertEquals("[redacted]", scrubbed.hintText)
        assertNull(CustomerTextMarkers.firstUnredactedInputNode(scrubbed))
    }

    @Test
    fun `an isEditable node of any class is scrubbed (#919)`() {
        val node = UiNode(className = "android.view.View", isEditable = true, text = "4321")
        assertEquals("android.view.View", CustomerTextMarkers.unredactedInputNode(node))
        assertEquals("[redacted]", CustomerTextMarkers.scrubUnknown(node).text)
    }

    @Test
    fun `a non-input node with the same text is untouched by the input scan (#919)`() {
        val node = UiNode(
            className = "android.widget.TextView",
            text = "they only had one of the juice boxes in stock",
        )
        assertNull(CustomerTextMarkers.unredactedInputNode(node))
        assertEquals(node.text, CustomerTextMarkers.scrubUnknown(node).text)
    }

    @Test
    fun `an empty or already-masked input is not a hit (#919)`() {
        val node = UiNode(className = "android.widget.EditText", text = null, hintText = null)
        assertNull(CustomerTextMarkers.unredactedInputNode(node))
        assertNull(CustomerTextMarkers.unredactedInputNode(node.copy(text = "[redacted]")))
    }

    @Test
    fun `the recognized-path scan ignores inputs (#919)`() {
        val tree = UiNode(
            className = "android.widget.EditText",
            text = "they only had one of the juice boxes in stock",
        )
        assertNull(CustomerTextMarkers.firstUnredactedMarker(tree))
        assertEquals(tree, CustomerTextMarkers.scrub(tree))
    }

    @Test
    fun `message_input scrubs by id alone (#919)`() {
        val node = UiNode(
            viewIdResourceName = "com.doordash.driverapp:id/message_input",
            className = "android.widget.TextView",
            text = "On my way",
        )
        assertEquals("message_input", CustomerTextMarkers.firstUnredactedIdMarker(node))
        assertEquals("[redacted]", CustomerTextMarkers.scrubUnknown(node).text)
    }
}

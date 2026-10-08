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

enum class FieldType { STRING, DOUBLE, LONG, INT, BOOLEAN }
enum class FieldKind { PLAIN, CUSTOMER_NAME_HASH, CUSTOMER_ADDRESS_HASH }

data class FieldSpec(
    val name: String,
    val type: FieldType,
    val kind: FieldKind = FieldKind.PLAIN,
    val defaultTransform: List<String> = emptyList(),
    val read: String = "text",
)

/** Authoring vocabulary mirrored from the app owners and pinned by its source/enum guards. */
object RuleAuthoringVocabulary {
    /** #1069: the `presentationIdentity` literal values (mirrors `StateMachineContract.SUPPORTED_PRESENTATION_IDENTITIES`). */
    val PRESENTATION_IDENTITIES: List<String> = listOf("store", "economics")
    /** #1121: mirrors `StateMachineContract.SUPPORTED_QUOTE_BASES`. */
    val QUOTE_BASES: List<String> = listOf("total", "incremental")

    val FLOWS: List<String> = listOf(
        "idle", "offer:presented", "task:pickup:navigation", "task:pickup:arrived",
        "task:dropoff:navigation", "task:dropoff:arrived", "post:task", "task:unassigned",
        "task:active", "session:ended",
    )
    val SCREEN_CLASSES: List<String> = FLOWS + listOf("sensitive", "noise")
    val MODES: List<String> = listOf("offline", "online", "paused")
    val OFFER_SURFACES: List<String> = listOf("card", "decline_confirm")
    /** SessionType enum names are the parser wires (the owner has no separate wire property). */
    val SESSION_TYPES: List<String> = listOf("PerOffer", "ByTime")
    val TASK_PHASES: List<String> = listOf("PICKUP", "DROPOFF")
    val TASK_SUB_FLOWS: List<String> = listOf("NAVIGATION", "ARRIVED")
    val SHAPES: List<String> = listOf(
        "sensitive", "noise", "none", "idle", "task", "post_task", "session_ended",
        "paused", "timeline", "ratings", "offer",
    )
    val DEFAULT_SHAPE_BY_CLASS: Map<String, String> = mapOf(
        "idle" to "idle", "offer:presented" to "offer",
        "task:pickup:navigation" to "task", "task:pickup:arrived" to "task",
        "task:dropoff:navigation" to "task", "task:dropoff:arrived" to "task",
        "post:task" to "post_task", "task:unassigned" to "none", "task:active" to "none",
        "session:ended" to "session_ended", "sensitive" to "sensitive", "noise" to "noise",
    )
    /** Flow-less paused/ratings/timeline shapes are not draftable in this slice. */
    val LEGAL_SHAPES_BY_CLASS: Map<String, List<String>> = SCREEN_CLASSES.associateWith { flow ->
        if (flow in FLOWS) listOf(DEFAULT_SHAPE_BY_CLASS.getValue(flow), "none").distinct()
        else listOf(flow)
    }
    val TASK_CONSTANTS_BY_CLASS: Map<String, Pair<String, String>> = mapOf(
        "task:pickup:navigation" to ("PICKUP" to "NAVIGATION"),
        "task:pickup:arrived" to ("PICKUP" to "ARRIVED"),
        "task:dropoff:navigation" to ("DROPOFF" to "NAVIGATION"),
        "task:dropoff:arrived" to ("DROPOFF" to "ARRIVED"),
    )

    val FIELDS_BY_SHAPE: Map<String, List<FieldSpec>> = mapOf(
        "sensitive" to emptyList(),
        "noise" to emptyList(),
        "none" to emptyList(),
        "idle" to listOf(
            FieldSpec("zoneName", FieldType.STRING),
            FieldSpec("sessionType", FieldType.STRING),
            FieldSpec("sessionPay", FieldType.DOUBLE, defaultTransform = listOf("parseGlyphCurrency")),
            FieldSpec("waitTimeEstimate", FieldType.STRING),
            FieldSpec("isHeadingBackToZone", FieldType.BOOLEAN),
            FieldSpec("spotSaveDeadline", FieldType.LONG, defaultTransform = listOf("parseDeadline")),
            FieldSpec("startingSession", FieldType.BOOLEAN),
        ),
        "task" to listOf(
            FieldSpec("phase", FieldType.STRING),
            FieldSpec("subFlow", FieldType.STRING),
            FieldSpec("storeName", FieldType.STRING),
            FieldSpec("storeAddress", FieldType.STRING),
            FieldSpec("customerNameHash", FieldType.STRING, FieldKind.CUSTOMER_NAME_HASH,
                listOf("trim", "normalizeCustomerName", "sha256")),
            FieldSpec("customerAddressHash", FieldType.STRING, FieldKind.CUSTOMER_ADDRESS_HASH,
                listOf("trim", "sha256")),
            FieldSpec("deadlineText", FieldType.STRING, defaultTransform = listOf("stripDeadlinePrefix")),
            FieldSpec("deadlineMillis", FieldType.LONG, defaultTransform = listOf("parseDeadline")),
            FieldSpec("itemsRemaining", FieldType.INT, defaultTransform = listOf("parseLeadingInt")),
            FieldSpec("itemsShopped", FieldType.INT, defaultTransform = listOf("parseLeadingInt")),
            FieldSpec("redCardTotal", FieldType.DOUBLE, defaultTransform = listOf("parseCurrency")),
            FieldSpec("arrivalConfirmed", FieldType.BOOLEAN),
        ),
        "post_task" to listOf(
            FieldSpec("totalPay", FieldType.DOUBLE, defaultTransform = listOf("parseCurrency")),
            FieldSpec("appPay", FieldType.DOUBLE, defaultTransform = listOf("parseCurrency")),
            FieldSpec("customerTips", FieldType.DOUBLE, defaultTransform = listOf("parseCurrency")),
            FieldSpec("isExpanded", FieldType.BOOLEAN),
            FieldSpec("expandButtonId", FieldType.STRING, read = "viewIdResourceName"),
            FieldSpec("sessionEarnings", FieldType.DOUBLE, defaultTransform = listOf("parseCurrency")),
        ),
        "session_ended" to listOf(
            FieldSpec("totalEarnings", FieldType.DOUBLE, defaultTransform = listOf("parseCurrency")),
            FieldSpec("sessionDurationMillis", FieldType.LONG, defaultTransform = listOf("parseDuration")),
            FieldSpec("offersAccepted", FieldType.INT, defaultTransform = listOf("parseLeadingInt")),
            FieldSpec("offersTotal", FieldType.INT, defaultTransform = listOf("parseLeadingInt")),
            FieldSpec("weeklyEarnings", FieldType.DOUBLE, defaultTransform = listOf("parseCurrency")),
        ),
        "paused" to listOf(
            FieldSpec("remainingText", FieldType.STRING),
            FieldSpec("remainingMillis", FieldType.LONG, defaultTransform = listOf("parseDuration")),
        ),
        "timeline" to emptyList(),
        "ratings" to emptyList(),
        "offer" to listOf(
            FieldSpec("payAmount", FieldType.DOUBLE, defaultTransform = listOf("parseCurrency")),
            FieldSpec("distance", FieldType.DOUBLE, defaultTransform = listOf("parseDistance")),
            FieldSpec("deliveryTimeText", FieldType.STRING),
            FieldSpec("timeToCompleteMinutes", FieldType.LONG, defaultTransform = listOf("parseTotalMinutes")),
            FieldSpec("deliveryTime", FieldType.LONG, defaultTransform = listOf("parseDeadline")),
            FieldSpec("initialCountdownSeconds", FieldType.INT, defaultTransform = listOf("parseClockSeconds")),
            FieldSpec("offerKind", FieldType.STRING),
            // #1069: the platform's per-offer assignment token (hashed at the factory into an exact presentation
            // identity) and the rule-declared fallback literal (`store` | `economics`, load-validated).
            FieldSpec("assignmentId", FieldType.STRING),
            FieldSpec("presentationIdentity", FieldType.STRING),
            FieldSpec("quoteBasis", FieldType.STRING),
            FieldSpec("storeName", FieldType.STRING),
        ),
    )

    // Lists preserve the factory sets' declaration order for deterministic form rendering.
    val REQUIRED_FIELDS_BY_SHAPE: Map<String, List<String>> = mapOf(
        "offer" to listOf("payAmount", "distance"),
        "post_task" to listOf("totalPay"),
        "session_ended" to listOf("totalEarnings"),
    )
    val REQUIRED_ONE_OF_BY_SHAPE: Map<String, List<List<String>>> = mapOf(
        "offer" to listOf(listOf("deliveryTimeText", "timeToCompleteMinutes")),
    )
    val BIND_TARGETS: Map<String, String> = mapOf(
        "acceptButton" to "accept_offer", "declineButton" to "decline_offer",
        "confirmDeclineButton" to "confirm_decline", "expandButton" to "expand_earnings",
    )
    val TRANSFORMS: List<String> = listOf(
        "parseCurrency", "parseGlyphCurrency", "parseDistance", "parseItemCount", "parseItemCountUnit",
        "parseDeadline", "parseTime", "parseDuration", "parseHrMin", "parseMinutes", "parseTotalMinutes",
        "parseClockSeconds", "parseLeadingInt", "parsePercent", "sha256", "normalizeCustomerName",
        "trim", "lower", "upper", "toDouble", "toInt", "stripDeadlinePrefix",
    )
    /**
     * Terminal result types in the authoring contract. Minute counts are LONG for the factory's
     * long-valued fields; runtime parseMinutes/parseTotalMinutes return Int, widened by the factory.
     * parseItemCountUnit returns a string unit. The app guard pins registry signatures and this widening.
     */
    val TRANSFORM_RESULT_TYPE: Map<String, FieldType> = mapOf(
        "parseCurrency" to FieldType.DOUBLE, "parseGlyphCurrency" to FieldType.DOUBLE,
        "parseDistance" to FieldType.DOUBLE, "parsePercent" to FieldType.DOUBLE, "toDouble" to FieldType.DOUBLE,
        "parseDeadline" to FieldType.LONG, "parseDuration" to FieldType.LONG,
        "parseTotalMinutes" to FieldType.LONG, "parseMinutes" to FieldType.LONG,
        "parseHrMin" to FieldType.LONG, "parseTime" to FieldType.LONG,
        "parseLeadingInt" to FieldType.INT, "parseItemCount" to FieldType.INT,
        "parseClockSeconds" to FieldType.INT, "toInt" to FieldType.INT,
        "trim" to FieldType.STRING, "lower" to FieldType.STRING, "upper" to FieldType.STRING,
        "stripDeadlinePrefix" to FieldType.STRING, "normalizeCustomerName" to FieldType.STRING,
        "sha256" to FieldType.STRING, "stripPrefixes" to FieldType.STRING, "parseItemCountUnit" to FieldType.STRING,
    )
    val EMITTED_NODE_PREDICATES: List<String> = listOf(
        "hasIdSuffix", "hasText", "hasDesc", "hasClassNameEndsWith",
        "hasPrecedingSiblingText", "hasTextMatchesRegex",
    )
    val EMITTED_TREE_OPERATORS: List<String> = listOf("all", "exists")
    val EMITTED_PARSE_EXPRESSIONS: List<String> = listOf("find", "siblingOf")
    val EMITTED_PARAMETERIZED_TRANSFORMS: List<String> = listOf("stripPrefixes")

    /** Shapes disambiguate fields only among same-id/class peers; they never replace an anchor. */
    val VALUE_SHAPES_BY_TRANSFORM: Map<String, String> = mapOf(
        "parseCurrency" to """^\$[0-9]{1,4}(,[0-9]{3})?(\.[0-9]{2})?( [^\n]*)?\z""",
        "parseGlyphCurrency" to """^\$[0-9]{1,4}(,[0-9]{3})?(\.[0-9]{2})?( [^\n]*)?\z""",
        "parseDistance" to """^[0-9]{1,3}(\.[0-9]{1,2})? ?(mi|km)\z""",
        "parseTotalMinutes" to """^([0-9]{1,2} ?hr?s? ?)?[0-9]{1,3} ?min\z""",
        "parseMinutes" to """^([0-9]{1,2} ?hr?s? ?)?[0-9]{1,3} ?min\z""",
        "parseDuration" to """^([0-9]{1,2} ?hr?s? ?)?[0-9]{1,3} ?min\z""",
        "parseHrMin" to """^([0-9]{1,2} ?hr?s? ?)?[0-9]{1,3} ?min\z""",
        "parseDeadline" to """^[A-Za-z ]{0,24}[0-9]{1,2}:[0-9]{2} ?(AM|PM|am|pm)\z""",
        "stripDeadlinePrefix" to """^[A-Za-z ]{0,24}[0-9]{1,2}:[0-9]{2} ?(AM|PM|am|pm)\z""",
        "parseClockSeconds" to """^[0-9]{1,2}:[0-9]{2}\z""",
        "parseLeadingInt" to """^[0-9]{1,4}( [^\n]*)?\z""",
        "parsePercent" to """^[0-9]{1,3}(\.[0-9]+)?%\z""",
    )
    /** Authoring-side DATA mirror of domain.privacy.PiiShapes; app guards pin these bytes. */
    val ANCHOR_LEAD_INS: List<String> = listOf(
        "Pickup for ", "Pickup from ", "Deliver to ", "Deliver to door of ", "Delivery for ", "Order for ",
        "Message from ", "Heading to ", "Pick up at ",
    )
    /** Prefix text only from PiiShapes.GATED_NAME_PREFIXES; authoring conservatively refuses all tails. */
    val ANCHOR_GATED_LEAD_INS: List<String> = listOf("Return ", "Contact ") // #1127: mirrors PiiShapes.GATED_NAME_PREFIXES (key ORDER matters — the guard compares lists)
    const val FIRST_LAST_INITIAL_BODY: String =
        """[\p{L}][\p{L}'-]{0,20}(\s{1,4}[\p{L}][\p{L}'-]{0,20}){0,3}\s{1,4}[A-Z]\.?"""

    const val FIRST_LAST_INITIAL_EMBEDDED: String =
        """(?<![\p{L}])[\p{L}][\p{L}'-]{0,20}(\s{1,4}[\p{L}][\p{L}'-]{0,20}){0,3}\s{1,4}(?-i:[A-Z])\.?(?![\p{L}])"""

    val INTENT: Regex = Regex("^[a-z][a-z0-9_]{0,47}$")
    val PRIORITY_RANGE: IntRange = 1..998
    const val ANCHOR_TEXT_MAX: Int = 80
}

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
)

/** Authoring vocabulary mirrored from the app owners and pinned by its source/enum guards. */
object RuleAuthoringVocabulary {
    val FLOWS: List<String> = listOf(
        "idle", "offer:presented", "task:pickup:navigation", "task:pickup:arrived",
        "task:dropoff:navigation", "task:dropoff:arrived", "post:task", "task:unassigned",
        "task:active", "session:ended",
    )
    val SCREEN_CLASSES: List<String> = FLOWS + listOf("sensitive", "noise")
    val MODES: List<String> = listOf("offline", "online", "paused")
    val OFFER_SURFACES: List<String> = listOf("card", "decline_confirm")
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
        "post:task" to "post_task", "task:unassigned" to "none", "task:active" to "task",
        "session:ended" to "session_ended", "sensitive" to "sensitive", "noise" to "noise",
    )
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
            FieldSpec("expandButtonId", FieldType.STRING),
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
            FieldSpec("offerHash", FieldType.STRING),
            FieldSpec("deliveryTime", FieldType.LONG, defaultTransform = listOf("parseDeadline")),
            FieldSpec("initialCountdownSeconds", FieldType.INT, defaultTransform = listOf("parseClockSeconds")),
            FieldSpec("offerKind", FieldType.STRING),
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
    val EMITTED_PREDICATES: List<String> = listOf(
        "hasIdSuffix", "hasText", "hasDesc", "hasClassNameEndsWith", "isClickable",
        "hasPrecedingSiblingText", "all", "exists",
    )
    val INTENT: Regex = Regex("^[a-z][a-z0-9_]{0,47}$")
    val PRIORITY_RANGE: IntRange = 1..998
    const val ANCHOR_TEXT_MAX: Int = 80
}

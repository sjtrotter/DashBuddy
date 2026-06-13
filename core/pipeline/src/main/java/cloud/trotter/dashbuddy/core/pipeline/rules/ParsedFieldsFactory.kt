package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.ParsedTime
import cloud.trotter.dashbuddy.domain.model.offer.OfferBadge
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderBadge
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.SessionType
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.domain.state.TimelineTaskEntry
import timber.log.Timber

/**
 * Maps raw `Map<String, Any?>` from the parse phase into typed [ParsedFields] subtypes.
 *
 * Each shape has a builder that extracts named fields with safe type coercion.
 * Bad types log a warning and fall back to [ParsedFields.None].
 */
object ParsedFieldsFactory {

    /**
     * Required parse fields per shape. The rule compiler verifies at load time
     * that any parse block declaring a shape includes these field names.
     * Shapes not listed here have no required fields.
     */
    val REQUIRED_FIELDS_BY_SHAPE: Map<String, Set<String>> = mapOf(
        "offer" to setOf("payAmount", "distance"),
        "post_task" to setOf("totalPay"),
        "session_ended" to setOf("totalEarnings"),
    )

    /**
     * "At least one of" constraints per shape. Each entry is a set of field names
     * where at least one must be present. Platforms may use different field names
     * for the same concept (e.g. DoorDash: deliveryTimeText, Uber: timeToCompleteMinutes).
     */
    val REQUIRED_ONE_OF_BY_SHAPE: Map<String, List<Set<String>>> = mapOf(
        "offer" to listOf(setOf("deliveryTimeText", "timeToCompleteMinutes")),
    )

    /**
     * Validate that a parse block declares all required fields for its shape.
     * @throws RuleCompileException if required fields are missing.
     */
    fun validateShapeFields(shape: String, declaredFields: Set<String>, ruleId: String) {
        val required = REQUIRED_FIELDS_BY_SHAPE[shape]
        if (required != null) {
            val missing = required - declaredFields
            if (missing.isNotEmpty()) {
                throw RuleCompileException(
                    "Rule '$ruleId' declares shape '$shape' but parse block is missing required " +
                        "fields: ${missing.joinToString(", ") { "'$it'" }}",
                )
            }
        }
        val oneOfGroups = REQUIRED_ONE_OF_BY_SHAPE[shape]
        if (oneOfGroups != null) {
            for (group in oneOfGroups) {
                if (group.none { it in declaredFields }) {
                    throw RuleCompileException(
                        "Rule '$ruleId' declares shape '$shape' but parse block must include " +
                            "at least one of: ${group.joinToString(", ") { "'$it'" }}",
                    )
                }
            }
        }
    }

    fun create(shape: String?, fields: Map<String, Any?>): ParsedFields {
        if (shape == null) return ParsedFields.None

        return try {
            when (shape) {
                // Shapes that require no parsed fields
                "sensitive" -> ParsedFields.SensitiveFields()
                "noise" -> ParsedFields.NoiseFields()
                "none" -> ParsedFields.None

                // Shapes that require parsed fields
                "idle" -> if (fields.isEmpty()) ParsedFields.None else buildIdle(fields)
                "task" -> if (fields.isEmpty()) ParsedFields.None else buildTask(fields)
                "post_task" -> if (fields.isEmpty()) ParsedFields.None else buildPostTask(fields)
                "session_ended" -> if (fields.isEmpty()) ParsedFields.None else buildSessionEnded(fields)
                "paused" -> if (fields.isEmpty()) ParsedFields.None else buildPaused(fields)
                "timeline" -> if (fields.isEmpty()) ParsedFields.None else buildTimeline(fields)
                "ratings" -> if (fields.isEmpty()) ParsedFields.None else buildRatings(fields)
                "offer" -> if (fields.isEmpty()) ParsedFields.None else buildOffer(fields)
                "click" -> if (fields.isEmpty()) ParsedFields.None else buildClick(fields)
                "notification" -> if (fields.isEmpty()) ParsedFields.None else buildNotification(fields)

                else -> {
                    Timber.w("ParsedFieldsFactory: unknown shape '$shape'")
                    ParsedFields.None
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "ParsedFieldsFactory: failed to build shape '$shape'")
            ParsedFields.None
        }
    }

    private fun buildIdle(f: Map<String, Any?>) = ParsedFields.IdleFields(
        activity = f.str("activity"),
        zoneName = f.str("zoneName"),
        sessionType = f.str("sessionType")?.let {
            try { SessionType.valueOf(it) } catch (_: Exception) { null }
        },
        sessionPay = f.double("sessionPay"),
        waitTimeEstimate = f.str("waitTimeEstimate"),
        isHeadingBackToZone = f.bool("isHeadingBackToZone") ?: false,
        spotSaveDeadline = f.long("spotSaveDeadline"),
        startingSession = f.bool("startingSession") ?: false,
    )

    private fun buildTask(f: Map<String, Any?>) = ParsedFields.TaskFields(
        activity = f.str("activity"),
        phase = f.str("phase")?.let {
            try { TaskPhase.valueOf(it) } catch (_: Exception) { null }
        } ?: TaskPhase.PICKUP,
        subFlow = f.str("subFlow")?.let {
            try { TaskSubFlow.valueOf(it) } catch (_: Exception) { null }
        } ?: TaskSubFlow.NAVIGATION,
        storeName = f.str("storeName"),
        storeAddress = f.str("storeAddress"),
        customerNameHash = f.str("customerNameHash"),
        customerAddressHash = f.str("customerAddressHash"),
        deadline = f.parsedTime("deadline", "deadlineText", "deadlineMillis"),
        itemsRemaining = f.int("itemsRemaining"),
        itemsShopped = f.int("itemsShopped"),
        redCardTotal = f.double("redCardTotal"),
        arrivalConfirmed = f.bool("arrivalConfirmed") ?: false,
    )

    private fun buildPostTask(f: Map<String, Any?>): ParsedFields.PostTaskFields {
        @Suppress("UNCHECKED_CAST")
        val rawLineItems = f["payLineItems"] as? List<Map<String, Any?>> ?: emptyList()

        val parsedPay = if (rawLineItems.isNotEmpty()) {
            val allItems = rawLineItems.map { item ->
                ParsedPayItem(
                    type = item.str("type") ?: "",
                    amount = item.double("amount") ?: 0.0,
                )
            }
            // Split: DoorDash pay components have labels like "Base pay", "Peak pay";
            // customer tips have store names/IDs that don't contain "pay".
            val (appPay, tips) = allItems.partition {
                it.type.contains("pay", ignoreCase = true)
            }
            ParsedPay(appPayComponents = appPay, customerTips = tips)
        } else null

        return ParsedFields.PostTaskFields(
            activity = f.str("activity"),
            totalPay = f.double("totalPay") ?: 0.0,
            appPay = f.double("appPay"),
            customerTips = f.double("customerTips"),
            parsedPay = parsedPay,
            isExpanded = f.bool("isExpanded") ?: false,
            expandButtonId = f.str("expandButtonId"),
            sessionEarnings = f.double("sessionEarnings"),
        )
    }

    private fun buildSessionEnded(f: Map<String, Any?>) = ParsedFields.SessionEndedFields(
        activity = f.str("activity"),
        totalEarnings = f.double("totalEarnings") ?: 0.0,
        sessionDurationMillis = f.long("sessionDurationMillis"),
        offersAccepted = f.int("offersAccepted"),
        offersTotal = f.int("offersTotal"),
        weeklyEarnings = f.double("weeklyEarnings"),
    )

    private fun buildPaused(f: Map<String, Any?>) = ParsedFields.PausedFields(
        activity = f.str("activity"),
        remainingText = f.str("remainingText"),
        remainingMillis = f.long("remainingMillis"),
    )

    private fun buildTimeline(f: Map<String, Any?>): ParsedFields.TimelineFields {
        @Suppress("UNCHECKED_CAST")
        val rawTasks = f["tasks"] as? List<Map<String, Any?>> ?: emptyList()
        val tasks = rawTasks.map { t ->
            TimelineTaskEntry(
                taskType = t.str("taskType") ?: "",
                nameHash = t.str("nameHash"),
                deadline = t.parsedTime("deadline", "deadlineText", "deadlineMillis"),
                storeHint = t.str("storeHint"),
                isCurrent = t.bool("isCurrent") ?: false,
            )
        }
        return ParsedFields.TimelineFields(
            activity = f.str("activity"),
            sessionEarnings = f.double("sessionEarnings"),
            offerEarnings = f.double("offerEarnings"),
            endsAtText = f.str("endsAtText"),
            endsAtMillis = f.long("endsAtMillis"),
            tasks = tasks,
        )
    }

    private fun buildRatings(f: Map<String, Any?>) = ParsedFields.RatingsFields(
        activity = f.str("activity"),
        acceptanceRate = f.double("acceptanceRate"),
        completionRate = f.double("completionRate"),
        onTimeRate = f.double("onTimeRate"),
        customerRating = f.double("customerRating"),
        deliveriesLast30Days = f.int("deliveriesLast30Days"),
        lifetimeDeliveries = f.int("lifetimeDeliveries"),
        originalItemsFoundRate = f.double("originalItemsFoundRate"),
        totalItemsFoundRate = f.double("totalItemsFoundRate"),
        substitutionIssuesRate = f.double("substitutionIssuesRate"),
        itemsWithQualityIssuesRate = f.double("itemsWithQualityIssuesRate"),
        itemsWrongOrMissingRate = f.double("itemsWrongOrMissingRate"),
        lifetimeShoppingOrders = f.int("lifetimeShoppingOrders"),
    )

    private fun buildClick(f: Map<String, Any?>) = ParsedFields.ClickFields(
        activity = f.str("activity"),
        intent = f.str("intent") ?: "unknown",
        nodeId = f.str("nodeId"),
        nodeText = f.str("nodeText"),
    )

    private fun buildNotification(f: Map<String, Any?>) = ParsedFields.NotificationFields(
        activity = f.str("activity"),
        intent = f.str("intent") ?: "unknown",
        amount = f.double("amount"),
        storeName = f.str("storeName"),
        deliveredAt = f.str("deliveredAt"),
        rawText = f.str("rawText"),
    )

    private fun buildOffer(f: Map<String, Any?>): ParsedFields {
        @Suppress("UNCHECKED_CAST")
        val rawOrders = f["orders"] as? List<Map<String, Any?>> ?: emptyList()
        val orders = rawOrders.mapIndexed { idx, o ->
            val badges = buildSet {
                if (o.bool("isRedCard") == true) add(OrderBadge.RED_CARD)
                if (o.bool("isLargeOrder") == true) add(OrderBadge.LARGE_ORDER)
                if (o.bool("hasAlcohol") == true) add(OrderBadge.ALCOHOL)
            }
            ParsedOrder(
                orderIndex = idx,
                orderType = o.str("orderType")?.let {
                    try { OrderType.valueOf(it) } catch (_: Exception) { null }
                } ?: OrderType.PICKUP,
                storeName = o.str("storeName") ?: "",
                itemCount = o.int("itemCount") ?: 1,
                isItemCountEstimated = o.int("itemCount") == null,
                badges = badges,
            )
        }

        val payAmount = f.double("payAmount")
        val distance = f.double("distance")
        val deliveryTimeText = f.str("deliveryTimeText")
        val timeToCompleteMinutes = f.long("timeToCompleteMinutes")

        // Compute offer hash from extracted fields (same as Kotlin parser)
        val storeNames = orders.joinToString(",") { it.storeName }
        val hashInput = "$payAmount|$distance|${deliveryTimeText ?: timeToCompleteMinutes}|$storeNames"
        // Fail-closed hash (#362): on digest failure fall back to a
        // non-reversible identity — NEVER the plaintext input.
        val offerHash = f.str("offerHash")
            ?: sha256OrNull(hashInput)
            ?: "offer-${hashInput.hashCode()}"

        return ParsedFields.OfferFields(
            activity = f.str("activity"),
            parsedOffer = ParsedOffer(
                offerHash = offerHash,
                itemCount = orders.sumOf { it.itemCount }.coerceAtLeast(1),
                payAmount = payAmount,
                distanceMiles = distance,
                dueByTimeText = deliveryTimeText,
                dueByTimeMillis = f.long("deliveryTime"),
                timeToCompleteMinutes = timeToCompleteMinutes,
                orders = orders,
            ),
        )
    }

    // ========================================================================
    //  Safe type coercion helpers
    // ========================================================================

    private fun Map<String, Any?>.str(key: String): String? = get(key)?.toString()

    private fun Map<String, Any?>.double(key: String): Double? = when (val v = get(key)) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private fun Map<String, Any?>.int(key: String): Int? = when (val v = get(key)) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    private fun Map<String, Any?>.long(key: String): Long? = when (val v = get(key)) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    private fun Map<String, Any?>.bool(key: String): Boolean? = when (val v = get(key)) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull()
        else -> null
    }

    /**
     * Build a [ParsedTime] from either a nested map or separate text/millis fields.
     */
    private fun Map<String, Any?>.parsedTime(
        compositeKey: String,
        textKey: String,
        millisKey: String,
    ): ParsedTime? {
        // Try composite (from a nested parse expression that returns a map)
        @Suppress("UNCHECKED_CAST")
        val composite = get(compositeKey) as? Map<String, Any?>
        if (composite != null) {
            val text = composite.str("text") ?: return null
            val millis = composite.long("millis")
            return ParsedTime(text, millis)
        }
        // Try separate fields
        val text = str(textKey) ?: return null
        val millis = long(millisKey)
        return ParsedTime(text, millis)
    }
}

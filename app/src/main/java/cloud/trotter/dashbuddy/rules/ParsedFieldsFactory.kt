package cloud.trotter.dashbuddy.rules

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
        itemCount = f.int("itemCount"),
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
        remainingText = f.str("remainingText") ?: "35:00",
        remainingMillis = f.long("remainingMillis") ?: (35 * 60 * 1000L),
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
        val offerHash = f.str("offerHash") ?: generateSha256(hashInput)

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

    private fun generateSha256(input: String): String {
        return try {
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            bytes.fold("") { str, it -> str + "%02x".format(it) }
        } catch (_: Exception) {
            input
        }
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

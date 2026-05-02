package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ModeConfidence
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.util.UtilityFunctions
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replaces 9 reducers + 8 factories. Diffs each region before/after
 * stepping and emits the appropriate [AppEffect] list.
 *
 * Flow-region transitions → UI overlay effects (offer evaluation, screenshot).
 * Platform-region transitions → durable effects (odometer, session, event log).
 * Cross-platform transitions → aggregate bookkeeping (currently none).
 */
@Singleton
class EffectMap @Inject constructor() {

    private val gson = Gson()

    fun diff(prev: AppState, next: AppState, obs: Observation): List<AppEffect> = buildList {
        addAll(diffFlowRegion(prev.regions.flow, next.regions.flow, obs))
        val allPlatforms = (prev.regions.platforms.keys + next.regions.platforms.keys).distinct()
        for (p in allPlatforms) {
            addAll(
                diffPlatformRegion(
                    p,
                    prev.regions.platforms[p],
                    next.regions.platforms[p],
                    prev.regions.flow,
                    next.regions.flow,
                    obs,
                )
            )
        }
    }

    // =========================================================================
    // FLOW REGION DIFFS
    // =========================================================================

    private fun diffFlowRegion(
        prev: FlowRegion,
        next: FlowRegion,
        obs: Observation,
    ): List<AppEffect> = buildList {
        val flowObs = obs as? Observation.FlowObservation
        val prevOffer = prev.pendingOffer
        val nextOffer = next.pendingOffer

        // Offer presented
        if (prevOffer == null && nextOffer != null) {
            val offer = nextOffer.offerFields
            val merchantName = offer.parsedOffer.orders.joinToString(", ") { it.storeName }

            // Log
            add(logEffect(null, AppEventType.OFFER_RECEIVED, offer.parsedOffer))

            // Screenshot
            val safeMerchant = merchantName.replace(Regex("[^a-zA-Z0-9 ,.()'-]"), "")
            add(AppEffect.CaptureScreenshot("Offer - $safeMerchant"))

            // Evaluate
            add(AppEffect.EvaluateOffer(offer.parsedOffer))
        }

        // Offer replaced (different hash)
        if (prevOffer != null && nextOffer != null &&
            prevOffer.offerHash != nextOffer.offerHash
        ) {
            // Log resolution of old offer
            val outcome = resolveOfferOutcome(obs)
            add(logEffect(null, outcome, "Replaced by new offer"))

            // Log + evaluate new offer
            val offer = nextOffer.offerFields
            val merchantName = offer.parsedOffer.orders.joinToString(", ") { it.storeName }
            add(logEffect(null, AppEventType.OFFER_RECEIVED, offer.parsedOffer))
            val safeMerchant = merchantName.replace(Regex("[^a-zA-Z0-9 ,.()'-]"), "")
            add(AppEffect.CaptureScreenshot("Offer - $safeMerchant"))
            add(AppEffect.EvaluateOffer(offer.parsedOffer))
        }

        // Offer resolved (accepted/declined/timeout)
        if (prevOffer != null && nextOffer == null) {
            val outcome = resolveOfferOutcome(obs)
            val description = when (outcome) {
                AppEventType.OFFER_ACCEPTED -> "Transitioned to Pickup"
                AppEventType.OFFER_DECLINED -> "Returned to search"
                else -> "Offer timed out"
            }
            add(logEffect(null, outcome, description))

            if (outcome == AppEventType.OFFER_TIMEOUT) {
                add(AppEffect.UpdateBubble("Offer Timed Out!", persona = ChatPersona.Dispatcher))
            }
        }

        // Click feedback for offer accept/decline
        if (flowObs is Observation.Click && prev.flow == Flow.OfferPresented) {
            val fields = flowObs.parsed as? ParsedFields.ClickFields
            when (fields?.intent) {
                "accept_offer" -> add(
                    AppEffect.UpdateBubble("Offer Accepted", persona = ChatPersona.Dispatcher)
                )
                "decline_offer" -> add(
                    AppEffect.UpdateBubble("Offer Declined", persona = ChatPersona.Dispatcher)
                )
            }
        }
    }

    // =========================================================================
    // PLATFORM REGION DIFFS
    // =========================================================================

    private fun diffPlatformRegion(
        platform: Platform,
        prev: PlatformRegion?,
        next: PlatformRegion?,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation,
    ): List<AppEffect> {
        if (next == null) return emptyList()
        val p = prev ?: PlatformRegion(platform)

        return buildList {
            addAll(diffMode(p, next, obs))
            addAll(diffTask(p, next, prevFlow, nextFlow, obs))
            addAll(diffPostTask(p, next, prevFlow, nextFlow, obs))
            addAll(diffNotification(obs))
        }
    }

    private fun diffMode(
        prev: PlatformRegion,
        next: PlatformRegion,
        obs: Observation,
    ): List<AppEffect> {
        if (prev.mode == next.mode) return emptyList()
        val prevSession = prev.session
        val nextSession = next.session
        val sessionId = nextSession?.sessionId ?: prevSession?.sessionId

        return buildList {
            when {
                // Session start: offline/paused → online
                prev.mode != Mode.Online && next.mode == Mode.Online -> {
                    if (prevSession == null && nextSession != null) {
                        val payload = mapOf(
                            "source" to if (next.confidence != ModeConfidence.EMPTY) "recovery" else "interaction",
                            "start_screen" to "WaitingForOffer",
                        )
                        add(logEffect(nextSession.sessionId, AppEventType.DASH_START, payload))
                        add(AppEffect.StartOdometer)
                        add(AppEffect.StartDash(nextSession.sessionId))
                    }

                    // Cancel pause safety timer if resuming from paused
                    if (prev.mode == Mode.Paused) {
                        add(AppEffect.CancelTimeout(oldTimeoutType(TimeoutType.SESSION_PAUSED_SAFETY)))
                    }
                }

                // Session end: online/paused → offline
                next.mode == Mode.Offline -> {
                    if (prevSession != null) {
                        // Check if this is a session summary screen
                        val flowObs = obs as? Observation.FlowObservation
                        val parsed = flowObs?.parsed
                        if (parsed is ParsedFields.SessionEndedFields) {
                            val earnings = UtilityFunctions.formatCurrency(parsed.totalEarnings)
                            add(logEffect(sessionId, AppEventType.DASH_STOP, parsed))
                            add(AppEffect.StopOdometer)
                            add(
                                AppEffect.UpdateBubble(
                                    "Dash Ended. Total: $earnings",
                                    ChatPersona.Dispatcher,
                                )
                            )
                            add(AppEffect.CaptureScreenshot("DashSummary - ${parsed.totalEarnings}"))
                        } else {
                            add(logEffect(sessionId, AppEventType.DASH_STOP, "Return to Map"))
                            add(AppEffect.StopOdometer)
                        }
                        add(AppEffect.EndDash)
                    }

                    // Cancel pause safety timer
                    if (prev.mode == Mode.Paused) {
                        add(AppEffect.CancelTimeout(oldTimeoutType(TimeoutType.SESSION_PAUSED_SAFETY)))
                    }
                }

                // Pause: online → paused
                prev.mode == Mode.Online && next.mode == Mode.Paused -> {
                    val flowObs = obs as? Observation.FlowObservation
                    val pausedFields = flowObs?.parsed as? ParsedFields.PausedFields
                    val durationMs = (pausedFields?.remainingMillis ?: 0L) + 1000L
                    val remainingText = pausedFields?.remainingText ?: "?"

                    add(
                        logEffect(
                            sessionId, AppEventType.DASH_PAUSED,
                            "Paused. Time left: $remainingText",
                        )
                    )
                    add(
                        AppEffect.ScheduleTimeout(
                            durationMs,
                            oldTimeoutType(TimeoutType.SESSION_PAUSED_SAFETY),
                        )
                    )
                    add(AppEffect.UpdateBubble("Dash Paused!"))
                }
            }
        }
    }

    private fun diffTask(
        prev: PlatformRegion,
        next: PlatformRegion,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation,
    ): List<AppEffect> {
        val flowObs = obs as? Observation.FlowObservation ?: return emptyList()
        val nextFlowVal = nextFlow.flow
        val sessionId = next.session?.sessionId

        return buildList {
            val prevTask = prev.activeTask
            val nextTask = next.activeTask

            // Task started — pickup navigation
            if (prevTask == null && nextTask != null &&
                nextTask.phase == TaskPhase.PICKUP
            ) {
                val storeName = nextTask.storeName ?: "Unknown"
                val payload = mapOf(
                    "message" to "Pickup Started",
                    "storeName" to storeName,
                    "status" to "NAVIGATING",
                )
                add(logEffect(sessionId, AppEventType.PICKUP_NAV_STARTED, payload))
                add(AppEffect.ResumeOdometer)

                val persona = determinePickupPersona(
                    nextTask.activity,
                    nextTask.arrivedAt != null,
                    storeName,
                    nextTask.customerNameHash,
                )
                add(AppEffect.UpdateBubble("Pickup: $storeName", persona))
            }

            // Task phase changed — pickup → dropoff (pickup confirmed)
            if (prevTask?.phase == TaskPhase.PICKUP &&
                nextTask?.phase == TaskPhase.DROPOFF
            ) {
                val customerHash = nextTask.customerNameHash
                val payload = mapOf(
                    "status" to "DROPOFF_STARTED",
                    "customerHash" to customerHash,
                    "addressHash" to nextTask.customerAddressHash,
                )
                add(logEffect(sessionId, AppEventType.PICKUP_CONFIRMED, payload))
                add(AppEffect.ResumeOdometer)

                val customer = customerHash?.take(6) ?: "Customer"
                add(AppEffect.UpdateBubble("Heading to $customer", ChatPersona.Customer(customer)))
            }

            // Arrival detection — task subflow changed to ARRIVED
            if (nextTask != null && nextTask.arrivedAt != null &&
                (prevTask?.arrivedAt == null)
            ) {
                add(AppEffect.PauseOdometer)

                if (nextTask.phase == TaskPhase.PICKUP) {
                    add(
                        logEffect(
                            sessionId, AppEventType.PICKUP_ARRIVED,
                            mapOf("storeName" to (nextTask.storeName ?: "Unknown")),
                        )
                    )
                }
            }

            // Internal pickup updates — store name, status, etc.
            if (prevTask != null && nextTask != null &&
                prevTask.phase == TaskPhase.PICKUP &&
                nextTask.phase == TaskPhase.PICKUP
            ) {
                val storeChanged = nextTask.storeName != prevTask.storeName &&
                    nextTask.storeName != null && nextTask.storeName != "Unknown"
                val activityChanged = nextTask.activity != prevTask.activity

                if (storeChanged || activityChanged) {
                    val storeName = nextTask.storeName ?: "Unknown"
                    val persona = determinePickupPersona(
                        nextTask.activity,
                        nextTask.arrivedAt != null,
                        storeName,
                        nextTask.customerNameHash,
                    )
                    add(AppEffect.UpdateBubble("Pickup: $storeName", persona))
                }

                // Store name resolution logging
                if (storeChanged) {
                    add(
                        logEffect(
                            sessionId, AppEventType.PICKUP_NAV_STARTED,
                            mapOf(
                                "message" to "Store Name Updated",
                                "previous" to (prevTask.storeName ?: "Unknown"),
                                "updated" to (nextTask.storeName ?: "Unknown"),
                            ),
                        )
                    )
                }
            }

            // Post-task: delivery completed
            if (nextFlowVal == Flow.PostTask && prevFlow.flow != Flow.PostTask) {
                add(AppEffect.ResumeOdometer)
            }
        }
    }

    /**
     * Handle PostTask automation effects (expand button clicking).
     * This preserves the existing PostDeliveryReducer's closed-loop automation.
     */
    private fun diffPostTask(
        prev: PlatformRegion,
        next: PlatformRegion,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation,
    ): List<AppEffect> {
        if (nextFlow.flow != Flow.PostTask) return emptyList()
        val flowObs = obs as? Observation.FlowObservation ?: return emptyList()
        val parsed = flowObs.parsed as? ParsedFields.PostTaskFields ?: return emptyList()
        val sessionId = next.session?.sessionId

        return buildList {
            val payData = parsed.parsedPay

            // If we got expanded pay data, log it
            if (payData != null) {
                val receiptText = buildString {
                    append("Saved: ${UtilityFunctions.formatCurrency(payData.total)}")
                    payData.customerTips.forEach { item ->
                        append("\nTip: ${item.type} • ${UtilityFunctions.formatCurrency(item.amount)}")
                    }
                }
                add(AppEffect.UpdateBubble(receiptText, ChatPersona.Earnings))
            }

            // Leaving post-task → log delivery completed
            if (prevFlow.flow == Flow.PostTask && nextFlow.flow != Flow.PostTask) {
                val payload: Any = payData
                    ?: mapOf(
                        "total" to parsed.totalPay,
                        "warning" to "Collapsed Data Only - Breakdown Missing",
                    )
                add(logEffect(sessionId, AppEventType.DELIVERY_COMPLETED, payload))
            }
        }
    }

    /**
     * Handle notification-driven effects. These are global interceptors
     * that apply regardless of state.
     */
    private fun diffNotification(obs: Observation): List<AppEffect> {
        if (obs !is Observation.Notification) return emptyList()
        val fields = obs.parsed as? ParsedFields.ClickFields ?: return emptyList()

        return buildList {
            when (fields.intent) {
                "additional_tip" -> {
                    val parts = fields.nodeText?.split("|")
                    if (parts != null && parts.size == 3) {
                        val amount = parts[0].toDoubleOrNull()
                        if (amount != null) {
                            add(
                                AppEffect.ProcessTipNotification(
                                    amount = amount,
                                    storeName = parts[1],
                                    deliveredAt = parts[2],
                                )
                            )
                        }
                    }
                    add(logEffect(null, AppEventType.NOTIFICATION_RECEIVED, "TIP_ADDED: ${fields.nodeText}"))
                }
                "new_order" -> {
                    add(logEffect(null, AppEventType.NOTIFICATION_RECEIVED, "NEW_ORDER"))
                }
                "scheduled_dash_expired" -> {
                    add(logEffect(null, AppEventType.NOTIFICATION_RECEIVED, "SCHEDULED_DASH_EXPIRED"))
                }
                else -> {
                    add(logEffect(null, AppEventType.NOTIFICATION_RECEIVED, "UNKNOWN: ${fields.nodeText}"))
                }
            }
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun resolveOfferOutcome(obs: Observation): AppEventType {
        val clickFields = when (obs) {
            is Observation.Click -> obs.parsed as? ParsedFields.ClickFields
            is Observation.Screen -> null
            else -> null
        }
        return when (clickFields?.intent) {
            "accept_offer" -> AppEventType.OFFER_ACCEPTED
            "decline_offer" -> AppEventType.OFFER_DECLINED
            else -> AppEventType.OFFER_TIMEOUT
        }
    }

    private fun determinePickupPersona(
        activity: String?,
        arrived: Boolean,
        storeName: String,
        customerHash: String?,
    ): ChatPersona {
        return when {
            activity == "shopping" -> ChatPersona.Shopper
            activity == "confirmed" -> ChatPersona.Customer(customerHash?.take(6) ?: "Customer")
            arrived -> ChatPersona.Merchant(storeName)
            else -> ChatPersona.Navigator
        }
    }

    private fun logEffect(dashId: String?, type: AppEventType, payload: Any): AppEffect {
        val payloadStr = payload as? String ?: gson.toJson(payload)
        val metadataJson = DashBuddyApplication.createMetadata()
        return AppEffect.LogEvent(
            AppEventEntity(
                aggregateId = dashId,
                eventType = type,
                eventPayload = payloadStr,
                occurredAt = System.currentTimeMillis(),
                metadata = metadataJson,
            )
        )
    }

    /**
     * Bridge: convert new TimeoutType to old TimeoutType until SideEffectEngine
     * is updated to use the new enum directly.
     */
    private fun oldTimeoutType(type: TimeoutType): cloud.trotter.dashbuddy.domain.model.state.TimeoutType {
        return when (type) {
            TimeoutType.SESSION_PAUSED_SAFETY -> cloud.trotter.dashbuddy.domain.model.state.TimeoutType.DASH_PAUSED_SAFETY
            TimeoutType.SETTLE_UI -> cloud.trotter.dashbuddy.domain.model.state.TimeoutType.SETTLE_UI
            TimeoutType.RETRY_CLICK -> cloud.trotter.dashbuddy.domain.model.state.TimeoutType.RETRY_CLICK_TIMEOUT
            TimeoutType.DECLINE_POPUP_WAIT -> cloud.trotter.dashbuddy.domain.model.state.TimeoutType.DECLINE_POPUP_WAIT
            TimeoutType.SCREENSHOT_WAIT -> cloud.trotter.dashbuddy.domain.model.state.TimeoutType.SETTLE_UI // fallback
        }
    }
}

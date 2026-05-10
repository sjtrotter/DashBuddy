package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ParsedFieldsGate
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.pipeline.TransitionTrigger
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ModeConfidence
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.util.UtilityFunctions
import com.google.gson.Gson
import timber.log.Timber
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

    companion object {
        /**
         * Safety buffer added to the platform's reported pause countdown
         * before scheduling the offline timeout. Accounts for clock skew
         * between the parsed timestamp and the actual timer start.
         */
        const val PAUSE_TIMEOUT_BUFFER_MS = 1000L
    }

    private val gson = Gson()

    fun diff(prev: AppState, next: AppState, obs: Observation): List<AppEffect> = buildList {
        addAll(diffRuleEffects(obs))
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
        // Screenshot + log now handled by rule-declared effects on offer screen rules
        // (deduped via dedupeKey + throttleMs). Evaluate + speak remain here
        // because they need rich ParsedOffer objects, not string args.
        if (prevOffer == null && nextOffer != null) {
            val offer = nextOffer.offerFields

            // Evaluate
            add(AppEffect.EvaluateOffer(offer.parsedOffer))

            // Speak offer aloud
            val platform = next.activePlatform?.name ?: "Unknown"
            add(AppEffect.SpeakOffer(offer.parsedOffer, platform))
        }

        // Offer replaced (different hash)
        // Screenshot + log for new offer handled by rule-declared effects (deduped
        // per offerHash). Resolution log for old offer stays here.
        if (prevOffer != null && nextOffer != null &&
            prevOffer.offerHash != nextOffer.offerHash
        ) {
            // Log resolution of old offer
            val outcome = resolveOfferOutcome(obs, prevOffer)
            add(logEffect(null, outcome, "Replaced by new offer"))

            // Evaluate + speak new offer
            val offer = nextOffer.offerFields
            add(AppEffect.EvaluateOffer(offer.parsedOffer))
            val platform = next.activePlatform?.name ?: "Unknown"
            add(AppEffect.SpeakOffer(offer.parsedOffer, platform))
        }

        // Offer resolved (accepted/declined/timeout)
        if (prevOffer != null && nextOffer == null) {
            val outcome = resolveOfferOutcome(obs, prevOffer)
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
            addAll(diffPostTask(p, next, nextFlow, obs))
            addAll(diffNotification(obs, next.session?.sessionId))

            // Delivery completed: leaving PostTask for a non-PostTask flow
            if (prevFlow.flow == Flow.PostTask && nextFlow.flow != Flow.PostTask) {
                val taskCompletedOverride = triggerOverrideEffects(obs, TransitionTrigger.TASK_COMPLETED)
                if (taskCompletedOverride != null) {
                    addAll(taskCompletedOverride)
                } else {
                    val sessionId = next.session?.sessionId ?: p.session?.sessionId
                    val completedTask = next.recentTasks.lastOrNull()
                    val payload = mapOf(
                        "storeName" to (completedTask?.storeName ?: "Unknown"),
                        "jobId" to (p.activeJob?.jobId),
                    )
                    add(logEffect(sessionId, AppEventType.DELIVERY_COMPLETED, payload))
                }
            }
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
                    val modeOverride = triggerOverrideEffects(obs, TransitionTrigger.MODE_TO_ONLINE)
                    if (modeOverride != null) {
                        addAll(modeOverride)
                    } else if (prevSession == null && nextSession != null) {
                        val payload = mapOf(
                            "source" to if (next.confidence != ModeConfidence.EMPTY) "recovery" else "interaction",
                            "start_screen" to "WaitingForOffer",
                        )
                        add(logEffect(nextSession.sessionId, AppEventType.DASH_START, payload))
                        add(AppEffect.StartOdometer)
                        add(AppEffect.StartSession(nextSession.sessionId, next.platform.name))
                    }

                    // Cancel pause safety timer if resuming from paused
                    if (prev.mode == Mode.Paused) {
                        val resumeOverride = triggerOverrideEffects(obs, TransitionTrigger.RESUME_FROM_PAUSE)
                        if (resumeOverride != null) {
                            addAll(resumeOverride)
                        } else {
                            add(AppEffect.CancelTimeout(TimeoutType.SESSION_PAUSED_SAFETY))
                        }
                    }
                }

                // Session end: online/paused → offline
                next.mode == Mode.Offline -> {
                    val modeOverride = triggerOverrideEffects(obs, TransitionTrigger.MODE_TO_OFFLINE)
                    if (modeOverride != null) {
                        addAll(modeOverride)
                    } else if (prevSession != null) {
                        // Check if this is a session summary screen
                        val flowObs = obs as? Observation.FlowObservation
                        val parsed = flowObs?.parsed
                        val platform = prev.platform.name
                        if (parsed is ParsedFields.SessionEndedFields) {
                            val earnings = UtilityFunctions.formatCurrency(parsed.totalEarnings)
                            add(logEffect(sessionId, AppEventType.DASH_STOP, parsed))
                            add(AppEffect.StopOdometer)
                            add(
                                AppEffect.UpdateBubble(
                                    "Session Ended. Total: $earnings",
                                    ChatPersona.Dispatcher,
                                )
                            )
                            add(AppEffect.CaptureScreenshot("DashSummary - ${parsed.totalEarnings}"))
                        } else {
                            add(logEffect(sessionId, AppEventType.DASH_STOP, "Return to Map"))
                            add(AppEffect.StopOdometer)
                        }
                        add(AppEffect.EndSession(platform))
                    }

                    // Cancel pause safety timer
                    if (prev.mode == Mode.Paused) {
                        val resumeOverride = triggerOverrideEffects(obs, TransitionTrigger.RESUME_FROM_PAUSE)
                        if (resumeOverride != null) {
                            addAll(resumeOverride)
                        } else {
                            add(AppEffect.CancelTimeout(TimeoutType.SESSION_PAUSED_SAFETY))
                        }
                    }
                }

                // Pause: online → paused
                prev.mode == Mode.Online && next.mode == Mode.Paused -> {
                    val modeOverride = triggerOverrideEffects(obs, TransitionTrigger.MODE_TO_PAUSED)
                    if (modeOverride != null) {
                        addAll(modeOverride)
                    } else {
                        val flowObs = obs as? Observation.FlowObservation
                        val pausedFields = flowObs?.parsed as? ParsedFields.PausedFields
                        val durationMs = (pausedFields?.remainingMillis ?: 0L) + PAUSE_TIMEOUT_BUFFER_MS
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
                                TimeoutType.SESSION_PAUSED_SAFETY,
                            )
                        )
                        add(AppEffect.UpdateBubble("Dash Paused!"))
                    }
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
                val taskStartOverride = triggerOverrideEffects(obs, TransitionTrigger.TASK_START)
                if (taskStartOverride != null) {
                    addAll(taskStartOverride)
                } else {
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
                add(logEffect(sessionId, AppEventType.DELIVERY_NAV_STARTED, payload))
                add(AppEffect.ResumeOdometer)

                val customer = customerHash?.take(6) ?: "Customer"
                add(AppEffect.UpdateBubble("Heading to $customer", ChatPersona.Customer(customer)))
            }

            // Arrival detection — task subflow changed to ARRIVED
            if (nextTask != null && nextTask.arrivedAt != null &&
                (prevTask?.arrivedAt == null)
            ) {
                val arrivedOverride = triggerOverrideEffects(obs, TransitionTrigger.TASK_ARRIVED)
                if (arrivedOverride != null) {
                    addAll(arrivedOverride)
                } else {
                    add(AppEffect.PauseOdometer)

                    when (nextTask.phase) {
                        TaskPhase.PICKUP -> add(
                            logEffect(
                                sessionId, AppEventType.PICKUP_ARRIVED,
                                mapOf("storeName" to (nextTask.storeName ?: "Unknown")),
                            )
                        )
                        TaskPhase.DROPOFF -> add(
                            logEffect(
                                sessionId, AppEventType.DELIVERY_ARRIVED,
                                mapOf("customerHash" to nextTask.customerNameHash),
                            )
                        )
                    }
                }
            }

            // Internal pickup updates — store name, status, etc.
            if (prevTask != null && nextTask != null &&
                prevTask.phase == TaskPhase.PICKUP &&
                nextTask.phase == TaskPhase.PICKUP
            ) {
                val prevName = prevTask.storeName?.trim()
                val nextName = nextTask.storeName?.trim()
                val storeChanged = nextName != prevName &&
                    nextName != null && nextName != "Unknown"
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
     * Handle PostTask effects while on the PostTask screen.
     * DELIVERY_COMPLETED is emitted from [diffPlatformRegion] when leaving PostTask.
     */
    private fun diffPostTask(
        prev: PlatformRegion,
        next: PlatformRegion,
        nextFlow: FlowRegion,
        obs: Observation,
    ): List<AppEffect> {
        if (nextFlow.flow != Flow.PostTask) return emptyList()
        val flowObs = obs as? Observation.FlowObservation ?: return emptyList()
        val parsed = flowObs.parsed as? ParsedFields.PostTaskFields ?: return emptyList()

        return buildList {
            val payData = parsed.parsedPay

            // If we got expanded pay data AND it's different from last emission, log it
            if (payData != null && next.lastPostTaskPayHash != prev.lastPostTaskPayHash) {
                val receiptText = buildString {
                    append("Saved: ${UtilityFunctions.formatCurrency(payData.total)}")
                    payData.customerTips.forEach { item ->
                        append("\nTip: ${item.type} • ${UtilityFunctions.formatCurrency(item.amount)}")
                    }
                }
                add(AppEffect.UpdateBubble(receiptText, ChatPersona.Earnings))
            }
        }
    }

    /**
     * Handle notification-driven effects. These are global interceptors
     * that apply regardless of state.
     */
    private fun diffNotification(obs: Observation, sessionId: String?): List<AppEffect> {
        if (obs !is Observation.Notification) return emptyList()
        val fields = obs.parsed as? ParsedFields.NotificationFields ?: return emptyList()

        return buildList {
            when (fields.intent) {
                // DoorDash
                "additional_tip" -> {
                    val amount = fields.amount
                    val storeName = fields.storeName
                    val deliveredAt = fields.deliveredAt
                    if (amount != null && storeName != null && deliveredAt != null) {
                        add(
                            AppEffect.ProcessTipNotification(
                                amount = amount,
                                storeName = storeName,
                                deliveredAt = deliveredAt,
                            )
                        )
                    }
                    add(logEffect(sessionId, AppEventType.NOTIFICATION_RECEIVED, "TIP_ADDED: \$$amount $storeName"))
                }
                "new_order" -> {
                    add(logEffect(sessionId, AppEventType.NOTIFICATION_RECEIVED, "NEW_ORDER"))
                }
                "scheduled_dash_expired" -> {
                    add(logEffect(sessionId, AppEventType.NOTIFICATION_RECEIVED, "SCHEDULED_DASH_EXPIRED"))
                }
                "demand_nudge" -> {
                    add(logEffect(null, AppEventType.NOTIFICATION_RECEIVED, "DEMAND_NUDGE"))
                }
                "peak_pay_promo" -> {
                    add(logEffect(null, AppEventType.NOTIFICATION_RECEIVED, "PEAK_PAY_PROMO"))
                }

                // Uber trip leg notifications
                "trip_en_route_pickup" -> {
                    add(logEffect(sessionId, AppEventType.NOTIFICATION_RECEIVED, "TRIP_EN_ROUTE_PICKUP"))
                }
                "trip_arrived_pickup" -> {
                    add(logEffect(sessionId, AppEventType.NOTIFICATION_RECEIVED, "TRIP_ARRIVED_PICKUP"))
                }
                "trip_en_route_dropoff" -> {
                    add(logEffect(sessionId, AppEventType.NOTIFICATION_RECEIVED, "TRIP_EN_ROUTE_DROPOFF"))
                }
                "trip_at_dropoff" -> {
                    add(logEffect(sessionId, AppEventType.NOTIFICATION_RECEIVED, "TRIP_AT_DROPOFF"))
                }
                "tip_received" -> {
                    add(logEffect(sessionId, AppEventType.NOTIFICATION_RECEIVED, "TIP_RECEIVED: ${fields.rawText}"))
                }

                // Uber promo notifications
                "quest_promo" -> {
                    add(logEffect(sessionId, AppEventType.NOTIFICATION_RECEIVED, "QUEST_PROMO"))
                }
                "quest_deadline" -> {
                    add(logEffect(sessionId, AppEventType.NOTIFICATION_RECEIVED, "QUEST_DEADLINE"))
                }

                else -> {
                    add(logEffect(sessionId, AppEventType.NOTIFICATION_RECEIVED, "UNHANDLED: ${fields.intent} — ${fields.rawText}"))
                }
            }
        }
    }

    // =========================================================================
    // TRANSITION OVERRIDE CHECK
    // =========================================================================

    /**
     * Check if the observation carries a rule-declared override for [trigger].
     * When present, returns [AppEffect.RequestEffect] for each override effect
     * (replacing the built-in defaults). Returns null when no override exists
     * (caller falls through to defaults).
     */
    private fun triggerOverrideEffects(
        obs: Observation,
        trigger: TransitionTrigger,
    ): List<AppEffect>? {
        val flowObs = obs as? Observation.FlowObservation ?: return null
        val overrides = flowObs.transitionOverrides[trigger] ?: return null
        return overrides
            .filter { evaluateGate(it.onlyIf, flowObs.parsed) }
            .map { AppEffect.RequestEffect(it) }
    }

    // =========================================================================
    // RULE-ORIGINATED EFFECTS
    // =========================================================================

    /**
     * Extract rule-declared effects from the observation and emit
     * [AppEffect.RequestEffect] for each that passes its gate.
     * Runs at top level — NOT inside any region stepper.
     */
    private fun diffRuleEffects(obs: Observation): List<AppEffect> {
        val flowObs = obs as? Observation.FlowObservation ?: return emptyList()
        if (flowObs.effects.isEmpty()) return emptyList()
        return flowObs.effects
            .filter { evaluateGate(it.onlyIf, flowObs.parsed) }
            .map { AppEffect.RequestEffect(it) }
    }

    private fun evaluateGate(gate: ParsedFieldsGate?, parsed: ParsedFields): Boolean {
        if (gate == null) return true
        val fieldsMap = parsedFieldsToMap(parsed)
        return when (gate) {
            is ParsedFieldsGate.FieldEquals -> fieldsMap[gate.field] == gate.value
            is ParsedFieldsGate.FieldNotEquals -> fieldsMap[gate.field] != gate.value
            is ParsedFieldsGate.FieldNotNull -> fieldsMap[gate.field] != null
        }
    }

    /**
     * Extract named fields from a [ParsedFields] subtype into a flat map
     * for gate evaluation. Uses Java reflection on data class fields.
     *
     * `activity` is excluded because it is a classification tag inherited
     * from the sealed parent — rules gate on structural fields, not the
     * activity discriminator. If gate evaluation fails, the gate rejects
     * (safe default — the action simply won't fire).
     */
    private fun parsedFieldsToMap(parsed: ParsedFields): Map<String, Any?> {
        if (parsed is ParsedFields.None) return emptyMap()
        return try {
            parsed::class.java.declaredFields
                .filter { it.name != "activity" }
                .associate { field ->
                    field.isAccessible = true
                    field.name to field.get(parsed)
                }
        } catch (e: Exception) {
            Timber.w(e, "Gate field extraction failed for %s", parsed::class.simpleName)
            emptyMap()
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun resolveOfferOutcome(obs: Observation, prevOffer: PendingOffer? = null): AppEventType {
        // 1. Stored click intent on PendingOffer — covers the common case where
        //    the click was observed first and the resolving obs is a Screen
        when (prevOffer?.lastClickIntent) {
            "accept_offer" -> return AppEventType.OFFER_ACCEPTED
            "decline_offer" -> return AppEventType.OFFER_DECLINED
        }
        // 2. Direct click observation — covers the edge case where click and
        //    flow change arrive in the same observation
        val clickFields = when (obs) {
            is Observation.Click -> obs.parsed as? ParsedFields.ClickFields
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

}

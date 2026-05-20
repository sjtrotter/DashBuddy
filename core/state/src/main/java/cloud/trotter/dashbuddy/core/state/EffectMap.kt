package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferReceivedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionPausedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ParsedFieldsGate
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.pipeline.TransitionTrigger
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.TransitionKind
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.domain.util.formatCurrency
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
class EffectMap @Inject constructor(
    private val metadataProvider: MetadataProvider,
) {

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
        // Offer-resolution events fire in the FlowRegion handler. They need to
        // be scoped to the active platform's session so AppEventDao queries
        // by dashId see them — without this, the bubble HUD's card stack
        // never sees offer events (#257).
        val activeSessionId = next.regions.flow.activePlatform
            ?.let { next.regions.platforms[it]?.session?.sessionId }
            ?: prev.regions.flow.activePlatform
                ?.let { prev.regions.platforms[it]?.session?.sessionId }
        addAll(diffFlowRegion(prev.regions.flow, next.regions.flow, obs, activeSessionId))
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
        sessionId: String?,
    ): List<AppEffect> = buildList {
        val flowObs = obs as? Observation.FlowObservation
        val prevOffer = prev.pendingOffer
        val nextOffer = next.pendingOffer

        // Offer presented
        // Screenshot for the offer screen still comes from rule-declared
        // effects (deduped via dedupeKey + throttleMs). DB-persisted
        // events flow through here so payloads stay typed and consistent
        // across platforms (#257 design discussion).
        if (prevOffer == null && nextOffer != null) {
            val offer = nextOffer.offerFields
            val platform = next.activePlatform?.name ?: "Unknown"

            // Log OFFER_RECEIVED with the full parsed offer. Evaluation
            // hasn't run yet at this point (it fires async via the
            // EvaluateOffer side effect); the rich evaluation lands on
            // the closing OFFER_ACCEPTED / DECLINED / TIMEOUT payload.
            val receivedPayload = OfferReceivedPayload(
                offerHash = nextOffer.offerHash,
                parsedOffer = offer.parsedOffer,
                presentedAt = nextOffer.presentedAt,
                platform = platform,
                returnFlow = nextOffer.returnFlow,
            )
            add(logEffect(sessionId, AppEventType.OFFER_RECEIVED, receivedPayload))

            // Evaluate
            add(AppEffect.EvaluateOffer(offer.parsedOffer))

            // Speak offer aloud
            add(AppEffect.SpeakOffer(offer.parsedOffer, platform))
        }

        // Offer replaced (different hash)
        // Screenshot + log for new offer handled by rule-declared effects (deduped
        // per offerHash). Resolution log for old offer stays here.
        if (prevOffer != null && nextOffer != null &&
            prevOffer.offerHash != nextOffer.offerHash
        ) {
            // Log resolution of old offer with full context.
            val outcome = resolveOfferOutcome(obs, prevOffer)
            add(logEffect(sessionId, outcome, offerPayload(prevOffer, outcome, obs.timestamp, "Replaced by new offer")))

            // Evaluate + speak new offer
            val offer = nextOffer.offerFields
            add(AppEffect.EvaluateOffer(offer.parsedOffer))
            val platform = next.activePlatform?.name ?: "Unknown"
            add(AppEffect.SpeakOffer(offer.parsedOffer, platform))
        }

        // Offer resolved (accepted/declined/timeout)
        if (prevOffer != null && nextOffer == null) {
            val outcome = resolveOfferOutcome(obs, prevOffer)
            add(logEffect(sessionId, outcome, offerPayload(prevOffer, outcome, obs.timestamp)))

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
            addAll(diffNotification(obs))

            // Delivery completed: leaving PostTask for a non-PostTask flow
            if (prevFlow.flow == Flow.PostTask && nextFlow.flow != Flow.PostTask) {
                val taskCompletedOverride = triggerOverrideEffects(obs, TransitionTrigger.TASK_COMPLETED)
                if (taskCompletedOverride != null) {
                    addAll(taskCompletedOverride)
                } else {
                    val sessionId = next.session?.sessionId ?: p.session?.sessionId
                    val completedTask = next.recentTasks.lastOrNull()
                    val payload = deliveryCompletedPayload(
                        task = completedTask,
                        jobId = p.activeJob?.jobId,
                        completedAt = obs.timestamp,
                        postTaskFields = p.lastPostTaskFields,
                        sessionEarnings = next.session?.runningEarnings ?: p.session?.runningEarnings,
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
                    } else if (nextSession != null && prevSession?.sessionId != nextSession.sessionId) {
                        val payload = SessionStartPayload(
                            sessionId = nextSession.sessionId,
                            platform = next.platform.name,
                            startedAt = nextSession.startedAt,
                            source = if (next.lastTransitionKind == TransitionKind.Unexpected) "recovery" else "interaction",
                            startScreen = "WaitingForOffer",
                        )
                        add(logEffect(nextSession.sessionId, AppEventType.DASH_START, payload))
                        add(AppEffect.StartOdometer)
                        add(AppEffect.StartSession(nextSession.sessionId, next.platform.name))
                    } else if (nextSession != null && prevSession?.sessionId == nextSession.sessionId) {
                        // Grace resume — same session, no start effects needed
                        Timber.d("Session grace resume: ${nextSession.sessionId}")
                        add(AppEffect.UpdateBubble("Session resumed (grace)"))
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
                            val earnings = formatCurrency(parsed.totalEarnings)
                            val payload = SessionStopPayload(
                                sessionId = sessionId,
                                endedAt = obs.timestamp,
                                source = "summary_screen",
                                totalEarnings = parsed.totalEarnings,
                                sessionDurationMillis = parsed.sessionDurationMillis,
                                offersAccepted = parsed.offersAccepted,
                                offersTotal = parsed.offersTotal,
                                weeklyEarnings = parsed.weeklyEarnings,
                            )
                            add(logEffect(sessionId, AppEventType.DASH_STOP, payload))
                            add(AppEffect.StopOdometer)
                            add(
                                AppEffect.UpdateBubble(
                                    "Session Ended. Total: $earnings",
                                    ChatPersona.Dispatcher,
                                )
                            )
                            add(AppEffect.CaptureScreenshot("DashSummary - ${parsed.totalEarnings}"))
                        } else {
                            val payload = SessionStopPayload(
                                sessionId = sessionId,
                                endedAt = obs.timestamp,
                                source = "early_offline",
                                totalEarnings = prevSession.runningEarnings,
                            )
                            add(logEffect(sessionId, AppEventType.DASH_STOP, payload))
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

                        val pausePayload = SessionPausedPayload(
                            sessionId = sessionId,
                            pausedAt = obs.timestamp,
                            remainingText = pausedFields?.remainingText,
                            remainingMillis = pausedFields?.remainingMillis,
                        )
                        add(logEffect(sessionId, AppEventType.DASH_PAUSED, pausePayload))
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

            // Task started — pickup navigation.
            //
            // Fires whenever a new PICKUP task is the active task — either the
            // first task of the session (prevTask == null) or a new task minted
            // for a stacked-pickup transition (prevTask is the now-completed
            // previous pickup with a different taskId).
            if (nextTask != null &&
                nextTask.phase == TaskPhase.PICKUP &&
                prevTask?.taskId != nextTask.taskId
            ) {
                val taskStartOverride = triggerOverrideEffects(obs, TransitionTrigger.TASK_START)
                if (taskStartOverride != null) {
                    addAll(taskStartOverride)
                } else {
                    val storeName = nextTask.storeName ?: "Unknown"
                    val payload = pickupPayload(nextTask, storeName)
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

            // Delivery confirmed: the active task is no longer this dropoff,
            // either because it became null (PostTask / Idle / session end) or
            // because a new task took over (next pickup, next dropoff leg).
            // DoorDash drop-off doesn't surface an explicit "arrived" screen
            // we can rely on, so this transition is the closure signal.
            if (prevTask?.phase == TaskPhase.DROPOFF &&
                (nextTask == null || nextTask.taskId != prevTask.taskId)
            ) {
                val deliveryConfirmed = deliveryPhasePayload(
                    task = prevTask,
                    phaseStartedAt = prevTask.startedAt,
                )
                add(logEffect(sessionId, AppEventType.DELIVERY_CONFIRMED, deliveryConfirmed))
            }

            // Task phase changed — pickup → dropoff (pickup confirmed)
            if (prevTask?.phase == TaskPhase.PICKUP &&
                nextTask?.phase == TaskPhase.DROPOFF
            ) {
                val customerHash = nextTask.customerNameHash
                val pickupConfirmed = pickupPayload(
                    task = prevTask,
                    storeName = prevTask.storeName ?: "Unknown",
                    confirmedAt = obs.timestamp,
                )
                val deliveryStart = deliveryPhasePayload(
                    task = nextTask,
                    phaseStartedAt = obs.timestamp,
                )
                add(logEffect(sessionId, AppEventType.PICKUP_CONFIRMED, pickupConfirmed))
                add(logEffect(sessionId, AppEventType.DELIVERY_NAV_STARTED, deliveryStart))
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
                                pickupPayload(
                                    task = nextTask,
                                    storeName = nextTask.storeName ?: "Unknown",
                                ),
                            )
                        )
                        TaskPhase.DROPOFF -> add(
                            logEffect(
                                sessionId, AppEventType.DELIVERY_ARRIVED,
                                deliveryPhasePayload(
                                    task = nextTask,
                                    phaseStartedAt = nextTask.startedAt,
                                ),
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

                // Store name resolution — re-emit the pickup payload with the
                // updated store name. The mapper treats the latest
                // PICKUP_NAV_STARTED per task as canonical, so this is the
                // store name the Pickup card will render.
                if (storeChanged) {
                    val storeName = nextTask.storeName ?: "Unknown"
                    add(
                        logEffect(
                            sessionId, AppEventType.PICKUP_NAV_STARTED,
                            pickupPayload(nextTask, storeName),
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
                    append("Saved: ${formatCurrency(payData.total)}")
                    payData.customerTips.forEach { item ->
                        append("\nTip: ${item.type} • ${formatCurrency(item.amount)}")
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
    /**
     * Intent-specific notification processing that can't be expressed as
     * a JSON effect. Logging and other simple effects are now declared in
     * the rule JSON and handled by [diffRuleEffects].
     */
    private fun diffNotification(obs: Observation): List<AppEffect> {
        if (obs !is Observation.Notification) return emptyList()
        val fields = obs.parsed as? ParsedFields.NotificationFields ?: return emptyList()

        return buildList {
            when (fields.intent) {
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
        val metadataJson = metadataProvider.createMetadata()
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

    // =========================================================================
    // PAYLOAD BUILDERS
    //
    // Build rich phase-boundary payloads from in-memory state. Same emit
    // moments as before — richer payload each one writes. The flow-card
    // mapper folds these into per-phase snapshots without joining other
    // entities; see #257.
    // =========================================================================

    private fun offerPayload(
        offer: PendingOffer,
        outcome: AppEventType,
        decidedAt: Long,
        description: String? = null,
    ): OfferPayload = OfferPayload(
        offerHash = offer.offerHash,
        parsedOffer = offer.offerFields.parsedOffer,
        evaluation = offer.evaluation,
        outcome = outcome,
        presentedAt = offer.presentedAt,
        decidedAt = decidedAt,
        returnFlow = offer.returnFlow,
        description = description,
    )

    private fun pickupPayload(
        task: Task,
        storeName: String,
        confirmedAt: Long? = null,
    ): PickupPayload = PickupPayload(
        jobId = task.jobId,
        taskId = task.taskId,
        storeName = storeName,
        phaseStartedAt = task.startedAt,
        arrivedAt = task.arrivedAt,
        confirmedAt = confirmedAt,
        odometerAtEntry = task.odometerAtEntry,
        odometerAtArrival = task.odometerAtArrival,
        deadlineMillis = task.deadlineMillis,
        itemCount = task.itemCount,
        redCardTotal = task.redCardTotal,
        activity = task.activity,
    )

    private fun deliveryPhasePayload(
        task: Task,
        phaseStartedAt: Long,
    ): DeliveryPayload = DeliveryPayload(
        jobId = task.jobId,
        taskId = task.taskId,
        storeName = task.storeName,
        customerHash = task.customerNameHash,
        addressHash = task.customerAddressHash,
        phaseStartedAt = phaseStartedAt,
        arrivedAt = task.arrivedAt,
        odometerAtEntry = task.odometerAtEntry,
        odometerAtArrival = task.odometerAtArrival,
        deadlineMillis = task.deadlineMillis,
    )

    private fun deliveryCompletedPayload(
        task: Task?,
        jobId: String?,
        completedAt: Long,
        postTaskFields: ParsedFields.PostTaskFields?,
        sessionEarnings: Double?,
    ): DeliveryPayload = DeliveryPayload(
        jobId = jobId ?: task?.jobId ?: "unknown",
        taskId = task?.taskId ?: "unknown",
        storeName = task?.storeName,
        customerHash = task?.customerNameHash,
        addressHash = task?.customerAddressHash,
        phaseStartedAt = task?.startedAt ?: completedAt,
        arrivedAt = task?.arrivedAt,
        completedAt = completedAt,
        odometerAtEntry = task?.odometerAtEntry,
        odometerAtArrival = task?.odometerAtArrival,
        deadlineMillis = task?.deadlineMillis,
        totalPay = postTaskFields?.totalPay,
        parsedPay = postTaskFields?.parsedPay,
        sessionEarningsAtCompletion = sessionEarnings,
    )

}

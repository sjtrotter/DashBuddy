package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.model.pay.displayLabel
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TransitionTrigger
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PickupActivity
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.UNKNOWN_STORE
import cloud.trotter.dashbuddy.domain.state.customerLabel

/**
 * #240 — the pickup/dropoff task-lifecycle effect diffs, extracted from [EffectMap] (past the
 * #237 ceiling once the #438 B-series grew it to ~1450 lines). `internal`/`private` extensions on
 * [EffectMap] (mirroring the [OfferEffects]/[JobAcceptFlow] precedent) so they keep direct access
 * to [EffectMap.logEffect] and [EffectMap.triggerOverrideEffects] (both widened `private` →
 * `internal` on [EffectMap] for this split). Pure move: no behavior change. Covers pickup nav/
 * arrival, dropoff nav/arrival, the PICKUP_CONFIRMED sweep, and the post-task "Saved: $X" bubble —
 * everything up to but NOT including the DELIVERY_COMPLETED mint itself, which is big enough on
 * its own (the PostTask-exit + #596 close-out pair, sharing one dual-mint-exclusivity set) to
 * warrant its own file, [DeliveryCompletionEffects.kt].
 */
internal fun EffectMap.diffTask(
    prev: PlatformRegion,
    next: PlatformRegion,
    obs: Observation,
): List<AppEffect> {
    // Diffs are computed from prev/next region state for EVERY observation type:
    // lazy expiry can retire a task on a non-flow observation (a routed timeout,
    // #342), and that closure must still emit DELIVERY_CONFIRMED (#345).
    // #438 B5: the acted-flow reads that drove the (now cross-platform-arbitrated) odometer
    // Pause/Resume were removed here — the task edges below emit only log events + bubbles.
    val sessionId = next.session?.sessionId ?: prev.session?.sessionId

    return buildList {
        val prevTask = prev.activeTask
        val nextTask = next.activeTask

        // #526 D5 sweep: a pickup→pickup active-task change emits NOTHING new here — the new
        // pickup's PICKUP_NAV_STARTED fires in the branch just below, and the displaced pickup's
        // PICKUP_CONFIRMED is now emitted by the CONFIRM SWEEP at the pickup→dropoff edge / the
        // #596 close-out (see [pickupConfirmSweepEffects]). The old per-edge displacement confirm
        // fired a premature confirm on an order-not-ready reroute/flap (then the real confirm was
        // suppressed by the burned per-task key + polluted the #556 shop rate) and a phantom
        // confirm on a D4a swap edge — the sweep replaces all of it.

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
                val storeName = nextTask.storeName ?: UNKNOWN_STORE
                val payload = pickupPayload(nextTask, storeName)
                add(logEffect(sessionId, AppEventType.PICKUP_NAV_STARTED, obs.timestamp, payload))
                // #438 B5: ResumeOdometer is arbitrated by the cross-platform stationary
                // predicate (diffCrossPlatform), no longer emitted per pickup-nav edge.

                val persona = determinePickupPersona(
                    nextTask.activity,
                    nextTask.arrivedAt != null,
                    storeName,
                )
                add(AppEffect.UpdateBubble("Pickup: $storeName", persona, dedupeScope = nextTask.taskId))
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
            add(logEffect(sessionId, AppEventType.DELIVERY_CONFIRMED, obs.timestamp, deliveryConfirmed))
        }

        // Task phase changed — pickup → dropoff (pickup confirmed): the
        // FIRST leg of a delivery. The stacked leg-2+ case (dropoff→dropoff /
        // null→dropoff) is handled by the branch just below.
        if (prevTask?.phase == TaskPhase.PICKUP &&
            nextTask?.phase == TaskPhase.DROPOFF
        ) {
            // #526 D5 sweep: confirm EVERY arrived pickup in this job's lineage (the just-
            // displaced prevTask + any earlier displaced pickups), each at its own completion
            // time, BEFORE the dropoff's DELIVERY_NAV_STARTED (CONFIRMED-before-NAV). Per-task
            // keys make this idempotent with the close-out sweep below.
            addAll(
                pickupConfirmSweepEffects(
                    sessionId, next, prevTask.jobId, obs,
                    // #159 FIX 9: source the job's offer hashes from the job matching prevTask.jobId in
                    // PREV state first (next's active job may have already swapped to a stacked next job on
                    // this edge, which would drop the hashes to empty); fall back to next's, then empty
                    // only when the job is truly gone from both.
                    jobOfferHashes = (
                        prev.activeJob?.takeIf { it.jobId == prevTask.jobId }
                            ?: next.activeJob?.takeIf { it.jobId == prevTask.jobId }
                        )?.parentOfferHashes ?: emptyList(),
                ),
            )
            addAll(deliveryNavStartedEffects(sessionId, nextTask, obs))
        }

        // New dropoff leg — the active task became a DIFFERENT dropoff (#603).
        // The pickup→dropoff branch above only mints DELIVERY_NAV_STARTED on
        // the first leg; a stacked leg-2 (or later) drop arrives as
        // dropoff→dropoff, or as null→dropoff once leg-1 has been
        // grace-retired. Without this the second drop was silent — no nav
        // event, no odometer resume, no "Heading to" bubble.
        // Guards:
        //  - a genuinely new task (taskId changed),
        //  - that started on THIS frame (fresh mint AND placeholder-resolve
        //    both stamp startedAt = obs.timestamp; a RESUMED task keeps its
        //    old startedAt, so a replay/re-sight can't re-fire the nav),
        //  - whose predecessor was NOT a pickup (the pickup→dropoff case is
        //    already handled above — this keeps the two branches disjoint).
        if (nextTask?.phase == TaskPhase.DROPOFF &&
            nextTask.taskId != prevTask?.taskId &&
            nextTask.startedAt == obs.timestamp &&
            prevTask?.phase != TaskPhase.PICKUP
        ) {
            addAll(deliveryNavStartedEffects(sessionId, nextTask, obs))
        }

        // Arrival detection — task subflow changed to ARRIVED
        if (nextTask != null && nextTask.arrivedAt != null &&
            (prevTask?.arrivedAt == null)
        ) {
            val arrivedOverride = triggerOverrideEffects(obs, TransitionTrigger.TASK_ARRIVED)
            if (arrivedOverride != null) {
                addAll(arrivedOverride)
            } else {
                // #438 B5: PauseOdometer is arbitrated by the cross-platform stationary
                // predicate (diffCrossPlatform) — it fires only when ALL live regions are
                // parked, so one platform's arrival can't pause GPS mid-drive on another's leg.

                when (nextTask.phase) {
                    TaskPhase.PICKUP -> add(
                        logEffect(
                            sessionId, AppEventType.PICKUP_ARRIVED, obs.timestamp,
                            pickupPayload(
                                task = nextTask,
                                storeName = nextTask.storeName ?: UNKNOWN_STORE,
                            ),
                        )
                    )
                    TaskPhase.DROPOFF -> add(
                        logEffect(
                            sessionId, AppEventType.DELIVERY_ARRIVED, obs.timestamp,
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
                nextName != null && nextName != UNKNOWN_STORE
            val activityChanged = nextTask.activity != prevTask.activity

            if (storeChanged || activityChanged) {
                val storeName = nextTask.storeName ?: UNKNOWN_STORE
                val persona = determinePickupPersona(
                    nextTask.activity,
                    nextTask.arrivedAt != null,
                    storeName,
                )
                add(AppEffect.UpdateBubble("Pickup: $storeName", persona, dedupeScope = nextTask.taskId))
            }

            // Store name resolution — re-emit the pickup payload with the
            // updated store name. The mapper treats the latest
            // PICKUP_NAV_STARTED per task as canonical, so this is the
            // store name the Pickup card will render.
            if (storeChanged) {
                val storeName = nextTask.storeName ?: UNKNOWN_STORE
                add(
                    logEffect(
                        sessionId, AppEventType.PICKUP_NAV_STARTED, obs.timestamp,
                        pickupPayload(nextTask, storeName),
                    )
                )
            }
        }

        // #438 B5: the PostTask-entry ResumeOdometer moved to the cross-platform stationary
        // predicate (diffCrossPlatform). Leaving an arrived drop for PostTask is a
        // stationary→moving crossing there; entering PostTask from a non-arrived drive was a
        // redundant no-op Resume (GPS already running) and is correctly elided.
    }
}

/**
 * The "delivery nav started" effect trio emitted when a NEW dropoff task
 * becomes the active task: the DELIVERY_NAV_STARTED log event (phase clock
 * stamped to this frame), the odometer resume, and the store-flavored
 * "Heading to <customer>" bubble. Shared by the first-leg pickup→dropoff
 * transition and the stacked leg-2 dropoff→dropoff / null→dropoff transition
 * (#603) so a stacked drop gets the exact same driver-facing treatment as
 * the first — one silent-second-drop bug, not two code paths that can drift.
 *
 * [task]'s storeName may be null on a leg-2 drop the dropoff screen didn't
 * carry a store for; [customerLabel] then falls back to the generic
 * recipient (never the hash). Leg-2 store re-attribution is #526's scope.
 */
private fun EffectMap.deliveryNavStartedEffects(
    sessionId: String?,
    task: Task,
    obs: Observation,
): List<AppEffect> = buildList {
    add(
        logEffect(
            sessionId,
            AppEventType.DELIVERY_NAV_STARTED,
            obs.timestamp,
            deliveryPhasePayload(task = task, phaseStartedAt = obs.timestamp),
        ),
    )
    // #438 B5: ResumeOdometer arbitrated by the cross-platform stationary predicate
    // (diffCrossPlatform) — leaving an arrived pickup/drop for the next leg is the
    // stationary→moving crossing that resumes GPS there.
    // #568: store-flavored label ("Heading to H-E-B's customer") — friendlier
    // than the raw 6-char hash, and it disambiguates a multi-store stack's drops.
    val customer = customerLabel(task.storeName)
    add(AppEffect.UpdateBubble("Heading to $customer", ChatPersona.Customer(customer), dedupeScope = task.taskId))
}

/**
 * #526 D5 sweep: confirm every ARRIVED pickup in [jobId]'s lineage as visible to EffectMap —
 * the job's completed/displaced pickups in [PlatformRegion.recentTasks] plus the active task if
 * it is still a pickup of this job. Each emits one `PICKUP_CONFIRMED` keyed on its task id, so
 * the two sweep sites (the pickup→dropoff edge AND the #596 close-out) are idempotent under the
 * engine's effects_fired dedup. Each task confirms at its OWN completion time ([Task.completedAt],
 * the real confirm moment) — a still-active edge task, not yet displaced, falls back to
 * `obs.timestamp`. Replaces the old pickup→pickup displacement confirm.
 *
 * `internal`, not `private` (#240 split): the #596 close-out sweep site now lives in
 * [DeliveryCompletionEffects.kt], a separate file from this one.
 */
internal fun EffectMap.pickupConfirmSweepEffects(
    sessionId: String?,
    region: PlatformRegion,
    jobId: String?,
    obs: Observation,
    // #159 D3: the job's contributing offer hashes, carried onto each PICKUP_CONFIRMED payload for the
    // offer↔job link. Passed by the caller (the job is in scope there); empty ⇒ temporal fallback (F12).
    jobOfferHashes: List<String> = emptyList(),
): List<AppEffect> = buildList {
    if (jobId == null) return@buildList
    val lineage = (region.recentTasks + listOfNotNull(region.activeTask))
        .filter { it.jobId == jobId && it.phase == TaskPhase.PICKUP && it.arrivedAt != null }
        .distinctBy { it.taskId }
    for (task in lineage) {
        addAll(
            pickupConfirmedEffects(
                sessionId, task, obs,
                // #588: the region owns the platform — a shop's measured pace folds into THIS
                // platform's learned rate, never a shared global.
                platform = region.platform,
                confirmedAt = task.completedAt ?: obs.timestamp,
                jobOfferHashes = jobOfferHashes,
            ),
        )
    }
}

/**
 * #526 D5/D5a: the effects for confirming a completed PICKUP leg — `PICKUP_CONFIRMED` keyed on
 * the confirmed task's id (so an A→B→A resume then A→dropoff can't double-confirm the same
 * pickup under mixed keying), plus the #556 [AppEffect.RecordShopRate] rider when it was a shop.
 * [confirmedAt] is the real confirm time (the swept task's `completedAt`, or `obs.timestamp` at
 * the edge) — it stamps the log event AND the shop-rate window (arrived→confirmed).
 */
private fun EffectMap.pickupConfirmedEffects(
    sessionId: String?,
    prevTask: Task,
    obs: Observation,
    platform: Platform,
    confirmedAt: Long = obs.timestamp,
    jobOfferHashes: List<String> = emptyList(),
): List<AppEffect> = buildList {
    add(
        logEffect(
            sessionId,
            AppEventType.PICKUP_CONFIRMED,
            confirmedAt,
            pickupPayload(
                task = prevTask,
                storeName = prevTask.storeName ?: UNKNOWN_STORE,
                confirmedAt = confirmedAt,
                jobOfferHashes = jobOfferHashes,
            ),
            effectKeyOverride = "log:${AppEventType.PICKUP_CONFIRMED}:${prevTask.taskId}",
        ),
    )
    // #556: a completed SHOP pickup feeds the learned items/min. In-store time is measured
    // arrived→confirmed (the 0.8/min seed basis); the handler floors out noise.
    val shopItems = prevTask.itemsShopped ?: 0
    val shopArrivedAt = prevTask.arrivedAt
    if (prevTask.activity == PickupActivity.SHOPPING && shopItems > 0 && shopArrivedAt != null) {
        add(
            AppEffect.RecordShopRate(
                platform = platform,
                itemsShopped = shopItems,
                shopDurationMs = confirmedAt - shopArrivedAt,
                storeName = prevTask.storeName,
                jobId = prevTask.jobId,
                taskId = prevTask.taskId,
            ),
        )
    }
}

/**
 * Handle PostTask effects while on the PostTask screen.
 *
 * Emits at most ONE "Saved: $X" bubble per delivery, gated by the
 * per-task idempotency field `lastAnnouncedPostTaskTaskId` (which the
 * stepper stamps with the current taskId on every PostTaskFields
 * observation). The bubble uses whatever shape is available on first
 * sighting:
 *  - parsedPay present (expanded) → full receipt with line items
 *  - parsedPay null (collapsed) → minimal "Saved: $X"
 *
 * If the breakdown arrives later (e.g. auto-expand succeeds after a
 * collapsed-first sighting), it's still captured in `lastPostTaskFields`
 * by the stepper and surfaces via the post-task card in the bubble HUD
 * + the DELIVERY_COMPLETED payload. But no second bubble fires.
 *
 * DELIVERY_COMPLETED itself is emitted from [diffPlatformRegion] when
 * leaving PostTask (with the full pay breakdown if it was ever captured).
 */
internal fun EffectMap.diffPostTask(
    prev: PlatformRegion,
    next: PlatformRegion,
    actedNextFlow: Flow,
    obs: Observation,
): List<AppEffect> {
    // #438 item 5: gate on THIS region's own acted flow — the obs is global, so without this a
    // non-observing region would also fire the "Saved: $X" bubble on the owner's PostTask frame.
    if (actedNextFlow != Flow.PostTask) return emptyList()
    val flowObs = obs as? Observation.FlowObservation ?: return emptyList()
    val parsed = flowObs.parsed as? ParsedFields.PostTaskFields ?: return emptyList()

    // The completing task stays ACTIVE while its retire grace is pending
    // (#431 pt 2) — resolve it first, falling back to the last committed
    // task. Must mirror the stepper's lastAnnouncedPostTaskTaskId stamp.
    val taskId = next.activeTask?.taskId
        ?: next.recentTasks.lastOrNull()?.taskId
        ?: return emptyList()
    if (prev.lastAnnouncedPostTaskTaskId == taskId) return emptyList()
    if (parsed.totalPay <= 0) return emptyList()

    val payData = parsed.parsedPay
    val text = if (payData != null) {
        buildString {
            append("Saved: ${Formats.money(payData.total)}")
            payData.customerTips.forEach { item ->
                append("\nTip: ${item.displayLabel} • ${Formats.money(item.amount)}")
            }
        }
    } else {
        "Saved: ${Formats.money(parsed.totalPay)}"
    }
    return listOf(AppEffect.UpdateBubble(text, ChatPersona.Earnings))
}

private fun EffectMap.determinePickupPersona(
    activity: String?,
    arrived: Boolean,
    storeName: String,
): ChatPersona {
    return when {
        activity == PickupActivity.SHOPPING -> ChatPersona.Shopper
        // #568: store-flavored, never the raw hash (keeps the "never show the hash" invariant
        // literal). customerHash stays the identity key; storeName drives display.
        activity == PickupActivity.CONFIRMED -> ChatPersona.Customer(customerLabel(storeName))
        arrived -> ChatPersona.Merchant(storeName)
        else -> ChatPersona.Navigator
    }
}

private fun EffectMap.pickupPayload(
    task: Task,
    storeName: String,
    confirmedAt: Long? = null,
    jobOfferHashes: List<String> = emptyList(),
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
    itemsRemaining = task.itemsRemaining,
    itemsShopped = task.itemsShopped,
    redCardTotal = task.redCardTotal,
    activity = task.activity,
    // #159 D4/D3: the parsed store address + the job's offer hashes for entity resolution.
    storeAddress = task.storeAddress,
    jobOfferHashes = jobOfferHashes,
)

private fun EffectMap.deliveryPhasePayload(
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

package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.StoreNameMatch
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import java.util.Locale
import timber.log.Timber

/**
 * The dropoff-store / job-task lineage family + the unassign-abandon, extracted from
 * [PlatformRegionStepper] (#761 — the #237 file-ceiling residue; [OfferLifecycle] / [JobAcceptFlow]
 * are the same-package #718 precedent). Pure moves, no logic change: [reconcileDropoffStore] (the
 * customer-hash store join, #526/#733/#745), [reconcileJobTasks] (the non-unassigned lineage mirror,
 * #752), and [abandonActiveTask] (the #736 unassign-via-help) are `internal` extensions on
 * [PlatformRegionStepper] (called from its `step` wrapper / task lifecycle and sharing
 * [PlatformRegionStepper.completeActiveJob] via the implicit receiver); [activatedDropoffsWithHash]
 * and [resolveFromPickupSet] are file-private helpers of [reconcileDropoffStore] only.
 *
 * The mode/grace/session/job arms, `mintId`, `completeActiveJob`, `retireActiveTask`, and the
 * top-level `Flow` task helpers deliberately stay in `PlatformRegionStepper.kt`; the task-arm
 * (`updateTaskLifecycle`) lives in the sibling `TaskLifecycle.kt`.
 */
/**
 * #526/#733: a dropoff's store is resolved from the job's PICKUP lineage, not from the dropoff
 * card's own text format (which varies per merchant — `Target (02426)` / `Maple Street Biscuit -
 * Alamo Ranch` — and can't be reliably parsed, see the rule comment). A dropoff always follows
 * its pickup, and the job already holds the pickups with their authoritative, screen-parsed store
 * names. Resolution is the **customer-hash join** (#526 D6, generalized + constrained #733):
 *  1. **EXACT single-match join** — this drop's `customerNameHash` joins one or more pickups that
 *     all map to ONE store → resolve it. Unconditional: a single agreed store is correct for this
 *     drop even if a colliding customer shares the hash. Normalization (#733) keeps the hashes
 *     cross-surface-stable so a customer's short/full name forms join.
 *  2. **CONSTRAINED multi-store default** — the matched pickups span ≥2 stores. This drop is then
 *     genuinely multi-store-fed and resolves to the deterministic multi-store default (the
 *     earliest-confirmed matched pickup's store, [resolveFromPickupSet]) — but ONLY when this drop
 *     is the **sole ACTIVATED dropoff** in the job carrying that hash. If ≥2 activated dropoffs
 *     share the hash (colliding customers whose normalized keys collapse, or a strip-miss), the
 *     hash is INCONCLUSIVE for those drops → fall through. Fail-null beats fail-wrong.
 *  3. **TOKEN MATCH (existing)** — a 0-match drop (or an inconclusive collision) falls back to
 *     matching its parsed store *candidate* to the pickup with the most shared brand tokens; a
 *     garbage candidate matches nothing → null, never a wrong store.
 *  - **no pickup seen yet** → keep the candidate as-is.
 *
 * The former **structural single-drop arm** (resolve any job with exactly one dropoff from ALL its
 * pickups, no hash) was DELETED (#745 review): its premise — placeholder count == physical drops —
 * is desynchronized by per-order placeholders (`JobAcceptFlow` mints one per ORDER, so a
 * same-customer 2-order job counts 2 forever) and by the unparsed-offer `dropoffCount=1` fallback,
 * where it would stamp a WRONG store on a real 2-drop stack, pre-empting the exact hash join. It
 * was also proven inert on the fielded 07-08 shape — the nav-sheet customer hash actually joins
 * BOTH pickups, so the multi-match arm does the real work there. "Never a wrong store" holds: we
 * only ever attribute a store INSIDE the drop's own matched lineage. The dropoff card's full
 * running-key form set still lives on the payout for the store-entity projector (#159).
 *
 * The per-order-placeholder desync above (a same-customer multi-order job carries a leftover TBD
 * dropoff placeholder that never activates, since the one physical drop resumes a single taskId)
 * used to defeat `isJobPhysicallyComplete` for the job's whole lifetime → the #596 T2 guard never
 * fired → the NEXT offer folded into an already-finished job. FIXED by #749 for the
 * **distinct-store class**: `JobCompleteness`'s per-customer coverage arm proves completion from
 * the pickup side (pickups map 1:1 to orders at distinct stores, so their hash set is the job's
 * customer set) when every pickup is hashed AND confirmed and every customer hash has a finished,
 * arrived drop — the placeholder count no longer matters there. RESIDUAL family members where the
 * coverage gate deliberately fails (absorption direction — the T2 fold-in class can recur; both
 * pre-existing on master, #759 review F5): (a) duplicated-store-hint shapes — a same-store
 * multi-order job has |P_pick| < |P_drop|, so coverage is unprovable from counts; (b) the unassign
 * composition — a #752 dropoff-placeholder retire after an unassign leaves e.g. pickups 2 vs
 * drops 1, the count gate fails, and a same-customer job on that path stays strict-only.
 */
internal fun PlatformRegionStepper.reconcileDropoffStore(region: PlatformRegion): PlatformRegion {
    val active = region.activeTask ?: return region
    if (active.phase != TaskPhase.DROPOFF) return region
    val jobPickups = region.recentTasks
        .filter { it.jobId == active.jobId && it.phase == TaskPhase.PICKUP }

    // CUSTOMER-HASH JOIN (#526 D6, generalized + constrained #733). The dropoff carries the order's
    // customer and so does its pickup; with normalization the sha256s match across surfaces.
    val dropHash = active.customerNameHash
    if (dropHash != null) {
        val matched = jobPickups.filter { it.customerNameHash == dropHash && it.storeName != null }
        if (matched.isNotEmpty()) {
            val matchedStores = matched.map { it.storeName!! }.distinctBy { it.lowercase(Locale.ROOT) }
            if (matchedStores.size == 1) {
                // EXACT single-match join — unconditional. All matched pickups agree on one store,
                // so it is correct for this drop even if a colliding customer shares the hash.
                val resolved = matchedStores.single()
                return if (resolved == active.storeName) region
                else region.copy(activeTask = active.copy(storeName = resolved))
            }
            // Matched pickups span ≥2 stores → the deterministic multi-store default, but ONLY
            // when this drop is the sole ACTIVATED dropoff carrying this hash. A never-activated
            // placeholder has no resolved customerNameHash, so it never counts. ≥2 activated drops
            // sharing the hash → we can't say which store feeds which → inconclusive; fall through.
            if (activatedDropoffsWithHash(region, dropHash) <= 1) {
                val resolved = resolveFromPickupSet(matched, fallback = active.storeName)
                return if (resolved == active.storeName) region
                else region.copy(activeTask = active.copy(storeName = resolved))
            }
            // else: inconclusive collision → fall through to the token rules.
        }
    }

    // --- fall-through: D6 join-miss WARN gate + token match ---
    var r = region
    // #526 FIX5 / #733 part 5 (D6 observability): a multi-store job whose drop joins ZERO
    // pickup-lineage hashes is the signature of cross-surface hash-form drift a rule forgot to
    // normalize. Surface it, EDGE-GATED ONCE PER taskId (#745 re-key): a two-form customer surface
    // flaps the hash A↔B per frame, so a per-(taskId,hash) key would re-storm the log every flap
    // (the field ×23). The gate lives in region state ([PlatformRegion.lastJoinMissWarnTaskId]) so
    // it is pure + replay-deterministic. PII-safe: counts + a 6-char hash prefix only (P7). Only
    // the true 0-match case warns — an inconclusive collision (matched but ambiguous) does not.
    if (dropHash != null && active.storeName == null &&
        jobPickups.none { it.customerNameHash == dropHash && it.storeName != null }
    ) {
        val distinctPickupStores = jobPickups.mapNotNull { it.storeName }
            .distinctBy { it.lowercase(Locale.ROOT) }.size
        if (distinctPickupStores >= 2 && region.lastJoinMissWarnTaskId != active.taskId) {
            Timber.tag("StateMachine").w(
                "D6 join miss (#526/#733): drop hash %s matched 0 of %d pickup-lineage customer " +
                    "hashes across %d stores — pickup/dropoff hash-form drift",
                dropHash.take(6),
                jobPickups.count { it.customerNameHash != null },
                distinctPickupStores,
            )
            r = r.copy(lastJoinMissWarnTaskId = active.taskId)
        }
    }

    // Rule 2 (existing): store from the job's pickup lineage by name-token match.
    val pickupStores = jobPickups
        .filter { it.storeName != null }
        .map { it.storeName!! }
        .distinctBy { it.lowercase(Locale.ROOT) }
    val resolved = when {
        pickupStores.size == 1 -> pickupStores.single()
        pickupStores.isEmpty() -> active.storeName
        else -> {
            val cand = active.storeName ?: return r
            // Match the dropoff's candidate to the pickup with the most shared brand tokens
            // (the SSOT comparison in [StoreNameMatch]); no match → null, never a wrong store.
            StoreNameMatch.bestMatch(pickupStores, cand)
        }
    }
    return if (resolved == active.storeName) r else r.copy(activeTask = active.copy(storeName = resolved))
}

/**
 * #733/#745 — the number of DISTINCT **activated** dropoff tasks in this job carrying [hash],
 * deduped by taskId, across the completed lineage ([PlatformRegion.recentTasks]), the active drop,
 * and the job's task mirror ([Job.tasks]). A never-activated offer-spawned placeholder is
 * customer-TBD (`customerNameHash == null`), so filtering on a non-null [hash] excludes it — only
 * drops a screen has actually resolved onto count. Used to gate the multi-store default: it may
 * fire only when this is the SOLE activated drop with the hash (else two colliding drops could
 * each grab a wrong lineage store).
 */
private fun activatedDropoffsWithHash(region: PlatformRegion, hash: String): Int {
    val ids = HashSet<String>()
    (region.recentTasks + listOfNotNull(region.activeTask) + region.activeJob?.tasks.orEmpty())
        .filter { it.phase == TaskPhase.DROPOFF && it.customerNameHash == hash }
        .forEach { ids += it.taskId }
    return ids.size
}

/**
 * #733 — resolve a dropoff's store from a set of its lineage pickups. One distinct store →
 * that store; ≥2 → the deterministic multi-store default: the EARLIEST-CONFIRMED pickup's store
 * (fallback earliest-arrived, then earliest-started, then lineage order). All keys are
 * `obs.timestamp`-derived, so replay-stable. Empty set → [fallback] (keep the drop's own
 * candidate — no pickup seen yet).
 */
private fun resolveFromPickupSet(pickups: List<Task>, fallback: String?): String? {
    val withStore = pickups.filter { it.storeName != null }
    val distinct = withStore.map { it.storeName!! }.distinctBy { it.lowercase(Locale.ROOT) }
    return when {
        distinct.isEmpty() -> fallback
        distinct.size == 1 -> distinct.single()
        else -> withStore.minWithOrNull(
            compareBy(
                { it.completedAt ?: Long.MAX_VALUE }, // earliest-confirmed (a confirmed pickup completes)
                { it.arrivedAt ?: Long.MAX_VALUE },   // else earliest-arrived
                { it.startedAt },                     // else earliest-started
            ),
        )?.storeName
    }
}

/**
 * Step 1 of the #503 job-container re-model (additive): mirror the active job's **non-unassigned**
 * task lineage onto [Job.tasks] after every core step — the completed tasks still in
 * [PlatformRegion.recentTasks] plus the active task, EXCLUDING any task marked
 * [Task.unassignedAt] (#752: an abandoned task is a resolved lifecycle fact, `recentTasks`-only —
 * it must never re-enter the outstanding-placeholder mirror, or the abandon's placeholder retire
 * is undone every step and the job can never close). This runs on EVERY step, so the exclusion is
 * an every-step semantic of the mirror, not an abandon-frame special case. Pure derivation from
 * existing region state. Later slices made the list authoritative (resume from it; create dropoff
 * subtasks onto it from the offer).
 */
internal fun PlatformRegionStepper.reconcileJobTasks(region: PlatformRegion): PlatformRegion {
    val job = region.activeJob ?: return region
    // #752 review Fix 2: an UNASSIGNED (abandoned) task is a RESOLVED lifecycle fact — it belongs
    // in `recentTasks` only, NEVER in `job.tasks` (the OUTSTANDING-placeholder mirror). Without
    // this filter, `abandonActiveTask`'s step-2 placeholder retire is immediately undone here (the
    // abandoned task re-enters `job.tasks` via the lineage copy), leaving a permanently
    // unaccountable drop that keeps a multi-drop job from ever closing (job-61 absorption class).
    // Excluding it is model-correct: it is no longer owed, so it must not count as an outstanding
    // dropoff (step 3 close check / `isJobPhysicallyComplete`) nor as a job placeholder.
    val lineage = region.recentTasks.filter { it.jobId == job.jobId && it.unassignedAt == null } +
        listOfNotNull(region.activeTask?.takeIf { it.jobId == job.jobId })
    val lineageIds = lineage.mapTo(HashSet()) { it.taskId }
    // #503 slice 3: preserve pre-created (offer-spawned, not-yet-activated) subtasks — those on
    // the job but not yet in the active/completed lineage — then the lineage. Once an expected
    // subtask is activated its taskId enters the lineage, so it isn't double-listed.
    //
    // #526 FIX9 (sticky lineage): retain EVERY task already on job.tasks that isn't in the
    // current lineage — not just still-open placeholders. On a huge session a displaced,
    // COMPLETED pickup evicted from recentTasks past MAX_RECENT_TASKS would otherwise vanish from
    // job.tasks mid-job, breaking the confirm sweep + resume. The lineage copy wins when present
    // (freshest); an evicted task keeps its last-known job.tasks copy. Replay-deterministic (pure
    // derivation from persisted state), and on a small session this equals the old open-only set
    // because every completed task is still in the lineage.
    val sticky = job.tasks.filter { it.taskId !in lineageIds }
    val jobTasks = sticky + lineage
    return if (job.tasks == jobTasks) region else region.copy(activeJob = job.copy(tasks = jobTasks))
}

/**
 * #736: abandon the active task because the dasher UNASSIGNED the order (a `task:unassigned`
 * frame). Distinct from [retireActiveTask]: an abandon marks the task with [Task.unassignedAt]
 * and **leaves [Task.completedAt] null**. The load-bearing defense against a fabricated
 * `PICKUP_CONFIRMED` is the close-out sweep's `unassignedAt == null` FILTER
 * (`pickupConfirmSweepEffects`), which excludes an abandoned-but-arrived pickup. (An INLINE
 * abandon leaves `completedAt` null, so [isJobPhysicallyComplete] reads the drop as un-accounted,
 * keeping the job OPEN; the CROSS-FRAME retro-mark (#752) instead keeps a grace-stamped
 * `completedAt` and relies on the `unassignedAt` guard that [isJobPhysicallyComplete] and the
 * DeliveryCompletionEffects belt both now carry — either way the drop is never a false complete.
 * Step 2 below removes the drop's own placeholder so a single-order job can still close.) The
 * commit is inline (ungraced): see the call site.
 *
 * Steps (all pure, `[timestamp]`-driven):
 * 1. Move [PlatformRegion.activeTask] → [PlatformRegion.recentTasks] with `unassignedAt` set.
 * 2. Retire the abandoned order's dropoff placeholder(s) from the job's task mirror: a
 *    single-dropoff job removes its one drop; a multi-dropoff job removes only placeholders
 *    whose `customerNameHash` matches the abandoned task's (best-effort — customer-TBD
 *    placeholders carry no hash, the documented same-store-two-orders residual); no hash on a
 *    multi-dropoff job removes none and WARNs (PII-safe: ids/counts only). Fail direction is
 *    absorption (#596 class), never fabrication.
 * 3. If the job then owns no outstanding dropoff placeholder and no other open task → close it
 *    (single-order case: the job closes here, on the confirmation frame).
 * 4. A pending `TASK_RETIRE` is superseded (cleared); a pending `SESSION_END` is preserved;
 *    `pendingModeResume` is untouched (#605 non-interaction — the sheet is Online).
 * 5. Idempotent, with the retro-mark EDGE-GATED (#752 review): the cross-frame retro-mark (step 1)
 *    only runs on the edge INTO `TaskUnassigned` (prev flow ≠ `TaskUnassigned`), so a SECOND
 *    consecutive `task:unassigned` frame does NOT re-select and mark a further task — it re-runs
 *    steps 2/3 best-effort (placeholder retire + close check) and, with neither an active task nor
 *    a retro target, is otherwise a no-op.
 */
internal fun PlatformRegionStepper.abandonActiveTask(
    region: PlatformRegion,
    timestamp: Long,
    prevFlowVal: Flow,
): PlatformRegion {
    val abandoned = region.activeTask
    // Step 4: the abandon supersedes a pending TASK_RETIRE; a SESSION_END is the graced-offline
    // state and must survive.
    val keptPending = region.pendingDestructive?.takeIf { it.kind == DestructiveKind.SESSION_END }

    // Cross-frame retro-mark (#736 review, widened by #752): when the active task is ALREADY gone —
    // the retire grace committed on a PRIOR frame (a help-flow offer/idle intrusion armed
    // TASK_RETIRE and its grace fired before this confirmation frame, stamping `completedAt` on the
    // retired task while the job stayed open) — the authoritative confirmation must retro-mark the
    // task it was actually about. Find the most-recently-retired task of the active job, **ANY
    // phase** (`completedAt` set, `unassignedAt` still null): a grace-retired DROPOFF (retired en
    // route, or arrived-then-retired) is exactly as much an unassign target as a pickup, and the
    // old PICKUP-only filter mis-marked a completed sibling pickup while leaving the grace-retired
    // drop to fabricate a `DELIVERY_COMPLETED` (#752, both defects). `maxByOrNull { completedAt }`
    // picks the most-recently-retired task so a job with both a retired pickup and a later retired
    // dropoff marks the drop (the leg the abandon was about). Set `unassignedAt` WITHOUT touching
    // `completedAt`: nulling a stamped `completedAt` risks disturbing lineage reconciliation, and
    // the sweep filter + the DeliveryCompletionEffects belt + `isJobPhysicallyComplete` all key on
    // `unassignedAt`, so the marker alone suppresses the fabricated completion. Only runs when
    // there is no live task to abandon.
    //
    // #752 review Fix 1: EDGE-GATE the retro-mark on the flow edge. A multi-order job stays open
    // after frame 1's abandon, so a SECOND consecutive `task:unassigned` frame would re-run this
    // selector and walk `unassignedAt` onto the NEXT most-recently-completed task (a legitimately
    // confirmed pickup, or worse a receipt-skipped delivered drop whose pending mint then gets
    // belt-suppressed → real pay lost to unattributed). The retro-mark is a one-shot for the
    // edge INTO `TaskUnassigned`; skip it when the previous flow was already `TaskUnassigned`
    // (steps 2/3 still re-run best-effort, per the idempotency contract).
    //
    // Documented A-B-A residual (#752 re-verification, edge-gate + documentation ruled the right
    // trade): a state-bearing interlude between two confirmation frames — most plausibly an
    // OfferPresented overlay over the confirmation — re-opens the gate, so the second frame can
    // re-run the retro walk onto a further task; symmetrically, a GENUINE second unassign whose
    // frames are separated only by recognize-only screens is swallowed by the gate. Both are
    // never-fielded multi-order shapes; fail direction stays absorption, never fabrication.
    val retroTarget: Task? = if (abandoned == null && prevFlowVal != Flow.TaskUnassigned) {
        region.activeJob?.let { job ->
            region.recentTasks
                .filter {
                    it.jobId == job.jobId &&
                        it.completedAt != null && it.unassignedAt == null
                }
                .maxByOrNull { it.completedAt!! }
        }
    } else {
        null
    }
    val retroTargetId = retroTarget?.taskId

    // Step 1: move the active task to recentTasks, marked unassigned (completedAt stays null); or,
    // when the task already retired on a prior frame, retro-mark it in place.
    val recentTasks = when {
        abandoned != null ->
            (region.recentTasks + abandoned.copy(unassignedAt = timestamp))
                .takeLast(PlatformRegionStepper.MAX_RECENT_TASKS)
        retroTargetId != null ->
            region.recentTasks.map {
                if (it.taskId == retroTargetId) it.copy(unassignedAt = timestamp) else it
            }
        else -> region.recentTasks
    }
    var r = region.copy(
        activeTask = null,
        recentTasks = recentTasks,
        pendingDestructive = keptPending,
    )

    // Step 2: retire the abandoned order's dropoff placeholder(s) from the job's task mirror.
    val job = r.activeJob
    if (job != null) {
        val dropoffs = job.tasks.filter { it.phase == TaskPhase.DROPOFF }
        val abandonedHash = abandoned?.customerNameHash
        val remaining = when {
            dropoffs.size <= 1 ->
                job.tasks.filterNot { it.phase == TaskPhase.DROPOFF }
            // #752 review Fix 2: a CROSS-FRAME retro-mark onto a DROPOFF must retire that drop's
            // own placeholder by taskId (the abandon-keyed arms below can't — `abandoned` is null
            // in the retro shape). Without this the marked drop stays a live placeholder in
            // `job.tasks`, now permanently unaccountable under the `unassignedAt` guard in
            // `isJobPhysicallyComplete`, so the job never closes and the next offer folds into the
            // dead job (job-61 absorption class). Keyed on the exact retro-target taskId — never a
            // customer-hash join that could over-remove a colliding sibling.
            retroTarget?.phase == TaskPhase.DROPOFF ->
                job.tasks.filterNot { it.phase == TaskPhase.DROPOFF && it.taskId == retroTarget.taskId }
            // #736 Fix 4: a DROPOFF-phase abandon retires ITS OWN placeholder by taskId — the
            // abandoned drop IS the exact one to remove, so a customer-hash join here would
            // over-remove a colliding sibling drop (two customers whose normalized keys collapse).
            // The hash join stays only for a PICKUP-phase abandon, where the pickup task carries
            // the order's customer but is not itself a dropoff placeholder, so it must find the
            // matching drop(s) by hash.
            abandoned?.phase == TaskPhase.DROPOFF ->
                job.tasks.filterNot { it.phase == TaskPhase.DROPOFF && it.taskId == abandoned.taskId }
            abandonedHash != null ->
                job.tasks.filterNot { it.phase == TaskPhase.DROPOFF && it.customerNameHash == abandonedHash }
            else -> {
                // Multi-dropoff job, no hash to disambiguate which drop the abandoned order owns —
                // remove none (absorption over fabrication). PII-safe: ids + counts only (P7).
                Timber.tag("StateMachine").w(
                    "#736 unassign: multi-dropoff job %s (%d drops), abandoned task has no customer " +
                        "hash — no placeholder removed; job stays open, self-heals on resume",
                    job.jobId, dropoffs.size,
                )
                job.tasks
            }
        }
        r = r.copy(activeJob = job.copy(tasks = remaining))
    }

    // Step 3: if the job now owns no outstanding dropoff placeholder and no other open task, close it.
    val remainingJob = r.activeJob
    if (remainingJob != null && r.activeTask == null) {
        val hasOutstandingDropoff = remainingJob.tasks.any {
            it.phase == TaskPhase.DROPOFF && it.completedAt == null && it.unassignedAt == null
        }
        if (!hasOutstandingDropoff) {
            r = completeActiveJob(r)
        }
    }
    return r
}

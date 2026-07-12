package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import timber.log.Timber

/**
 * #596 / #749: is [job] *physically complete* — every dropoff delivered, nothing outstanding — so it
 * may close on its next exit signal even when DoorDash skips the post-delivery receipt (the only
 * job-exit the pre-#596 machine had)? Extracted from `PlatformRegionStepper` (past the #237 size
 * ceiling); both consumers (T1 `retireActiveTask`, T2 `consumeAcceptIntoJob`) call this one predicate.
 *
 * Completion truth is computed from [recentTasks] + [justRetired], **never** [Job.tasks] for the
 * *finished* state: the `Job.tasks` mirror re-runs after `stepCore`, so at the moment a retire/accept
 * consults it, its copy of the just-finished drop is stale (`completedAt` still null — amdt #6). The
 * mirror is used only for the *placeholder set* (which dropoffs/pickups the job owns — reliable, since
 * offer-spawned placeholders persist there until resolved+completed) and for a task's OBSERVED
 * customer HASH (a resolved placeholder's stale-completedAt copy still carries the right hash).
 *
 * Two arms, evaluated in order (short-circuit; the coverage arm runs ONLY when the strict arm fails):
 *
 * **1. Strict arm (#596/#615/#752, unchanged).** Complete ⇔ every DROPOFF placeholder the job owns is
 * accounted — its taskId appears in [recentTasks] with `completedAt != null`, or it IS [justRetired] —
 * with no unresolved customer-TBD placeholder outstanding, and at least one dropoff actually finished.
 * A zero-dropoff job never qualifies. Accounted additionally requires ARRIVAL evidence
 * (`arrivedAt != null`, #615 review): a grace-stamped `completedAt` alone is not delivery proof — a
 * drop retired EN ROUTE must never read as delivered. An UNASSIGNED dropoff (#752) is never
 * "delivered": the cross-frame retro-mark stamps `unassignedAt` on grace-retired DROPOFFs, so the
 * `unassignedAt == null` filter here excludes them explicitly (fail direction stays absorption — a
 * genuinely delivered drop keeps `unassignedAt` null and still counts).
 *
 * **2. Per-customer coverage arm (#749).** The strict arm is defeated for the lifetime of a
 * same-customer multi-order job: `JobAcceptFlow` mints one dropoff placeholder per ORDER, but a
 * same-customer stack only ever ACTIVATES one physical drop (every later same-hash frame resumes the
 * one taskId, #498), so the extra TBD placeholder never appears finished → strict is false forever →
 * the #596 T2 guard never fires and the next offer folds into the finished job (the job-61 class).
 *
 * The missing per-order customer knowledge comes from the PICKUP side. When every order sits at a
 * distinct store, pickup placeholders map 1:1 to orders (`preCreatedPickups` is per distinct store,
 * `preCreatedDropoffs` per order — counts equal ⟺ all orders at distinct stores), so the pickups'
 * distinct hash set is EXACTLY the job's customer set; combined with the resume-collapse invariant
 * (at most one dropoff taskId per customer hash), "every customer hash has a finished, arrived drop"
 * ⟺ "every physical drop is done" — placeholder count is then irrelevant. See [perCustomerCoverage].
 *
 * Fail direction is absorption / fail-null everywhere: coverage unproven → strict arm only (today's
 * behavior — same-store multi-order jobs, hash-less GoPuff bin-scan pickups, the unparsed-offer
 * fallback all stay strict); pickup↔drop hash drift → stays open; a live mid-route drop → blocked.
 * The arm never manufactures a completion event — it only permits a close backed by ≥1 finished, ARRIVED
 * drop per known customer; all `DELIVERY_COMPLETED` mint guards stay at the effect belt.
 *
 * Pure: derives exclusively from `Task`/`Job`/`TaskPhase` structure and the machine's own resume
 * invariant — no platform literals, no rule vocabulary (P8). No clock (all times are stamped).
 */
internal fun isJobPhysicallyComplete(
    job: Job,
    recentTasks: List<Task>,
    justRetired: Task?,
): Boolean {
    // ---- Strict arm (#596/#615/#752) — moved verbatim from PlatformRegionStepper ----
    // #752: an UNASSIGNED dropoff is never "delivered" even when a retire grace stamped its
    // `completedAt` (and, in the arrived-then-unassigned shape, its `arrivedAt`). The cross-frame
    // retro-mark now stamps `unassignedAt` on grace-retired DROPOFFs too, so this predicate — which
    // pre-#752 could only ever see completedAt-null unassigned drops — must exclude the marker
    // explicitly, or a retro-marked drop would read as accounted+finished and produce a false
    // job-complete. Fail direction stays safe (absorption): a genuinely delivered drop keeps
    // `unassignedAt` null and still counts.
    val completedDropoffIds = recentTasks
        .filter {
            it.jobId == job.jobId && it.phase == TaskPhase.DROPOFF &&
                it.completedAt != null && it.arrivedAt != null && it.unassignedAt == null
        }
        .mapTo(HashSet()) { it.taskId }
    val retiredDropoffId = justRetired
        ?.takeIf {
            it.jobId == job.jobId && it.phase == TaskPhase.DROPOFF &&
                it.completedAt != null && it.arrivedAt != null && it.unassignedAt == null
        }
        ?.taskId

    // Every dropoff the job owns must be accounted; any outstanding (incl. a customer-TBD
    // placeholder that never resolved) → not complete under the strict arm.
    val allDropoffsAccounted = job.tasks
        .filter { it.phase == TaskPhase.DROPOFF }
        .all { it.taskId in completedDropoffIds || it.taskId == retiredDropoffId }
    // …and at least one dropoff actually finished (guards zero-dropoff jobs).
    val finished = completedDropoffIds.size +
        (if (retiredDropoffId != null && retiredDropoffId !in completedDropoffIds) 1 else 0)
    if (allDropoffsAccounted && finished >= 1) return true

    // ---- Per-customer coverage arm (#749) — evaluated ONLY when the strict arm failed ----
    if (perCustomerCoverage(job, recentTasks, justRetired)) {
        // DEBUG (firehose only, P7): jobId + counts only — no raw store/customer text.
        Timber.tag("StateMachine").d(
            "#749 coverage arm closed job %s: per-customer coverage complete (pickups=%d dropoffs=%d)",
            job.jobId,
            job.tasks.count { it.phase == TaskPhase.PICKUP && it.unassignedAt == null },
            job.tasks.count { it.phase == TaskPhase.DROPOFF && it.unassignedAt == null },
        )
        return true
    }
    return false
}

/**
 * The #749 per-customer completeness arm (see [isJobPhysicallyComplete]). Evidence-gated: it decides
 * complete ONLY when the pickup side proves per-order customer coverage. Reusable by the #736/#752
 * unassign family's own "no outstanding work" check if that fix wants it.
 *
 * Let, all restricted to this job and with UNASSIGNED copies excluded (#752 — an abandoned task is a
 * resolved lifecycle fact: neither owed nor requiring delivery, exactly as `reconcileJobTasks`
 * excludes it from the mirror):
 *  - `P_pick` / `P_drop` = distinct pickup / dropoff placeholder taskIds on [Job.tasks];
 *  - a task's OBSERVED hashes = its non-null `customerNameHash` across the mirror ∪ [recentTasks] ∪
 *    [justRetired] (so a resolved placeholder's stale-completedAt mirror copy still contributes its hash);
 *  - `C` = union of observed hashes of all pickups (the job's customer set, since pickups map 1:1 to
 *    orders under the coverage gate);
 *  - `F` = hashes of FINISHED dropoffs — copies in [recentTasks] ∪ [justRetired] with
 *    `completedAt != null && arrivedAt != null` (#615 arrival gate);
 *  - `A` = ALL observed dropoff hashes (mirror ∪ recent ∪ retired — no activated drop outstanding).
 *
 * Complete ⇔ `|P_pick| ≥ 1 ∧ |P_pick| == |P_drop| ∧ every pickup hashed AND CONFIRMED ∧ C ⊆ F ∧
 * A ⊆ F ∧ F ≠ ∅`. Every gate fails toward absorption (stay open), never toward a fabricated close.
 *
 * Every pickup must be CONFIRMED (`completedAt != null` on some observed copy), not merely hashed
 * (#759 review F1): a pickup stamps its hash at activation/arrival — minutes before pickup-confirm —
 * so a hashed-but-unconfirmed pickup means its order's items are still IN THE STORE, physically owed
 * regardless of what the dropoff side shows. Without this gate a deliver-before-last-pickup ordering
 * (a same-customer distinct-store add-on: drop 1 delivered, pickup 2 arrived-but-not-picked-up)
 * satisfies every other gate (`C ⊆ F` trivially — same hash) and reads complete mid-route.
 */
private fun perCustomerCoverage(
    job: Job,
    recentTasks: List<Task>,
    justRetired: Task?,
): Boolean {
    // Non-unassigned copies of this job's tasks across the three pools (#752 exclusion).
    val mirror = job.tasks.filter { it.unassignedAt == null }
    val jobRecent = recentTasks.filter { it.jobId == job.jobId && it.unassignedAt == null }
    val retired = justRetired?.takeIf { it.jobId == job.jobId && it.unassignedAt == null }

    // Placeholder sets from the (non-unassigned) mirror.
    val pickupIds = mirror.filter { it.phase == TaskPhase.PICKUP }.mapTo(HashSet()) { it.taskId }
    val dropoffIds = mirror.filter { it.phase == TaskPhase.DROPOFF }.mapTo(HashSet()) { it.taskId }

    // Coverage gate: ≥1 pickup, and pickups map 1:1 to dropoffs — i.e. every order at a distinct
    // store, so the pickups' hash set IS the job's customer set. A same-store multi-order stack
    // (|P_pick| < |P_drop|) or the unparsed-offer / GoPuff-bin-scan shape fails here → strict-only.
    if (pickupIds.isEmpty() || pickupIds.size != dropoffIds.size) return false

    val allCopies = mirror + jobRecent + listOfNotNull(retired)
    fun observedHashes(taskId: String): Set<String> =
        allCopies.asSequence().filter { it.taskId == taskId }.mapNotNull { it.customerNameHash }.toSet()

    // Every pickup placeholder must have resolved a customer hash (a hash-less GoPuff bin-scan pickup
    // proves no customer coverage → strict-only) AND be CONFIRMED (#759 review F1: a hash lands at
    // activation/arrival, before pickup-confirm — an unconfirmed pickup's order is still in the store,
    // physically owed, so it can never serve as completion evidence).
    fun isConfirmed(taskId: String): Boolean =
        allCopies.any { it.taskId == taskId && it.completedAt != null }
    if (pickupIds.any { observedHashes(it).isEmpty() || !isConfirmed(it) }) return false

    // C = the job's customer set, from the pickups (1:1 with orders under the gate above).
    val customerHashes: Set<String> = pickupIds.flatMapTo(HashSet()) { observedHashes(it) }

    // F = customers with a FINISHED, ARRIVED drop (#615 gate); mirror never proves finished.
    val finishedDropHashes: Set<String> = (jobRecent + listOfNotNull(retired))
        .asSequence()
        .filter { it.phase == TaskPhase.DROPOFF && it.completedAt != null && it.arrivedAt != null }
        .mapNotNull { it.customerNameHash }
        .toSet()
    if (finishedDropHashes.isEmpty()) return false // F ≠ ∅

    // A = every observed dropoff hash — a live mid-route drop's hash sits here but not in F → blocked.
    val observedDropHashes: Set<String> = allCopies.asSequence()
        .filter { it.phase == TaskPhase.DROPOFF }
        .mapNotNull { it.customerNameHash }
        .toSet()

    // C ⊆ F (every known customer delivered) ∧ A ⊆ F (no activated drop outstanding).
    return finishedDropHashes.containsAll(customerHashes) && finishedDropHashes.containsAll(observedDropHashes)
}

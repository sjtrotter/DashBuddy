package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.di.DefaultDispatcher
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.domain.model.state.TimeoutEvent
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.core.pipeline.PipelineV2
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StateManagerV2 @Inject constructor(
    private val pipeline: PipelineV2,
    private val engine: EffectExecutor,
    private val stateMachine: StateMachine,
    private val journal: ObservationJournal,
    private val snapshots: SnapshotStore,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val scope = CoroutineScope(defaultDispatcher + SupervisorJob())

    /**
     * The wall clock, and the ONLY one in this class's recovery path (#1054).
     *
     * [rearmRecoveredTimers] has to answer "how long is left on this deadline", which is a wall-clock
     * question — a restored deadline is an absolute instant and the process has been dead for an
     * unknown interval. That read belongs HERE, at the effect boundary, and nowhere below it: the
     * steppers stay `obs.timestamp`-driven so a replay is reproducible (Principle 1), which is why
     * [pendingDeadlineTimers] states deadlines and computes no durations.
     *
     * A `var` with a default rather than a constructor parameter because the constructor is Hilt's
     * (`@Inject`), and Dagger has no binding for a `() -> Long` — a defaulted parameter would simply
     * fail to build. `internal`, so only this module's tests can move it.
     */
    internal var clock: () -> Long = System::currentTimeMillis

    // UI input stream (clicks, debug buttons)
    private val uiInputChannel = Channel<StateEvent>(Channel.UNLIMITED)

    private val _state = MutableStateFlow(AppState())
    val state = _state.asStateFlow()

    /**
     * #438 B5 (item 9): armed by [restoreState] when a snapshot restore recovered a NON-fresh
     * state; consumed on the first LIVE observation to reconcile the odometer. Odometer effects are
     * recovery-suppressed externals ([SideEffectEngine] `isExternalEffect`), so after a crash GPS is
     * dead — and a level-*crossing* Resume won't re-fire (the level is already "moving" at restore →
     * no crossing → GPS stays dead until a full pause→resume cycle, losing IRS miles for the whole
     * post-crash leg). The reconciliation re-establishes tracking for a still-live session directly,
     * at the edge (never inside a pure reducer).
     */
    private var recoveryReconcilePending = false

    /**
     * #1052 round 4: the recovery checkpoint has not landed yet, and every LIVE observation retries
     * it until it does.
     *
     * [checkpointRecovery] writes the park-hygiene-cleaned state as the next replay base, retries
     * once, and then reports at ERROR — but reporting is not repairing. Two failed inserts leave
     * the PRE-hygiene snapshot on disk as the replay base while the journal keeps growing, so the
     * next restart replays the pre-crash park over a tail that now contains a frame past its
     * deadline and commits a mid-spin figure: exactly the hole round 2 opened the checkpoint to
     * close, reopened by a transient DB failure at the one moment it matters.
     *
     * The retry costs nothing and is bounded by its own success. The journal and the snapshot live
     * in the SAME database, so a journal append that persists is direct evidence that the
     * checkpoint can land too; retrying it on the live path is therefore not hope, it is following
     * the write that just worked. Cleared on the first successful write, and simply left standing
     * on a failure — one DEBUG line per attempt at most, never an ERROR per frame.
     */
    private var recoveryCheckpointPending = false

    fun initialize() {
        Timber.i("Initializing V2 State Machine (multi-region)...")
        journal.start(scope, ioDispatcher)
        scope.launch {
            // Collect BEFORE restoring (#352): the pipeline flows are hot and don't
            // replay, so events arriving during recovery used to be silently lost.
            // They buffer here and process — in arrival order — once restore is done.
            val buffer = Channel<StateEvent>(Channel.UNLIMITED)
            scope.launch {
                merge(
                    pipeline.events,
                    engine.events,
                    uiInputChannel.receiveAsFlow(),
                ).collect { buffer.send(it) }
            }

            restoreState()

            for (stateEvent in buffer) {
                Timber.d("PROCESSING: ${stateEvent::class.simpleName}")
                processEvent(stateEvent)
            }
        }
    }

    fun dispatch(stateEvent: StateEvent) {
        uiInputChannel.trySend(stateEvent)
    }

    private suspend fun processEvent(stateEvent: StateEvent) {
        val obs = toObservation(stateEvent) ?: return

        val currentState = _state.value

        // #438 B5: on the FIRST live observation after a crash recovery, re-establish the odometer
        // for a still-live session (recovery suppressed the Start/Resume that normally would run).
        // Emitted at the edge, keyed off the restored (pre-step) state, BEFORE the obs steps — the
        // effects are idempotent (startTracking/stopTracking no-op if already in that state), so any
        // crossing the incoming obs itself produces is harmless on top.
        if (recoveryReconcilePending) {
            recoveryReconcilePending = false
            reconcileOdometerAfterRecovery(currentState)
        }

        val transition = stateMachine.step(currentState, obs)

        // Update state. `correlationVersion` increments unconditionally in
        // StateMachine.step (#439) — newState is NEVER structurally equal to
        // currentState, so the old `!=` guard here was always true; removed.
        _state.value = transition.newState

        // Persist observation to the append-only log (ordered single writer, #352)
        journal.append(obs, transition.newState)

        // #1052 round 4: a recovery checkpoint that never landed is retried here, on the first
        // live observation after it and every one after that until it does. Ordered AFTER the
        // journal append so the checkpoint can only ever be at or ahead of the log it is the base
        // for, and written at THIS observation's state so the retry keeps closing the gap rather
        // than re-offering a stale one. Cheap by construction: the flag is false on every normal
        // recovery and always false when nothing was recovered at all.
        if (recoveryCheckpointPending) {
            val landed = snapshots.checkpoint(transition.newState)
            if (landed) recoveryCheckpointPending = false
            Timber.tag("StateMachine").d(
                "Recovery checkpoint retry at cv=%d: %s",
                transition.newState.correlationVersion,
                if (landed) "landed" else "still pending",
            )
        }

        // Periodic + major-transition snapshots
        snapshots.maybeSnapshot(scope, ioDispatcher, currentState, transition.newState)

        // Low-cadence journal pruning (#364): the observation log grew
        // unbounded — pruneOlderThan had zero callers. Retention comfortably
        // exceeds snapshot retention, so replay-since-snapshot stays intact.
        if (transition.newState.correlationVersion % JOURNAL_PRUNE_EVERY == 0L) {
            scope.launch(ioDispatcher) {
                journal.pruneOlderThan(System.currentTimeMillis() - JOURNAL_RETENTION_MS)
            }
        }

        // Emit effects — the engine serializes execution in this order (#351).
        transition.effects.forEach { effect ->
            engine.process(effect, correlationVersion = transition.newState.correlationVersion)
        }
    }

    /**
     * #438 B5: reconcile the odometer to the [restored] state after a crash. If any session is live
     * (`activeSessionCount > 0`), re-emit [AppEffect.StartOdometer] (re-establishes GPS tracking +
     * the ongoing notification; the per-session anchor persisted across the crash, so no reset), and
     * — if EVERY live region is parked ([OdometerArbiter.allLiveStationary]) — a [AppEffect.PauseOdometer]
     * so GPS matches the restored "stationary at a drop" level. A moving restore leaves GPS running.
     * Emitted on the LIVE path (`recovering = false`), so it actually fires (recovery suppresses it).
     */
    private fun reconcileOdometerAfterRecovery(restored: AppState) {
        val effects = OdometerArbiter.recoveryReconciliation(restored)
        if (effects.isEmpty()) return
        Timber.i("Recovery odometer reconciliation: %s", effects.map { it::class.simpleName })
        effects.forEach { engine.process(it, correlationVersion = restored.correlationVersion) }
    }

    companion object {
        /** Prune the observation journal every N accepted observations. */
        private const val JOURNAL_PRUNE_EVERY = 500L

        /** Keep observations for 48h — 2× snapshot retention (#364). */
        private const val JOURNAL_RETENTION_MS = 48 * 60 * 60 * 1000L
    }

    // ── Crash Recovery ──────────────────────────────────────────────────

    private suspend fun restoreState() {
        try {
            val restored = snapshots.restoreLatest()
            if (restored == null) {
                Timber.i("No usable snapshot — starting fresh")
                _state.value = AppState()
                return
            }

            // #1029: a parked running-total read is evidence from BEFORE the crash, and nothing
            // after the restore re-arms its `SESSION_PAY_SETTLE` wake timer — so a restored park
            // would either sit forever or, on the first frame past its deadline, commit a figure
            // whose surface has been gone since the process died. It is dropped on BOTH restore
            // paths — but at the LIVE boundary, not here (#1052): the tail is a faithful replay of
            // what already happened, so it must run against the snapshot exactly as recorded (a
            // park whose commit timer is IN the tail committed live and must commit again;
            // scrubbing the base changes the replayed result). Dropping from the FINAL state
            // instead also discards a park a tail frame re-created — whose `ScheduleTimeout` the
            // recovery fold does execute (it is not an external effect), which would otherwise wake
            // pre-crash evidence with no fresh screen behind it. The recovery-scheduled timer then
            // finds no park and no-ops (`handleTimeout`'s `else -> prev`; lazy expiry has nothing
            // to expire). Fail-null beats fail-wrong (#745).
            //
            // And the drop is CHECKPOINTED on both paths below (#1052 round 2): installing the
            // cleaned state in memory is not enough, because the snapshot on disk still carries the
            // park. A second restart — with no ordinary snapshot in between, which is the normal
            // case since neither the cadence nor a major transition need fire — would replay that
            // same snapshot plus a tail that has GROWN with live frames, one of which lands past
            // the park's deadline and commits it. `SnapshotStore.checkpoint` writes the cleaned
            // state at the restored correlation version (snapshot rows REPLACE by that key), making
            // it the next replay base.
            val base = restored.state

            // Tail-replay observations after the snapshot, in cv order (#352)
            val tail = journal.tailAfter(restored.correlationVersion)
            if (tail.isEmpty()) {
                Timber.i("Restored from snapshot at cv=%d, no tail", restored.correlationVersion)
                val cleaned = base.droppingSessionPayParks()
                // #1052: the drop is only DURABLE if the cleaned state is the next replay base.
                checkpointRecovery(cleaned)
                // #1054: a commitment in flight is re-armed (the park above is not — it is stale
                // evidence). Emitted BEFORE the state installs, so no live observation can be
                // interleaved between the state the timers describe and the timers themselves.
                rearmRecoveredTimers(cleaned)
                _state.value = cleaned
                // #438 B5: recovered a non-fresh state → reconcile the odometer on the first live obs.
                recoveryReconcilePending = true
                return
            }

            Timber.i(
                "Replaying %d observations after snapshot cv=%d",
                tail.size, restored.correlationVersion,
            )

            val finalState = tail.fold(base) { acc, row ->
                val transition = stateMachine.step(acc, row.observation)
                // #1052 round 3: stamp the row's TRUE correlation version. `StateMachine.step`
                // numbers its result `prev + 1`, which matches the journal only while the journal
                // is gap-free — and `ObservationJournal.append` logs and DROPS a failed insert, so
                // gaps are real. The undercount used to be merely cosmetic; since the recovery
                // checkpoint (round 2) it becomes the next replay BASE, so a gapped recovery would
                // persist a boundary BEHIND rows it had already consumed and the next restart
                // would re-apply them — a receipt's pay accumulating twice. Effects are processed
                // with the same true version so their idempotency keys match what ran live.
                val stamped = transition.newState.copy(
                    correlationVersion = row.correlationVersion,
                )
                // Process effects in recovery mode (external suppressed, keyed deduped)
                transition.effects.forEach { effect ->
                    engine.process(
                        effect,
                        recovering = true,
                        correlationVersion = row.correlationVersion,
                    )
                }
                stamped
            }

            val cleaned = finalState.droppingSessionPayParks()
            // #1052: same checkpoint on the tail path, at the FINAL correlation version — the tail
            // it replayed stays in the journal but is now behind the base, so the next restart
            // starts from the cleaned state instead of replaying the pre-hygiene park again.
            checkpointRecovery(cleaned)
            // #1054: same re-arm on the tail path. The tail's OWN `ScheduleTimeout`s were armed
            // against the replayed frames' timestamps (so they fire late by the whole replay lag);
            // this one is based on the real clock and, because `SideEffectEngine.scheduleTimer`
            // REPLACES by (type, platform), simply supersedes them.
            rearmRecoveredTimers(cleaned)
            _state.value = cleaned
            // #438 B5: recovered a non-fresh state → reconcile the odometer on the first live obs.
            recoveryReconcilePending = true
            Timber.i("Recovery complete — state at cv=%d", finalState.correlationVersion)
        } catch (e: Exception) {
            Timber.e(e, "State recovery failed — starting fresh")
            _state.value = AppState()
        }
    }

    /**
     * Re-arm the wake timer of every deadline-bearing pending the recovery restored (#1054).
     *
     * `restoreState` installs `pendingDestructive` / `pendingModeResume` from the snapshot and the
     * tail fold emits a `ScheduleTimeout` only where the TAIL itself moved a deadline — so an empty
     * or deadline-neutral tail leaves both graces live with no timer behind them, and a tail that
     * did arm one armed it against a replayed frame's timestamp (late by the whole replay lag). A
     * `SESSION_END` grace from a dash-summary snapshot then waits for the next admitted
     * observation, which an offline, backgrounded dash need never produce.
     *
     * The complement of the park drop, not a contradiction of it: a park is stale evidence and is
     * dropped, a grace is a commitment already in flight and is re-armed. [pendingDeadlineTimers]
     * is the one owner of that distinction.
     *
     * Emitted on the LIVE path (`recovering = false`) — recovery mode suppresses external effects,
     * and this timer's whole purpose is to fire for real. `SideEffectEngine.scheduleTimer` REPLACES
     * by (type, platform), so a timer the tail already armed is superseded by this correctly-based
     * one rather than duplicated. A deadline already in the past arms the 1 ms floor and its fire
     * lands with `obs.timestamp >= deadline`, which the stepper's lazy expiry commits (#1054 part
     * 1). One INFO line, counts only (principle 7).
     */
    private fun rearmRecoveredTimers(restored: AppState) {
        val pendings = restored.pendingDeadlineTimers()
        if (pendings.isEmpty()) return
        val nowMs = clock()
        Timber.tag("StateMachine").i("Recovery re-armed %d grace timers", pendings.size)
        pendings.forEach { pending ->
            engine.process(
                AppEffect.ScheduleTimeout(
                    durationMs = (pending.deadline - nowMs).coerceAtLeast(1L),
                    type = pending.type,
                    platform = pending.platform,
                ),
                recovering = false,
                correlationVersion = restored.correlationVersion,
            )
        }
    }

    /**
     * Persist the recovery checkpoint, retrying ONCE, and fail LOUD rather than silently (#1052
     * round 3).
     *
     * `SnapshotStore.write` swallows every `Throwable` by design (a snapshot is a cache and must
     * never take the process down), which is right for the periodic cadence — the next one is five
     * observations away — and wrong here: this write is what makes the park hygiene durable, so a
     * swallowed failure silently reopens the double-recovery hole the checkpoint exists to close.
     * ERROR is the level, per principle 7: a persistence failure is lost durability. The message
     * carries no PII — a rule id would be the most it could ever carry, and it carries none.
     *
     * It does not block or throw: refusing to install a recovered state because the DB is failing
     * would trade a bounded, stated risk for total sensing loss. One retry covers the transient
     * (a locked DB, a momentary IO failure); a second failure is a real fault worth reporting —
     * and, since round 4, worth REPAIRING: it arms [recoveryCheckpointPending], and every live
     * observation retries the write until it lands. Reporting a lost checkpoint at ERROR still
     * left the pre-hygiene snapshot standing as the next replay base.
     */
    private suspend fun checkpointRecovery(state: AppState) {
        if (snapshots.checkpoint(state)) return
        if (snapshots.checkpoint(state)) return
        recoveryCheckpointPending = true
        Timber.tag("StateMachine").e(
            "Recovery checkpoint failed twice — the cleaned state is NOT durable; retrying on " +
                "every live observation until it lands",
        )
    }

    // ── Legacy Bridge ───────────────────────────────────────────────────

    /**
     * Convert legacy StateEvent types to Observation.
     * Pipeline events are already Observations. Engine events (timeouts,
     * evaluations) need bridging until SideEffectEngine is updated.
     */
    private fun toObservation(event: StateEvent): Observation? {
        if (event is Observation) return event

        return when (event) {
            is TimeoutEvent -> Observation.Timeout(
                timestamp = event.timestamp,
                type = event.type,
                targetPlatform = event.platform,
                payload = event.payload,
            )

            else -> {
                Timber.w("Unhandled StateEvent type: ${event::class.simpleName}")
                null
            }
        }
    }

}

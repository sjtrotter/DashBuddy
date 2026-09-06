package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.di.DefaultDispatcher
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.domain.model.state.TimeoutEvent
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.core.pipeline.PipelineV2
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
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
            // #1054 round 4: launching the collector is not the same as it being SUBSCRIBED.
            // `SideEffectEngine._events` is a `MutableSharedFlow(replay = 0)`, so anything emitted
            // before the merge actually subscribes goes to zero collectors and is DROPPED —
            // silently, with no log line and invisible to a recording executor. The recovery
            // re-arm is exactly the emitter at risk: a grace whose window already elapsed fires at
            // the 1 ms floor, and if `restoreState` does not happen to suspend long enough on the
            // DAOs the fire races the subscription and the grace is stranded again, which is the
            // whole bug this issue exists to close. So wait for it.
            // The engine's flow is the one at risk: `rearmRecoveredTimers` emits into it and a
            // fire can come straight back. `onSubscription` runs once THIS collector is registered
            // with that `SharedFlow` — the real guarantee, since a launched coroutine is not yet a
            // subscribed one — and awaiting it costs nothing: the merge subscribes on its first
            // dispatch.
            val collectorReady = CompletableDeferred<Unit>()
            scope.launch {
                merge(
                    pipeline.events,
                    engine.events.onSubscription { collectorReady.complete(Unit) },
                    uiInputChannel.receiveAsFlow(),
                ).collect { buffer.send(it) }
            }
            collectorReady.await()

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

            // Both exits below end in `recoveryHygiene(nowMs)` → [finishRestore]: stale EVIDENCE —
            // the #1029 parked running-total read and the #605 graced resume — is dropped, while the
            // destructive grace (a decision in flight) is kept, re-based to serve its REMAINING
            // window live, and re-armed alongside the pause-safety net.
            // `AppState.recoveryHygiene` states the rule and why each pending falls where it does;
            // [finishRestore] states why the checkpoint/reconcile/install order is what it is.
            //
            // What belongs HERE is only WHERE the hygiene runs: at the LIVE boundary, on the FINAL
            // state, never on the snapshot the tail replays from (#1052). The tail is a faithful
            // replay of what already happened, so it must run against the snapshot exactly as
            // recorded — a park whose commit timer is IN the tail committed live and must commit
            // again, and scrubbing the base would replay a different history. Scrubbing the final
            // state instead also covers a pending a TAIL frame re-created, whose `ScheduleTimeout`
            // the recovery fold really does execute (it is not an external effect); that timer then
            // finds nothing and no-ops (`handleTimeout`'s `else -> prev`, lazy expiry with nothing
            // to expire). Fail-null beats fail-wrong (#745).
            val base = restored.state

            // Tail-replay observations after the snapshot, in cv order (#352)
            val tail = journal.tailAfter(restored.correlationVersion)
            if (tail.isEmpty()) {
                Timber.i("Restored from snapshot at cv=%d, no tail", restored.correlationVersion)
                finishRestore(base)
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

            finishRestore(finalState)
            Timber.i("Recovery complete — state at cv=%d", finalState.correlationVersion)
        } catch (e: Exception) {
            Timber.e(e, "State recovery failed — starting fresh")
            _state.value = AppState()
        }
    }

    /**
     * The tail both [restoreState] exits share (#1054 round 3) — order-constrained, so it has one
     * owner rather than two copies that can drift apart.
     *
     * The hygiene runs **twice**, and that is the content of the method (#1054 round 5):
     *
     * 1. **`recoveryHygiene(now0)` → checkpoint.** Dropping stale evidence and re-basing the
     *    destructive grace is only DURABLE if the cleaned state is the next replay base (#1052):
     *    the snapshot on disk still carries what was dropped, and a second restart with no ordinary
     *    snapshot in between (neither the cadence nor a major transition need fire) would replay it
     *    over a journal tail that has since grown.
     * 2. **`recoveryHygiene(nowInstall)` → arm → install.** The re-base is a fixed point
     *    ([AppState.recoveryHygiene]), so applying it again costs nothing but slides the deadline
     *    forward by exactly the checkpoint latency. Without it the served window starts at a clock
     *    read taken BEFORE the snapshot load, the tail replay and the checkpoint write, and all of
     *    that latency is subtracted from the window #1054 exists to give back — a 2.5 s summary
     *    grace restored through a 4 s recovery was already overdue on arrival, so the engine fired
     *    at the 1 ms floor and a contradicting task frame 50 ms later found the session already
     *    ended. The checkpointed deadline therefore lags the installed one, deliberately: nothing
     *    depends on their being equal, because a further restart re-bases from the same
     *    `servedFrom` either way.
     *
     * Arming before `_state.value` means no live observation can be interleaved between the state
     * the timers describe and the timers themselves. Then the #438 B5 odometer reconciliation is
     * armed for the first live observation: recovery suppresses external effects, so GPS is dead
     * until something re-establishes it.
     *
     * No cancels here since round 5 — `SideEffectEngine` skips a [TimeoutType.REGION_TIMERS] arm
     * while `recovering == true`, so the replay never armed one to cancel.
     */
    private suspend fun finishRestore(restored: AppState) {
        checkpointRecovery(restored.recoveryHygiene(System.currentTimeMillis()))
        val cleaned = restored.recoveryHygiene(System.currentTimeMillis())
        rearmRecoveredTimers(cleaned)
        _state.value = cleaned
        recoveryReconcilePending = true
    }

    /**
     * Re-arm the wake timers of what the recovery kept (#1054) — the destructive grace, at the
     * deadline `recoveryHygiene` re-based so it serves its remaining window live, and the
     * pause-safety net at the platform's own countdown. [pendingDeadlineTimers] owns that list and
     * names everything it deliberately omits.
     *
     * Without this a restored pending has nothing behind it: `restoreState` installs it straight
     * from the snapshot, and the tail fold emits a `ScheduleTimeout` only where the TAIL itself
     * moved a deadline — so an empty or deadline-neutral tail leaves it live with no wake at all. A
     * `SESSION_END` grace from a dash-summary snapshot then waits for the next admitted observation,
     * which an offline, backgrounded dash need never produce; a restore into Paused had no timer of
     * ANY kind before round 4 made the safety deadline state.
     *
     * The effect carries the ABSOLUTE deadline and a `GraceWake` identity, and nothing else:
     * `durationMs` is the 1 ms floor because the engine ignores it whenever `deadlineMs` is set.
     * That matters here more than anywhere — the engine is a queue, and this emission lands behind
     * every effect the tail replay just produced, so a duration computed at THIS moment could start
     * its wait an arbitrary interval later and run its full length late, in exactly the case where
     * a recovered grace is most overdue. `SideEffectEngine.scheduleTimer` REPLACES by
     * (type, platform), so a timer the tail already armed is superseded rather than duplicated. One
     * INFO line, counts only (principle 7).
     */
    private fun rearmRecoveredTimers(restored: AppState) {
        val pendings = restored.pendingDeadlineTimers()
        if (pendings.isEmpty()) return
        Timber.tag("StateMachine").i("Recovery re-armed %d grace timers", pendings.size)
        pendings.forEach { pending ->
            engine.process(
                AppEffect.ScheduleTimeout(
                    // The floor, not a computed remainder (#1054 round 3): `deadlineMs` is set, so
                    // the engine computes the real wait at scheduling time and this value is never
                    // read. Computing one here would be dead code pretending to be a fallback.
                    durationMs = 1L,
                    type = pending.type,
                    platform = pending.platform,
                    payload = ObservationPayload.GraceWake(pending.wake.wakeId),
                    deadlineMs = pending.wake.deadline,
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

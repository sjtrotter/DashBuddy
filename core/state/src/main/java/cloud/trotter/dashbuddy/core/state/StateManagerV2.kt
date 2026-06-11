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

    // UI input stream (clicks, debug buttons)
    private val uiInputChannel = Channel<StateEvent>(Channel.UNLIMITED)

    private val _state = MutableStateFlow(AppState())
    val state = _state.asStateFlow()

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

    private fun processEvent(stateEvent: StateEvent) {
        val obs = toObservation(stateEvent) ?: return

        val currentState = _state.value
        val transition = stateMachine.step(currentState, obs)

        // Update state
        if (transition.newState != currentState) {
            _state.value = transition.newState
        }

        // Persist observation to the append-only log (ordered single writer, #352)
        journal.append(obs, transition.newState)

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

            // Tail-replay observations after the snapshot, in cv order (#352)
            val tail = journal.tailAfter(restored.correlationVersion)
            if (tail.isEmpty()) {
                Timber.i("Restored from snapshot at cv=%d, no tail", restored.correlationVersion)
                _state.value = restored.state
                return
            }

            Timber.i(
                "Replaying %d observations after snapshot cv=%d",
                tail.size, restored.correlationVersion,
            )

            val finalState = tail.fold(restored.state) { acc, obs ->
                val transition = stateMachine.step(acc, obs)
                // Process effects in recovery mode (external suppressed, keyed deduped)
                transition.effects.forEach { effect ->
                    engine.process(
                        effect,
                        recovering = true,
                        correlationVersion = transition.newState.correlationVersion,
                    )
                }
                transition.newState
            }

            _state.value = finalState
            Timber.i("Recovery complete — state at cv=%d", finalState.correlationVersion)
        } catch (e: Exception) {
            Timber.e(e, "State recovery failed — starting fresh")
            _state.value = AppState()
        }
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

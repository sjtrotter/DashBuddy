package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.core.database.observation.ObservationDao
import cloud.trotter.dashbuddy.core.database.observation.ObservationEntity
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotDao
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotEntity
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.state.OfferEvaluationEvent
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.domain.model.state.TimeoutEvent
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.core.pipeline.PipelineV2
import cloud.trotter.dashbuddy.state.effects.SideEffectEngine
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val engine: SideEffectEngine,
    private val stateMachine: StateMachine,
    private val observationDao: ObservationDao,
    private val snapshotDao: AppStateSnapshotDao,
) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapterFactory(parsedFieldsAdapterFactory())
        .create()

    // UI input stream (clicks, debug buttons)
    private val uiInputChannel = Channel<StateEvent>(Channel.UNLIMITED)

    private val _state = MutableStateFlow(AppState())
    val state = _state.asStateFlow()

    companion object {
        /** Write a state snapshot every N accepted observations. */
        const val SNAPSHOT_INTERVAL = 5

        /** Prune state snapshots older than this (24 hours). */
        const val SNAPSHOT_RETENTION_MS = 24 * 60 * 60 * 1000L
    }

    fun initialize() {
        Timber.i("Initializing V2 State Machine (multi-region)...")
        scope.launch {
            restoreState()
            startProcessor()
        }
    }

    fun dispatch(stateEvent: StateEvent) {
        uiInputChannel.trySend(stateEvent)
    }

    private fun startProcessor() {
        scope.launch {
            Timber.d("Connecting all event streams...")

            merge(
                pipeline.events,
                engine.events,
                uiInputChannel.receiveAsFlow()
            )
                .collect { stateEvent ->
                    Timber.d("PROCESSING: ${stateEvent::class.simpleName}")
                    processEvent(stateEvent)
                }
        }
    }

    private fun processEvent(stateEvent: StateEvent) {
        val obs = toObservation(stateEvent) ?: return

        val currentState = _state.value
        val transition = stateMachine.step(currentState, obs)

        // Update state
        if (transition.newState != currentState) {
            _state.value = transition.newState
        }

        // Persist observation to append-only log
        persistObservation(obs, transition.newState)

        // Periodic + major-transition snapshots
        maybeSnapshot(transition.newState, currentState)

        // Emit effects
        transition.effects.forEach { effect ->
            engine.process(effect, scope)
        }
    }

    // ── Observation Persistence ─────────────────────────────────────────

    private fun persistObservation(obs: Observation, state: AppState) {
        scope.launch(Dispatchers.IO) {
            try {
                observationDao.insert(obs.toEntity(state))
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist observation")
            }
        }
    }

    private fun Observation.toEntity(state: AppState): ObservationEntity {
        val flowObs = this as? Observation.FlowObservation
        val session = state.regions.platforms[platform]?.session
        return ObservationEntity(
            occurredAt = timestamp,
            sessionId = session?.sessionId,
            pipelineId = pipelineIdOf(this),
            ruleId = ruleId,
            platform = platform.name,
            flow = flowObs?.flow?.name,
            modeHint = flowObs?.modeHint?.name,
            parsedJson = if (flowObs != null) gson.toJson(flowObs.parsed, ParsedFields::class.java) else "{}",
            captureId = captureId,
            metadataJson = gson.toJson(metadata),
            correlationVersion = state.correlationVersion,
            timeoutType = (this as? Observation.Timeout)?.type?.name,
        )
    }

    private fun pipelineIdOf(obs: Observation): String = when (obs) {
        is Observation.Screen -> "accessibility.window"
        is Observation.Click -> "accessibility.click"
        is Observation.Notification -> "notification"
        is Observation.Timeout -> "internal.timeout"
        is Observation.UiInput -> "internal.ui"
        is Observation.Loopback -> "internal.loopback"
    }

    // ── Snapshots ───────────────────────────────────────────────────────

    private fun maybeSnapshot(next: AppState, prev: AppState) {
        val shouldSnapshot =
            next.correlationVersion % SNAPSHOT_INTERVAL == 0L ||
                    isMajorTransition(prev, next)

        if (!shouldSnapshot) return

        scope.launch(Dispatchers.IO) {
            try {
                val activeSession = next.regions.platforms.values
                    .maxByOrNull { it.lastObservedAt }?.session
                snapshotDao.insert(
                    AppStateSnapshotEntity(
                        correlationVersion = next.correlationVersion,
                        capturedAt = System.currentTimeMillis(),
                        sessionId = activeSession?.sessionId,
                        stateJson = gson.toJson(next),
                    )
                )
                // Prune snapshots older than 24h
                snapshotDao.pruneOlderThan(System.currentTimeMillis() - SNAPSHOT_RETENTION_MS)
            } catch (e: Exception) {
                Timber.e(e, "Failed to write state snapshot")
            }
        }
    }

    private fun isMajorTransition(prev: AppState, next: AppState): Boolean {
        val allPlatforms = (prev.regions.platforms.keys + next.regions.platforms.keys)
        for (p in allPlatforms) {
            val prevRegion = prev.regions.platforms[p]
            val nextRegion = next.regions.platforms[p]

            // Session start/end
            if (prevRegion?.session?.sessionId != nextRegion?.session?.sessionId) return true

            // Job start/end
            if (prevRegion?.activeJob?.jobId != nextRegion?.activeJob?.jobId) return true
        }

        // Flow transitions that mark lifecycle boundaries
        val prevFlow = prev.regions.flow.flow
        val nextFlow = next.regions.flow.flow
        if (prevFlow != nextFlow) {
            val majorFlows = setOf(
                Flow.OfferPresented,
                Flow.SessionEnded,
            )
            if (nextFlow in majorFlows || prevFlow in majorFlows) return true
        }

        return false
    }

    // ── Crash Recovery ──────────────────────────────────────────────────

    private suspend fun restoreState() {
        try {
            val snapshot = snapshotDao.latest()
            if (snapshot == null) {
                Timber.i("No snapshot found — starting fresh")
                _state.value = AppState()
                return
            }

            // Restore from snapshot
            val restoredState = try {
                gson.fromJson(snapshot.stateJson, AppState::class.java)
            } catch (e: Exception) {
                Timber.w(e, "Failed to deserialize snapshot — starting fresh")
                _state.value = AppState()
                return
            }

            // Tail-replay observations after the snapshot
            val tail = observationDao.since(snapshot.correlationVersion)
            if (tail.isEmpty()) {
                Timber.i("Restored from snapshot at cv=%d, no tail", snapshot.correlationVersion)
                _state.value = restoredState
                return
            }

            Timber.i(
                "Replaying %d observations after snapshot cv=%d",
                tail.size, snapshot.correlationVersion,
            )

            val finalState = tail.fold(restoredState) { acc, entity ->
                val obs = entity.toObservation()
                val transition = stateMachine.step(acc, obs)
                // Process effects in recovery mode (external suppressed, keyed deduped)
                transition.effects.forEach { effect ->
                    engine.process(effect, scope, recovering = true)
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

    /**
     * Reconstruct an [Observation] from a persisted [ObservationEntity].
     * Used during crash-recovery tail replay.
     */
    private fun ObservationEntity.toObservation(): Observation {
        return when (pipelineId) {
            "accessibility.window" -> Observation.Screen(
                timestamp = occurredAt,
                captureId = captureId,
                ruleId = ruleId,
                metadata = ReplayMetadata.EMPTY,
                flow = flow?.let { runCatching { enumValueOf<Flow>(it) }.getOrNull() },
                modeHint = modeHint?.let {
                    runCatching { enumValueOf<cloud.trotter.dashbuddy.domain.state.Mode>(it) }.getOrNull()
                },
                parsed = deserializeParsed(parsedJson),
                target = ruleId?.substringAfterLast('.'),
            )

            "accessibility.click" -> Observation.Click(
                timestamp = occurredAt,
                captureId = captureId,
                ruleId = ruleId,
                metadata = ReplayMetadata.EMPTY,
                flow = flow?.let { runCatching { enumValueOf<Flow>(it) }.getOrNull() },
                modeHint = modeHint?.let {
                    runCatching { enumValueOf<cloud.trotter.dashbuddy.domain.state.Mode>(it) }.getOrNull()
                },
                parsed = deserializeParsed(parsedJson),
                target = ruleId?.substringAfterLast('.'),
            )

            "notification" -> Observation.Notification(
                timestamp = occurredAt,
                captureId = captureId,
                ruleId = ruleId,
                metadata = ReplayMetadata.EMPTY,
                flow = flow?.let { runCatching { enumValueOf<Flow>(it) }.getOrNull() },
                modeHint = modeHint?.let {
                    runCatching { enumValueOf<cloud.trotter.dashbuddy.domain.state.Mode>(it) }.getOrNull()
                },
                parsed = deserializeParsed(parsedJson),
                target = ruleId?.substringAfterLast('.'),
            )

            "internal.timeout" -> Observation.Timeout(
                timestamp = occurredAt,
                type = timeoutType?.let {
                    runCatching { enumValueOf<TimeoutType>(it) }.getOrNull()
                } ?: TimeoutType.SETTLE_UI,
            )

            "internal.ui" -> Observation.UiInput(
                timestamp = occurredAt,
                action = "replay",
            )

            "internal.loopback" -> Observation.Loopback(
                timestamp = occurredAt,
                effect = "replay",
            )

            else -> Observation.Loopback(
                timestamp = occurredAt,
                effect = "unknown_pipeline:$pipelineId",
            )
        }
    }

    private fun deserializeParsed(json: String): ParsedFields {
        return try {
            gson.fromJson(json, ParsedFields::class.java) ?: ParsedFields.None
        } catch (_: Exception) {
            ParsedFields.None
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
            )

            is OfferEvaluationEvent -> Observation.Loopback(
                timestamp = System.currentTimeMillis(),
                effect = "offer_evaluated",
                payload = mapOf("action" to event.action.name),
            )

            else -> {
                Timber.w("Unhandled StateEvent type: ${event::class.simpleName}")
                null
            }
        }
    }

}

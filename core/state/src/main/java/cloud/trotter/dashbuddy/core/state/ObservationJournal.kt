package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.core.database.observation.ObservationDao
import cloud.trotter.dashbuddy.core.database.observation.ObservationEntity
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed projection of the non-flow observations' payloads (#352). FlowObservations
 * persist their ParsedFields; Timeout/UiInput/Loopback used to replay as STUBS —
 * notably the `offer_evaluated` loopback, so replayed state never carried its
 * evaluations. Map-payload entries beyond these fields are #366's typed-payload work.
 */
@Serializable
internal data class InternalObsPayload(
    val action: String? = null,
    val effect: String? = null,
    val offerHash: String? = null,
    val evaluation: OfferEvaluation? = null,
    val targetPlatform: String? = null,
)

/**
 * The append-only observation log (#352) — owns persistence, the replay-tail read,
 * and the entity↔observation codec. Extracted from StateManagerV2 so recovery
 * fidelity is testable in isolation.
 */
@Singleton
class ObservationJournal @Inject constructor(
    private val observationDao: ObservationDao,
) {

    /**
     * Single-writer queue: entries land in submission order with no gaps. The old
     * per-insert fire-and-forget launches could land out of order, and a crash
     * could persist cv=N+1 while cv=N was still in flight.
     */
    private val writeQueue = Channel<ObservationEntity>(Channel.UNLIMITED)

    /** Starts the single writer. Call once; [dispatcher] is IO in production. */
    fun start(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
        scope.launch(dispatcher) {
            for (entity in writeQueue) {
                try {
                    observationDao.insert(entity)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Timber.e(e, "Failed to persist observation cv=%d", entity.correlationVersion)
                }
            }
        }
    }

    /** Append to the log — ordered, asynchronous. */
    fun append(obs: Observation, state: AppState) {
        writeQueue.trySend(obs.toEntity(state))
    }

    /** The replay tail after [afterVersion], in correlation-version order. */
    suspend fun tailAfter(afterVersion: Long): List<Observation> =
        observationDao.since(afterVersion).map { it.toObservation() }

    suspend fun pruneOlderThan(cutoff: Long) = observationDao.pruneOlderThan(cutoff)

    // ── Codec ───────────────────────────────────────────────────────────

    internal fun Observation.toEntity(state: AppState): ObservationEntity {
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
            parsedJson = if (flowObs != null) {
                StateJson.encodeToString<ParsedFields>(flowObs.parsed)
            } else "{}",
            captureId = captureId,
            metadataJson = StateJson.encodeToString(metadata),
            correlationVersion = state.correlationVersion,
            timeoutType = (this as? Observation.Timeout)?.type?.name,
            payloadJson = internalPayloadOf(this)?.let { StateJson.encodeToString(it) },
        )
    }

    private fun internalPayloadOf(obs: Observation): InternalObsPayload? = when (obs) {
        is Observation.Timeout -> obs.targetPlatform?.let {
            InternalObsPayload(targetPlatform = it.wire)
        }
        // Persisting the REAL action is safe: PerformOfferAction is classified
        // external (#341), so recovery can never replay an offer click from it.
        is Observation.UiInput -> InternalObsPayload(action = obs.action)
        is Observation.Loopback -> InternalObsPayload(
            effect = obs.effect,
            offerHash = obs.payload["offerHash"] as? String,
            evaluation = obs.payload["evaluation"] as? OfferEvaluation,
        )
        else -> null
    }

    /** Reconstruct an [Observation] from a persisted row, for crash-recovery replay. */
    internal fun ObservationEntity.toObservation(): Observation {
        val payload = payloadJson?.let {
            try {
                StateJson.decodeFromString<InternalObsPayload>(it)
            } catch (e: Exception) {
                Timber.e(e, "Failed to decode internal payload — replaying as stub")
                null
            }
        }
        return when (pipelineId) {
            "accessibility.window" -> Observation.Screen(
                timestamp = occurredAt,
                captureId = captureId,
                ruleId = ruleId,
                metadata = ReplayMetadata.EMPTY,
                flow = flow?.let { runCatching { enumValueOf<Flow>(it) }.getOrNull() },
                modeHint = modeHint?.let { runCatching { enumValueOf<Mode>(it) }.getOrNull() },
                parsed = deserializeParsed(parsedJson),
                target = ruleId?.substringAfterLast('.'),
            )

            "accessibility.click" -> Observation.Click(
                timestamp = occurredAt,
                captureId = captureId,
                ruleId = ruleId,
                metadata = ReplayMetadata.EMPTY,
                flow = flow?.let { runCatching { enumValueOf<Flow>(it) }.getOrNull() },
                modeHint = modeHint?.let { runCatching { enumValueOf<Mode>(it) }.getOrNull() },
                parsed = deserializeParsed(parsedJson),
                target = ruleId?.substringAfterLast('.'),
            )

            "notification" -> Observation.Notification(
                timestamp = occurredAt,
                captureId = captureId,
                ruleId = ruleId,
                metadata = ReplayMetadata.EMPTY,
                flow = flow?.let { runCatching { enumValueOf<Flow>(it) }.getOrNull() },
                modeHint = modeHint?.let { runCatching { enumValueOf<Mode>(it) }.getOrNull() },
                parsed = deserializeParsed(parsedJson),
                target = ruleId?.substringAfterLast('.'),
            )

            "internal.timeout" -> Observation.Timeout(
                timestamp = occurredAt,
                type = timeoutType?.let {
                    runCatching { enumValueOf<TimeoutType>(it) }.getOrNull()
                } ?: TimeoutType.SETTLE_UI,
                targetPlatform = payload?.targetPlatform?.let(Platform::fromWire),
            )

            "internal.ui" -> Observation.UiInput(
                timestamp = occurredAt,
                action = payload?.action ?: "replay",
            )

            "internal.loopback" -> Observation.Loopback(
                timestamp = occurredAt,
                effect = payload?.effect ?: "replay",
                // Rebuild the exact keys FlowRegionStepper.handleLoopback reads, so
                // a replayed offer_evaluated LANDS its evaluation (#352).
                payload = buildMap {
                    payload?.offerHash?.let { put("offerHash", it) }
                    payload?.evaluation?.let {
                        put("evaluation", it)
                        put("action", it.action.name)
                    }
                },
            )

            else -> Observation.Loopback(
                timestamp = occurredAt,
                effect = "unknown_pipeline:$pipelineId",
            )
        }
    }

    private fun pipelineIdOf(obs: Observation): String = when (obs) {
        is Observation.Screen -> "accessibility.window"
        is Observation.Click -> "accessibility.click"
        is Observation.Notification -> "notification"
        is Observation.Timeout -> "internal.timeout"
        is Observation.UiInput -> "internal.ui"
        is Observation.Loopback -> "internal.loopback"
    }

    private fun deserializeParsed(json: String): ParsedFields {
        // Non-flow observations persist "{}" — that's a legitimate None, not corruption.
        if (json.isBlank() || json == "{}") return ParsedFields.None
        return try {
            StateJson.decodeFromString<ParsedFields>(json)
        } catch (e: Exception) {
            // LOUD (#353): a silent fallback here hid replay corruption entirely.
            Timber.e(e, "Failed to decode persisted ParsedFields — replaying as None: %s", json.take(120))
            ParsedFields.None
        }
    }
}

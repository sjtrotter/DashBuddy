package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.contract.NotificationSkeletonSchema
import cloud.trotter.census.contract.SensitiveMarkerScan
import cloud.trotter.census.contract.SkeletonKind
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.state.Platform
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/** Pure ADR-0011 §10 publication gate, independent of capture enablement or its scrubbed payload. */
object NotificationSkeletonBuilder {
    enum class Refusal {
        /** Publisher refused this kind because the server policy does not advertise its schema. */
        POLICY_UNSUPPORTED_SCHEMA,
        SENSITIVE_NOTIFICATION, INVALID_CHANNEL, OVERSIZE,
        FINGERPRINT_FAILED, INVALID_ENVELOPE, BUILD_FAILED,
    }

    sealed interface Outcome {
        data class Built(val skeleton: NotificationSkeletonDto, val json: String, val itemBytes: Int) : Outcome
        data class Refused(val reason: Refusal) : Outcome
    }

    private val channelGrammar = Regex(NotificationSkeletonDto.CHANNEL_ID_PATTERN)

    fun outcome(raw: RawNotificationData, meta: ReplayMetadata, platform: Platform, day: LocalDate): Outcome = try {
        buildOutcome(raw, meta, platform, day)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        Outcome.Refused(Refusal.BUILD_FAILED)
    }

    private fun buildOutcome(raw: RawNotificationData, meta: ReplayMetadata, platform: Platform, day: LocalDate): Outcome {
        if (platform == Platform.Unknown || platform != Platform.fromPackage(raw.packageName)) {
            return Outcome.Refused(Refusal.INVALID_ENVELOPE)
        }
        // Scan ORIGINAL fields/actions separately and across boundaries, before any customer scrub.
        val values = raw.textFields().mapNotNull { it.second } + raw.actionLabels
        // Empty fields and boundary padding must not insert extra spaces into a split marker.
        val combined = values.filterNot { it.isBlank() }.joinToString(" ") { it.trim() }
        for (value in values + combined) {
            when (SensitiveMarkerScan.findMarker(value)) {
                null -> Unit
                SensitiveMarkerScan.NORMALIZE_FAILED -> return Outcome.Refused(Refusal.BUILD_FAILED)
                else -> return Outcome.Refused(Refusal.SENSITIVE_NOTIFICATION)
            }
        }
        val channel = raw.channelId
        if (channel == null || !channelGrammar.matches(channel)) return Outcome.Refused(Refusal.INVALID_CHANNEL)

        // Actions participated only in refusal. Never emit their scrubbed values or metadata.
        val scrubbed = CustomerTextMarkers.scrubNotif(raw)
        val frame = FrameFilter(SkeletonBuilder::withholdingStep)
        val pending = scrubbed.textFields().map { (field, value) -> field to frame.field(value, IdClass.NONE) }
        val slots = pending.associate { (field, value) -> field to (value?.let(frame::slot) ?: TextSlot.WITHHELD) }
        val fingerprint = CensusFingerprint.of(platform.wire, channel, slots)
            ?: return Outcome.Refused(Refusal.FINGERPRINT_FAILED)
        val item = try {
            NotificationSkeletonDto(
                kind = SkeletonKind.NOTIFICATION,
                schemaId = NotificationSkeletonSchema.SCHEMA_ID,
                hashDomain = CensusHash.HASH_DOMAIN,
                filterRev = SkeletonBuilder.FILTER_REV,
                fingerprint = fingerprint,
                platform = platform.wire,
                platformAppVersion = SkeletonBuilder.stamp(meta.platformAppVersion),
                appVersion = SkeletonBuilder.stamp(meta.appVersion),
                rulesetReleaseTag = SkeletonBuilder.stamp(meta.rulesetReleaseTag),
                engineVersion = meta.engineVersion,
                rulesetFormatVersion = meta.rulesetFormatVersion,
                day = day.toString(),
                channelId = channel,
                slots = slots,
            )
        } catch (_: IllegalArgumentException) {
            return Outcome.Refused(Refusal.INVALID_ENVELOPE)
        }
        val measured = NotificationSkeletonSchema.measure(item)
        if (measured.bytes > SkeletonSchema.MAX_ITEM_BYTES) return Outcome.Refused(Refusal.OVERSIZE)
        return Outcome.Built(item, measured.json, measured.bytes)
    }
}

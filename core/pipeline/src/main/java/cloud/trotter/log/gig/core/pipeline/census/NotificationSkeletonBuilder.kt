package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.contract.NotificationSkeletonSchema
import cloud.trotter.census.contract.SensitiveMarkerScan
import cloud.trotter.census.contract.SkeletonKind
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.SkeletonVersion
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.log.gig.core.pipeline.CustomerTextMarkers
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.notification.RawNotificationData
import cloud.trotter.log.gig.domain.state.Platform
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pure ADR-0011 §10 publication gate, independent of capture enablement or its scrubbed payload. From v2 (§13,
 * #1619) the action labels travel as slots through the same filter as the five text fields (a hash, a coarse kind,
 * a shape or `withheld`), never a label in clear, judged in a frame of their own so they never change the fields. A v1 item is the v2 build projected
 * through `NotificationSkeletonDto.toV1`, which drops them.
 */
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

    fun outcome(
        raw: RawNotificationData,
        meta: ReplayMetadata,
        platform: Platform,
        day: LocalDate,
        version: SkeletonVersion = SkeletonVersion.V1,
    ): Outcome = try {
        buildOutcome(raw, meta, platform, day, version)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        Outcome.Refused(Refusal.BUILD_FAILED)
    }

    private fun buildOutcome(
        raw: RawNotificationData,
        meta: ReplayMetadata,
        platform: Platform,
        day: LocalDate,
        version: SkeletonVersion,
    ): Outcome {
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

        // The scrub masks a marker-bearing field or action label before the filter sees it.
        val scrubbed = CustomerTextMarkers.scrubNotif(raw)
        // The five fields are judged in a frame of their own, exactly as v1 always judged them: an action label
        // must never change a field's slot (a near-duplicate label seeding the caught set would turn a hashed title
        // `withheld`), because that would change the v1 bytes and the fingerprint, and `toV1` cannot undo it.
        val frame = FrameFilter(SkeletonBuilder::withholdingStep)
        val pending = scrubbed.textFields().map { (field, value) -> field to frame.field(value, IdClass.NONE) }
        val slots = pending.associate { (field, value) -> field to (value?.let(frame::slot) ?: TextSlot.WITHHELD) }
        val actions = if (version == SkeletonVersion.V2) actionSlots(scrubbed) else emptyList()
        val fingerprint = CensusFingerprint.of(platform.wire, channel, slots)
            ?: return Outcome.Refused(Refusal.FINGERPRINT_FAILED)
        val item = try {
            NotificationSkeletonDto(
                kind = SkeletonKind.NOTIFICATION,
                schemaId = NotificationSkeletonSchema.SCHEMA_ID_V2,
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
                actions = actions,
            ).let { if (version == SkeletonVersion.V1) it.toV1() else it }
        } catch (_: IllegalArgumentException) {
            return Outcome.Refused(Refusal.INVALID_ENVELOPE)
        }
        val measured = NotificationSkeletonSchema.measure(item)
        if (measured.bytes > SkeletonSchema.MAX_ITEM_BYTES) return Outcome.Refused(Refusal.OVERSIZE)
        return Outcome.Built(item, measured.json, measured.bytes)
    }

    /**
     * The v2 action slots: a second frame that sees the five fields and then every label, so a value caught in a
     * field also withholds the same label (and labels seed each other), while nothing here reaches [buildOutcome]'s
     * field frame. A blank label is omitted, as a blank field is everywhere else; the list is cut at the contract's cap.
     */
    private fun actionSlots(scrubbed: RawNotificationData): List<TextSlot> {
        val frame = FrameFilter(SkeletonBuilder::withholdingStep)
        scrubbed.textFields().forEach { (_, value) -> frame.field(value, IdClass.NONE) }
        val pending = scrubbed.actionLabels.mapNotNull { frame.field(it, IdClass.NONE) }
        return pending.take(NotificationSkeletonDto.MAX_ACTIONS).map(frame::slot)
    }
}

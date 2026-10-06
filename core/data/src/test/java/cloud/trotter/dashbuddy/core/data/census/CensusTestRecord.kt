package cloud.trotter.dashbuddy.core.data.census

import cloud.trotter.dashbuddy.domain.capture.CensusRecord
import cloud.trotter.dashbuddy.domain.state.Platform

internal fun censusRecord(number: Int = 0): CensusRecord {
    val fingerprint = number.toString(16).padStart(64, '0')
    val json = "{\"schemaId\":\"uinode.skeleton.v1\",\"hashDomain\":1,\"filterRev\":1," +
        "\"fingerprint\":\"$fingerprint\",\"platform\":\"doordash\",\"engineVersion\":1," +
        "\"day\":\"2026-10-02\",\"root\":{\"class\":\"android.widget.TextView\"}}"
    return CensusRecord(Platform.DoorDash, fingerprint, json, json.toByteArray().size, null)
}

/** Contract-only synthetic notification for transport tests; no pipeline dependency. */
internal fun notificationCensusRecord(): CensusRecord {
    val slots = cloud.trotter.census.contract.NotifTextField.entries.associateWith { cloud.trotter.census.contract.TextSlot.WITHHELD }
    val fingerprint = requireNotNull(cloud.trotter.census.contract.CensusFingerprint.of(Platform.DoorDash.wire, "synthetic", slots))
    val item = cloud.trotter.census.contract.NotificationSkeletonDto(
        kind = cloud.trotter.census.contract.SkeletonKind.NOTIFICATION,
        schemaId = cloud.trotter.census.contract.NotificationSkeletonSchema.SCHEMA_ID,
        hashDomain = 1, filterRev = 1, fingerprint = fingerprint, platform = Platform.DoorDash.wire,
        engineVersion = 1, day = "2026-10-02", channelId = "synthetic", slots = slots,
    )
    val measured = cloud.trotter.census.contract.NotificationSkeletonSchema.measure(item)
    return CensusRecord(Platform.DoorDash, fingerprint, measured.json, measured.bytes, null)
}

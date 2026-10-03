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

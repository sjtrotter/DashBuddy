package cloud.trotter.dashbuddy.domain.model.accessibility

import kotlinx.serialization.Serializable

@Serializable

data class ParsedTime(val text: String, val time: Long?)

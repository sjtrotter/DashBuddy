package cloud.trotter.dashbuddy.core.database.log.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UiNodeDto(
    @SerialName("text") val text: String? = null,
    @SerialName("desc") val contentDescription: String? = null,
    @SerialName("state") val stateDescription: String? = null,
    @SerialName("id") val viewIdResourceName: String? = null,
    @SerialName("class") val className: String? = null,

    val isClickable: Boolean = false,
    val isEnabled: Boolean = false,
    val isChecked: Int = 0,

    @SerialName("bounds") val boundsInScreen: BoundingBoxDto,
    @SerialName("children") val children: List<UiNodeDto> = emptyList()
)
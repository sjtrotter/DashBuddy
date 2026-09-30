package cloud.trotter.dashbuddy.domain.capture.dto

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
    // #1149 review J2: default false and omitted when false (encodeDefaults = false), so committed
    // fixtures and captures without it are byte-identical.
    @SerialName("clickAction") val hasClickAction: Boolean = false,
    // #1149 review L3/L4: same default-and-omitted contract — fixtures stay byte-identical.
    @SerialName("foreign") val foreignPackage: Boolean = false,
    @SerialName("nullKids") val unreadableChildren: Int = 0,

    @SerialName("bounds") val boundsInScreen: BoundingBoxDto,
    @SerialName("children") val children: List<UiNodeDto> = emptyList()
)
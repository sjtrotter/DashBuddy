package cloud.trotter.dashbuddy.domain.capture.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UiNodeDto(
    @SerialName("text") val text: String? = null,
    @SerialName("desc") val contentDescription: String? = null,
    @SerialName("state") val stateDescription: String? = null,
    // #1147: the TalkBack-study strings. Keys must equal UiNodeTextField.wire (pinned by
    // UiNodeScrubbableFieldsTest). Null default + encodeDefaults = false → omitted when absent, so
    // every committed fixture re-serializes byte-identically.
    @SerialName("pane") val paneTitle: String? = null,
    @SerialName("role") val roleDescription: String? = null,
    @SerialName("hint") val hintText: String? = null,
    @SerialName("tooltip") val tooltipText: String? = null,
    @SerialName("error") val errorText: String? = null,
    @SerialName("clickLabel") val clickActionLabel: String? = null,
    @SerialName("uid") val uniqueId: String? = null,
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
    // #1147: same default-and-omitted contract (defaults match UiNode's).
    @SerialName("visible") val isVisibleToUser: Boolean = true,
    @SerialName("focusable") val isFocusable: Boolean = false,
    @SerialName("srFocusable") val isScreenReaderFocusable: Boolean = false,
    @SerialName("checkable") val isCheckable: Boolean = false,
    @SerialName("selected") val isSelected: Boolean = false,
    @SerialName("heading") val isHeading: Boolean = false,
    @SerialName("live") val liveRegion: Int = 0,
    @SerialName("collRows") val collectionRows: Int = -1,
    @SerialName("collCols") val collectionCols: Int = -1,
    @SerialName("itemRow") val itemRow: Int = -1,
    @SerialName("itemCol") val itemCol: Int = -1,

    @SerialName("bounds") val boundsInScreen: BoundingBoxDto,
    @SerialName("children") val children: List<UiNodeDto> = emptyList()
)
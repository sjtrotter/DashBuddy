package cloud.trotter.dashbuddy.core.data.capture.schema

import cloud.trotter.dashbuddy.domain.capture.CaptureSchema
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Capture schema for raw notification payloads.
 * Serializes [RawNotificationData] via a parallel [RawNotificationDto].
 */
object RawNotificationSchema : CaptureSchema<RawNotificationData> {

    override val schemaId: String = "notification.v1"

    private val json = Json {
        prettyPrint = false
        encodeDefaults = false
    }

    override fun serialize(payload: RawNotificationData): String =
        json.encodeToString(payload.toDto())

    override fun deserialize(json: String): RawNotificationData =
        this.json.decodeFromString<RawNotificationDto>(json).toDomain()
}

@Serializable
internal data class RawNotificationDto(
    val title: String? = null,
    val text: String? = null,
    val tickerText: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    val packageName: String,
    val postTime: Long,
    val isClearable: Boolean,
    val isOngoing: Boolean = false,
    val category: String? = null,
    val channelId: String? = null,
    val actionLabels: List<String> = emptyList(),
)

private fun RawNotificationData.toDto() = RawNotificationDto(
    title = title,
    text = text,
    tickerText = tickerText,
    bigText = bigText,
    subText = subText,
    packageName = packageName,
    postTime = postTime,
    isClearable = isClearable,
    isOngoing = isOngoing,
    category = category,
    channelId = channelId,
    actionLabels = actionLabels,
)

private fun RawNotificationDto.toDomain() = RawNotificationData(
    title = title,
    text = text,
    tickerText = tickerText,
    bigText = bigText,
    subText = subText,
    packageName = packageName,
    postTime = postTime,
    isClearable = isClearable,
    isOngoing = isOngoing,
    category = category,
    channelId = channelId,
    actionLabels = actionLabels,
)

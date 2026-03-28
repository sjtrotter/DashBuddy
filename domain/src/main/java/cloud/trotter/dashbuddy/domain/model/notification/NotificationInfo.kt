package cloud.trotter.dashbuddy.domain.model.notification

data class NotificationInfo(
    val title: String?,
    val text: String?,
    val tickerText: String?,
    val bigText: String?,
    val packageName: String,
    val postTime: Long,
    val isClearable: Boolean,
) {
    fun toFullString(): String {
        return listOfNotNull(title, text, bigText, tickerText).joinToString(" | ")
    }

}
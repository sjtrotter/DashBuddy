package cloud.trotter.dashbuddy.services.accessibility.notification

data class NotificationInfo(
    val title: String,
    val text: String,
    val bigText: String?,
    val packageName: String,
    val timestamp: Long
) {
    fun toFullString(): String {
        return listOfNotNull(title, text, bigText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
    }
}
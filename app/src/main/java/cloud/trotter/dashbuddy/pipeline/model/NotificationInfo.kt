package cloud.trotter.dashbuddy.pipeline.model

import android.service.notification.StatusBarNotification

data class NotificationInfo(
    val title: String,
    val text: String,
    val bigText: String?,
    val packageName: String,
    val timestamp: Long
) {
    fun toFullString(): String {
        return listOfNotNull(title, text, bigText).joinToString(" | ")
    }

    companion object {
        // The "Heavy Lifting" moves here (Pure Kotlin logic)
        fun from(sbn: StatusBarNotification?): NotificationInfo? {
            if (sbn == null) return null

            // 1. FILTER
            val packageName = sbn.packageName
            val validPackages = setOf("com.doordash.driverapp")
            if (packageName !in validPackages) return null

            // 2. PARSE
            val notification = sbn.notification ?: return null
            val extras = notification.extras
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val bigText = extras.getCharSequence("android.bigText")?.toString()

            return NotificationInfo(
                title = title,
                text = text,
                bigText = bigText,
                packageName = packageName,
                timestamp = sbn.postTime
            )
        }
    }
}
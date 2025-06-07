package cloud.trotter.dashbuddy.data.log.dash // Or your preferred package for UI models

import android.text.SpannableString

data class DashLogItem(
    val message: SpannableString, // Or CharSequence if you want more flexibility initially
    val timestamp: Long? = null // Optional: System.currentTimeMillis() when logged
)

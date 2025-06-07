package cloud.trotter.dashbuddy.data.log.debug // Or your preferred package for UI models

import android.text.SpannableString

data class DebugLogItem(
    val message: SpannableString, // Or CharSequence if you want more flexibility initially
)

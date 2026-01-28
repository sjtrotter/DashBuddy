package cloud.trotter.dashbuddy.state.effects

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.state.AppEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
//import cloud.trotter.dashbuddy.log.Logger as Log
import java.util.regex.Pattern

object TipEffectHandler {

    // Regex for: "A customer added $5.00 tip on a past McDonald's order delivered at 12/15, 1:30 PM"
    // Groups: 1=Amount, 2=Store, 3=Date, 4=Time, 5=AM/PM
    private val TIP_PATTERN = Pattern.compile(
        "added \\$(\\d+\\.\\d{2}) tip on a past (.+) order delivered at (\\d{1,2}/\\d{1,2}), (\\d{1,2}:\\d{2}) ([AP]M)",
        Pattern.CASE_INSENSITIVE
    )

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun process(scope: CoroutineScope, effect: AppEffect.ProcessTipNotification) {
        scope.launch(Dispatchers.IO) {
            try {
                val matcher = TIP_PATTERN.matcher(effect.rawText)
                if (matcher.find()) {
                    val amountStr = matcher.group(1) // "5.00"
                    val storeName = matcher.group(2) // "McDonald's"
                    val dateStr = matcher.group(3)   // "12/15"
                    val timeStr = matcher.group(4)   // "1:30"
                    val amPm = matcher.group(5)      // "PM"

                    Timber.i("Parsed Tip: $$amountStr from $storeName at $dateStr $timeStr $amPm")

                    // TODO: Database Correlation Logic
                    // 1. Calculate approximate timestamp from dateStr/timeStr (assume current year, handle Year rollover)
                    // 2. Query OrderRepo for orders from 'storeName' within +/- 1 hour of timestamp
                    // 3. If found, update order with new tip amount
                    // 4. DashBuddyApplication.orderRepo.updateTip(orderId, amount.toDouble())

                    // For now, just show a bubble confirming we parsed it
                    DashBuddyApplication.sendBubbleMessage("Nice! $$amountStr tip from $storeName")

                } else {
                    Timber.w("Failed to parse tip notification: ${effect.rawText}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error processing tip notification")
            }
        }
    }
}
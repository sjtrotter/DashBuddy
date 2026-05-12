package cloud.trotter.dashbuddy.domain.util

import java.util.Locale

fun formatCurrency(amount: Double): String =
    String.format(Locale.getDefault(), "%.2f", amount)

package cloud.trotter.dashbuddy.feature.bubble.hud.expense

/** New expenses and fill-ups belong to the active dash, else none. */
fun owner(active: String?): String? = active

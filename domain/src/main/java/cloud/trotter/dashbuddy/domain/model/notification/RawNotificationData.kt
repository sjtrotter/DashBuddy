package cloud.trotter.dashbuddy.domain.model.notification

/**
 * Raw notification payload extracted from a [android.service.notification.StatusBarNotification].
 * Analogous to [cloud.trotter.dashbuddy.domain.model.accessibility.UiNode] — this is the
 * uninterpreted source data; [cloud.trotter.dashbuddy.domain.state.ParsedFields.NotificationFields] carries the typed, parsed result.
 */
data class RawNotificationData(
    val title: String?,
    val text: String?,
    val tickerText: String?,
    val bigText: String?,
    val subText: String? = null,
    val packageName: String,
    val postTime: Long,
    val isClearable: Boolean,
    val isOngoing: Boolean = false,
    val category: String? = null,
    val channelId: String? = null,
    val actionLabels: List<String> = emptyList(),
) {
    /**
     * SSOT enumeration of the notification's 5 flat text fields (#666/F3). Every
     * scrub/redact/serialize site that needs "all the text fields" iterates THIS
     * instead of hand-listing title/text/bigText/tickerText/subText — a field
     * added here is covered by every reader automatically, and one missed by a
     * caller is a compile-visible gap rather than a silent leak channel.
     *
     * Order is significant: [toFullString] joins in this exact order, and that
     * join is the input to [contentHash] (CaptureBus dedup identity) — changing
     * the order or membership here changes historical dedup behavior.
     */
    fun textFields(): List<Pair<NotifTextField, String?>> = listOf(
        NotifTextField.TITLE to title,
        NotifTextField.TEXT to text,
        NotifTextField.BIG_TEXT to bigText,
        NotifTextField.TICKER_TEXT to tickerText,
        NotifTextField.SUB_TEXT to subText,
    )

    /**
     * Return a copy with every text field replaced per [fields] (must supply a
     * value — possibly unchanged — for every [NotifTextField]; typically built
     * from a transform over [textFields]). This `copy(...)` call is the ONE
     * place that enumerates the 5 fields for writing; [textFields] is the one
     * place that enumerates them for reading.
     */
    fun withTextFields(fields: Map<NotifTextField, String?>): RawNotificationData = copy(
        title = fields.getValue(NotifTextField.TITLE),
        text = fields.getValue(NotifTextField.TEXT),
        bigText = fields.getValue(NotifTextField.BIG_TEXT),
        tickerText = fields.getValue(NotifTextField.TICKER_TEXT),
        subText = fields.getValue(NotifTextField.SUB_TEXT),
    )

    fun toFullString(): String =
        textFields().mapNotNull { it.second }.joinToString(" | ")

    /** Content hash for CaptureBus dedup — identical text content deduplicates per session. */
    val contentHash: Int get() = toFullString().hashCode()
}

/**
 * The 5 flat text fields [RawNotificationData] carries (#666) — the wire-string
 * vocabulary shared by the notification `redact` rule DSL (`RuleCompiler`/
 * `CompiledNotifRedact` in `:core:pipeline`) and every text-field scrub backstop
 * (`CustomerTextMarkers`, [RawNotificationData.textFields]/[RawNotificationData.withTextFields]).
 * `actionLabels` is a separate list field, deliberately NOT a member here — it
 * is not part of [RawNotificationData.toFullString]/`contentHash` (dedup
 * identity must stay stable) and is scrubbed by its own path (#666 item 2).
 */
enum class NotifTextField(val wire: String) {
    TITLE("title"),
    TEXT("text"),
    BIG_TEXT("bigText"),
    TICKER_TEXT("tickerText"),
    SUB_TEXT("subText"),
    ;

    companion object {
        fun fromWire(wire: String): NotifTextField? = entries.firstOrNull { it.wire == wire }
    }
}

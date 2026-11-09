package cloud.trotter.log.gig.notice

import android.content.res.Resources
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.domain.pipeline.ParseRotCause
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncident
import cloud.trotter.log.gig.domain.state.Platform

/**
 * The one owner of the recognition incident copy (#1560, principle 5): the App notices notification
 * ([RecognitionHealthNotifier]) and Home's recognition card both render it from here.
 *
 * Built from label maps keyed by shape and by shape field, never by platform or rule id (principle 8):
 * a new platform's incident reads correctly with no copy change. One cause makes its line the title;
 * several causes get a shared title and list every cause in the text. Every incident ends with the same
 * conditional line, "Other screens may still record."
 *
 * The copy never states a percentage, an update, a fix, a rule or a wire: the dasher gets which screens
 * stopped reading and which values went missing, nothing to decode. PII-free by construction — a
 * platform's display name and our own labels.
 */
internal object RecognitionNoticeCopy {

    /** Shape field → string resource id. Keyed by shape field, never by platform (#1560). */
    internal val FIELD_LABELS: Map<String, Int> = mapOf(
        "payAmount" to R.string.recognition_field_pay_amount,
        "distance" to R.string.recognition_field_distance,
        "totalPay" to R.string.recognition_field_total_pay,
        "totalEarnings" to R.string.recognition_field_total_earnings,
    )

    /** Shape → string resource id; a shape not listed reads [R.string.recognition_shape_other]. */
    internal val SHAPE_LABELS: Map<String, Int> = mapOf(
        "offer" to R.string.recognition_shape_offer,
        "post_task" to R.string.recognition_shape_post_task,
        "session_ended" to R.string.recognition_shape_session_ended,
    )

    fun title(res: Resources, incident: RecognitionIncident): String {
        val lines = causeLines(res, incident)
        return lines.singleOrNull()
            ?: res.getString(R.string.recognition_incident_title_format, platformName(res, incident.platformWire))
    }

    fun text(res: Resources, incident: RecognitionIncident): String {
        val lines = causeLines(res, incident)
        val stillOther = res.getString(R.string.recognition_still_other)
        return if (lines.size == 1) stillOther else (lines + stillOther).joinToString("\n")
    }

    /** The UNKNOWN cause first, then each rotted rule in rule-id order. */
    private fun causeLines(res: Resources, incident: RecognitionIncident): List<String> {
        val platform = platformName(res, incident.platformWire)
        return buildList {
            if (incident.screensUnrecognized) {
                add(res.getString(R.string.recognition_unknown_line_format, platform))
            }
            incident.parseRot.sortedBy { it.ruleId }.forEach { add(rotLine(res, platform, it)) }
        }
    }

    private fun rotLine(res: Resources, platform: String, cause: ParseRotCause): String {
        val shape = res.getString(SHAPE_LABELS[cause.shape] ?: R.string.recognition_shape_other)
        val labels = cause.nullFields
            .map { res.getString(FIELD_LABELS[it] ?: R.string.recognition_field_other) }
            .distinct() // totalPay and payAmount both read "pay"
        val fields = labels.reduceOrNull { pair, next ->
            res.getString(R.string.recognition_fields_pair_format, pair, next)
        } ?: res.getString(R.string.recognition_field_other)
        return res.getString(R.string.recognition_rot_line_format, platform, shape, fields)
    }

    /**
     * The registry is the only path from a wire to display copy (principle 8). An unknown wire — a
     * ruleset naming a platform this build doesn't carry — gets the generic wording, never a
     * fabricated brand name.
     */
    private fun platformName(res: Resources, platformWire: String): String =
        Platform.fromWire(platformWire)
            ?.takeIf { it != Platform.Unknown }
            ?.displayName
            ?: res.getString(R.string.recognition_platform_fallback)
}

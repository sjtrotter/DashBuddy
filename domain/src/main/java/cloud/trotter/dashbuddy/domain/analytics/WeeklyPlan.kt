package cloud.trotter.dashbuddy.domain.analytics

/**
 * The Weekly Plan (#981 / brief §8) — a set of hour windows drawn from the driver's **own** record,
 * with the value of following it stated next to the value of not bothering.
 *
 * Every number here is traceable to [HourOfWeekSamples]: a window's rate is the MEDIAN of the driver's
 * own occurrence rates for exactly that weekday and hour range, its projection is `median × hours`,
 * and the baseline is the median of every hour they have ever worked. Nothing is modelled, learned or
 * assumed — which is why [WeeklyPlanner] can refuse to place a window at all and say why.
 *
 * **Not a promise.** The surface rendering this is required to say whose data it is and that it is a
 * projection, on every state, including the thin ones (brief §9).
 */
data class WeeklyPlan(
    val target: PlanTarget,
    /** The picked windows, ordered by weekday then start hour (a week reads chronologically). */
    val windows: List<PlannedWindow>,
    /** Weekdays that hold NO picked window, each with the honest reason (brief §8's `NOT PICKED`). */
    val notPicked: List<UnpickedDay>,
    /**
     * The median rate across every hour the driver has ever worked — the **random-placement baseline**.
     * Null when nothing in the record clears the coverage floor, in which case the whole comparison is
     * withheld rather than invented.
     */
    val randomPlacementRate: Double?,
    /** The edits ([PlanEdit]) this plan was replayed with — kept so a save round-trips the driver's own arrangement. */
    val edits: List<PlanEdit>,
) {
    /** Hours placed. */
    val totalHours: Int get() = windows.sumOf { it.lengthHours }

    /** Hours placed on a window the record can actually price (a moved window may have no history). */
    val pricedHours: Int get() = windows.filter { it.dollarsPerHour != null }.sumOf { it.lengthHours }

    /** True when some placed hour carries no evidence — the surface must say so instead of quietly under-projecting. */
    val hasUnpricedHours: Boolean get() = pricedHours < totalHours

    /** `Σ median rate × hours`, over the windows that have a rate. */
    val projectedKept: Double get() = windows.sumOf { it.projectedKept }

    /** The same hours placed without regard to when — `overall median × hours`. Null with no baseline. */
    val randomKept: Double? get() = randomPlacementRate?.let { it * totalHours }

    /**
     * What the plan is worth: projected minus random. Null when there is no baseline to compare
     * against. **Can be negative** and is shown as such — a plan that doesn't beat picking at random
     * is information the driver is entitled to, not something to hide.
     */
    val planWorth: Double? get() = randomKept?.let { projectedKept - it }

    /** True once the plan reaches what the driver asked for (hours placed, or dollars projected). */
    val targetMet: Boolean
        get() = when (target) {
            is PlanTarget.Hours -> totalHours >= target.hours
            is PlanTarget.Dollars -> projectedKept >= target.amount
        }

    /** Hours still unplaced against an hours target (0 for a dollars target, or when met). */
    val shortfallHours: Int
        get() = when (target) {
            is PlanTarget.Hours -> (target.hours - totalHours).coerceAtLeast(0)
            is PlanTarget.Dollars -> 0
        }

    /** True for the cell ([dayIndex] 0=Monday, [hour] 0..23) if a picked window covers it — the heatmap outline. */
    fun covers(dayIndex: Int, hour: Int): Boolean =
        windows.any { it.dayIndex == dayIndex && hour >= it.startHour && hour < it.endHourExclusive }

    companion object {
        /** The pre-data plan: nothing placed, nothing claimed. */
        fun empty(target: PlanTarget) = WeeklyPlan(target, emptyList(), emptyList(), null, emptyList())
    }
}

/**
 * What the driver asked the planner for (brief §8 item 1: `8h · 12h · 20h · $ goal`).
 *
 * A sealed pair rather than a nullable-hours/nullable-dollars record (Principle 4): the two targets
 * terminate the greedy fill on different conditions, and the type is what makes that unmissable.
 */
sealed interface PlanTarget {
    data class Hours(val hours: Int) : PlanTarget
    data class Dollars(val amount: Double) : PlanTarget

    companion object {
        /** The brief's three hour presets — the selector's options, in order. */
        val HOUR_PRESETS = listOf(8, 12, 20)

        /** The default when the screen first opens. */
        val DEFAULT: PlanTarget = Hours(12)
    }
}

/**
 * One placed window: weekday, hour range, the driver's own median rate across it, and how many of
 * their own days that median was taken over.
 *
 * [dollarsPerHour] and [sampleCount] are the **evidence line** (`your Fridays: $26.40/hr over 6
 * Fridays`). A null rate means the driver moved this window somewhere they have no qualifying record —
 * legal, but then the row says so and the window contributes nothing to the projection.
 */
data class PlannedWindow(
    /** 0=Monday..6=Sunday. */
    val dayIndex: Int,
    val startHour: Int,
    val endHourExclusive: Int,
    /** MEDIAN of the driver's own occurrence rates for exactly this weekday+range; null ⇒ no qualifying history. */
    val dollarsPerHour: Double?,
    /** How many of the driver's own days that median was computed over — quoted verbatim in the evidence line. */
    val sampleCount: Int,
    /** True for the single highest-rate placed window — brief §8's `BEST` pill. */
    val isBest: Boolean = false,
    /** True once the driver has moved this window off where the planner put it. */
    val moved: Boolean = false,
) {
    val lengthHours: Int get() = endHourExclusive - startHour

    /** `median rate × hours`, or 0 when the range carries no qualifying history (never a guessed rate). */
    val projectedKept: Double get() = (dollarsPerHour ?: 0.0) * lengthHours

    /**
     * True when this window's median rests on fewer days than [WeeklyPlanner.MIN_SAMPLE_DAYS].
     *
     * The planner never *places* such a window itself — but the driver can move one onto thinly-recorded
     * hours, and when they do the row keeps its (weak) number and says how weak it is, rather than
     * hiding either half.
     */
    val hasThinEvidence: Boolean get() = sampleCount < WeeklyPlanner.MIN_SAMPLE_DAYS

    /** Overlap test in weekday+hour space — the greedy fill's non-collision rule. */
    fun overlaps(other: PlannedWindow): Boolean =
        dayIndex == other.dayIndex && startHour < other.endHourExclusive && other.startHour < endHourExclusive
}

/** A weekday with no placed window, and the reason — never a silent omission (brief §8, §9). */
data class UnpickedDay(
    val dayIndex: Int,
    val reason: UnpickedReason,
    /** Distinct dates of this weekday the driver was online at all — the `only 3 Sundays on record` figure. */
    val daysOnRecord: Int,
)

/**
 * Why a weekday holds no window. Every arm is a *measured* fact about the driver's record or about the
 * target, never a judgement — the UI renders each as its own sentence.
 */
enum class UnpickedReason {
    /** Nothing at all recorded on this weekday. */
    NO_HISTORY,

    /** Some record, but no hour has enough separate days behind it to trust a median. */
    THIN_HISTORY,

    /** Enough days, but the hours that qualify never line up into a run of [WeeklyPlanner.MIN_WINDOW_HOURS]. */
    NO_CONTIGUOUS_RUN,

    /** Measured, and the money isn't there — this weekday's qualifying hours don't clear $0 net. */
    NO_POSITIVE_RATE,

    /** A real candidate that simply lost: the target filled up with better-paying windows first. */
    TARGET_ALREADY_MET,

    /** The driver dropped this weekday's window themselves — their edit, stated as their edit. */
    REMOVED_BY_YOU,
}

/**
 * A driver edit replayed on top of the algorithm's own output.
 *
 * The plan is never mutated in place — the ViewModel holds an ordered list of these and the planner
 * **replays** them over a freshly computed base (Principle 1: the derivation stays pure and the edits
 * are the input). That is what lets a moved window re-derive its rate from the record at its NEW hours
 * instead of carrying the old number somewhere it was never measured.
 *
 * Both arms address a window by the position it occupies *at the time the edit is replayed*, so the
 * list is order-sensitive by construction and a replay is deterministic.
 */
sealed interface PlanEdit {
    val dayIndex: Int
    val startHour: Int

    /** Drop the window at ([dayIndex], [startHour]); the greedy fill then re-fills from the next best. */
    data class Drop(override val dayIndex: Int, override val startHour: Int) : PlanEdit

    /** Shift the window at ([dayIndex], [startHour]) by [deltaHours] (negative = earlier). */
    data class Shift(
        override val dayIndex: Int,
        override val startHour: Int,
        val deltaHours: Int,
    ) : PlanEdit
}

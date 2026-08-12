package cloud.trotter.dashbuddy.domain.analytics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * A plan the driver pressed **Save this plan** on (#981 / brief §8 item 6) — the artifact the Home
 * pointer row reads each morning and next Sunday's notification grades.
 *
 * **Frozen at save time, on purpose.** The windows carry the rate and projection the driver was
 * actually looking at when they committed to the week. Re-deriving them later against a record that has
 * since grown would grade them against a plan they were never shown — the same reasoning that freezes a
 * `delivery_record`'s economics at projection time (CLAUDE.md §5). The record keeps moving; the
 * commitment does not.
 *
 * **This is a user artifact, not analytics state.** It is not derivable from `app_events` and is
 * therefore deliberately NOT stored in the read-model database, whose tables are a rebuildable
 * projection that a `PROJECTOR_VERSION` bump wipes wholesale. It lives in its own single-concern
 * DataStore instead (see `WeeklyPlanDataSource`), encoded by [WeeklyPlanCodec].
 */
data class SavedWeeklyPlan(
    /** Monday of the week this plan is FOR — the identity a Home pointer and a grade both match on. */
    val weekStart: LocalDate,
    /** When the driver saved it (device clock at the save edge). */
    val savedAtMillis: Long,
    val target: PlanTarget,
    val windows: List<SavedPlanWindow>,
    /** The plan's projection, frozen: `Σ median rate × hours` as shown at save time. */
    val projectedKept: Double,
    /** The same hours placed at random, frozen; null when the record had no baseline to offer. */
    val randomKept: Double?,
) {
    val totalHours: Int get() = windows.sumOf { it.lengthHours }

    /** What the plan claimed to be worth over placing the same hours at random. Null with no baseline. */
    val planWorth: Double? get() = randomKept?.let { projectedKept - it }

    /**
     * True when a planned window holds ([dayIndex], [hour]) — what outlines the picked cells on a
     * heatmap. Byte-identical in meaning to [WeeklyPlan.covers]; the frozen artifact needs its own
     * because the Playbook (#1024) draws the *saved* plan over the grid, not the live derivation.
     */
    fun covers(dayIndex: Int, hour: Int): Boolean =
        windows.any { it.dayIndex == dayIndex && hour >= it.startHour && hour < it.endHourExclusive }

    companion object {
        /**
         * How many saved plans are kept. Two: the week being worked and the week just graded. A plan
         * older than that has no consumer, and unbounded growth in a preferences blob is a bug waiting
         * to happen.
         */
        const val MAX_KEPT = 2

        /** Freeze [plan] as the artifact for the week starting [weekStart]. */
        fun from(plan: WeeklyPlan, weekStart: LocalDate, savedAtMillis: Long) = SavedWeeklyPlan(
            weekStart = weekStart,
            savedAtMillis = savedAtMillis,
            target = plan.target,
            windows = plan.windows.map {
                SavedPlanWindow(
                    dayIndex = it.dayIndex,
                    startHour = it.startHour,
                    endHourExclusive = it.endHourExclusive,
                    dollarsPerHour = it.dollarsPerHour,
                    sampleCount = it.sampleCount,
                )
            },
            projectedKept = plan.projectedKept,
            randomKept = plan.randomKept,
        )
    }
}

/** One frozen window of a [SavedWeeklyPlan]. [dayIndex] 0=Monday..6=Sunday. */
data class SavedPlanWindow(
    val dayIndex: Int,
    val startHour: Int,
    val endHourExclusive: Int,
    val dollarsPerHour: Double?,
    val sampleCount: Int,
) {
    val lengthHours: Int get() = endHourExclusive - startHour

    /** `frozen median rate × hours` — what this window promised when it was saved. */
    val projectedKept: Double get() = (dollarsPerHour ?: 0.0) * lengthHours
}

/**
 * JSON codec for the saved-plan list, **fail-closed in both directions** (the `LegStateCodec` pattern,
 * #688 phase B): a blob that does not decode is treated as *no saved plan* rather than throwing, so a
 * corrupt or older-shaped preference can never crash the Home screen or the Sunday worker open.
 *
 * The wire shape is a private DTO layer, not the domain types: `LocalDate` and the [PlanTarget] sealed
 * pair have no stable serial form of their own, and pinning the wire here means a later domain rename
 * is not silently a data migration.
 */
object WeeklyPlanCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Hours-target discriminator on the wire. Do not rename — saved blobs carry it. */
    private const val TARGET_HOURS = "hours"

    /** Dollars-target discriminator on the wire. Do not rename — saved blobs carry it. */
    private const val TARGET_DOLLARS = "dollars"

    fun encode(plans: List<SavedWeeklyPlan>): String = json.encodeToString(
        SavedPlansDto(plans.map { it.toDto() }),
    )

    /** Decode, or an empty list for null / blank / malformed / structurally impossible input. */
    fun decode(raw: String?): List<SavedWeeklyPlan> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<SavedPlansDto>(raw).plans.mapNotNull { it.toDomain() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Serializable
    private data class SavedPlansDto(val plans: List<SavedPlanDto> = emptyList())

    @Serializable
    private data class SavedPlanDto(
        val weekStartEpochDay: Long,
        val savedAtMillis: Long,
        val targetKind: String,
        val targetValue: Double,
        val windows: List<SavedWindowDto> = emptyList(),
        val projectedKept: Double,
        val randomKept: Double? = null,
    )

    @Serializable
    private data class SavedWindowDto(
        val dayIndex: Int,
        val startHour: Int,
        val endHourExclusive: Int,
        val dollarsPerHour: Double? = null,
        val sampleCount: Int = 0,
    )

    private fun SavedWeeklyPlan.toDto() = SavedPlanDto(
        weekStartEpochDay = weekStart.toEpochDay(),
        savedAtMillis = savedAtMillis,
        targetKind = when (target) {
            is PlanTarget.Hours -> TARGET_HOURS
            is PlanTarget.Dollars -> TARGET_DOLLARS
        },
        targetValue = when (val t = target) {
            is PlanTarget.Hours -> t.hours.toDouble()
            is PlanTarget.Dollars -> t.amount
        },
        windows = windows.map {
            SavedWindowDto(it.dayIndex, it.startHour, it.endHourExclusive, it.dollarsPerHour, it.sampleCount)
        },
        projectedKept = projectedKept,
        randomKept = randomKept,
    )

    /** Null for a row whose shape is impossible — one bad entry is dropped, the rest survive. */
    private fun SavedPlanDto.toDomain(): SavedWeeklyPlan? {
        val target = when (targetKind) {
            TARGET_HOURS -> PlanTarget.Hours(targetValue.toInt())
            TARGET_DOLLARS -> PlanTarget.Dollars(targetValue)
            else -> return null
        }
        val decoded = windows.mapNotNull { it.toDomain() }
        return SavedWeeklyPlan(
            weekStart = LocalDate.ofEpochDay(weekStartEpochDay),
            savedAtMillis = savedAtMillis,
            target = target,
            windows = decoded,
            projectedKept = projectedKept,
            randomKept = randomKept,
        )
    }

    private fun SavedWindowDto.toDomain(): SavedPlanWindow? {
        if (dayIndex !in 0 until EarningsHeatmap.DAYS) return null
        if (startHour !in 0 until EarningsHeatmap.HOURS) return null
        if (endHourExclusive <= startHour || endHourExclusive > EarningsHeatmap.HOURS) return null
        return SavedPlanWindow(dayIndex, startHour, endHourExclusive, dollarsPerHour, sampleCount.coerceAtLeast(0))
    }
}

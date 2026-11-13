package cloud.trotter.log.gig.domain.census

import kotlin.random.Random

/**
 * #1620 (ADR-0011 §21): the census uploads once a day at a random time, drawn on the phone and drawn again each day,
 * so the upload time says nothing about when a shift starts or ends.
 */
object CensusUploadWindow {
    const val DAY_MILLIS: Long = 86_400_000L

    /** A uniformly random instant in the UTC day after the one holding [nowMillis]. */
    fun next(nowMillis: Long, random: Random): Long =
        Math.floorDiv(nowMillis, DAY_MILLIS) * DAY_MILLIS + DAY_MILLIS + random.nextLong(DAY_MILLIS)
}

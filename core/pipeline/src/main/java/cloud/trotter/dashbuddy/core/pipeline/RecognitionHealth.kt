package cloud.trotter.dashbuddy.core.pipeline

/**
 * The pure half of the #937 recognition-health alarm: a rolling per-platform
 * recognized-vs-UNKNOWN ratio over the frames the pipeline actually admitted.
 *
 * No Android, no logging, no notification — [record] just returns the trip edge, so the whole
 * decision is unit-testable and the side effects live in [RecognitionHealthMonitor].
 *
 * **Why a window and not a lifetime ratio.** A lifetime counter dilutes: after two healthy
 * hours a total recognition break barely moves the average, which is precisely the moment the
 * dasher needs to hear about it. A rolling window forgets the healthy past.
 *
 * **Where the samples come from.** The caller feeds frames that survived `FrameGate`
 * (post-dedup), not raw frames. Both classes are deduped there — recognized screens by
 * identity, UNKNOWN frames by content hash — so a dasher parked on one unruled screen
 * contributes a couple of samples rather than a thousand, and the measured baseline is the
 * one the field logs report. The same dedup is also this alarm's known limit: it needs a
 * window's worth of DISTINCT frames, so it detects a broken-but-LIVE app (the platform-update
 * case it exists for — offers, timers and maps keep the content changing) rather than a wedged
 * one, which is the bubble/odometer liveness signals' territory (#916/#917).
 *
 * **Tuning (all three consts are deliberate and field-derived).** The 2026-07-28/29 pulls put
 * a HEALTHY dash at roughly 16 % UNKNOWN on the admitted stream (`forwarded=450
 * unknownDropped=86` and `forwarded=500 unknownDropped=87` in consecutive `PipelineStats`
 * lines). [UNKNOWN_RATIO_THRESHOLD] sits at 0.80 — five times that baseline — because a real
 * anchor break drives the ratio toward 1.0, so the alarm can afford to be far from the noise
 * rather than sensitive to it. [WINDOW_SIZE] of 50 admitted frames is a few minutes of dashing
 * at those rates, and the window must be FULL before it is evaluated, so a handful of unruled
 * launch screens can never trip it.
 *
 * Not thread-safe on its own; [RecognitionHealthMonitor] owns the lock.
 */
class RecognitionHealth(
    private val windowSize: Int = WINDOW_SIZE,
    private val threshold: Double = UNKNOWN_RATIO_THRESHOLD,
) {

    /** One trip, reported once. */
    data class Alarm(val platformWire: String, val unknownPercent: Int)

    private val windows = HashMap<String, Window>()

    /** Platforms already alarmed — the once-per-process edge gate. */
    private val alarmed = HashSet<String>()

    /**
     * Record one admitted frame for [platformWire].
     *
     * @return the [Alarm] on the frame that crosses the threshold, null otherwise (including
     *   every frame after the first trip: a platform alarms at most once per process).
     */
    fun record(platformWire: String, unknown: Boolean): Alarm? {
        if (platformWire in alarmed) return null

        val window = windows.getOrPut(platformWire) { Window(windowSize) }
        window.add(unknown)
        if (!window.isFull) return null

        val ratio = window.unknownRatio
        if (ratio < threshold) return null

        alarmed += platformWire
        return Alarm(platformWire, (ratio * 100).toInt())
    }

    /** True once [platformWire] has alarmed — recovery deliberately does NOT re-arm it. */
    fun hasAlarmed(platformWire: String): Boolean = platformWire in alarmed

    /** A fixed-capacity ring of "was this frame UNKNOWN", with a running count. */
    private class Window(private val capacity: Int) {
        private val samples = BooleanArray(capacity)
        private var next = 0
        private var size = 0
        private var unknowns = 0

        val isFull: Boolean get() = size == capacity
        val unknownRatio: Double get() = if (size == 0) 0.0 else unknowns.toDouble() / size

        fun add(unknown: Boolean) {
            if (isFull && samples[next]) unknowns--
            samples[next] = unknown
            if (unknown) unknowns++
            next = (next + 1) % capacity
            if (size < capacity) size++
        }
    }

    companion object {
        /** Admitted frames per platform in the rolling window; evaluated only when full. */
        const val WINDOW_SIZE = 50

        /** Share of the window that must classify UNKNOWN to trip. See the class KDoc. */
        const val UNKNOWN_RATIO_THRESHOLD = 0.80
    }
}

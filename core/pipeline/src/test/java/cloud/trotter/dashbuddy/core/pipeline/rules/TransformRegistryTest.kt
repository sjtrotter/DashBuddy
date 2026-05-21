package cloud.trotter.dashbuddy.core.pipeline.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.util.Calendar

/**
 * Tests for the deadline-rollover behavior in [TransformRegistry.apply] for
 * the `parseDeadline` / `parseTime` transforms.
 *
 * Background: prior to the 2026-05-19 fix the transform unconditionally rolled
 * a parsed time forward a day if it was in the past relative to "now". That
 * caused the "1434:38" ghost-countdown bug (field log 2026-05-19 #2) when
 * the dasher arrived ~37 seconds past the pickup-by deadline and the next
 * re-parse rolled the deadline ~24 hours forward.
 *
 * The fix clamps the rollover to a 12-hour past-threshold: past by < 12h
 * stays today (renders as "X min late"); past by > 12h rolls forward
 * (catches the late-night-offer-for-next-morning case).
 *
 * These tests use `Calendar.getInstance()` for the "now" baseline and
 * compute expected timestamps from the same snapshot, so the assertions
 * are tolerant to wall-clock drift during the test run.
 */
class TransformRegistryTest {

    /**
     * Build a "Pick up by H:mm AM/PM" string offset by [offsetMinutes] from
     * the test's "now" snapshot. Positive offset = future; negative = past.
     */
    private fun deadlineTextAt(now: Calendar, offsetMinutes: Long): String {
        val target = (now.clone() as Calendar).apply {
            add(Calendar.MINUTE, offsetMinutes.toInt())
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val hour = target.get(Calendar.HOUR_OF_DAY)
        val minute = target.get(Calendar.MINUTE)
        val time12 = LocalTime.of(hour, minute).format(
            java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US),
        )
        return "Pick up by $time12"
    }

    /** Same hour:minute today, seconds/millis zeroed. */
    private fun todayAtSameMinute(now: Calendar, offsetMinutes: Long): Long {
        return (now.clone() as Calendar).apply {
            add(Calendar.MINUTE, offsetMinutes.toInt())
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** Same hour:minute tomorrow, seconds/millis zeroed. */
    private fun tomorrowAtSameMinute(now: Calendar, offsetMinutes: Long): Long {
        return (now.clone() as Calendar).apply {
            add(Calendar.MINUTE, offsetMinutes.toInt())
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
    }

    // =========================================================================
    // Future / not-past cases
    // =========================================================================

    @Test
    fun `future time stays today`() {
        val now = Calendar.getInstance()
        val text = deadlineTextAt(now, offsetMinutes = +30)
        val result = TransformRegistry.apply("parseDeadline", text) as? Long
        assertNotNull(result)
        assertEquals(todayAtSameMinute(now, offsetMinutes = +30), result)
    }

    // =========================================================================
    // Past-but-within-threshold: stay today (the bug fix)
    // =========================================================================

    @Test
    fun `slightly past time stays today (was the 1434 ghost bug)`() {
        val now = Calendar.getInstance()
        val text = deadlineTextAt(now, offsetMinutes = -1)
        val result = TransformRegistry.apply("parseDeadline", text) as? Long
        assertNotNull(result)
        val expected = todayAtSameMinute(now, offsetMinutes = -1)
        assertEquals(expected, result)
        // Sanity: it's NOT ~24h in the future.
        assertTrue(
            "Result should be near now (not rolled forward)",
            kotlin.math.abs(result!! - now.timeInMillis) < 5L * 60_000L,
        )
    }

    @Test
    fun `hours past stays today (well within threshold)`() {
        val now = Calendar.getInstance()
        val text = deadlineTextAt(now, offsetMinutes = -240)  // 4h ago
        val result = TransformRegistry.apply("parseDeadline", text) as? Long
        assertNotNull(result)
        assertEquals(todayAtSameMinute(now, offsetMinutes = -240), result)
    }

    @Test
    fun `just under 12h threshold stays today`() {
        val now = Calendar.getInstance()
        val text = deadlineTextAt(now, offsetMinutes = -11 * 60)  // 11h ago
        val result = TransformRegistry.apply("parseDeadline", text) as? Long
        assertNotNull(result)
        assertEquals(todayAtSameMinute(now, offsetMinutes = -11 * 60), result)
    }

    // =========================================================================
    // Past-beyond-threshold: roll to tomorrow (late-night offer scenario)
    // =========================================================================

    @Test
    fun `13 hours past rolls to tomorrow`() {
        val now = Calendar.getInstance()
        val text = deadlineTextAt(now, offsetMinutes = -13 * 60)
        val result = TransformRegistry.apply("parseDeadline", text) as? Long
        assertNotNull(result)
        // The text encodes a wall-clock that's 13h ago. Rolled to tomorrow,
        // that's 24h - 13h = 11h in the future.
        assertEquals(tomorrowAtSameMinute(now, offsetMinutes = -13 * 60), result)
        assertTrue(
            "Result should be in the future after rollover",
            result!! > now.timeInMillis,
        )
    }

    @Test
    fun `far past rolls to tomorrow (late-night offer for next morning)`() {
        val now = Calendar.getInstance()
        // ~17h ago — analogue of 11pm-now-for-6am-tomorrow.
        val text = deadlineTextAt(now, offsetMinutes = -17 * 60)
        val result = TransformRegistry.apply("parseDeadline", text) as? Long
        assertNotNull(result)
        assertEquals(tomorrowAtSameMinute(now, offsetMinutes = -17 * 60), result)
    }

    // =========================================================================
    // Format coverage
    // =========================================================================

    @Test
    fun `parseTime handles HH mm 24-hour format`() {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            add(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val hh = target.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val mm = target.get(Calendar.MINUTE).toString().padStart(2, '0')
        val text = "$hh:$mm"
        val result = TransformRegistry.apply("parseTime", text) as? Long
        assertNotNull(result)
        assertEquals(target.timeInMillis, result)
    }

    @Test
    fun `unparseable text returns null`() {
        assertNull(TransformRegistry.apply("parseDeadline", "no time here"))
        assertNull(TransformRegistry.apply("parseTime", "garbage"))
    }
}

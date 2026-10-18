package cloud.trotter.dashbuddy.feature.bubble.hud

import org.junit.Test
import org.junit.Assert.*
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.SessionNet
import cloud.trotter.dashbuddy.domain.state.*
import cloud.trotter.dashbuddy.domain.model.cards.*
import cloud.trotter.dashbuddy.domain.model.event.AppEventType

class RibbonLayoutTest {
    private fun model(segments: List<RibbonSegment> = emptyList(), marks: List<OfferMark> = emptyList(), tail: RibbonKind? = null) =
        RibbonModel(0, segments, marks, tail)
    private fun layout(model: RibbonModel, now: Long = 1000) = RibbonLayout.layout(model, now, 106f, 6f, 1.5f, 8.5f)

    @Test
    fun `positions scale to now with the knob inset`() {
        val geometry = layout(model(listOf(RibbonSegment(RibbonKind.PICKUP, 250, 500)), tail = RibbonKind.WAIT))
        assertEquals(25f, geometry.rects.single().left, 0.01f)
        assertEquals(50f, geometry.rects.single().right, 0.01f)
        assertEquals(100f, geometry.knobX!!, 0.01f)
    }

    @Test
    fun `short segment keeps the minimum width`() {
        val rect = layout(model(listOf(RibbonSegment(RibbonKind.PICKUP, 1, 2)))).rects.single()
        assertEquals(1.5f, rect.right - rect.left, 0.001f)
    }

    @Test
    fun `close marks are pushed apart`() {
        val marks = layout(model(marks = listOf(OfferMark(900, true), OfferMark(901, false), OfferMark(999, true)))).marks
        assertTrue(marks.last().x <= 100f)
        assertTrue(marks.zipWithNext().all { (a, b) -> b.x - a.x >= 8.49f })
        assertEquals(listOf(true, false, true), marks.map { it.accepted })
    }

    @Test
    fun `live tail grows as now advances`() {
        val m = model(listOf(RibbonSegment(RibbonKind.PICKUP, 500, null)), tail = RibbonKind.PICKUP)
        val before = layout(m, 1000).rects.single()
        val after = layout(m, 2000).rects.single()
        assertTrue(after.right - after.left > before.right - before.left)
        assertTrue(after.live)
    }

    @Test
    fun `now equal to start draws nothing and does not divide by zero`() {
        assertEquals(RibbonGeometry(emptyList(), emptyList(), null), layout(model(tail = RibbonKind.WAIT), 0))
    }

    @Test
    fun `a widened short segment is not overpainted by the next`() {
        val rects = layout(model(listOf(RibbonSegment(RibbonKind.PICKUP, 1, 2), RibbonSegment(RibbonKind.WAIT, 2, 5000)))).rects
        assertEquals(1.5f, rects[0].right - rects[0].left, 0.001f)
        org.junit.Assert.assertTrue(rects[1].left >= rects[0].right)
    }
}

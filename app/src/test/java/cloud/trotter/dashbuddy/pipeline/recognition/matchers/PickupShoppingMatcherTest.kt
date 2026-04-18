package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.order.PickupStatus
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupShoppingMatcher
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickupShoppingMatcherTest {

    private val matcher = PickupShoppingMatcher()

    // --- TEST DATA ---

    // Standard shopping screen with tab layout
    private val shoppingLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
      UiNode(, id=action_bar_root, state=null, class=android.widget.LinearLayout)
        UiNode(, id=content, state=null, class=android.widget.FrameLayout)
          UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
            UiNode(text='Shop and Deliver', id=no_id, state=null, class=android.widget.TextView)
            UiNode(, id=tab_layout, state=null, class=android.widget.LinearLayout)
              UiNode(text='To shop (3)', id=no_id, state=null, class=android.widget.TextView)
              UiNode(text='Done', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // Alternative: "To shop" text instead of tab_layout ID
    private val shoppingFallbackLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Shop and Deliver', id=no_id, state=null, class=android.widget.TextView)
  UiNode(text='To shop (5 items)', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // "Shop and Deliver" title but no tabs or "To shop" — should NOT match
    private val titleOnlyLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Shop and Deliver', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    private val unrelatedLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Heading to McDonald''s', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // --- RECOGNITION TESTS ---

    @Test
    fun `matches PICKUP_DETAILS_POST_ARRIVAL_SHOP with tab layout`() {
        val root = LogToUiNodeParser.parseLog(shoppingLog)
        assertNotNull("Failed to parse log", root)

        val result = matcher.matches(root!!)

        assertNotNull("Should match shopping screen", result)
        assertTrue(result is ScreenInfo.PickupDetails)
        assertEquals(Screen.PICKUP_DETAILS_POST_ARRIVAL_SHOP, result!!.screen)
    }

    @Test
    fun `matches PICKUP_DETAILS_POST_ARRIVAL_SHOP with To shop text fallback`() {
        val root = LogToUiNodeParser.parseLog(shoppingFallbackLog)
        assertNotNull("Failed to parse log", root)

        val result = matcher.matches(root!!)

        assertNotNull("Should match fallback layout", result)
        assertEquals(Screen.PICKUP_DETAILS_POST_ARRIVAL_SHOP, result!!.screen)
    }

    @Test
    fun `returns null when Shop and Deliver title present but no tabs or To shop text`() {
        val root = LogToUiNodeParser.parseLog(titleOnlyLog)
        assertNotNull("Failed to parse log", root)

        assertNull(matcher.matches(root!!))
    }

    @Test
    fun `returns null for unrelated screen`() {
        val root = LogToUiNodeParser.parseLog(unrelatedLog)
        assertNotNull("Failed to parse log", root)

        assertNull(matcher.matches(root!!))
    }

    // --- PARSING TESTS ---

    @Test
    fun `store name is null (not available on shopping screen)`() {
        val root = LogToUiNodeParser.parseLog(shoppingLog)!!
        val result = matcher.matches(root) as ScreenInfo.PickupDetails

        // Store name uses "sticky" logic from previous screen — not parsed here
        assertNull("Store name not available on shopping screen", result.storeName)
    }

    @Test
    fun `status is SHOPPING`() {
        val root = LogToUiNodeParser.parseLog(shoppingLog)!!
        val result = matcher.matches(root) as ScreenInfo.PickupDetails

        assertEquals(PickupStatus.SHOPPING, result.status)
    }
}

package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.pipeline.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.DashSummaryMatcher
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashSummaryMatcherTest {

    private val matcher = DashSummaryMatcher()

    // Log 1: Standard Dash Summary ($60.41)
    private val log1 = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
      UiNode(, id=action_bar_root, state=null, class=android.widget.FrameLayout)
        UiNode(, id=content, state=null, class=android.widget.FrameLayout)
          UiNode(, id=layout_bottom_sheet, state=null, class=android.view.ViewGroup)
            UiNode(, id=overlay_view, state=null, class=android.view.View)
            UiNode(, id=prism_sheet, state=null, class=android.view.ViewGroup)
              UiNode(, id=prism_sheet_collar_view, state=null, class=android.widget.FrameLayout)
                UiNode(, id=handle_overlay, state=null, class=android.widget.FrameLayout)
                  UiNode(, id=handle_overlay_image, state=null, class=android.widget.ImageView)
              UiNode(, id=prism_sheet_content_parent_container, state=null, class=android.view.ViewGroup)
                UiNode(, id=nested_scroll_view, state=null, class=android.widget.ScrollView)
                  UiNode(, id=prism_content_container, state=null, class=android.widget.LinearLayout)
                    UiNode(, id=bottomsheet_content_container, state=null, class=android.widget.FrameLayout)
                      UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                        UiNode(, id=dash_summary_list, state=null, class=androidx.recyclerview.widget.RecyclerView)
                          UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                            UiNode(text='Dash summary', id=header_title, state=null, class=android.widget.TextView)
                            UiNode(text='$60.41', id=header_pay, state=null, class=android.widget.TextView)
                          UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                            UiNode(, id=root_container, state=null, class=android.widget.LinearLayout)
                              UiNode(, id=earnings_container, state=null, class=android.view.ViewGroup)
                                UiNode(, id=section_title_container, state=null, class=android.view.ViewGroup)
                                UiNode(, id=expandable_view, state=null, class=android.widget.LinearLayout)
                                  UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                    UiNode(, id=expandable_layout, state=null, class=android.view.ViewGroup)
                                      UiNode(, id=expandable_items, state=null, class=androidx.recyclerview.widget.RecyclerView)
                                        UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                          UiNode(, id=leading_icon, state=null, class=android.widget.ImageView)
                                          UiNode(text='Total online time', id=name, state=null, class=android.widget.TextView)
                                          UiNode(text='3 hr 14 min', id=value, state=null, class=android.widget.TextView)
                                        UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                          UiNode(, id=leading_icon, state=null, class=android.widget.ImageView)
                                          UiNode(text='Offers accepted', id=name, state=null, class=android.widget.TextView)
                                          UiNode(text='6 out of 15', id=value, state=null, class=android.widget.TextView)
                                        UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                          UiNode(, id=item_divider, state=null, class=android.view.View)
                                        UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                          UiNode(, id=leading_icon, state=null, class=android.widget.ImageView)
                                          UiNode(text='Earnings this week', id=name, state=null, class=android.widget.TextView)
                                          UiNode(text='$151.93', id=value, state=null, class=android.widget.TextView)
                                      UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                  UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                    UiNode(text='Payout sent to your Crimson Debit Account', id=metadata_line_item_title, state=null, class=android.widget.TextView)
              UiNode(, id=prism_sheet_header_divider, state=null, class=android.view.View)
            UiNode(, id=prism_sheet_footer_divider, state=null, class=android.view.View)
            UiNode(, id=prism_sheet_footer_container, state=null, class=android.widget.LinearLayout)
              UiNode(, id=prism_sheet_actions_container, state=null, class=android.widget.LinearLayout)
                UiNode(, id=no_id, state=null, class=android.widget.Button)
                  UiNode(text='Done', id=textView_prism_button_title, state=null, class=android.widget.TextView)
  UiNode(, id=navigationBarBackground, state=null, class=android.view.View)
""".trimIndent()

    // Log 2: Another Dash Summary ($42.20)
    private val log2 = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
      UiNode(, id=action_bar_root, state=null, class=android.widget.FrameLayout)
        UiNode(, id=content, state=null, class=android.widget.FrameLayout)
          UiNode(, id=layout_bottom_sheet, state=null, class=android.view.ViewGroup)
            UiNode(, id=overlay_view, state=null, class=android.view.View)
            UiNode(, id=prism_sheet_footer_container, state=null, class=android.widget.LinearLayout)
              UiNode(, id=prism_sheet_actions_container, state=null, class=android.widget.LinearLayout)
                UiNode(, id=no_id, state=null, class=android.widget.Button)
                  UiNode(text='Done', id=textView_prism_button_title, state=null, class=android.widget.TextView)
            UiNode(, id=prism_sheet, state=null, class=android.view.ViewGroup)
              UiNode(, id=prism_sheet_collar_view, state=null, class=android.widget.FrameLayout)
                UiNode(, id=handle_overlay, state=null, class=android.widget.FrameLayout)
                  UiNode(, id=handle_overlay_image, state=null, class=android.widget.ImageView)
              UiNode(, id=prism_sheet_content_parent_container, state=null, class=android.view.ViewGroup)
                UiNode(, id=nested_scroll_view, state=null, class=android.widget.ScrollView)
                  UiNode(, id=prism_content_container, state=null, class=android.widget.LinearLayout)
                    UiNode(, id=bottomsheet_content_container, state=null, class=android.widget.FrameLayout)
                      UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                        UiNode(, id=dash_summary_list, state=null, class=androidx.recyclerview.widget.RecyclerView)
                          UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                            UiNode(text='Dash summary', id=header_title, state=null, class=android.widget.TextView)
                            UiNode(text='$42.20', id=header_pay, state=null, class=android.widget.TextView)
                          UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                            UiNode(, id=root_container, state=null, class=android.widget.LinearLayout)
                              UiNode(, id=earnings_container, state=null, class=android.view.ViewGroup)
                                UiNode(, id=section_title_container, state=null, class=android.view.ViewGroup)
                                UiNode(, id=expandable_view, state=null, class=android.widget.LinearLayout)
                                  UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                    UiNode(, id=expandable_layout, state=null, class=android.view.ViewGroup)
                                      UiNode(, id=expandable_items, state=null, class=androidx.recyclerview.widget.RecyclerView)
                                        UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                          UiNode(, id=leading_icon, state=null, class=android.widget.ImageView)
                                          UiNode(text='Total online time', id=name, state=null, class=android.widget.TextView)
                                          UiNode(text='2 hr 27 min', id=value, state=null, class=android.widget.TextView)
                                        UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                          UiNode(, id=leading_icon, state=null, class=android.widget.ImageView)
                                          UiNode(text='Offers accepted', id=name, state=null, class=android.widget.TextView)
                                          UiNode(text='4 out of 21', id=value, state=null, class=android.widget.TextView)
                                        UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                          UiNode(, id=item_divider, state=null, class=android.view.View)
                                        UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                          UiNode(, id=leading_icon, state=null, class=android.widget.ImageView)
                                          UiNode(text='Earnings this week', id=name, state=null, class=android.widget.TextView)
                                          UiNode(text='$194.13', id=value, state=null, class=android.widget.TextView)
                                      UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                  UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                    UiNode(text='Payout sent to your Crimson Debit Account', id=metadata_line_item_title, state=null, class=android.widget.TextView)
              UiNode(, id=prism_sheet_header_divider, state=null, class=android.view.View)
            UiNode(, id=prism_sheet_footer_divider, state=null, class=android.view.View)
  UiNode(, id=navigationBarBackground, state=null, class=android.view.View)
""".trimIndent()

    @Test
    fun `matches log1 correctly`() {
        val root = LogToUiNodeParser.parseLog(log1)!!
        val result = matcher.matches(root)

        assertNotNull("Should match Dash Summary", result)
        assertTrue(result is ScreenInfo.DashSummary)

        val info = result as ScreenInfo.DashSummary

        // Assert Pay
        assertEquals("Total Pay", 60.41, info.totalEarnings!!, 0.01)
        assertEquals("Weekly Pay", 151.93, info.weeklyEarnings!!, 0.01)

        // Assert Stats
        assertEquals("Accepted", 6, info.offersAccepted)
        assertEquals("Total Offers", 15, info.offersTotal)

        // Assert Duration (3hr 14min = 194 min = 11,640,000 ms)
        val expectedMillis = (3 * 3600 * 1000L) + (14 * 60 * 1000L)
        assertEquals("Duration", expectedMillis, info.onlineDurationMillis)
    }

    @Test
    fun `matches log2 correctly`() {
        val root = LogToUiNodeParser.parseLog(log2)!!
        val result = matcher.matches(root)

        assertNotNull("Should match Dash Summary", result)
        assertTrue(result is ScreenInfo.DashSummary)

        val info = result as ScreenInfo.DashSummary

        // Assert Pay
        assertEquals("Total Pay", 42.20, info.totalEarnings!!, 0.01)

        // Assert Stats
        assertEquals("Accepted", 4, info.offersAccepted)
        assertEquals("Total Offers", 21, info.offersTotal)

        // Assert Duration (2hr 27min = 147 min = 8,820,000 ms)
        val expectedMillis = (2 * 3600 * 1000L) + (27 * 60 * 1000L)
        assertEquals("Duration", expectedMillis, info.onlineDurationMillis)
    }
}
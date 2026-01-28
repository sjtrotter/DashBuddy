package cloud.trotter.dashbuddy.state.postdelivery

import cloud.trotter.dashbuddy.data.pay.PayParser
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.DeliverySummaryMatcher
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.event.TimeoutEvent
import cloud.trotter.dashbuddy.state.model.TimeoutType
import cloud.trotter.dashbuddy.state.reducers.postdelivery.PostDeliveryReducer
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostDeliveryFlowTest {

    private val payParser: PayParser = PayParser()
    private val matcher = DeliverySummaryMatcher(payParser)

    // --- REUSING YOUR LOGS ---
    // (I'm pasting the relevant ones here for the test context)

    private val collapsedLog = """
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
                        UiNode(, id=delivery_summary_layout, state=null, class=android.widget.ScrollView)
                          UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                            UiNode(text='This dash so far', id=secondary_title, state=null, class=android.widget.TextView)
                            UiNode(, id=title_layout, state=null, class=android.widget.LinearLayout)
                              UiNode(, id=earnings_ticker, state=null, class=androidx.compose.ui.platform.ComposeView)
                                UiNode(, id=no_id, state=null, class=android.view.View)
                                  UiNode(text='$', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='0', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='.', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='0', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='0', id=no_id, state=null, class=android.widget.TextView)
                            UiNode(, id=status_layout, state=null, class=android.view.ViewGroup)
                            UiNode(, id=recyclers, state=null, class=android.view.ViewGroup)
                              UiNode(, id=pay_breakdown_epoxy_recycler, state=null, class=androidx.recyclerview.widget.RecyclerView)
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
                                                UiNode(text=' 38 min', id=value, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(, id=leading_icon, state=null, class=android.widget.ImageView)
                                                UiNode(text='Offers accepted', id=name, state=null, class=android.widget.TextView)
                                                UiNode(text='1 out of 1', id=value, state=null, class=android.widget.TextView)
                                    UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                  UiNode(, id=root_container, state=null, class=android.widget.LinearLayout)
                                    UiNode(, id=earnings_container, state=null, class=android.view.ViewGroup)
                                      UiNode(, id=section_title_container, state=null, class=android.view.ViewGroup)
                                        UiNode(text='This offer', id=section_title, state=null, class=android.widget.TextView)
                                      UiNode(, id=primary_icon, state=null, class=android.widget.ImageView)
                                      UiNode(text='$14.50', id=final_value, state=null, class=android.widget.TextView)
                                      UiNode(, id=expandable_view, state=null, class=android.widget.LinearLayout)
                                    UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                      UiNode(, id=banner_icon, state=null, class=android.widget.ImageView)
                                      UiNode(text='Your Pro Shopper ratings gave you priority for this offer', id=banner_description, state=null, class=android.widget.TextView)
                            UiNode(desc='We've extended your arrival time: We've added time so you can get to your dashing area on time without losing your spot.', id=commute_to_dash_v2_banner, state=null, class=android.view.ViewGroup)
                              UiNode(, id=actions_group, state=null, class=android.view.View)
                              UiNode(, id=subdued_decoration_view, state=null, class=android.view.View)
                              UiNode(text='We've extended your arrival time', id=label_text_view, state=null, class=android.widget.TextView)
                              UiNode(text='We've added time so you can get to your dashing area on time without losing your spot.', id=body_text_view, state=null, class=android.widget.TextView)
                              UiNode(, id=end_icon_button, state=null, class=android.widget.Button)
                              UiNode(, id=button_container, state=null, class=android.widget.LinearLayout)
                              UiNode(, id=subdued_border_view, state=null, class=android.view.View)
              UiNode(, id=prism_sheet_header_divider, state=null, class=android.view.View)
            UiNode(, id=prism_sheet_footer_divider, state=null, class=android.view.View)
            UiNode(, id=prism_sheet_footer_container, state=null, class=android.widget.LinearLayout)
              UiNode(, id=prism_sheet_actions_container, state=null, class=android.widget.LinearLayout)
                UiNode(, id=no_id, state=null, class=android.widget.Button)
                  UiNode(text='Continue dashing', id=textView_prism_button_title, state=null, class=android.widget.TextView)
  UiNode(, id=navigationBarBackground, state=null, class=android.view.View)
""".trimIndent()

    private val expandedLog = """
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
                        UiNode(, id=delivery_summary_layout, state=null, class=android.widget.ScrollView)
                          UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                            UiNode(text='This dash so far', id=secondary_title, state=null, class=android.widget.TextView)
                            UiNode(, id=title_layout, state=null, class=android.widget.LinearLayout)
                              UiNode(, id=earnings_ticker, state=null, class=androidx.compose.ui.platform.ComposeView)
                                UiNode(, id=no_id, state=null, class=android.view.View)
                                  UiNode(text='$', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='1', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='4', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='.', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='5', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='0', id=no_id, state=null, class=android.widget.TextView)
                            UiNode(, id=status_layout, state=null, class=android.view.ViewGroup)
                            UiNode(, id=recyclers, state=null, class=android.view.ViewGroup)
                              UiNode(, id=pay_breakdown_epoxy_recycler, state=null, class=androidx.recyclerview.widget.RecyclerView)
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
                                                UiNode(text=' 38 min', id=value, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(, id=leading_icon, state=null, class=android.widget.ImageView)
                                                UiNode(text='Offers accepted', id=name, state=null, class=android.widget.TextView)
                                                UiNode(text='1 out of 1', id=value, state=null, class=android.widget.TextView)
                                    UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                  UiNode(, id=root_container, state=null, class=android.widget.LinearLayout)
                                    UiNode(, id=earnings_container, state=null, class=android.view.ViewGroup)
                                      UiNode(, id=section_title_container, state=null, class=android.view.ViewGroup)
                                        UiNode(text='This offer', id=section_title, state=null, class=android.widget.TextView)
                                      UiNode(, id=primary_icon, state=null, class=android.widget.ImageView)
                                      UiNode(text='$14.50', id=final_value, state=null, class=android.widget.TextView)
                                      UiNode(, id=expandable_view, state=null, class=android.widget.LinearLayout)
                                        UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                          UiNode(, id=expandable_layout, state=null, class=android.view.ViewGroup)
                                            UiNode(, id=divider, state=null, class=android.view.View)
                                            UiNode(, id=expandable_items, state=null, class=androidx.recyclerview.widget.RecyclerView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='DoorDash pay', id=header, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='Base pay', id=name, state=null, class=android.widget.TextView)
                                                UiNode(text='$9.50', id=value, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='Customer tips', id=header, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='Walgreens (9697)', id=name, state=null, class=android.widget.TextView)
                                                UiNode(text='$5.00', id=value, state=null, class=android.widget.TextView)
                                    UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                      UiNode(, id=banner_icon, state=null, class=android.widget.ImageView)
                                      UiNode(text='Your Pro Shopper ratings gave you priority for this offer', id=banner_description, state=null, class=android.widget.TextView)
                            UiNode(desc='We've extended your arrival time: We've added time so you can get to your dashing area on time without losing your spot.', id=commute_to_dash_v2_banner, state=null, class=android.view.ViewGroup)
                              UiNode(, id=actions_group, state=null, class=android.view.View)
                              UiNode(, id=subdued_decoration_view, state=null, class=android.view.View)
                              UiNode(text='We've extended your arrival time', id=label_text_view, state=null, class=android.widget.TextView)
                              UiNode(text='We've added time so you can get to your dashing area on time without losing your spot.', id=body_text_view, state=null, class=android.widget.TextView)
                              UiNode(, id=end_icon_button, state=null, class=android.widget.Button)
                              UiNode(, id=button_container, state=null, class=android.widget.LinearLayout)
                              UiNode(, id=subdued_border_view, state=null, class=android.view.View)
              UiNode(, id=prism_sheet_header_divider, state=null, class=android.view.View)
            UiNode(, id=prism_sheet_footer_divider, state=null, class=android.view.View)
            UiNode(, id=prism_sheet_footer_container, state=null, class=android.widget.LinearLayout)
              UiNode(, id=prism_sheet_actions_container, state=null, class=android.widget.LinearLayout)
                UiNode(, id=no_id, state=null, class=android.widget.Button)
                  UiNode(text='Continue dashing', id=textView_prism_button_title, state=null, class=android.widget.TextView)
  UiNode(, id=navigationBarBackground, state=null, class=android.view.View)
""".trimIndent()

    @Test
    fun full_transaction_flow_simulation() {
        // 1. SETUP: Start in a neutral state
        var currentState: AppStateV2 = AppStateV2.PostDelivery(
            dashId = "test_dash",
            phase = AppStateV2.PostDelivery.Phase.STABILIZING
        )

        // Prepare our "Real World" Inputs
        val collapsedNode = LogToUiNodeParser.parseLog(collapsedLog)!!
        val expandedNode = LogToUiNodeParser.parseLog(expandedLog)!!

        val collapsedInput = matcher.matches(collapsedNode) as ScreenInfo.DeliverySummaryCollapsed
        val expandedInput = matcher.matches(expandedNode) as ScreenInfo.DeliveryCompleted

        println("--- STARTING SIMULATION ---")

        // ---------------------------------------------------
        // STEP 1: Stabilization (Waiting for screen to settle)
        // ---------------------------------------------------
        println("\nStep 1: Processing Collapsed Screen (Stabilizing)")
        // In the real app, we are already in STABILIZING, so reduce() returns null until timeout
        // But let's verify that inputs don't break it.
        var transition =
            PostDeliveryReducer.reduce(currentState as AppStateV2.PostDelivery, collapsedInput)

        // Assert: It should basically ignore input here while waiting
        assertTrue(transition == null)

        // ---------------------------------------------------
        // STEP 2: Timeout Fires (Move to Clicking)
        // ---------------------------------------------------
        println("\nStep 2: Timeout (EXPAND_STABILITY)")
        transition = PostDeliveryReducer.onTimeout(
            currentState,
            TimeoutEvent(type = TimeoutType.EXPAND_STABILITY)
        )

        // Update our state
        currentState = transition.newState
        assertEquals(
            AppStateV2.PostDelivery.Phase.CLICKING,
            (currentState as AppStateV2.PostDelivery).phase
        )

        // ---------------------------------------------------
        // STEP 3: Clicking Phase (Finding the button)
        // ---------------------------------------------------
        println("\nStep 3: Processing Collapsed Screen (Hunting for button)")
        transition =
            PostDeliveryReducer.reduce(currentState, collapsedInput)!!

        // Update State
        currentState = transition.newState
        val effects = transition.effects

        // ASSERT: We found the button and clicked it?
        val clickEffect = effects.find { it is AppEffect.ClickNode }
        assertTrue("Must generate a ClickNode effect", clickEffect != null)
        assertEquals(
            AppStateV2.PostDelivery.Phase.VERIFYING,
            (currentState as AppStateV2.PostDelivery).phase
        )
        println("   -> Click Fired on node: ${(clickEffect as AppEffect.ClickNode).node.text}")

        // ---------------------------------------------------
        // STEP 4: Verifying (Waiting for data)
        // ---------------------------------------------------
        println("\nStep 4: Processing Expanded Screen (Data Arrived)")
        // Now we feed it the EXPANDED log
        transition =
            PostDeliveryReducer.reduce(currentState, expandedInput)!!

        currentState = transition.newState
        val parsedPay = (currentState as AppStateV2.PostDelivery).parsedPay

        // ASSERT: Data was captured
        assertTrue("Pay data must be captured", parsedPay != null)
        assertEquals(14.5, parsedPay?.total!!, 0.01)
        println("   -> Data Verified: $${parsedPay.total}")

        // ---------------------------------------------------
        // STEP 5: Final Timeout (Recording)
        // ---------------------------------------------------
        println("\nStep 5: Timeout (VERIFY_PAY)")
        transition = PostDeliveryReducer.onTimeout(
            currentState,
            TimeoutEvent(type = TimeoutType.VERIFY_PAY)
        )

        currentState = transition.newState
        assertEquals(
            AppStateV2.PostDelivery.Phase.RECORDED,
            (currentState as AppStateV2.PostDelivery).phase
        )

        // ASSERT: Database Event Logged
        val logEvent = transition.effects.find { it is AppEffect.LogEvent }
        assertTrue("Must log event to DB", logEvent != null)
        println("   -> Transaction Successfully Recorded!")
    }
}
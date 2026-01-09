package cloud.trotter.dashbuddy.services.accessibility.screen.matchers

import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.*
import org.junit.Test

class DeliverySummaryMatcherTest {

    private val matcher = DeliverySummaryMatcher()

    // --- LOG SNAPSHOTS ---

    // Snapshot 1: The "Buggy" Collapsed State
    // "This dash so far" appears BEFORE "This offer".
    // We need to ensure we don't click the "This dash" expandable_view.
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
                            UiNode(, id=celebrations_animation_view, state=null, class=android.widget.ImageView)
                            UiNode(text='This dash so far', id=secondary_title, state=null, class=android.widget.TextView)
                            UiNode(, id=title_layout, state=null, class=android.widget.LinearLayout)
                              UiNode(, id=earnings_ticker, state=null, class=androidx.compose.ui.platform.ComposeView)
                                UiNode(, id=no_id, state=null, class=android.view.View)
                                  UiNode(text='$', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='3', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='6', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='4', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='.', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='2', id=no_id, state=null, class=android.widget.TextView)
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
                                                UiNode(text='2 hr 23 min', id=value, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(, id=leading_icon, state=null, class=android.widget.ImageView)
                                                UiNode(text='Offers accepted', id=name, state=null, class=android.widget.TextView)
                                                UiNode(text='4 out of 18', id=value, state=null, class=android.widget.TextView)
                                    UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                  UiNode(, id=root_container, state=null, class=android.widget.LinearLayout)
                                    UiNode(, id=earnings_container, state=null, class=android.view.ViewGroup)
                                      UiNode(, id=section_title_container, state=null, class=android.view.ViewGroup)
                                        UiNode(text='This offer', id=section_title, state=null, class=android.widget.TextView)
                                      UiNode(, id=primary_icon, state=null, class=android.widget.ImageView)
                                      UiNode(text='$8.00', id=final_value, state=null, class=android.widget.TextView)
                                      UiNode(, id=expandable_view, state=null, class=android.widget.LinearLayout)
                                    UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
              UiNode(, id=prism_sheet_header_divider, state=null, class=android.view.View)
            UiNode(, id=prism_sheet_footer_divider, state=null, class=android.view.View)
            UiNode(, id=prism_sheet_footer_container, state=null, class=android.widget.LinearLayout)
              UiNode(, id=prism_sheet_actions_container, state=null, class=android.widget.LinearLayout)
                UiNode(, id=no_id, state=null, class=android.widget.Button)
                  UiNode(text='Continue dashing', id=textView_prism_button_title, state=null, class=android.widget.TextView)
  UiNode(, id=navigationBarBackground, state=null, class=android.view.View)
""".trimIndent()

    // Snapshot 2: The Expanded State
    // Contains "DoorDash pay" and "Customer tips" headers
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
                            UiNode(, id=celebrations_animation_view, state=null, class=android.widget.ImageView)
                            UiNode(text='This dash so far', id=secondary_title, state=null, class=android.widget.TextView)
                            UiNode(, id=title_layout, state=null, class=android.widget.LinearLayout)
                              UiNode(, id=earnings_ticker, state=null, class=androidx.compose.ui.platform.ComposeView)
                                UiNode(, id=no_id, state=null, class=android.view.View)
                                  UiNode(text='$', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='4', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='2', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='.', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='2', id=no_id, state=null, class=android.widget.TextView)
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
                                UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                  UiNode(, id=root_container, state=null, class=android.widget.LinearLayout)
                                    UiNode(, id=earnings_container, state=null, class=android.view.ViewGroup)
                                      UiNode(, id=section_title_container, state=null, class=android.view.ViewGroup)
                                        UiNode(text='This offer', id=section_title, state=null, class=android.widget.TextView)
                                      UiNode(, id=primary_icon, state=null, class=android.widget.ImageView)
                                      UiNode(text='$8.00', id=final_value, state=null, class=android.widget.TextView)
                                      UiNode(, id=expandable_view, state=null, class=android.widget.LinearLayout)
                                        UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                          UiNode(, id=expandable_layout, state=null, class=android.view.ViewGroup)
                                            UiNode(, id=divider, state=null, class=android.view.View)
                                            UiNode(, id=expandable_items, state=null, class=androidx.recyclerview.widget.RecyclerView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='DoorDash pay', id=header, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='Base pay', id=name, state=null, class=android.widget.TextView)
                                                UiNode(text='$3.00', id=value, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='Customer tips', id=header, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='Walgreens (11971)', id=name, state=null, class=android.widget.TextView)
                                                UiNode(text='$5.00', id=value, state=null, class=android.widget.TextView)
                                    UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
              UiNode(, id=prism_sheet_header_divider, state=null, class=android.view.View)
            UiNode(, id=prism_sheet_footer_divider, state=null, class=android.view.View)
            UiNode(, id=prism_sheet_footer_container, state=null, class=android.widget.LinearLayout)
              UiNode(, id=prism_sheet_actions_container, state=null, class=android.widget.LinearLayout)
                UiNode(, id=no_id, state=null, class=android.widget.Button)
                  UiNode(text='Continue dashing', id=textView_prism_button_title, state=null, class=android.widget.TextView)
  UiNode(, id=navigationBarBackground, state=null, class=android.view.View)
""".trimIndent()

    // Snapshot 3: Expanded, additional Dasher pay
    // Contains "DoorDash pay" and "Customer tips" headers
    private val dashPayLog = """
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
                            UiNode(, id=celebrations_animation_view, state=null, class=android.widget.ImageView)
                            UiNode(text='This dash so far', id=secondary_title, state=null, class=android.widget.TextView)
                            UiNode(, id=title_layout, state=null, class=android.widget.LinearLayout)
                              UiNode(, id=earnings_ticker, state=null, class=androidx.compose.ui.platform.ComposeView)
                                UiNode(, id=no_id, state=null, class=android.view.View)
                                  UiNode(text='$', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='4', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='2', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='.', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='2', id=no_id, state=null, class=android.widget.TextView)
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
                                UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                  UiNode(, id=root_container, state=null, class=android.widget.LinearLayout)
                                    UiNode(, id=earnings_container, state=null, class=android.view.ViewGroup)
                                      UiNode(, id=section_title_container, state=null, class=android.view.ViewGroup)
                                        UiNode(text='This offer', id=section_title, state=null, class=android.widget.TextView)
                                      UiNode(, id=primary_icon, state=null, class=android.widget.ImageView)
                                      UiNode(text='$8.00', id=final_value, state=null, class=android.widget.TextView)
                                      UiNode(, id=expandable_view, state=null, class=android.widget.LinearLayout)
                                        UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                          UiNode(, id=expandable_layout, state=null, class=android.view.ViewGroup)
                                            UiNode(, id=divider, state=null, class=android.view.View)
                                            UiNode(, id=expandable_items, state=null, class=androidx.recyclerview.widget.RecyclerView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='DoorDash pay', id=header, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='Base pay', id=name, state=null, class=android.widget.TextView)
                                                UiNode(text='$3.00', id=value, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='Arbitrarily Named Doordash Additional Pay', id=name, state=null, class=android.widget.TextView)
                                                UiNode(text='$3.00', id=value, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='Customer tips', id=header, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='Walgreens (11971)', id=name, state=null, class=android.widget.TextView)
                                                UiNode(text='$5.00', id=value, state=null, class=android.widget.TextView)
                                    UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
              UiNode(, id=prism_sheet_header_divider, state=null, class=android.view.View)
            UiNode(, id=prism_sheet_footer_divider, state=null, class=android.view.View)
            UiNode(, id=prism_sheet_footer_container, state=null, class=android.widget.LinearLayout)
              UiNode(, id=prism_sheet_actions_container, state=null, class=android.widget.LinearLayout)
                UiNode(, id=no_id, state=null, class=android.widget.Button)
                  UiNode(text='Continue dashing', id=textView_prism_button_title, state=null, class=android.widget.TextView)
  UiNode(, id=navigationBarBackground, state=null, class=android.view.View)
""".trimIndent()

    // Snapshot 4: Expanded, stacked order
    // Contains "DoorDash pay" and "Customer tips" headers
    private val stackedOrder = """
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
                            UiNode(, id=celebrations_animation_view, state=null, class=android.widget.ImageView)
                            UiNode(text='This dash so far', id=secondary_title, state=null, class=android.widget.TextView)
                            UiNode(, id=title_layout, state=null, class=android.widget.LinearLayout)
                              UiNode(, id=earnings_ticker, state=null, class=androidx.compose.ui.platform.ComposeView)
                                UiNode(, id=no_id, state=null, class=android.view.View)
                                  UiNode(text='$', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='1', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='0', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='.', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='7', id=no_id, state=null, class=android.widget.TextView)
                                  UiNode(text='5', id=no_id, state=null, class=android.widget.TextView)
                            UiNode(, id=status_layout, state=null, class=android.view.ViewGroup)
                            UiNode(, id=recyclers, state=null, class=android.view.ViewGroup)
                              UiNode(, id=pay_breakdown_epoxy_recycler, state=null, class=androidx.recyclerview.widget.RecyclerView)
                                UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                  UiNode(, id=root_container, state=null, class=android.widget.LinearLayout)
                                    UiNode(, id=earnings_container, state=null, class=android.view.ViewGroup)
                                      UiNode(, id=section_title_container, state=null, class=android.view.ViewGroup)
                                      UiNode(, id=expandable_view, state=null, class=android.widget.LinearLayout)
                                    UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                  UiNode(, id=root_container, state=null, class=android.widget.LinearLayout)
                                    UiNode(, id=earnings_container, state=null, class=android.view.ViewGroup)
                                      UiNode(, id=section_title_container, state=null, class=android.view.ViewGroup)
                                        UiNode(text='This offer', id=section_title, state=null, class=android.widget.TextView)
                                      UiNode(, id=primary_icon, state=null, class=android.widget.ImageView)
                                      UiNode(text='$10.75', id=final_value, state=null, class=android.widget.TextView)
                                      UiNode(, id=expandable_view, state=null, class=android.widget.LinearLayout)
                                        UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                          UiNode(, id=expandable_layout, state=null, class=android.view.ViewGroup)
                                            UiNode(, id=divider, state=null, class=android.view.View)
                                            UiNode(, id=expandable_items, state=null, class=androidx.recyclerview.widget.RecyclerView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='DoorDash pay', id=header, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='Base pay', id=name, state=null, class=android.widget.TextView)
                                                UiNode(text='$6.75', id=value, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='Customer tips', id=header, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='Target (02239)', id=name, state=null, class=android.widget.TextView)
                                                UiNode(text='$1.00', id=value, state=null, class=android.widget.TextView)
                                              UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
                                                UiNode(text='Target (02239)', id=name, state=null, class=android.widget.TextView)
                                                UiNode(text='$3.00', id=value, state=null, class=android.widget.TextView)
                                    UiNode(, id=no_id, state=null, class=android.view.ViewGroup)
              UiNode(, id=prism_sheet_header_divider, state=null, class=android.view.View)
            UiNode(, id=prism_sheet_footer_divider, state=null, class=android.view.View)
            UiNode(, id=prism_sheet_footer_container, state=null, class=android.widget.LinearLayout)
              UiNode(, id=prism_sheet_actions_container, state=null, class=android.widget.LinearLayout)
                UiNode(, id=no_id, state=null, class=android.widget.Button)
                  UiNode(text='Continue dashing', id=textView_prism_button_title, state=null, class=android.widget.TextView)
  UiNode(, id=navigationBarBackground, state=null, class=android.view.View)
""".trimIndent()

    // --- TESTS ---

    @Test
    fun `matches returns null when anchors are missing`() {
        val root = LogToUiNodeParser.parseLog("UiNode(text='Random Screen', class=View)")!!
        val context = StateContext(rootUiNode = root)

        val result = matcher.matches(context)

        assertNull("Should return null if 'This offer' is missing", result)
    }

    @Test
    fun `matches returns COLLAPSED and finds CORRECT button when both dash and offer summaries exist`() {
        val root = LogToUiNodeParser.parseLog(collapsedLog)
        assertNotNull("Failed to parse log string", root)
        val context = StateContext(rootUiNode = root)

        val result = matcher.matches(context)

        assertNotNull("Should match the screen", result)
        assertTrue("Should be Collapsed state", result is ScreenInfo.DeliverySummaryCollapsed)

        val info = result as ScreenInfo.DeliverySummaryCollapsed
        val clickedButton = info.expandButton
        assertNotNull("Must return a click target", clickedButton)

        // --- THE CRITICAL ASSERTION ---
        // Verify the clicked button is the sibling of "This offer", NOT "This dash so far"
        val parent = clickedButton.parent
        assertNotNull(parent)

        // Check the parent's other children for the text "This offer" (via the title container)
        // Based on the log structure: Button -> Parent -> TitleContainer -> Title("This offer")
        // We iterate the parent's children to verify we are in the correct container.
        val titleContainer = parent?.children?.find {
            it.viewIdResourceName?.endsWith("section_title_container") == true
        }
        val titleText = titleContainer?.children?.find {
            it.text == "This offer"
        }

        assertNotNull(
            "The clicked button must belong to the container with 'This offer'",
            titleText
        )
    }

    @Test
    fun `matches returns EXPANDED and parses pay correctly`() {
        val root = LogToUiNodeParser.parseLog(expandedLog)
        assertNotNull(root)
        val context = StateContext(rootUiNode = root)

        val result = matcher.matches(context)

        assertNotNull("Should match EXPANDED screen", result)
        assertTrue("Result should be DeliveryCompleted", result is ScreenInfo.DeliveryCompleted)

        val info = result as ScreenInfo.DeliveryCompleted
        assertEquals(Screen.DELIVERY_SUMMARY_EXPANDED, info.screen)

        val pay = info.parsedPay

        // Assertions based on the new log values:
        // Base pay: $3.00
        // Tip (Walgreens): $5.00
        // Total: $8.00

        assertEquals("Total Base Pay", 3.00, pay.totalBasePay, 0.01)
        assertEquals("Total Tip", 5.00, pay.totalTip, 0.01)
        assertEquals("Total Pay", 8.00, pay.total, 0.01)
    }

    @Test
    fun `matches returns EXPANDED and parses additional arbitrary doordash pay`() {
        val root = LogToUiNodeParser.parseLog(dashPayLog)
        assertNotNull(root)
        val context = StateContext(rootUiNode = root)

        val result = matcher.matches(context)

        assertNotNull("Should match EXPANDED screen", result)
        assertTrue("Result should be DeliveryCompleted", result is ScreenInfo.DeliveryCompleted)

        val info = result as ScreenInfo.DeliveryCompleted
        assertEquals(Screen.DELIVERY_SUMMARY_EXPANDED, info.screen)

        val pay = info.parsedPay

        // Assertions based on the new log values:
        // Base pay: $3.00
        // Tip (Walgreens): $5.00
        // Total: $8.00

        assertEquals("Total Base Pay", 6.00, pay.totalBasePay, 0.01)
        assertEquals("Total Tip", 5.00, pay.totalTip, 0.01)
        assertEquals("Total Pay", 11.00, pay.total, 0.01)
    }

    @Test
    fun `matches returns EXPANDED and parses stacked orders`() {
        val root = LogToUiNodeParser.parseLog(stackedOrder)
        assertNotNull(root)
        val context = StateContext(rootUiNode = root)

        val result = matcher.matches(context)

        assertNotNull("Should match EXPANDED screen", result)
        assertTrue("Result should be DeliveryCompleted", result is ScreenInfo.DeliveryCompleted)

        val info = result as ScreenInfo.DeliveryCompleted
        assertEquals(Screen.DELIVERY_SUMMARY_EXPANDED, info.screen)

        val pay = info.parsedPay

        // Assertions based on the new log values:
        // Base pay: $3.00
        // Tip (Walgreens): $5.00
        // Total: $8.00

        assertEquals("Total Base Pay", 6.75, pay.totalBasePay, 0.01)
        assertEquals("Total Tip", 4.0, pay.totalTip, 0.01)
        assertEquals("Total Pay", 10.75, pay.total, 0.01)
    }
}
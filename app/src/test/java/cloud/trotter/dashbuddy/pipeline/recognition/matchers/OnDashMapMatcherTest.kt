package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.OnDashMapMatcher
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OnDashMapMatcherTest {

    private val matcher = OnDashMapMatcher()

    // Dasher is mid-dash and has navigated to the home map screen.
    // The "Return to dash" button is visible.
    private val returnToDashLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
      UiNode(, id=action_bar_root, state=null, class=android.widget.LinearLayout)
        UiNode(, id=content, state=null, class=android.widget.FrameLayout)
          UiNode(, id=overlay_gesture_manager, state=null, class=android.widget.FrameLayout)
            UiNode(, id=root_constraint_layout, state=null, class=android.view.ViewGroup)
              UiNode(, id=side_nav_content_container, state=null, class=android.widget.FrameLayout)
                UiNode(, id=main_fragment, state=null, class=android.widget.FrameLayout)
                  UiNode(, id=no_id, state=null, class=androidx.compose.ui.platform.ComposeView)
                    UiNode(, id=no_id, state=null, class=android.view.View)
                      UiNode(, id=no_id, state=null, class=android.view.View)
                        UiNode(, id=no_id, state=null, class=androidx.compose.ui.viewinterop.ViewFactoryHolder)
                          UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
                            UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
                              UiNode(, id=no_id, state=null, class=android.view.TextureView)
                              UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
                        UiNode(, id=no_id, state=null, class=android.view.View)
                          UiNode(desc='Side Menu', id=no_id, state=null, class=android.view.View)
                          UiNode(, id=no_id, state=null, class=android.widget.Button)
                        UiNode(, id=no_id, state=null, class=android.view.View)
                          UiNode(, id=no_id, state=null, class=android.widget.Button)
                        UiNode(, id=no_id, state=null, class=android.view.View)
                          UiNode(desc='', id=no_id, state=null, class=android.view.View)
                          UiNode(, id=no_id, state=null, class=android.widget.Button)
                        UiNode(, id=no_id, state=null, class=android.view.View)
                          UiNode(text='${'$'}', id=no_id, state=null, class=android.widget.TextView)
                          UiNode(text='0', id=no_id, state=null, class=android.widget.TextView)
                          UiNode(text='.', id=no_id, state=null, class=android.widget.TextView)
                          UiNode(text='0', id=no_id, state=null, class=android.widget.TextView)
                          UiNode(text='0', id=no_id, state=null, class=android.widget.TextView)
                          UiNode(text='This week', id=no_id, state=null, class=android.widget.TextView)
                          UiNode(, id=no_id, state=null, class=android.widget.Button)
                        UiNode(, id=no_id, state=null, class=android.view.View)
                          UiNode(, id=no_id, state=null, class=android.widget.ScrollView)
                        UiNode(, id=no_id, state=null, class=android.view.View)
                          UiNode(desc='', id=no_id, state=null, class=android.view.View)
                          UiNode(, id=no_id, state=null, class=android.widget.Button)
                        UiNode(, id=no_id, state=null, class=android.view.View)
                          UiNode(desc='Safety tools', id=no_id, state=null, class=android.view.View)
                          UiNode(, id=no_id, state=null, class=android.widget.Button)
                        UiNode(, id=no_id, state=null, class=android.view.View)
                          UiNode(text='Return to dash', id=no_id, state=null, class=android.widget.TextView)
                          UiNode(, id=no_id, state=null, class=android.widget.Button)
              UiNode(, id=side_nav_container, state=null, class=android.widget.FrameLayout)
                UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
                  UiNode(, id=side_nav_compose_view, state=null, class=androidx.compose.ui.platform.ComposeView)
                    UiNode(, id=no_id, state=null, class=android.view.View)
                      UiNode(, id=no_id, state=null, class=android.view.View)
                        UiNode(, id=no_id, state=null, class=android.view.View)
                          UiNode(text='Stephen T', id=no_id, state=null, class=android.widget.TextView)
              UiNode(, id=side_nav_header_background, state=null, class=androidx.compose.ui.platform.ComposeView)
                UiNode(, id=no_id, state=null, class=android.view.View)
""".trimIndent()

    // Idle map — has Earnings Mode Switcher, no "Return to dash".
    // OnDashMapMatcher must NOT match this.
    private val idleMapLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
      UiNode(, id=action_bar_root, state=null, class=android.widget.LinearLayout)
        UiNode(, id=content, state=null, class=android.widget.FrameLayout)
          UiNode(, id=overlay_gesture_manager, state=null, class=android.widget.FrameLayout)
            UiNode(, id=root_constraint_layout, state=null, class=android.view.ViewGroup)
              UiNode(, id=side_nav_content_container, state=null, class=android.widget.FrameLayout)
                UiNode(, id=main_fragment, state=null, class=android.widget.FrameLayout)
                  UiNode(, id=no_id, state=null, class=androidx.compose.ui.platform.ComposeView)
                    UiNode(, id=no_id, state=null, class=android.view.View)
                      UiNode(, id=no_id, state=null, class=android.view.View)
                        UiNode(, id=no_id, state=null, class=android.view.View)
                          UiNode(desc='Side Menu', id=no_id, state=null, class=android.view.View)
                        UiNode(, id=no_id, state=null, class=android.view.View)
                          UiNode(desc='Earnings Mode Switcher', id=no_id, state=null, class=android.view.View)
                          UiNode(, id=no_id, state=null, class=android.view.View)
                            UiNode(desc='Time mode off', id=no_id, state=null, class=android.view.View)
                            UiNode(desc='Time mode on', id=no_id, state=null, class=android.view.View)
                        UiNode(, id=no_id, state=null, class=android.view.View)
                          UiNode(text='Dash', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // Waiting for offer — has "Looking for offers" text, no "Return to dash".
    // OnDashMapMatcher must NOT match this.
    private val waitingForOfferLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
      UiNode(, id=action_bar_root, state=null, class=android.widget.LinearLayout)
        UiNode(, id=content, state=null, class=android.widget.FrameLayout)
          UiNode(, id=overlay_gesture_manager, state=null, class=android.widget.FrameLayout)
            UiNode(, id=root_constraint_layout, state=null, class=android.view.ViewGroup)
              UiNode(, id=side_nav_content_container, state=null, class=android.widget.FrameLayout)
                UiNode(, id=main_fragment, state=null, class=android.widget.FrameLayout)
                  UiNode(, id=no_id, state=null, class=androidx.compose.ui.platform.ComposeView)
                    UiNode(, id=no_id, state=null, class=android.view.View)
                      UiNode(, id=no_id, state=null, class=android.view.View)
                        UiNode(, id=no_id, state=null, class=android.view.View)
                          UiNode(text='Looking for offers', id=no_id, state=null, class=android.widget.TextView)
                          UiNode(id=looking_for_order_progress_bar, state=null, class=android.widget.ProgressBar)
""".trimIndent()

    @Test
    fun `matches MAIN_MAP_ON_DASH when Return to dash button is present`() {
        val root = LogToUiNodeParser.parseLog(returnToDashLog)
        assertNotNull("Failed to parse log", root)
        assertEquals(Screen.MAIN_MAP_ON_DASH, matcher.matches(root!!))
    }

    @Test
    fun `does not match idle map (Earnings Mode Switcher present)`() {
        val root = LogToUiNodeParser.parseLog(idleMapLog)
        assertNotNull("Failed to parse log", root)

        val result = matcher.matches(root!!)

        assertNull("Should return null — MAIN_MAP_IDLE belongs to IdleMapMatcher", result)
    }

    @Test
    fun `does not match waiting for offer screen`() {
        val root = LogToUiNodeParser.parseLog(waitingForOfferLog)
        assertNotNull("Failed to parse log", root)

        val result = matcher.matches(root!!)

        assertNull("Should return null — ON_DASH_MAP_WAITING_FOR_OFFER belongs to WaitingForOfferMatcher", result)
    }
}

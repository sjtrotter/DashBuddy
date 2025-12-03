package cloud.trotter.dashbuddy.services.accessibility.screen.parsers

import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DeliverySummaryParserTest {

    // Case 1: Peak Pay + Tips
    // From Log Timestamp: 2025-11-30 18:28:02.707
    val peakPayLog = """
UiNode(, id=no_id, class=android.widget.FrameLayout)
  UiNode(, id=no_id, class=android.view.ViewGroup)
    UiNode(, id=no_id, class=android.view.ViewGroup)
      UiNode(, id=no_id, class=android.widget.ScrollView)
        UiNode(, id=no_id, class=android.widget.ScrollView)
          UiNode(text='This dash so far', id=no_id, class=android.widget.TextView)
          UiNode(, id=no_id, class=androidx.compose.ui.platform.ComposeView)
            UiNode(, id=no_id, class=android.view.View)
              UiNode(text='${'$'}', id=no_id, class=android.widget.TextView)
              UiNode(text='7', id=no_id, class=android.widget.TextView)
              UiNode(text='4', id=no_id, class=android.widget.TextView)
              UiNode(text='.', id=no_id, class=android.widget.TextView)
              UiNode(text='1', id=no_id, class=android.widget.TextView)
              UiNode(text='5', id=no_id, class=android.widget.TextView)
          UiNode(, id=no_id, class=androidx.recyclerview.widget.RecyclerView)
            UiNode(, id=no_id, class=android.view.ViewGroup)
              UiNode(, id=no_id, class=android.widget.LinearLayout)
                UiNode(, id=no_id, class=androidx.recyclerview.widget.RecyclerView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='Total online time', id=no_id, class=android.widget.TextView)
                    UiNode(text='3 hr 8 min', id=no_id, class=android.widget.TextView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='Offers accepted', id=no_id, class=android.widget.TextView)
                    UiNode(text='7 out of 7', id=no_id, class=android.widget.TextView)
            UiNode(, id=no_id, class=android.view.ViewGroup)
              UiNode(text='This offer', id=no_id, class=android.widget.TextView)
              UiNode(text='${'$'}12.25', id=no_id, class=android.widget.TextView)
              UiNode(text='${'$'}14.28', id=no_id, class=android.widget.TextView)
              UiNode(, id=no_id, class=android.widget.LinearLayout)
                UiNode(, id=no_id, class=androidx.recyclerview.widget.RecyclerView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='DoorDash pay', id=no_id, class=android.widget.TextView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='Base pay', id=no_id, class=android.widget.TextView)
                    UiNode(text='${'$'}2.00', id=no_id, class=android.widget.TextView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='Peak pay', id=no_id, class=android.widget.TextView)
                    UiNode(text='${'$'}1.00', id=no_id, class=android.widget.TextView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='Customer tips', id=no_id, class=android.widget.TextView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='Smokey Mo's BBQ- UTSA', id=no_id, class=android.widget.TextView)
                    UiNode(text='${'$'}11.28', id=no_id, class=android.widget.TextView)
              UiNode(text='Your Platinum status gave you priority for this offer', id=no_id, class=android.widget.TextView)
    UiNode(, id=no_id, class=android.widget.Button)
      UiNode(text='Continue dashing', id=no_id, class=android.widget.TextView)
    """.trimIndent()

    // Case 2: Standard Single Order
    // From Log Timestamp: 2025-12-01 12:40:04.135
    val standardOrderLog = """
UiNode(, id=no_id, class=android.widget.FrameLayout)
  UiNode(, id=no_id, class=android.view.ViewGroup)
    UiNode(, id=no_id, class=android.view.ViewGroup)
      UiNode(, id=no_id, class=android.widget.ScrollView)
        UiNode(, id=no_id, class=android.widget.ScrollView)
          UiNode(text='This dash so far', id=no_id, class=android.widget.TextView)
          UiNode(, id=no_id, class=androidx.compose.ui.platform.ComposeView)
            UiNode(, id=no_id, class=android.view.View)
              UiNode(text='${'$'}', id=no_id, class=android.widget.TextView)
              UiNode(text='5', id=no_id, class=android.widget.TextView)
              UiNode(text='.', id=no_id, class=android.widget.TextView)
              UiNode(text='0', id=no_id, class=android.widget.TextView)
              UiNode(text='0', id=no_id, class=android.widget.TextView)
          UiNode(, id=no_id, class=androidx.recyclerview.widget.RecyclerView)
            UiNode(, id=no_id, class=android.view.ViewGroup)
              UiNode(, id=no_id, class=android.widget.LinearLayout)
                UiNode(, id=no_id, class=androidx.recyclerview.widget.RecyclerView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='Total online time', id=no_id, class=android.widget.TextView)
                    UiNode(text=' 28 min', id=no_id, class=android.widget.TextView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='Offers accepted', id=no_id, class=android.widget.TextView)
                    UiNode(text='1 out of 1', id=no_id, class=android.widget.TextView)
            UiNode(, id=no_id, class=android.view.ViewGroup)
              UiNode(text='This offer', id=no_id, class=android.widget.TextView)
              UiNode(text='${'$'}5.00', id=no_id, class=android.widget.TextView)
              UiNode(, id=no_id, class=android.widget.LinearLayout)
                UiNode(, id=no_id, class=androidx.recyclerview.widget.RecyclerView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='DoorDash pay', id=no_id, class=android.widget.TextView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='Base pay', id=no_id, class=android.widget.TextView)
                    UiNode(text='${'$'}2.00', id=no_id, class=android.widget.TextView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='Customer tips', id=no_id, class=android.widget.TextView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='Steak n Shake (712)', id=no_id, class=android.widget.TextView)
                    UiNode(text='${'$'}3.00', id=no_id, class=android.widget.TextView)
          UiNode(desc='We've extended your arrival time: We've added time so you can get to your dashing area on time without losing your spot.', id=no_id, class=android.view.ViewGroup)
    UiNode(, id=no_id, class=android.widget.Button)
      UiNode(text='Continue dashing', id=no_id, class=android.widget.TextView)
    """.trimIndent()

    // Case 3: High Value / Priority
    // From Log Timestamp: 2025-12-01 18:59:24.011
    val highValueLog = """
UiNode(, id=no_id, class=android.widget.FrameLayout)
  UiNode(, id=no_id, class=android.view.ViewGroup)
    UiNode(, id=no_id, class=android.view.ViewGroup)
      UiNode(, id=no_id, class=android.widget.ScrollView)
        UiNode(, id=no_id, class=android.widget.ScrollView)
          UiNode(text='This dash so far', id=no_id, class=android.widget.TextView)
          UiNode(, id=no_id, class=androidx.compose.ui.platform.ComposeView)
            UiNode(, id=no_id, class=android.view.View)
              UiNode(text='${'$'}', id=no_id, class=android.widget.TextView)
              UiNode(text='2', id=no_id, class=android.widget.TextView)
              UiNode(text='2', id=no_id, class=android.widget.TextView)
              UiNode(text='.', id=no_id, class=android.widget.TextView)
              UiNode(text='6', id=no_id, class=android.widget.TextView)
              UiNode(text='2', id=no_id, class=android.widget.TextView)
          UiNode(, id=no_id, class=androidx.recyclerview.widget.RecyclerView)
            UiNode(, id=no_id, class=android.view.ViewGroup)
              UiNode(, id=no_id, class=android.widget.LinearLayout)
                UiNode(, id=no_id, class=androidx.recyclerview.widget.RecyclerView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='Total online time', id=no_id, class=android.widget.TextView)
                    UiNode(text=' 46 min', id=no_id, class=android.widget.TextView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='Offers accepted', id=no_id, class=android.widget.TextView)
                    UiNode(text='1 out of 1', id=no_id, class=android.widget.TextView)
            UiNode(, id=no_id, class=android.view.ViewGroup)
              UiNode(text='This offer', id=no_id, class=android.widget.TextView)
              UiNode(text='${'$'}22.62', id=no_id, class=android.widget.TextView)
              UiNode(, id=no_id, class=android.widget.LinearLayout)
                UiNode(, id=no_id, class=androidx.recyclerview.widget.RecyclerView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='DoorDash pay', id=no_id, class=android.widget.TextView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='Base pay', id=no_id, class=android.widget.TextView)
                    UiNode(text='${'$'}9.25', id=no_id, class=android.widget.TextView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='Customer tips', id=no_id, class=android.widget.TextView)
                  UiNode(, id=no_id, class=android.view.ViewGroup)
                    UiNode(text='Scuzzi's Italian Restaurant (N Loop 1604 W)', id=no_id, class=android.widget.TextView)
                    UiNode(text='${'$'}13.37', id=no_id, class=android.widget.TextView)
    UiNode(, id=no_id, class=android.widget.Button)
      UiNode(text='Continue dashing', id=no_id, class=android.widget.TextView)
    """.trimIndent()


    @Test
    fun `Peak Pay parsing`() {
        val rootNode = LogToUiNodeParser.parseLog(peakPayLog)
        assertNotNull("Log parsing failed", rootNode)

        val result = DeliverySummaryParser.parse(rootNode)

        // Expected: Base Pay ($2.00) + Peak Pay ($1.00)
        assertEquals("Should find 2 App Pay items", 2, result.appPayComponents.size)
        assertEquals("Base pay", result.appPayComponents[0].type)
        assertEquals(2.00, result.appPayComponents[0].amount, 0.0)

        assertEquals("Peak pay", result.appPayComponents[1].type)
        assertEquals(1.00, result.appPayComponents[1].amount, 0.0)

        // Expected: 1 Tip for Smokey Mo's
        assertEquals("Should find 1 Tip", 1, result.customerTips.size)
        assertEquals("Smokey Mo's BBQ- UTSA", result.customerTips[0].type)
        assertEquals(11.28, result.customerTips[0].amount, 0.0)
    }

    @Test
    fun `Standard Order parsing`() {
        val rootNode = LogToUiNodeParser.parseLog(standardOrderLog)
        assertNotNull(rootNode)

        val result = DeliverySummaryParser.parse(rootNode)

        // Expected: Base Pay ($2.00)
        assertEquals("Should find 1 App Pay item", 1, result.appPayComponents.size)
        assertEquals(2.00, result.appPayComponents[0].amount, 0.0)

        // Expected: 1 Tip for Steak n Shake ($3.00)
        assertEquals("Should find 1 Tip", 1, result.customerTips.size)
        assertEquals("Steak n Shake (712)", result.customerTips[0].type)
        assertEquals(3.00, result.customerTips[0].amount, 0.0)
    }

    @Test
    fun `High Value Order parsing`() {
        val rootNode = LogToUiNodeParser.parseLog(highValueLog)
        assertNotNull(rootNode)

        val result = DeliverySummaryParser.parse(rootNode)

        // Expected: Base Pay ($9.25)
        assertEquals("Should find 1 App Pay item", 1, result.appPayComponents.size)
        assertEquals(9.25, result.appPayComponents[0].amount, 0.0)

        // Expected: 1 Tip for Scuzzi's ($13.37)
        assertEquals("Should find 1 Tip", 1, result.customerTips.size)
        assertEquals("Scuzzi's Italian Restaurant (N Loop 1604 W)", result.customerTips[0].type)
        assertEquals(13.37, result.customerTips[0].amount, 0.0)
    }
}
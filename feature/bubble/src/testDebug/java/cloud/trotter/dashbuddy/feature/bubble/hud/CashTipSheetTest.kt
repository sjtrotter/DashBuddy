package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.theme.darkAppColors
import cloud.trotter.dashbuddy.core.designsystem.theme.HankenGrotesk
import cloud.trotter.dashbuddy.core.designsystem.theme.SpaceGrotesk
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.feature.bubble.hud.cashtip.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp") // tall host: no performScrollTo under the frozen clock (see build gotchas)
class CashTipSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var locale: Locale
    @Before fun setup() { locale = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun cleanup() { Locale.setDefault(locale) }
    private val rows = (1L..5L).map { id -> DeliveryRecord(id, "dash", Platform.DoorDash, "job$id", "task$id",
        "Store $id", 1_700_000_000_000L + id * 60_000, 8.0, "OFFER_PAY", 6.0, null, null, null, null, cashTip = 10.0) }
    private var model by mutableStateOf(CashTipSheetModel(rows, 5))
    private var glance by mutableStateOf(1f)
    private var fontScale by mutableStateOf(1f)
    private var commits = 0
    private val amounts = mutableListOf<Long>()
    private fun show(hostModifier: Modifier = Modifier.width(352.dp).height(390.dp)) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalHudTier provides FontScaleTier.of(fontScale, glance),
            ) {
                DashBuddyTheme(darkTheme = true, glance = glance) {
                    Column(hostModifier) {
                        CashTipSheet(model, onSelectDelivery = { model = model.copy(selectedDeliveryId = it, page = CashTipPage.COMPACT) },
                            onExpand = { model = model.copy(page = CashTipPage.EXPANDED) },
                            onBack = { model = model.copy(page = CashTipPage.COMPACT) },
                            onSign = { model = model.copy(sign = it) },
                            onAmount = { amount ->
                                amounts += amount
                                model = model.copy(totalCents = (model.totalCents + if (model.sign == CashTipSign.PLUS) amount else -amount).coerceAtLeast(0))
                            }, onOther = { model = model.copy(page = CashTipPage.OTHER_AMOUNT, otherAmount = "") },
                            onDigit = { model = model.copy(otherAmount = model.otherAmount + it) },
                            onPoint = { model = model.copy(otherAmount = model.otherAmount + ".") },
                            onBackspace = { model = model.copy(otherAmount = model.otherAmount.dropLast(1)) },
                            onDoneOther = { model = model.copy(page = CashTipPage.COMPACT,
                                totalCents = model.otherAmount.toBigDecimalOrNull()?.movePointRight(2)?.toLong() ?: model.totalCents) },
                            onCommit = { commits++ })
                    }
                }
            }
        }
    }
    // Draw the actual Compose view: PixelCopy-based captureToImage waits for a window
    // redraw that Robolectric does not deliver, even with native graphics enabled.
    private fun pixel(node: SemanticsNodeInteraction, x: Float, y: Float): Color {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        return compose.runOnIdle {
            val view = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val color = Color(bitmap.getPixel((bounds.left + bounds.width * x).roundToInt(),
                (bounds.top + bounds.height * y).roundToInt()))
            bitmap.recycle()
            color
        }
    }
    @Test
    fun `other amount keypad rests against Done with slack below the title`() {
        model = model.copy(page = CashTipPage.OTHER_AMOUNT, otherAmount = "7.25")
        show(Modifier.width(400.dp).height(760.dp))
        val title = compose.onNodeWithTag("cashTip.title").getBoundsInRoot()
        val firstKey = compose.onNodeWithTag("keypad.1").assertIsDisplayed().assertWidthIsEqualTo(60.dp).getBoundsInRoot()
        val lastKey = compose.onNodeWithTag("keypad.backspace").assertIsDisplayed().getBoundsInRoot()
        val done = compose.onNodeWithTag("cashTip.done").assertIsDisplayed().getBoundsInRoot()
        val gapAbove = firstKey.top - title.bottom
        val gapBelow = done.top - lastKey.bottom
        assertEquals("Keypad must touch Done", 0.dp, gapBelow)
        assertTrue("Slack belongs below the title and above the keypad", gapAbove > gapBelow)
    }

    @Test
    fun `Done stays reachable in a short frame while the amount and keypad scroll`() {
        model = model.copy(page = CashTipPage.OTHER_AMOUNT, otherAmount = "7.25")
        show(Modifier.requiredSize(352.dp, 200.dp))
        val done = compose.onNodeWithTag("cashTip.done")
            .assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            .assertWidthIsEqualTo(328.dp).assertHeightIsEqualTo(48.dp)
        val bounds = done.getBoundsInRoot()
        compose.onNodeWithTag("keypad.backspace").assertExists()
        compose.onNodeWithText("7.25").assertExists()
        compose.onNodeWithTag("keypad.block").assert(hasScrollAction())
        assertEquals(bounds, done.assertIsDisplayed().getBoundsInRoot())
        done.performTouchInput { click() }
        compose.onNodeWithTag("cashTip.total").assertTextEquals("$7.25")
        assertEquals(CashTipPage.COMPACT, model.page)
    }

    @Test fun `glance 112 scales amount labels Other and both sign glyphs`() {
        show()
        fun fontSize(text: String): Float {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style.fontSize.value
        }
        val base = listOf("$1", "$5", "$10", "$20", "Other", "+", "−").associateWith { fontSize(it) }
        compose.runOnIdle { glance = 1.12f }
        base.forEach { (text, size) -> assertEquals(text, size * 1.12f, fontSize(text), .001f) }
    }

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `whole remove commit label fits at font scale 180 in a 352 dp host`() {
        fontScale = 1.8f
        model = model.copy(totalCents = 2000, sign = CashTipSign.MINUS)
        show(Modifier.width(352.dp).height(760.dp))
        val button = compose.onNodeWithTag("cashTip.commit").assertIsDisplayed()
        val label = compose.onNodeWithText("Remove $20.00 cash tip", useUnmergedTree = true).assertIsDisplayed()
        val layout = textLayout(label)
        assertTrue(layout.lineCount in 1..2)
        assertTrue(layout.layoutInput.softWrap)
        assertEquals(androidx.compose.ui.text.style.TextOverflow.Clip, layout.layoutInput.overflow)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        assertTrue(button.getBoundsInRoot().height >= 48.dp)
        assertTextInsideButton(label, button, layout)

        // Also force wrapping if the font renderer fits the $20 label on one line.
        compose.runOnIdle { model = model.copy(totalCents = 99_999_999) }
        val wrapped = compose.onNodeWithText("Remove $999999.99 cash tip", useUnmergedTree = true).assertIsDisplayed()
        val wrappedLayout = textLayout(wrapped)
        assertEquals(2, wrappedLayout.lineCount)
        assertEquals(wrappedLayout.layoutInput.text.length, wrappedLayout.getLineEnd(1, visibleEnd = true))
        assertTrue(button.getBoundsInRoot().height > 48.dp)
        assertTextInsideButton(wrapped, button, wrappedLayout)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `pad labels stay inside buttons and amounts fit at every HUD tier`() {
        show(Modifier.width(352.dp).height(760.dp))
        for (scale in listOf(1f, 1.3f, 1.5f, 1.8f)) {
            compose.runOnIdle { fontScale = scale }
            for (dollars in listOf(1, 5, 10, 20)) {
                val button = compose.onNodeWithTag("cashTip.amount.$dollars")
                    .assertIsDisplayed().assertHeightIsEqualTo(if (scale >= 1.3f) 64.dp else 56.dp)
                val label = compose.onNodeWithText("$$dollars", useUnmergedTree = true).assertIsDisplayed()
                val layout = textLayout(label)
                assertEquals(1, layout.lineCount)
                assertFalse(layout.isLineEllipsized(0))
                assertEquals(layout.layoutInput.text.length, layout.getLineEnd(0, visibleEnd = true))
                assertFalse(layout.layoutInput.softWrap)
                assertEquals(androidx.compose.ui.text.style.TextOverflow.Ellipsis, layout.layoutInput.overflow)
                assertTextInsideButton(label, button, layout)
            }
            val other = compose.onNodeWithText("Other", useUnmergedTree = true).assertIsDisplayed()
            val layout = textLayout(other)
            assertEquals(1, layout.lineCount)
            assertFalse(layout.layoutInput.softWrap)
            assertEquals(androidx.compose.ui.text.style.TextOverflow.Ellipsis, layout.layoutInput.overflow)
            assertTextInsideButton(other, compose.onNodeWithTag("cashTip.other"), layout)
        }
    }

    private fun assertTextInsideButton(
        label: SemanticsNodeInteraction,
        button: SemanticsNodeInteraction,
        layout: TextLayoutResult,
    ) {
        // The cached paragraph can be wider than the measured text (e.g. $1 is
        // 23 px inside a 51 px paragraph). hasVisualOverflow flags that slack.
        // Compare actual semantics bounds, then every line's measured edges
        // so clipping or a missing second line cannot masquerade as a fit.
        val textBounds = label.fetchSemanticsNode().boundsInRoot
        val origin = label.fetchSemanticsNode().positionInRoot
        val bounds = button.fetchSemanticsNode().boundsInRoot
        assertTrue("Text left inside button", textBounds.left >= bounds.left - 1f)
        assertTrue("Text top inside button", textBounds.top >= bounds.top - 1f)
        assertTrue("Text right inside button", textBounds.right <= bounds.right + 1f)
        assertTrue("Text bottom inside button", textBounds.bottom <= bounds.bottom + 1f)
        for (line in 0 until layout.lineCount) {
            assertTrue("Line $line left inside text", origin.x + layout.getLineLeft(line) >= textBounds.left - 1f)
            assertTrue("Line $line right inside text", origin.x + layout.getLineRight(line) <= textBounds.right + 1f)
            assertTrue("Line $line top inside text", origin.y + layout.getLineTop(line) >= textBounds.top - 1f)
            assertTrue("Line $line bottom inside text", origin.y + layout.getLineBottom(line) <= textBounds.bottom + 1f)
        }
    }

    @Test fun `empty Other Done keeps the existing amount`() {
        model = model.copy(totalCents = 500)
        show()
        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithTag("cashTip.done").performClick()
        compose.onNodeWithTag("cashTip.total").assertTextEquals("$5.00")
        compose.onNodeWithText("Add $5.00 cash tip").assertIsDisplayed()
    }

    @Test fun `compact offers two rows and latest is preselected`() {
        show()
        compose.onNodeWithText("Store 5").assertIsDisplayed()
        compose.onNodeWithText("Store 4").assertIsDisplayed()
        compose.onNodeWithText("Store 3").assertDoesNotExist()
        compose.onNodeWithTag("cashTip.delivery.5").assertIsSelected()
        compose.onNodeWithTag("cashTip.delivery.4").assertIsNotSelected()
        compose.onNodeWithText("Another delivery").assertIsDisplayed()
    }
    @Test fun `pad taps add subtract and never make the total negative`() {
        show()
        compose.onNodeWithText("$5").performClick()
        compose.onNodeWithText("$1").performClick()
        compose.onNodeWithText("$1").performClick()
        compose.onNodeWithText("Add $7.00 cash tip").assertIsDisplayed().performClick()
        assertEquals(1, commits)
        compose.onNodeWithTag("cashTip.sign").performClick()
        compose.onNodeWithText("$5").performClick()
        compose.onNodeWithText("Remove $2.00 cash tip").assertIsDisplayed()
        compose.onNodeWithText("$20").performClick()
        compose.onNodeWithTag("cashTip.commit").assertIsNotEnabled()
        assertEquals(0L, model.totalCents)
        assertEquals(listOf(500L, 100L, 100L, 500L, 2000L), amounts)
    }
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `one sign button toggles both ways and highlights only its active glyph`() {
        show()
        val colors = darkAppColors()
        val button = compose.onNodeWithTag("cashTip.sign")
        button.assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        for ((index, active) in listOf(CashTipSign.PLUS, CashTipSign.MINUS, CashTipSign.PLUS).withIndex()) {
            button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                if (active == CashTipSign.PLUS) "Add" else "Remove"))
            for (sign in CashTipSign.entries) {
                val glyph = compose.onNodeWithTag("cashTip.sign.$sign", useUnmergedTree = true)
                glyph.assertHasNoClickAction().assertWidthIsEqualTo(34.dp).assertHeightIsEqualTo(22.dp)
                glyph.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
                assertEquals(if (sign == active) colors.accent else colors.surface3, pixel(glyph, 0.1f, 0.5f))
                val text = compose.onNode(hasText(if (sign == CashTipSign.PLUS) "+" else "−") and
                    hasAnyAncestor(hasTestTag("cashTip.sign.$sign")), useUnmergedTree = true)
                val layouts = mutableListOf<TextLayoutResult>()
                text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(if (sign == active) colors.accentText else colors.neutral,
                    layouts.single().layoutInput.style.color)
            }
            if (index < 2) button.performClick()
        }
        compose.runOnIdle { model = model.copy(busy = true) }
        button.assertIsNotEnabled()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `filled pad is a single 56 dp row below deliveries touching commit`() {
        show()
        val tags = listOf("cashTip.sign", "cashTip.amount.1", "cashTip.amount.5", "cashTip.amount.10",
            "cashTip.amount.20", "cashTip.other")
        val bounds = tags.map { tag ->
            val button = compose.onNodeWithTag(tag).assertHasClickAction().assertHeightIsEqualTo(56.dp)
            assertEquals(darkAppColors().surface3, pixel(button, 0.5f, 0.05f))
            button.getBoundsInRoot()
        }
        bounds.zipWithNext().forEach { (left, right) ->
            assertEquals(left.top, right.top)
            assertEquals(left.bottom, right.bottom)
            // Independently rounded weighted edges can differ by one physical pixel.
            (right.left - left.right).assertIsEqualTo(4.dp, "Gap between pad buttons",
                tolerance = with(compose.density) { 1.toDp() })
        }
        for ((text, size) in listOf("$1" to 18.sp, "$5" to 18.sp, "$10" to 18.sp, "$20" to 18.sp, "Other" to 13.sp)) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val style = layouts.single().layoutInput.style
            assertEquals(size, style.fontSize)
            assertEquals(FontWeight.SemiBold, style.fontWeight)
            assertEquals(if (text == "Other") HankenGrotesk else SpaceGrotesk, style.fontFamily)
            if (text != "Other") assertEquals("tnum", style.fontFeatureSettings)
        }
        val pad = compose.onNodeWithTag("cashTip.pad").assertHeightIsEqualTo(56.dp).getBoundsInRoot()
        val delivery = compose.onNodeWithTag("cashTip.deliveryRow.4").getBoundsInRoot()
        val another = compose.onNodeWithText("Another delivery").getBoundsInRoot()
        assertTrue(pad.top - delivery.bottom >= 10.dp)
        assertTrue(pad.top - another.bottom >= 10.dp)
        assertEquals(pad.bottom, compose.onNodeWithTag("cashTip.commit").getBoundsInRoot().top)
    }

    @Test fun `Other opens point enabled keypad and Done replaces fractional amount`() {
        model = model.copy(totalCents = 100)
        show()
        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithTag("keypad.point").assertIsEnabled()
        compose.onNodeWithTag("keypad.2").performClick()
        compose.onNodeWithTag("keypad.point").performClick()
        compose.onNodeWithTag("keypad.3").performClick()
        compose.onNodeWithTag("keypad.5").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Add $2.35 cash tip").assertIsDisplayed()
        compose.onNodeWithTag("cashTip.total").assertTextEquals("$2.35")
        compose.onNodeWithTag("keypad.point").assertDoesNotExist()
        compose.onNodeWithText("Other").performClick()
        assertEquals("", model.otherAmount)
        compose.onNodeWithTag("keypad.7").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("cashTip.total").assertTextEquals("$7.00")
    }
    @Test fun `expanded picker lists every delivery hides pad and selecting returns compact`() {
        show()
        compose.onNodeWithText("Another delivery").performClick()
        compose.onNodeWithText("Fewer").assertIsDisplayed()
        compose.onNodeWithText("$5").assertDoesNotExist()
        for (id in 5L downTo 1L) {
            compose.onNodeWithTag("cashTip.deliveries").performScrollToNode(hasTestTag("cashTip.delivery.$id"))
            compose.onNodeWithTag("cashTip.delivery.$id").assertIsDisplayed()
        }
        compose.onNodeWithTag("cashTip.delivery.1").performClick()
        compose.onNodeWithText("Other").assertIsDisplayed()
        compose.onNodeWithText("Fewer").assertDoesNotExist()
        compose.onNodeWithTag("cashTip.delivery.1").assertIsSelected()
    }
    @Test fun `busy and empty amounts disable commit`() {
        model = model.copy(totalCents = 700, busy = true)
        show()
        compose.onNodeWithTag("cashTip.commit").assertIsNotEnabled()
        compose.runOnIdle { model = model.copy(totalCents = 0, busy = false) }
        compose.onNodeWithTag("cashTip.commit").assertIsNotEnabled()
    }
    @Test fun `an empty dash offers no selectable delivery`() {
        model = model.copy(deliveries = emptyList(), selectedDeliveryId = null)
        show()
        compose.onNodeWithText("No completed deliveries in this dash").assertIsDisplayed()
        compose.onNodeWithTag("cashTip.commit").assertIsNotEnabled()
    }
    @Test fun `sheet errors distinguish refused removal from failed save and clear at none`() {
        model = model.copy(failed = CashTipFailure.BELOW_ZERO)
        show()
        compose.onNodeWithText("Less cash than that is logged. Nothing changed.").assertIsDisplayed()
        compose.onNodeWithText("Cash tip not saved. Try again.").assertDoesNotExist()
        compose.runOnIdle { model = model.copy(failed = CashTipFailure.WRITE) }
        compose.onNodeWithText("Cash tip not saved. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Less cash than that is logged. Nothing changed.").assertDoesNotExist()
        compose.runOnIdle { model = model.copy(failed = CashTipFailure.NONE) }
        compose.onNodeWithText("Cash tip not saved. Try again.").assertDoesNotExist()
        compose.onNodeWithText("Less cash than that is logged. Nothing changed.").assertDoesNotExist()
    }
    @Test fun `removing more than the selected delivery cash is disabled`() {
        model = model.copy(totalCents = 1100, sign = CashTipSign.MINUS)
        show()
        compose.onNodeWithText("Remove $11.00 cash tip").assertIsDisplayed()
        compose.onNodeWithTag("cashTip.commit").assertIsNotEnabled()
    }

}

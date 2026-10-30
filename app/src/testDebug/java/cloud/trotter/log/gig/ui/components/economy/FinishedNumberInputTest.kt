package cloud.trotter.log.gig.ui.components.economy

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import cloud.trotter.log.gig.ui.main.settings.garage.WriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinishedNumberInputTest {
    @get:Rule val compose = createComposeRule()
    private val commits = mutableListOf<Double>()
    private lateinit var session: EditSession
    private fun show(mpg: Boolean = false) {
        compose.setContent { GigLogTheme {
            val scope = rememberCoroutineScope()
            session = remember { EditSession(scope) }
            val focus = LocalFocusManager.current
            CompositionLocalProvider(LocalEditSession provides session) {
                Column {
                    if (mpg) VehicleMpgAndFuelInputs(30.0, FuelType.REGULAR,
                        { value -> stagedSave { commits += value } }, {})
                    else CurrencyInput("Cost", 0.0, { value -> stagedSave { commits += value } })
                    Button(onClick = { focus.clearFocus() }) { Text("Finish") }
                }
            }
        } }
    }
    @Test fun `typing 2 on the way to 25 saves only 25 on blur`() {
        show()
        compose.onNodeWithText("Cost").performTextReplacement("2")
        compose.onNodeWithText("Cost").performTextReplacement("25")
        compose.runOnIdle { assertTrue(commits.isEmpty()) }
        compose.onNodeWithText("Finish").performClick()
        compose.runOnIdle { assertEquals(listOf(25.0), commits) }
    }
    @Test fun `Done plus blur saves once`() {
        show()
        compose.onNodeWithText("Cost").performTextReplacement("25")
        compose.onNodeWithText("Cost").performImeAction()
        compose.onNodeWithText("Finish").performClick()
        compose.runOnIdle { assertEquals(listOf(25.0), commits) }
    }
    @Test fun `focus Done blur and host leave never save an untouched preset MPG`() {
        show(mpg = true)
        compose.onNodeWithText("Combined MPG").requestFocus()
        compose.onNodeWithText("Combined MPG").performImeAction()
        compose.onNodeWithText("Combined MPG").requestFocus()
        compose.onNodeWithText("Finish").performClick()
        compose.runOnIdle { runBlocking { assertTrue(session.finishAll()) }; assertTrue(commits.isEmpty()) }
    }
    @Test fun `invalid or empty text keeps its error and never submits`() {
        show()
        for (input in listOf("invalid", "", "-1", "NaN", "Infinity")) {
            compose.onNodeWithText("Cost").performTextReplacement(input)
            compose.onNodeWithText("Cost").performImeAction()
            compose.onNodeWithText("Cost").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            compose.runOnIdle { assertTrue(commits.isEmpty()) }
        }
    }
    @Test fun `equal-value acknowledgement then an outside update follows the store`() {
        var stored by mutableDoubleStateOf(10.0)
        val acknowledged = CompletableDeferred<WriteResult>()
        compose.setContent { GigLogTheme {
            val scope = rememberCoroutineScope()
            session = remember { EditSession(scope) }
            CompositionLocalProvider(LocalEditSession provides session) {
                CurrencyInput("Cost", stored, { commits += it; acknowledged })
            }
        } }
        compose.onNodeWithText("Cost").performTextReplacement("10.0")
        compose.onNodeWithText("Cost").performImeAction()
        compose.onNodeWithText("Cost").assertTextContains("10.0")
        compose.runOnIdle { acknowledged.complete(WriteResult.SAVED) }
        compose.waitForIdle()
        compose.runOnIdle { stored = 99.0 }
        compose.onNodeWithText("Cost").assertTextContains("99")
    }
    @Test fun `discard before disposal invalidates its captured finish callback`() {
        var visible by mutableStateOf(true)
        compose.setContent { GigLogTheme {
            val scope = rememberCoroutineScope()
            session = remember { EditSession(scope) }
            CompositionLocalProvider(LocalEditSession provides session) {
                if (visible) CurrencyInput("Cost", 0.0, { value -> stagedSave { commits += value } })
            }
        } }
        compose.onNodeWithText("Cost").performTextReplacement("25")
        // Discard before removing focus invalidates any callback retained by the field.
        compose.runOnIdle { session.discardAll(); visible = false }
        compose.runOnIdle { assertTrue(commits.isEmpty()) }
    }
}

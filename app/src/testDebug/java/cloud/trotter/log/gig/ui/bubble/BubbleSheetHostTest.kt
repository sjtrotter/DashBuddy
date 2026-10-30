package cloud.trotter.log.gig.ui.bubble

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.feature.bubble.hud.cashtip.CashTipPage
import cloud.trotter.log.gig.feature.bubble.hud.expense.ExpensePage
import cloud.trotter.log.gig.feature.bubble.hud.expense.ExpensePick
import cloud.trotter.log.gig.feature.bubble.hud.undo.UndoStripModel
import cloud.trotter.log.gig.feature.bubble.hud.undo.undoStripVisible
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BubbleSheetHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val clock = object : Clock() {
        var now = 1_700_000_000_000L
        override fun millis() = now
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private lateinit var host: BubbleSheetHost
    private val ports = EntrySheet.entries.map { TestPort(it) }
    private var bandVisible = false

    @Before fun setup() { compose.mainClock.autoAdvance = false }

    private class TestPort(override val key: EntrySheet) : SheetPort {
        override var open by mutableStateOf(false)
        override var compact by mutableStateOf(false)
        override var entryId by mutableStateOf(0L)
        override val completed = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 1)
        var handled = 0
        var backs = 0
        var refuses = false
        var strip by mutableStateOf<UndoStripModel?>(null)
        var bandPasses = 0
        override fun completionHandled() { handled++; completed.resetReplayCache() }
        override fun show() { if (!open && !refuses) { entryId++; open = true } }
        override fun dismiss() { open = false }
        override fun back() { backs++; dismiss() }
        @Composable override fun Band(): SheetBand? {
            val model = strip
            val visible = undoStripVisible(model)
            SideEffect { bandPasses++ }
            return model?.takeIf { visible }?.let { SheetBand(it.token) { Text("band.$key") } }
        }
    }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalNowProvider provides clock) {
                host = rememberBubbleSheetHost(ports.map { port -> SheetSlot(port) { Text("sheet.${port.key}") } })
                Column { host.Sheet(this) }
                bandVisible = host.BandVisible()
                host.Band()
            }
        }
        settle()
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(64); compose.waitForIdle() }
    private fun moveTime(millis: Long) {
        compose.runOnIdle { clock.now += millis }
        compose.mainClock.advanceTimeBy(millis)
        settle()
    }

    @Test fun `every ordered pair opens exclusively and close retains exiting content`() {
        show()
        for (first in ports) for (second in ports) {
            compose.runOnIdle {
                host.show(first.key)
                host.show(second.key)
                assertTrue(host.open)
                assertEquals(listOf(second), ports.filter { it.open })
                host.show(null)
                assertFalse(host.open)
                assertTrue(ports.none { it.open })
            }
            settle()
            compose.onNodeWithText("sheet.${second.key}").assertExists()
        }
    }

    @Test fun `refused opening still dismisses other ports`() {
        show()
        compose.runOnIdle {
            host.show(EntrySheet.CASH_TIP)
            ports.first().refuses = true
            host.show(EntrySheet.ODOMETER)
            assertFalse(host.open)
            assertTrue(ports.none { it.open })
        }
    }

    @Test fun `back delegates only to the open port and outside only dismisses compact`() {
        show()
        for (port in ports) compose.runOnIdle {
            host.show(port.key)
            host.outside()
            assertTrue(port.open)
            host.back()
            assertFalse(port.open)
            assertEquals(1, port.backs)
            host.back()
            assertEquals(1, port.backs)
            host.show(port.key)
            port.compact = true
            assertTrue(host.compact)
            host.outside()
            assertFalse(host.open)
        }
    }

    @Test fun `real cash tip and expense ports keep their subpage Back steps`() {
        val vm: BubbleViewModel = mock()
        val tipActions: CashTipActions = mock { on { committed } doReturn MutableSharedFlow() }
        val expenseActions: ExpenseActions = mock { on { committed } doReturn MutableSharedFlow() }
        whenever(vm.cashTipActions).thenReturn(tipActions)
        whenever(vm.expenseActions).thenReturn(expenseActions)
        lateinit var tip: CashTipEntryState
        lateinit var expense: ExpenseEntryState
        compose.setContent {
            tip = rememberCashTipEntryState()
            expense = rememberExpenseEntryState()
            val cashHud = remember { CashTipHud(vm, tip) {} }
            val expenseHud = remember { ExpenseHud(vm, expense, {}, {}) }
            host = rememberBubbleSheetHost(listOf(SheetSlot(cashHud) {}, SheetSlot(expenseHud) {}))
        }
        settle()
        compose.runOnIdle {
            host.show(EntrySheet.CASH_TIP)
            tip.page = CashTipPage.EXPANDED
            host.back()
            assertTrue(host.open)
            assertEquals(CashTipPage.COMPACT, tip.page)
            tip.other()
            host.back()
            assertTrue(host.open)
            assertEquals(CashTipPage.COMPACT, tip.page)
            host.back()
            assertFalse(host.open)
            host.show(EntrySheet.EXPENSE)
            assertTrue(host.compact)
            expense.select(ExpensePick.Fuel)
            assertFalse(host.compact)
            host.outside()
            assertTrue(host.open)
            host.back()
            assertEquals(ExpensePage.SUBCARD, expense.page)
            assertTrue(host.open)
            host.back()
            assertFalse(host.open)
        }
    }

    @Test fun `newest band wins and expiry yields while failures stay visible`() {
        val notice = ports[0]
        val tip = ports[1]
        val expense = ports[2]
        notice.strip = UndoStripModel(100, null, 1, clock.now + 6_000, 1)
        tip.strip = UndoStripModel(100, null, 2, clock.now + 4_000, 2)
        expense.strip = UndoStripModel(100, null, 3, clock.now + 2_000, 3)
        show()
        compose.onNodeWithText("band.EXPENSE").assertExists()
        compose.runOnIdle {
            assertTrue(bandVisible)
            assertTrue(ports.all { it.bandPasses > 0 })
        }
        moveTime(2_000)
        compose.onNodeWithText("band.CASH_TIP").assertExists()
        compose.runOnIdle { expense.strip = expense.strip!!.copy(failed = true, token = 4) }
        settle()
        compose.onNodeWithText("band.EXPENSE").assertExists()
        moveTime(2_000)
        compose.onNodeWithText("band.EXPENSE").assertExists()
        compose.runOnIdle { expense.strip = null }
        settle()
        compose.onNodeWithText("band.ODOMETER").assertExists()
        moveTime(2_000)
        compose.runOnIdle { assertFalse(bandVisible) }
        compose.onNodeWithText("band.ODOMETER").assertDoesNotExist()
    }

    @Test fun `late completion closes only its current entry and all completions are acknowledged`() {
        show()
        val origin = ports.first()
        compose.runOnIdle {
            host.show(origin.key)
            host.show(EntrySheet.CASH_TIP)
            assertTrue(origin.completed.tryEmit(origin.entryId))
        }
        settle()
        compose.runOnIdle {
            assertTrue(host.open)
            assertTrue(ports[1].open)
            assertEquals(1, origin.handled)
            host.show(origin.key)
            assertTrue(origin.completed.tryEmit(origin.entryId - 1))
        }
        settle()
        compose.runOnIdle {
            assertTrue(origin.open)
            assertEquals(2, origin.handled)
            assertTrue(origin.completed.tryEmit(origin.entryId))
        }
        settle()
        compose.runOnIdle {
            assertFalse(host.open)
            assertEquals(3, origin.handled)
            assertTrue(origin.completed.tryEmit(origin.entryId))
        }
        settle()
        compose.runOnIdle { assertEquals(4, origin.handled) }
    }

    @Test fun `stale completion replay after restoration leaves the reopened odometer open`() {
        val restoration = StateRestorationTester(compose)
        val completions = MutableSharedFlow<Long>(replay = 1)
        var staleId = 0L
        var handled = 0
        lateinit var entry: OdometerEntryState
        restoration.setContent {
            entry = rememberOdometerEntryState()
            val restoredEntry = entry
            val port = remember {
                object : SheetPort {
                    override val key = EntrySheet.ODOMETER
                    override val open get() = restoredEntry.open
                    override val entryId get() = restoredEntry.entryId
                    override val completed = completions
                    override fun completionHandled() { handled++; completions.resetReplayCache() }
                    override fun show() { restoredEntry.show(true) }
                    override fun dismiss() { restoredEntry.open = false }
                    override fun back() = restoredEntry.back()
                }
            }
            host = rememberBubbleSheetHost(listOf(SheetSlot(port) {}))
        }
        settle()
        compose.runOnIdle {
            host.show(EntrySheet.ODOMETER)
            staleId = entry.entryId
            host.back()
            host.show(EntrySheet.ODOMETER)
            entry.digit(7)
        }
        restoration.emulateSavedInstanceStateRestore()
        settle()
        // A completion for the entry opened before Back arrives after the restore: it must be acknowledged and ignored.
        compose.runOnIdle { check(completions.tryEmit(staleId)) }
        settle()
        compose.runOnIdle {
            assertEquals(staleId + 1, entry.entryId)
            assertEquals(7, entry.reading)
            assertTrue(host.open)
            assertEquals(1, handled)
            assertTrue(completions.replayCache.isEmpty())
        }
    }

    @Test fun `a stale completion already cached when the host subscribes is acknowledged once and closes nothing`() {
        // Sol r1 (#1425): the replay case a restored activity meets, a completion waiting in the cache BEFORE the host's
        // collector exists, for an entry that is no longer the open one.
        val completions = MutableSharedFlow<Long>(replay = 1)
        check(completions.tryEmit(1L))
        var open = true
        var handled = 0
        var dismissed = 0
        val port = object : SheetPort {
            override val key = EntrySheet.ODOMETER
            override val open get() = open
            override val entryId = 2L
            override val completed = completions
            override fun completionHandled() { handled++; completions.resetReplayCache() }
            override fun show() { open = true }
            override fun dismiss() { dismissed++; open = false }
            override fun back() { open = false }
        }
        compose.setContent { host = rememberBubbleSheetHost(listOf(SheetSlot(port) {})) }
        settle()
        compose.runOnIdle { host.show(EntrySheet.ODOMETER) }
        settle()
        compose.runOnIdle {
            assertEquals(1, handled)
            assertTrue(completions.replayCache.isEmpty())
            assertTrue(open)
            // show() dismisses only OTHER ports, so the odometer itself was never dismissed by the stale id.
            assertEquals(0, dismissed)
        }
    }

    @Test fun `restoration retains the selected goal and repeated goal launches keep its entry`() {
        val restoration = StateRestorationTester(compose)
        val vm: BubbleViewModel = mock()
        val actions: GoalActions = mock { on { committed } doReturn MutableSharedFlow() }
        whenever(vm.goalActions).thenReturn(actions)
        lateinit var entry: GoalEntryState
        var openedId = 0L
        restoration.setContent {
            entry = rememberGoalEntryState()
            val goal = remember { GoalHud(vm, entry) {} }
            host = rememberBubbleSheetHost(listOf(
                SheetSlot(ports.first()) { Text("odometer") },
                SheetSlot(goal) { Text("goal") },
            ))
            Column { host.Sheet(this) }
        }
        settle()
        compose.runOnIdle {
            host.show(EntrySheet.GOAL)
            openedId = entry.entryId
            // HudScreen calls outside before dispatching a goal-pill click.
            host.outside()
            host.show(EntrySheet.GOAL)
            assertEquals(openedId, entry.entryId)
        }
        settle()
        restoration.emulateSavedInstanceStateRestore()
        settle()
        compose.runOnIdle {
            assertTrue(host.open)
            assertEquals(openedId, entry.entryId)
        }
        compose.onNodeWithText("goal").assertExists()
        compose.onNodeWithText("odometer").assertDoesNotExist()
        compose.runOnIdle { host.back(); assertFalse(host.open) }
    }

    @Test fun `updated slot content is used without replacing the host`() {
        var label by mutableStateOf("before")
        compose.setContent {
            val currentLabel = label
            host = rememberBubbleSheetHost(listOf(SheetSlot(ports.first()) { Text(currentLabel) }))
            Column { host.Sheet(this) }
        }
        settle()
        val firstHost = host
        compose.runOnIdle { label = "after" }
        settle()
        assertSame(firstHost, host)
        compose.onNodeWithText("after").assertExists()
    }
}

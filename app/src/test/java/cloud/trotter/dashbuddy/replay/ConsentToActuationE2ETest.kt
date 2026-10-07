package cloud.trotter.dashbuddy.replay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cloud.trotter.dashbuddy.core.datastore.capability.GrantSnapshot
import cloud.trotter.dashbuddy.core.datastore.capability.RuleCapabilityDataSource
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.domain.action.ActionTrigger
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.capability.ConsentReceipt
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.settings.EventReceiptPreferences
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.feature.settings.CapabilityConsentViewModel
import cloud.trotter.dashbuddy.test.util.DoorDashFullDashJourney
import cloud.trotter.dashbuddy.test.util.E2ESessionReplay
import cloud.trotter.dashbuddy.test.util.ReplayApplication
import cloud.trotter.dashbuddy.test.util.ReplayEdges
import cloud.trotter.dashbuddy.test.util.SessionReplay
import cloud.trotter.dashbuddy.ui.main.setup.consent.ConsentPromptRow
import cloud.trotter.dashbuddy.ui.main.setup.consent.ConsentPromptViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #1271 scenario 5 — **consent → actuation, end to end (the Pledge's "capability gates fail
 * closed", #417/#843/#1167)**. The REAL consent surfaces write the REAL grant store
 * (`RuleCapabilityRepository` over its `RuleCapabilityDataSource`), which the production
 * `SideEffectEngine` reads at fire time before the production `UiInteractionHandler` may click the
 * harness's mirrored third-party tree ([E2ESessionReplay]).
 *
 * - Acquisition: `ConsentPromptViewModel` (the front-door prompt, Allow / Don't allow per row).
 * - Revocation: `CapabilityConsentViewModel` (Settings → Data & Privacy → Automation & Consent).
 *
 * Fixtures (committed, read unchanged; no new capture):
 * - `snapshots/delivery_summary_collapsed/2026-08-23_17-35-17-361__doordash__…__c65d43.json`
 *   ([DoorDashFullDashJourney.COLLAPSED]) — the 8.93.7 collapsed receipt whose id-less pay row binds
 *   the `expandButton` target, so EffectMap arms the app-owned `expand_earnings` AUTOMATION tap.
 * - `snapshots/sessions/two_pickup_stack_2026_07_05/01_*.json` — the stacked offer card that binds
 *   `acceptButton`, for the dasher's own (USER) press from the heads-up.
 *
 * The ONLY test-side manipulation is the content-changed case's ruleset update: the collapsed
 * receipt rule's `expandButton` find is re-expressed as a one-element `all` around the very same
 * predicate (same node, new definition), exactly the shape a remote ruleset update that repoints a
 * binding takes — the loader, compiler and capability enumeration run unchanged on the result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ReplayApplication::class)
class ConsentToActuationE2ETest {

    private val app get() = RuntimeEnvironment.getApplication() as ReplayApplication
    private val closers = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() {
        closers.asReversed().forEach { it.close() }
    }

    @Test
    fun `expand_earnings automation clicks only if granted when its armed tap fires - undecided, denied, granted, revoked and re-granted while armed`() {
        val replay = harness()
        val prompt = consentPrompt(replay)
        val settings = settingsScreen(replay)

        // ── Undecided: the prompt lists it; the tap is armed but the gate refuses it ─────────────
        val row = expandRow(prompt)
        assertNotNull("a fresh install lists expand_earnings as an UNDECIDED prompt row", row)
        val key = row!!.key
        assertEquals(Platform.DoorDash, row.platform)
        assertEquals("undecided → zero clicks", 0, receiptClicks(replay, at = RECEIPT_MS))

        // ── Denied from the prompt (a durable answer: the row leaves the prompt) ─────────────────
        prompt.onDecision(key, allow = false)
        replay.settle()
        assertTrue("denied", key in replay.grants.deniedKeys.value)
        assertNull("a denial is an answer — the prompt no longer asks", expandRow(prompt))
        assertEquals("denied → zero clicks", 0, receiptClicks(replay, at = RECEIPT_MS + 20_000L))

        // ── Granted from the prompt ──────────────────────────────────────────────────────────────
        prompt.onDecision(key, allow = true)
        replay.settle()
        assertTrue("granted", key in replay.grants.grantedKeys.value)
        assertTrue("the grant cleared the earlier denial", key !in replay.grants.deniedKeys.value)
        val receipt: ConsentReceipt? = replay.grants.receipts.value[key]
        assertEquals("the grant carries its receipt (#170)", true, receipt?.granted)
        val before = replay.accessibility.clicks.size
        assertEquals("granted → exactly one click", 1, receiptClicks(replay, at = RECEIPT_MS + 40_000L))
        val click = replay.accessibility.clicks[before]
        val fired = replay.executor.trace.map { it.effect }.filterIsInstance<AppEffect.PerformRuleAction>()
            .last { it.action == RuleAction.EXPAND_EARNINGS }
        val b = fired.targetRef.boundsInScreen
        assertEquals(
            "the click landed on the bound pay-breakdown row",
            listOf(b.left, b.top, b.right, b.bottom),
            listOf(click.bounds.left, click.bounds.top, click.bounds.right, click.bounds.bottom),
        )

        // ── Revoked from Settings WHILE a tap is armed: consent is checked at FIRE time ──────────
        // The receipt is recognized (and its SETTLE_UI timer armed) under the grant; the revocation
        // lands before that timer fires; no further screen is fed — the armed tap alone decides.
        assertEquals(
            "granted at recognition, revoked before the armed timer fired → zero clicks",
            0,
            receiptClicks(replay, at = RECEIPT_MS + 60_000L) {
                settings.setGranted(key, false)
                replay.settle()
                assertTrue("revoked", key !in replay.grants.grantedKeys.value && key in replay.grants.deniedKeys.value)
            },
        )

        // ── The mirror: denied at recognition, granted (from the prompt) before the timer fires ──
        assertEquals(
            "denied at recognition, granted before the armed timer fired → exactly one click",
            1,
            receiptClicks(replay, at = RECEIPT_MS + 80_000L) {
                prompt.onDecision(key, allow = true)
                replay.settle()
                assertTrue("re-granted", key in replay.grants.grantedKeys.value)
            },
        )

        val automationTaps = replay.executor.trace.map { it.effect }.filterIsInstance<AppEffect.PerformRuleAction>()
            .filter { it.action == RuleAction.EXPAND_EARNINGS }
        assertEquals(
            "every case really reached the engine's gate as an AUTOMATION tap (the zeros are refusals, not silence)",
            List(5) { ActionTrigger.AUTOMATION }, automationTaps.map { it.trigger },
        )
        assertEquals("two clicks across the five cases (granted, granted-while-armed)", 2, replay.accessibility.clicks.size)
    }

    @Test
    fun `a repointed expand binding re-enters consent - the old grant does not cover it`() {
        val grantStore = ReplayEdges.MemoryPreferences()

        // Launch 1 — the shipped ruleset; the dasher allows expand_earnings.
        val first = harness(grantStore)
        val firstPrompt = consentPrompt(first)
        val oldKey = expandRow(firstPrompt)!!.key
        firstPrompt.onDecision(oldKey, allow = true)
        first.settle()
        assertEquals("granted on the shipped ruleset → one click", 1, receiptClicks(first, at = RECEIPT_MS))
        close(first)

        // Launch 2 — same persisted grants, a ruleset whose expandButton definition changed.
        // Its start() ran the production consent-schema migration again: the store launch 1 stamped
        // is current-schema, so the migration is a no-op and the grant is KEPT (the pre-#1167 case,
        // where it is cleared, is pinned below).
        val second = harness(grantStore, ruleTransform = ::repointExpandBinding)
        val secondPrompt = consentPrompt(second)
        assertFalse(
            "the store is already at the current consent schema — a further migration is a no-op",
            second.await { RuleCapabilityDataSource(grantStore).migrateConsentSchemaIfNeeded() },
        )
        assertTrue("the current-schema grant survived the restart and its migration", oldKey in second.grants.grantedKeys.value)
        val newRow = expandRow(secondPrompt)
        assertNotNull("the changed binding is UNDECIDED again — consent re-entered", newRow)
        assertNotEquals("the content-pinned key moved with the definition", oldKey, newRow!!.key)
        assertEquals("the stale grant does not cover the repointed binding → zero clicks", 0, receiptClicks(second, at = RECEIPT_MS + 20_000L))

        secondPrompt.onDecision(newRow.key, allow = true)
        second.settle()
        assertEquals(
            "re-consented → one click (the repointed binding still aims at the real row)",
            1, receiptClicks(second, at = RECEIPT_MS + 40_000L),
        )
    }

    @Test
    fun `a pre-1167 grant is cleared by the startup migration before rules load - the tap stays undecided`() {
        // A store written before the #1167 key shape: a grant on the CURRENT expand key, with no
        // consent-schema version stamped (what every pre-#1167 install carries).
        val currentKey = harness().let { probe ->
            expandRow(consentPrompt(probe))!!.key.also { close(probe) }
        }
        val grantStore = ReplayEdges.MemoryPreferences()
        val legacy = RuleCapabilityDataSource(grantStore)
        kotlinx.coroutines.runBlocking {
            legacy.update { g, d, r -> GrantSnapshot(g + currentKey, d, r) }
        }
        assertTrue("seeded", currentKey in kotlinx.coroutines.runBlocking { legacy.granted.first() })

        val replay = harness(grantStore)
        val prompt = consentPrompt(replay)
        assertTrue("the startup migration cleared the pre-#1167 grant", currentKey !in replay.grants.grantedKeys.value)
        assertEquals("…so the capability is undecided again", currentKey, expandRow(prompt)?.key)
        assertEquals("…and the armed tap is refused", 0, receiptClicks(replay, at = RECEIPT_MS))
    }

    @Test
    fun `a dasher-pressed Accept clicks without any grant - even with accept_offer denied`() {
        val replay = harness()
        val prompt = consentPrompt(replay)
        val acceptRow = prompt.uiState.value.rows.singleOrNull { it.action == RuleAction.ACCEPT_OFFER && it.platform == Platform.DoorDash }
        assertNotNull("accept_offer is a consentable capability", acceptRow)
        prompt.onDecision(acceptRow!!.key, allow = false)
        replay.settle()
        assertTrue(acceptRow.key in replay.grants.deniedKeys.value)
        assertTrue("no capability is granted at all", replay.grants.grantedKeys.value.isEmpty())

        val offer = SessionReplay.loadSession(STACK).first { it.file.startsWith("01") }
        replay.screen(offer)
        replay.tapOfferNotification(OfferIntent.ACCEPT, offer.capturedAtMs + 5_000L)

        val accepts = replay.executor.trace.map { it.effect }.filterIsInstance<AppEffect.PerformRuleAction>()
            .filter { it.action == RuleAction.ACCEPT_OFFER }
        assertEquals("the press is a USER action — its own consent", listOf(ActionTrigger.USER), accepts.map { it.trigger })
        val click = replay.accessibility.clicks.single()
        assertTrue("…and it clicked DoorDash's accept button", click.viewId!!.endsWith(":id/accept_button"))
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun harness(
        grantStore: ReplayEdges.MemoryPreferences = ReplayEdges.MemoryPreferences(),
        ruleTransform: (String, String) -> String = { _, json -> json },
    ): E2ESessionReplay {
        val replay = E2ESessionReplay(app, EPOCH_MS, grantStore, ruleTransform)
        closers += replay
        replay.start()
        return replay
    }

    private fun close(replay: E2ESessionReplay) {
        closers.remove(replay)
        replay.close()
    }

    /** The real front-door prompt VM over the harness's grant store, owned by a store torn down after the test. */
    private fun consentPrompt(replay: E2ESessionReplay): ConsentPromptViewModel =
        viewModel { ConsentPromptViewModel(replay.grants) }.also { vm ->
            // Subscribed, as the composable is — so `uiState` tracks the store (WhileSubscribed).
            val ui = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
            closers += AutoCloseable { ui.cancel() }
            ui.launch { vm.uiState.collect {} }
            replay.settle()
        }

    /** The real Settings consent VM; its event-receipt half is irrelevant here (a fixed ALLOWED value). */
    private fun settingsScreen(replay: E2ESessionReplay): CapabilityConsentViewModel =
        viewModel { CapabilityConsentViewModel(replay.grants, AllowedEventReceipt) }

    private inline fun <reified VM : ViewModel> viewModel(crossinline create: () -> VM): VM {
        val store = ViewModelStore()
        closers += AutoCloseable { store.clear() }
        return ViewModelProvider(store, viewModelFactory { initializer { create() } })[VM::class.java]
    }

    /** The prompt's undecided expand_earnings row, as the VM's own (subscribed, live) state shows it. */
    private fun expandRow(prompt: ConsentPromptViewModel): ConsentPromptRow? =
        prompt.uiState.value.rows.singleOrNull { it.action == RuleAction.EXPAND_EARNINGS }

    /**
     * Show the collapsed receipt at [at], hold past the settle delay so the engine's own SETTLE_UI
     * timer fires the deferred tap, and return how many clicks landed meanwhile. [beforeFire] runs
     * while that timer is ARMED and not yet fired (asserted), with no further screen fed.
     */
    private fun receiptClicks(replay: E2ESessionReplay, at: Long, beforeFire: (() -> Unit)? = null): Int {
        val before = replay.accessibility.clicks.size
        val armed = replay.executor.trace.count { (it.effect as? AppEffect.PerformRuleAction)?.action == RuleAction.EXPAND_EARNINGS }
        replay.screen(SessionReplay.loadScreenFrame(DoorDashFullDashJourney.COLLAPSED, at))
        if (beforeFire != null) {
            val settle = replay.executor.trace.last { (it.effect as? AppEffect.ScheduleTimeout)?.type == TimeoutType.SETTLE_UI }
            assertEquals("the receipt armed its SETTLE_UI timer at $at", at, settle.atMs)
            replay.advanceTo(at + (settle.effect as AppEffect.ScheduleTimeout).durationMs / 2)
            assertEquals(
                "the armed tap has not fired yet",
                armed,
                replay.executor.trace.count { (it.effect as? AppEffect.PerformRuleAction)?.action == RuleAction.EXPAND_EARNINGS },
            )
            beforeFire()
        }
        replay.advanceTo(at + SETTLE_HOLD_MS)
        assertEquals(
            "the collapsed receipt armed exactly one expand tap at $at\n${replay.trace()}",
            armed + 1,
            replay.executor.trace.count { (it.effect as? AppEffect.PerformRuleAction)?.action == RuleAction.EXPAND_EARNINGS },
        )
        return replay.accessibility.clicks.size - before
    }

    private object AllowedEventReceipt : EventReceiptPreferences {
        override val consent: StateFlow<EventReceiptConsent?> = MutableStateFlow(EventReceiptConsent.ALLOWED)
        override val receipt: StateFlow<ConsentReceipt?> = MutableStateFlow(null)
        override suspend fun set(consent: EventReceiptConsent): Boolean = true
    }

    private companion object {
        const val STACK = "snapshots/sessions/two_pickup_stack_2026_07_05"

        /** The collapsed receipt's capture instant (2026-08-23T22:35:17.361Z, its filename). */
        const val RECEIPT_MS = 1_787_524_517_361L
        const val EPOCH_MS = 1_783_283_820_495L - 10_000L

        /** Well past `GraceConfig.EXPAND_SETTLE_MS`. */
        const val SETTLE_HOLD_MS = 5_000L

        const val COLLAPSED_RULE = "doordash.screen.delivery_summary_collapsed"

        /**
         * The ruleset UPDATE: re-express the collapsed receipt rule's `expandButton.find` as
         * `{"all": [<the same find>]}` — the same node, a different binding DEFINITION.
         */
        fun repointExpandBinding(file: String, json: String): String {
            if (file != "doordash.json") return json
            val root = Json.parseToJsonElement(json).jsonObject
            var hit = 0
            val screens = root.getValue("screens").jsonArray.map { rule ->
                val r = rule.jsonObject
                if (r["id"]?.jsonPrimitive?.content != COLLAPSED_RULE) return@map r
                val bind = r.getValue("bind").jsonObject
                val expand = bind.getValue("expandButton").jsonObject
                hit++
                val repointed = JsonObject(expand + ("find" to JsonObject(mapOf("all" to JsonArray(listOf(expand.getValue("find")))))))
                JsonObject(r + ("bind" to JsonObject(bind + ("expandButton" to repointed))))
            }
            check(hit == 1) { "expected one $COLLAPSED_RULE rule, found $hit" }
            return JsonObject(root + ("screens" to JsonArray(screens))).toString()
        }
    }
}

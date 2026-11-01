package cloud.trotter.log.gig.ui

import android.app.Notification
import android.app.NotificationManager
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.data.chat.ChatRepository
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.core.state.StateManagerV2
import cloud.trotter.log.gig.domain.action.RuleAction
import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.capability.PrivacyDisclosure
import cloud.trotter.log.gig.domain.capability.RuleCapability
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import cloud.trotter.log.gig.domain.config.OfferAutomationConfig
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.format.formatDuration
import cloud.trotter.log.gig.domain.model.cards.FlowCardSnapshot
import cloud.trotter.log.gig.domain.offer.story.AddOnSpeech
import cloud.trotter.log.gig.domain.offer.story.OfferFixtures
import cloud.trotter.log.gig.domain.settings.EventReceiptConsent
import cloud.trotter.log.gig.domain.settings.EventReceiptPreferences
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.feature.bubble.hud.LocalReducedMotion
import cloud.trotter.log.gig.feature.settings.CapabilityConsentScreen
import cloud.trotter.log.gig.feature.settings.CapabilityConsentViewModel
import cloud.trotter.log.gig.feature.settings.EventReceiptConsentDialog
import cloud.trotter.log.gig.feature.settings.EventReceiptOffConfirmDialog
import cloud.trotter.log.gig.feature.settings.PrivacyScreen
import cloud.trotter.log.gig.feature.settings.StrategySettingsContent
import cloud.trotter.log.gig.feature.settings.eventReceiptDisclosureParts
import cloud.trotter.log.gig.ui.bubble.BubbleManager
import cloud.trotter.log.gig.ui.main.MainScaffold
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsContent
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsTab
import cloud.trotter.log.gig.ui.main.dashboard.DashboardBody
import cloud.trotter.log.gig.ui.main.dashboard.DashboardUiState
import cloud.trotter.log.gig.ui.main.navigation.TopLevel
import cloud.trotter.log.gig.ui.main.playbook.PlaybookContent
import cloud.trotter.log.gig.ui.main.playbook.PlaybookUiState
import cloud.trotter.log.gig.ui.main.settings.SettingsHomeContent
import cloud.trotter.log.gig.ui.main.setup.consent.ConsentPromptPage
import cloud.trotter.log.gig.ui.main.setup.consent.ConsentPromptRow
import cloud.trotter.log.gig.ui.main.setup.consent.DebugEventReceiptBlock
import cloud.trotter.log.gig.ui.main.setup.consent.FrontDoorSheet
import cloud.trotter.log.gig.ui.main.setup.permissions.OsPermissionPoll
import cloud.trotter.log.gig.ui.main.setup.permissions.PermissionDeferrals
import cloud.trotter.log.gig.ui.main.setup.permissions.PermissionType
import cloud.trotter.log.gig.ui.main.setup.permissions.PermissionsBottomSheet
import cloud.trotter.log.gig.ui.main.setup.permissions.PermissionsViewModel
import cloud.trotter.log.gig.ui.main.setup.wizard.WizardScreen
import cloud.trotter.log.gig.ui.main.setup.wizard.wizardViewModelForTest
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone
import cloud.trotter.log.gig.feature.dashboard.R as DashboardR
import cloud.trotter.log.gig.feature.settings.R as SettingsR
import cloud.trotter.log.gig.feature.setup.R as SetupR

/**
 * #1463 — the app's key screens, light and dark, rendered for PR CI's `screenshots-<pr>` artifact
 * (`<area>/<name>-<theme>.png`). Recorded only, never compared. Runs only with `-PscreenShots=true`;
 * the normal gate compiles it and excludes it.
 *
 * Each shot composes the real screen body without Hilt (the hoisted bodies — the three tabs inside the
 * app shell's frame with its bottom bar (#1447), Settings home and Offer rules — the settings and
 * consent screens with in-file fakes, the permission sheet over its real ViewModel) on the Pixel 7
 * at 18:00 UTC on Wednesday 2026-10-28, over [ScreenshotData]. Sheets and dialogs are their own
 * windows, so those shots capture the whole screen. `-full` shots are supplemental: a 2000 dp tall
 * window so a scrolling page shows all of its content.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class ScreenshotsTest(private val theme: String) {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var locale: Locale
    private lateinit var timeZone: TimeZone

    @Before fun fixLocaleAndTimeZone() {
        locale = Locale.getDefault()
        timeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun restoreLocaleAndTimeZone() {
        Locale.setDefault(locale)
        TimeZone.setDefault(timeZone)
    }

    // ── Setup: the permission gate, the front door ───────────────────────

    @Test fun setupStep1() = permissionStep("step-1-screen-events", 1, EventReceiptConsent.UNDECIDED, OsPermissionPoll())

    @Test fun setupStep2() = permissionStep("step-2-screen-reading", 2, EventReceiptConsent.ALLOWED, OsPermissionPoll())

    @Test fun setupStep3() = permissionStep("step-3-notification-access", 3, EventReceiptConsent.ALLOWED,
        OsPermissionPoll(accessibility = true))

    @Test fun setupStep4() = permissionStep("step-4-location", 4, EventReceiptConsent.ALLOWED,
        OsPermissionPoll(accessibility = true, notificationListener = true))

    @Test fun setupStep5() = permissionStep("step-5-notifications", 5, EventReceiptConsent.ALLOWED,
        OsPermissionPoll(accessibility = true, notificationListener = true, location = true))

    @Test fun setupStep6() = permissionStep("step-6-bubbles", 6, EventReceiptConsent.ALLOWED,
        OsPermissionPoll(accessibility = true, notificationListener = true, location = true, postNotifications = true))

    /** The capability prompt over a ready Home, the way the front door opens once setup is done. */
    @Test fun setupConsentPrompt() = render("setup/consent-prompt", window = true,
        assertion = { text(string(R.string.consent_prompt_heading)).assertIsDisplayed() }) {
        HomeBody(ScreenshotData.dashboard(), hasPermissions = true)
        FrontDoorSheet({}) {
            ConsentPromptPage(listOf(
                ConsentPromptRow("k1", RuleAction.EXPAND_EARNINGS, Platform.DoorDash, true),
                ConsentPromptRow("k2", RuleAction.CONFIRM_DECLINE, Platform.DoorDash, true),
            ), { _, _ -> })
        }
    }

    /** #1447: the wizard's first step, with its "Setup" title bar and back arrow. */
    @Test fun setupWizardStep1() {
        val viewModel = wizardViewModelForTest()
        render("setup/wizard-step-1", assertion = {
            text(string(SetupR.string.wizard_title)).assertExists()
            compose.onNodeWithContentDescription(string(SetupR.string.common_content_desc_back)).assertExists()
        }) { WizardScreen(viewModel = viewModel, onComplete = {}, onExit = {}) }
    }

    /** A debug build whose Screen events were declined. */
    @Test fun setupDebugEventReceiptBlock() = render("setup/debug-event-receipt-block",
        assertion = { text(string(R.string.debug_event_receipt_block_title)).assertExists() }) {
        DebugEventReceiptBlock(onOpenSettings = {}, onExit = {})
    }

    // ── Settings: Automation & consent, Privacy ──────────────────────────

    /** #1447: Settings home below a tab — a title bar with a back button. */
    @Test fun settingsHome() = render("settings/home", assertion = {
        text(string(R.string.settings_home_title)).assertExists()
        compose.onNodeWithContentDescription(string(R.string.common_content_desc_back)).assertExists()
    }) {
        SettingsHomeContent(
            isDevUnlocked = false, garage = ScreenshotData.garage, userEconomy = ScreenshotData.userEconomy,
            onNavigate = {}, onBack = {},
        )
    }

    /** #1447: Offer rules — its own title bar, "Test offer" the sticky heading under it. */
    @Test fun settingsOfferRules() = render("settings/offer-rules", assertion = {
        text(string(SettingsR.string.strategy_settings_title)).assertExists()
        text(string(SettingsR.string.strategy_settings_the_lab_title)).assertExists()
    }) {
        StrategySettingsContent(
            config = ScreenshotData.evaluationConfig, automation = OfferAutomationConfig(), onBack = {},
            onReorder = {}, onToggleProtect = {}, onToggleShopping = {}, onToggleQuickDeclines = {},
            onUpdateRule = {},
        )
    }

    @Test fun settingsAutomationConsent() = automationConsent("settings/automation-consent")

    @Test @Config(qualifiers = FULL)
    fun settingsAutomationConsentFull() = automationConsent("settings/automation-consent-full")

    /** Turning Screen events on: the disclosure over the Automation & consent screen. */
    @Test fun settingsEventReceiptDialog() = render("settings/event-receipt-dialog", window = true,
        assertion = { text("gig.log gets a signal from every app").assertIsDisplayed() }) {
        ConsentScreen(EventReceiptConsent.UNDECIDED)
        EventReceiptConsentDialog(eventReceiptDisclosureParts(), onDecision = {}, onDismiss = {})
    }

    /** Turning Screen events off in a test build. */
    @Test fun settingsEventReceiptOffConfirm() = render("settings/event-receipt-off-confirm", window = true,
        assertion = { text(string(SettingsR.string.event_receipt_off_confirm_title)).assertIsDisplayed() }) {
        ConsentScreen(EventReceiptConsent.ALLOWED)
        EventReceiptOffConfirmDialog(onConfirm = {}, onDismiss = {})
    }

    /** The debug build's Privacy page, with its Test builds section. The release page is not rendered here. */
    @Test fun settingsPrivacyTestBuild() = privacy("settings/privacy-test-build")

    @Test @Config(qualifiers = FULL)
    fun settingsPrivacyTestBuildFull() = privacy("settings/privacy-test-build-full")

    // ── Home ─────────────────────────────────────────────────────────────

    @Test fun home() = home("home")

    @Test @Config(qualifiers = FULL)
    fun homeFull() = home("home-full")

    @Test fun homeGateScreenReadingOff() = render("home-gate-screen-reading-off",
        assertion = { text(string(R.string.dashboard_screen_permissions_a11y_title)).assertExists() }) {
        HomeBody(ScreenshotData.dashboard(), hasPermissions = false, screenReadingOff = true)
    }

    @Test fun homeGateSetup() = render("home-gate-setup",
        assertion = { text(string(R.string.dashboard_screen_permissions_setup_title)).assertExists() }) {
        HomeBody(ScreenshotData.dashboard(), hasPermissions = false, screenReadingOff = false)
    }

    @Test fun homeFirstRun() = render("home-first-run",
        assertion = { text(string(R.string.dashboard_screen_first_run_title)).assertExists() }) {
        HomeBody(ScreenshotData.dashboard(isFirstRun = true), hasPermissions = true)
    }

    /** #1447: Home at 200 % text, the bar's labels included (§5 font scale). */
    @Test @Config(fontScale = 2f)
    fun homeFont200() = home("home-font-200")

    // ── The Analytics hub ────────────────────────────────────────────────

    @Test fun hubMoney() = hub("hub/money", AnalyticsTab.Money, showMore = false,
        Formats.money(ScreenshotData.week.grossEarnings))

    /** More numbers: the latest-shifts list shows the week's oldest shift, Monday's, with its reported total. */
    @Test @Config(qualifiers = FULL)
    fun hubMoneyFull() = hub("hub/money-full", AnalyticsTab.Money, showMore = true,
        Formats.money(ScreenshotData.recentSessions.last().reportedEarnings!!))

    @Test fun hubOffers() = hub("hub/offers", AnalyticsTab.Offers, showMore = false,
        Formats.percent(ScreenshotData.acceptanceRate))

    @Test @Config(qualifiers = FULL)
    fun hubOffersFull() = hub("hub/offers-full", AnalyticsTab.Offers, showMore = true, "Sample Pizza")

    @Test fun hubTime() = hub("hub/time", AnalyticsTab.Time, showMore = false,
        formatDuration(ScreenshotData.typicalGapMillis))

    /**
     * More numbers: the net per hour while working, computed by `NetPerHourPair.of` from the fixture's
     * partitions and gaps. A working hour excludes the waits, so the rate must sit above the whole-shift
     * rate, and within three times it, or the fixture's time semantics are wrong.
     */
    @Test @Config(qualifiers = FULL)
    fun hubTimeFull() {
        val pair = ScreenshotData.analytics(AnalyticsTab.Time, showMore = true).netPerHour
        val working = pair.whileWorking!!
        val wholeShift = pair.wholeShift!!
        assertTrue("while working $working/hr against $wholeShift/hr for the whole shift",
            working > wholeShift && working < 3 * wholeShift)
        hub("hub/time-full", AnalyticsTab.Time, showMore = true, "${Formats.money(working)}/hr")
    }

    // ── The Playbook ─────────────────────────────────────────────────────

    /** Simple mode: the plan summary, the Rate heatmap and the top three stores by net. */
    @Test fun playbook() = playbook("playbook", ScreenshotData.playbook(showMore = false), "Sample Grocery")

    /** More numbers: every store, so the fifth by net shows. */
    @Test @Config(qualifiers = FULL)
    fun playbookFull() = playbook("playbook-full", ScreenshotData.playbook(showMore = true), "Sample Pharmacy")

    /** The one deliberate thin-data shot: no plan, no hours, no stores. */
    @Test fun playbookEmpty() = playbook("playbook-empty", ScreenshotData.playbookEmpty, string(R.string.patterns_tab_no_stores))

    // ── The offer heads-up notification ──────────────────────────────────

    @Suppress("DEPRECATION") // The Notification RemoteViews fields are the only window onto what SystemUI inflates.
    @Test fun notificationOfferCompact() = notification("notification/offer-compact", offerNotification().contentView)

    @Suppress("DEPRECATION")
    @Test fun notificationOfferExpanded() = notification("notification/offer-expanded", offerNotification().bigContentView)

    // ── Builders ─────────────────────────────────────────────────────────

    private class Prefs(consent: EventReceiptConsent, receipt: ConsentReceipt? = null) : EventReceiptPreferences {
        override val consent: StateFlow<EventReceiptConsent?> = MutableStateFlow(consent)
        override val receipt: StateFlow<ConsentReceipt?> = MutableStateFlow(receipt)
        override suspend fun set(consent: EventReceiptConsent): Boolean = error("unused")
    }

    /** `CapabilityConsentViewModelTest`'s fake, fixed: [capabilities] enumerated, [granted] granted, the rest denied. */
    private class Grants(caps: List<RuleCapability>, granted: Set<String>, receipts: Map<String, ConsentReceipt>) :
        RuleCapabilityGrants {
        override val capabilities: StateFlow<List<RuleCapability>> = MutableStateFlow(caps)
        override val grantedKeys: StateFlow<Set<String>> = MutableStateFlow(granted)
        override val deniedKeys: StateFlow<Set<String>> = MutableStateFlow(caps.map { it.key }.toSet() - granted)
        override val receipts: StateFlow<Map<String, ConsentReceipt>> = MutableStateFlow(receipts)
        override suspend fun setGranted(key: String, granted: Boolean) = error("unused")
        override suspend fun reconcile(capabilities: List<RuleCapability>) = error("unused")
        override suspend fun isActionGranted(ruleId: String?, action: RuleAction) = error("unused")
    }

    private fun receipt(granted: Boolean) = ConsentReceipt(
        decidedAt = ScreenshotData.clock.millis() - 86_400_000L, appVersion = APP_VERSION,
        disclosureRevision = PrivacyDisclosure.REVISION, granted = granted,
    )

    @Composable
    private fun ConsentScreen(consent: EventReceiptConsent) {
        val source = "asset:rules/doordash.json"
        val caps = listOf(
            RuleCapability("doordash.screen.delivery_summary_collapsed", RuleAction.EXPAND_EARNINGS,
                RuleAction.EXPAND_EARNINGS.targetBindName, "dd-expand", source),
            RuleCapability("doordash.screen.offer_popup_confirm_decline", RuleAction.CONFIRM_DECLINE,
                RuleAction.CONFIRM_DECLINE.targetBindName, "dd-confirm", source),
        )
        val viewModel = remember(consent) {
            val grants = Grants(caps, setOf("dd-expand"), mapOf("dd-expand" to receipt(true), "dd-confirm" to receipt(false)))
            CapabilityConsentViewModel(grants, Prefs(consent, if (consent == EventReceiptConsent.UNDECIDED) null else receipt(true)))
        }
        CapabilityConsentScreen(onBack = {}, viewModel = viewModel)
    }

    /** The title, plus the declined capability's decision line with its receipt (decision, date, app version). */
    private fun automationConsent(path: String) = render(path, assertion = {
        text(string(SettingsR.string.consent_title)).assertExists()
        text("${string(SettingsR.string.consent_receipt_denied)},").assertExists()
        text("app $APP_VERSION").assertExists()
    }) {
        ConsentScreen(EventReceiptConsent.ALLOWED)
    }

    private fun privacy(path: String) = render(path, assertion = {
        text(string(SettingsR.string.privacy_title)).assertExists()
        text(string(SettingsR.string.privacy_test_builds_title)).assertExists()
    }) { PrivacyScreen {} }

    /**
     * One step of the permission gate over Home in its gate state, through the real sheet and its real
     * ViewModel (the `PermissionsBottomSheetTest` shape): [poll] is what Android reports as granted.
     */
    private fun permissionStep(name: String, step: Int, consent: EventReceiptConsent, poll: OsPermissionPoll) {
        val vm = PermissionsViewModel({ poll }, Prefs(consent), isDebugBuild = false, PermissionDeferrals())
        val gate = vm.uiState.value
        render("setup/$name", window = true,
            assertion = { text(string(R.string.perm_step_format, step, 6)).assertIsDisplayed() }) {
            HomeBody(ScreenshotData.dashboard(), hasPermissions = if (gate.consentRead) gate.allGranted else null,
                screenReadingOff = PermissionType.Accessibility in gate.missing)
            PermissionsBottomSheet(onClosed = {}, viewModel = vm)
        }
    }

    private fun home(path: String) = render(path, assertion = {
        text(string(R.string.dashboard_screen_show_bubble_button)).assertExists()
        text(string(DashboardR.string.dashboard_weekly_plan_pointer_action)).assertExists()
    }) { HomeBody(ScreenshotData.dashboard(), hasPermissions = true) }

    /** Home in the app shell's frame, so the bottom bar shows under it (#1447). */
    @Composable
    private fun HomeBody(state: DashboardUiState, hasPermissions: Boolean?, screenReadingOff: Boolean = false) =
        MainScaffold(selectedTab = TopLevel.HOME, onSelectTab = {}) { modifier ->
            Box(modifier) {
                DashboardBody(
                    uiState = state, hasPermissions = hasPermissions, screenReadingOff = screenReadingOff,
                    onContinueGate = {}, onOpenWizard = {}, onCompleteSetup = {}, onShowBubble = {},
                    onOpenRecap = {}, onOpenPlan = {}, onOpenRatings = {}, onOpenSettings = {},
                )
            }
        }

    private fun hub(path: String, tab: AnalyticsTab, showMore: Boolean, row: String) = render(path, assertion = {
        text(string(tab.labelRes)).assertExists()
        text(row).assertExists()
    }) {
        MainScaffold(selectedTab = TopLevel.ANALYTICS, onSelectTab = {}) { modifier ->
            Box(modifier) {
                AnalyticsContent(
                    uiState = ScreenshotData.analytics(tab, showMore),
                    offersFeed = { ScreenshotData.offersFeed },
                    onOpenSettings = {}, onExportCsv = {}, onOpenSession = {}, onStep = {}, onOpenPicker = {},
                    onSelectGranularity = {}, onSelectTab = {}, onOpenNoSession = {}, onOpenOrphanOffers = {},
                    onSelectFilter = {}, onShowAllOffers = {}, onToggleShowMore = {},
                )
            }
        }
    }

    private fun playbook(path: String, state: PlaybookUiState, row: String) = render(path, assertion = {
        text(string(R.string.playbook_screen_title)).assertExists()
        text(row).assertExists()
    }) {
        MainScaffold(selectedTab = TopLevel.PLAYBOOK, onSelectTab = {}) { modifier ->
            Box(modifier) { PlaybookContent(uiState = state, onOpenSettings = {}, onOpenPlan = {}, onToggleShowMore = {}) }
        }
    }

    /** `OfferHeadsUpViewsTest`'s `post()`: the real `BubbleManager` builds the notification for the HUD's standard offer. */
    private fun offerNotification(): Notification {
        val notifications: NotificationManager = mock()
        val preferences: AppPreferencesRepository = mock()
        val garage: GarageRepository = mock()
        val readings: OdometerReadingRepository = mock()
        val state: StateManagerV2 = mock()
        whenever(preferences.odometerPrompts).thenReturn(flowOf(false))
        whenever(garage.garage).thenReturn(flowOf(Garage(emptyList(), null, readable = true)))
        whenever(readings.readings).thenReturn(flowOf(emptyList()))
        whenever(state.state).thenReturn(MutableStateFlow(AppState()))
        val manager = BubbleManager(context, notifications, mock<ChatRepository>(), dagger.Lazy { state },
            preferences, garage, readings, ScreenshotData.clock)
        // The countdown is a Chronometer whose base BubbleManager computes from the wall clock
        // (System.currentTimeMillis()), not its injected Clock, so the expiry is anchored to that same
        // wall clock here: 27 seconds left when the notification is posted.
        val now = System.currentTimeMillis()
        val pending = OfferFixtures.pending().copy(presentedAt = now - 3_000, countdownExpiresAt = now + 27_000)
        val snapshot = FlowCardSnapshot.Offer(phaseStartedAt = pending.presentedAt, offerHash = pending.offerHash,
            payAmount = pending.offerFields.parsedOffer.payAmount)
        manager.postOfferNotification(snapshot, pending.evaluation!!, pending.platform, refreshOnly = true,
            pending = pending, config = OfferFixtures.config, job = null, addOnSpeech = AddOnSpeech.JOB_TOTALS)
        val captor = argumentCaptor<Notification>()
        verify(notifications).notify(eq(BubbleManager.offerNotificationId(pending.offerHash)), captor.capture())
        return captor.firstValue
    }

    /**
     * The notification's own RemoteViews, inflated as SystemUI would, on the theme's surface. If inflation
     * fails the shot fails, never skips: the hero view is asserted after the render, and the countdown
     * must show time left, never a negative span.
     */
    private fun notification(path: String, remote: RemoteViews) {
        var inflated: View? = null
        render(path, assertion = {
            assertNotNull(inflated?.findViewById<TextView>(R.id.notif_offer_hero))
            val countdown = inflated?.findViewById<TextView>(R.id.notif_offer_countdown)?.text?.toString().orEmpty()
            assertTrue("countdown \"$countdown\" must show time left", countdown.any(Char::isDigit) &&
                countdown.none { it == '-' || it == '\u2212' })
        }) {
            Box(Modifier.fillMaxSize().background(AppTheme.colors.bg).padding(16.dp)) {
                AndroidView(factory = { ctx -> remote.apply(ctx, FrameLayout(ctx)).also { inflated = it } },
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }

    private fun string(@StringRes id: Int): String = context.getString(id)
    private fun string(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    /** The first node showing [text], case-insensitively: a label can be upper-cased or appear twice. */
    private fun text(text: String) = compose.onAllNodesWithText(text, substring = true, ignoreCase = true)[0]

    /**
     * strength.log's procedure: a frozen frame clock, two seconds of frames, then the assertion and the
     * capture. A sheet or dialog ([window]) runs a second round, so the capture never lands mid-slide,
     * and is captured as the whole screen because it is its own window.
     */
    private fun render(path: String, window: Boolean = false, assertion: () -> Unit, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme(darkTheme = theme == "dark") {
                CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock,
                    LocalReducedMotion provides true, content = content)
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        if (window) {
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
        }
        assertion()
        if (System.getProperty("roborazzi.test.record") == "true") {
            val file = File(System.getProperty("giglog.screenshots.dir"), "$path-$theme.png")
            file.parentFile?.mkdirs()
            if (window) captureScreenRoboImage(file) else compose.onRoot().captureRoboImage(file)
        }
    }

    companion object {
        /** Supplemental tall window: a scrolling page shows all of its content. */
        const val FULL = "w411dp-h2000dp-420dpi"

        /** The app version the consent receipts record. */
        private const val APP_VERSION = "0.230.0"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf<Any>("light"), arrayOf<Any>("dark"))
    }
}

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cloud.trotter.log.gig.BuildConfig
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.R as DesignR
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.core.data.chat.ChatRepository
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.core.state.StateManagerV2
import cloud.trotter.log.gig.domain.action.RuleAction
import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.capability.PrivacyDisclosure
import cloud.trotter.log.gig.domain.capability.RuleCapability
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import cloud.trotter.log.gig.domain.census.CensusConsent
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
import cloud.trotter.log.gig.feature.settings.CapabilityConsentScreen
import cloud.trotter.log.gig.feature.settings.CapabilityConsentViewModel
import cloud.trotter.log.gig.feature.settings.CensusContent
import cloud.trotter.log.gig.feature.settings.CensusDeleteDialog
import cloud.trotter.log.gig.feature.settings.CensusSentContent
import cloud.trotter.log.gig.feature.settings.CensusDisclosure
import cloud.trotter.log.gig.feature.settings.CensusPlatformRow
import cloud.trotter.log.gig.feature.settings.CensusUiState
import cloud.trotter.log.gig.feature.settings.EventReceiptConsentDialog
import cloud.trotter.log.gig.feature.settings.EventReceiptOffConfirmDialog
import cloud.trotter.log.gig.feature.settings.PrivacyScreen
import cloud.trotter.log.gig.feature.settings.PrivacyViewModel
import cloud.trotter.log.gig.feature.settings.StrategySettingsContent
import cloud.trotter.log.gig.feature.settings.eventReceiptDisclosureParts
import cloud.trotter.log.gig.ui.bubble.BubbleManager
import cloud.trotter.log.gig.ui.main.analytics.DatePickerChoice
import cloud.trotter.log.gig.ui.main.analytics.RecapDatePickerContent
import cloud.trotter.log.gig.ui.main.analytics.day.DayContent
import cloud.trotter.log.gig.ui.main.analytics.month.MonthContent
import cloud.trotter.log.gig.ui.main.analytics.month.MonthUiState
import cloud.trotter.log.gig.ui.main.analytics.rememberRecapDatePickerState
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsUiState
import cloud.trotter.log.gig.ui.main.analytics.shifts.SHIFTS_LIST_TAG
import cloud.trotter.log.gig.ui.main.analytics.shifts.ShiftsContent
import cloud.trotter.log.gig.ui.main.analytics.shifts.ShiftsUiState
import cloud.trotter.log.gig.ui.main.dashboard.EndShiftCandidate
import cloud.trotter.log.gig.ui.main.dashboard.EndShiftForm
import cloud.trotter.log.gig.ui.main.dashboard.EndShiftSheet
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** #1621: the census opt-in, accepted, as the test build shows it. */
    @Test fun settingsCensus() = render("settings/census", assertion = {
        text(string(SettingsR.string.census_switch_label)).assertExists()
    }) {
        CensusContent(
            state = CensusUiState(
                consent = CensusConsent.ON,
                platforms = listOf(CensusPlatformRow(Platform.DoorDash, true), CensusPlatformRow(Platform.Uber, false)),
                wifiOnly = true, serverHost = "census.dashbuddy.trotter.cloud", testBuild = true,
            ),
            onBack = {}, onAccept = {}, onDecline = {}, onWifiOnly = {}, onPlatform = { _, _ -> },
            onOpenSent = {}, onDelete = {},
        )
    }

    /** #1622: the delete confirmation: what is deleted, what the server cannot tie to this phone, the backups. */
    @Test fun settingsCensusDeleteDialog() = render("settings/census-delete-dialog", window = true, assertion = {
        text(string(SettingsR.string.census_delete_dialog_title)).assertExists()
    }) { CensusDeleteDialog(onConfirm = {}, onDismiss = {}) }

    /** #1622: what the census sent, a day with outlines, a notification and daily counts, the next upload ahead. */
    @Test fun settingsCensusSent() = render("settings/census-sent", assertion = {
        text(string(SettingsR.string.census_sent_title)).assertExists()
    }) { CensusSentContent(state = ScreenshotData.censusSent(), onBack = {}) }

    /** #1621: the census disclosure as a Play or sideload user sees it before accepting. */
    @Test fun settingsCensusDisclosure() = censusDisclosure("settings/census-disclosure")

    @Test @Config(qualifiers = FULL)
    fun settingsCensusDisclosureFull() = censusDisclosure("settings/census-disclosure-full")

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

    /** #1533: the end-shift sheet over Home, with the suggested time and an open delivery. */
    @Test fun homeEndShiftSheet() = render("home/end-shift-sheet", window = true,
        assertion = { text(string(R.string.end_shift_title)).assertExists() }) {
        val now = ScreenshotData.clock.millis()
        val candidate = EndShiftCandidate(Platform.DoorDash, "session-A", startedAt = now - 5_400_000L,
            lastActivityAt = now - 1_800_000L, hasOpenDelivery = true)
        HomeBody(ScreenshotData.dashboard().copy(endShiftCandidates = listOf(candidate)), hasPermissions = true)
        EndShiftSheet(listOf(candidate), EndShiftForm.opening(listOf(candidate)), onFormChange = {},
            onConfirm = { _, _ -> }, onDismiss = {})
    }

    /** #1447: Home at 200 % text with the icon-only bar (§5 font scale). */
    @Test @Config(fontScale = 2f)
    fun homeFont200() = home("home-font-200")

    // ── Recap (#1515) ─────────────────────────────────────────────────────

    /** Mid-week with today's shift running: the comparison waits; Tuesday's two shifts. */
    @Test fun recapWeek() = recap("recap/week", ScreenshotData.recap(), Formats.money(ScreenshotData.week.grossEarnings))

    @Test @Config(fontScale = 2f)
    fun recapWeekFont200() = recap("recap/week-font-200", ScreenshotData.recap(), Formats.money(ScreenshotData.week.grossEarnings))

    /** Last week: every day has happened, so the comparison covers Monday to Sunday. */
    /** Saturday had a shift with no pay found; a chart row speaks through its description, so that is what is checked. */
    @Test fun recapPastWeek() = render("recap/past-week", assertion = {
        text(string(R.string.analytics_screen_title)).assertExists()
        compose.onAllNodes(hasContentDescription(string(R.string.recap_day_no_pay), substring = true))[0].assertExists()
    }) { RecapBody(ScreenshotData.recapPastWeek()) }

    @Test fun recapWeekOff() = recap("recap/week-off", ScreenshotData.recapWeekOff(), string(R.string.recap_row_spending))

    @Test fun recapNoShiftsYet() = recap("recap/no-shifts-yet", ScreenshotData.recapNoShiftsYet, string(R.string.recap_no_shifts_yet))

    /** Tuesday's day page: two shifts, newest first. */
    @Test fun recapDay() = day("recap/day")

    @Test @Config(fontScale = 2f)
    fun recapDayFont200() = day("recap/day-font-200")

    // ── The Shifts list (#1515) ──────────────────────────────────────────

    /** Opened at this week: today's shift running, Monday's two apps and its check reason, then last week. */
    @Test fun shiftsList() = shifts("shifts/list")

    @Test @Config(fontScale = 2f)
    fun shiftsListFont200() = shifts("shifts/list-font-200")

    /**
     * Last week's 29 shifts at 200 % text: every row is scrolled into view, shown, and its text has no overflow; the
     * capture shows the week's last rows.
     */
    @Test @Config(fontScale = 2f)
    fun shiftsBusyWeekFont200() = render("shifts/busy-week-font-200", assertion = {
        val rows = ScreenshotData.busyWeek.weeks.single().shifts.map { it.shift.sessionId }
        assertEquals(ScreenshotData.BUSY_WEEK_SHIFTS, rows.size)
        rows.forEach { id ->
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag(SHIFTS_LIST_TAG).performScrollToKey("shift:$id")
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("shifts-row-$id").assertIsDisplayed()
            assertNoTextOverflow("shifts-row-$id")
        }
        compose.onNodeWithTag("shifts-row-${rows.last()}").assertIsDisplayed()
    }) { ShiftsBody(ScreenshotData.busyWeek) }

    // ── The date picker and the Month screen (#1515) ────────────────────

    /** Recap's picker on this week's Monday: Cancel, Week, Month, and the two periods it would open. */
    @Test fun recapDatePicker() = datePicker("recap/date-picker")

    @Test @Config(fontScale = 2f)
    fun recapDatePickerFont200() = datePicker("recap/date-picker-font-200")

    /** September, finished: whole months compared, every week row. */
    @Test fun monthFinished() = month("month/finished", ScreenshotData.monthFinished)

    @Test @Config(fontScale = 2f)
    fun monthFinishedFont200() = month("month/finished-font-200", ScreenshotData.monthFinished)

    /** October, today's month: the completed dates compared, only the weeks that have started, "This week". */
    @Test fun monthCurrent() = month("month/current", ScreenshotData.monthCurrent)

    @Test @Config(fontScale = 2f)
    fun monthCurrentFont200() = month("month/current-font-200", ScreenshotData.monthCurrent)

    @Test fun monthEmpty() = render("month/empty", assertion = {
        text(string(R.string.month_none_format, "July")).assertExists()
        text(string(R.string.month_comparison_none_format, "June")).assertExists()
    }) { MonthBody(ScreenshotData.monthEmpty) }

    // ── The Playbook ─────────────────────────────────────────────────────

    /** Simple mode: the plan summary, the Rate heatmap and the top three stores by net. */
    @Test fun playbook() = playbook("playbook", ScreenshotData.playbook(showMore = false), "Sample Grocery")

    /** More numbers: every store, so the fifth by net shows. */
    @Test @Config(qualifiers = FULL)
    fun playbookFull() = playbook("playbook-full", ScreenshotData.playbook(showMore = true), "Sample Pharmacy")

    /** The one deliberate thin-data shot: no plan, no hours, no stores. */
    @Test fun playbookEmpty() = playbook("playbook-empty", ScreenshotData.playbookEmpty, string(R.string.patterns_tab_no_stores))

    // ── Loading (#1524): each tab before its first data, past the loading body's 400 ms delay ──

    @Test fun homeLoading() = loading("home-loading") {
        HomeBody(ScreenshotData.dashboard().copy(isFirstRun = null), hasPermissions = true)
    }

    @Test fun recapLoading() = loading("recap/loading") {
        RecapBody(ScreenshotData.recap().copy(loaded = false))
    }

    @Test fun playbookLoading() = loading("playbook/loading") {
        PlaybookBody(ScreenshotData.playbook(showMore = false).copy(loading = true))
    }

    // ── The records states: rebuilding holds every figure back; a failed read offers Retry ──

    @Test fun recapRebuilding() = rebuilding("recap/rebuilding") {
        RecapBody(ScreenshotData.recap().copy(records = RecordsStatus.REBUILDING))
    }

    @Test fun recapFailed() = failed("recap/failed", R.string.analytics_could_not_load_what) {
        RecapBody(AnalyticsUiState(failed = true))
    }

    @Test fun playbookRebuilding() = rebuilding("playbook/rebuilding") {
        PlaybookBody(ScreenshotData.playbook(showMore = false).copy(records = RecordsStatus.REBUILDING))
    }

    @Test fun playbookFailed() = failed("playbook/failed", R.string.playbook_could_not_load_what) {
        PlaybookBody(PlaybookUiState(loading = false, failed = true))
    }

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
            CapabilityConsentViewModel(grants, Prefs(consent, if (consent == EventReceiptConsent.UNDECIDED) null else receipt(true)), eventReceiptOffered = true)
        }
        CapabilityConsentScreen(onBack = {}, onOpenPrivacy = {}, viewModel = viewModel)
    }

    /** The title, plus the declined capability's decision line with its receipt (decision, date, app version). */
    private fun automationConsent(path: String) = render(path, assertion = {
        text(string(SettingsR.string.consent_title)).assertExists()
        text("${string(SettingsR.string.consent_receipt_denied)},").assertExists()
        text("app $APP_VERSION").assertExists()
    }) {
        ConsentScreen(EventReceiptConsent.ALLOWED)
    }

    private fun censusDisclosure(path: String) = render(path, assertion = {
        text(string(SettingsR.string.census_disclosure_heading)).assertExists()
    }) {
        CensusDisclosure(
            serverHost = "census.dashbuddy.trotter.cloud", testBuild = false, accepted = false,
            onAccept = {}, onNotNow = {}, onClose = {},
        )
    }

    private fun privacy(path: String) = render(path, assertion = {
        text(string(SettingsR.string.privacy_title)).assertExists()
        text(string(SettingsR.string.privacy_test_builds_title)).assertExists()
    }) { PrivacyScreen(onBack = {}, viewModel = PrivacyViewModel(PrivacyDisclosure.URL, screenEventsOffered = true)) }

    /**
     * One step of the permission gate over Home in its gate state, through the real sheet and its real
     * ViewModel (the `PermissionsBottomSheetTest` shape): [poll] is what Android reports as granted. These shots
     * render the debug build's resources, so they take the debug build's own facts (#170); the per-build card
     * text is pinned by `AccessibilityDisclosureCompositionTest`, not here.
     */
    private fun permissionStep(name: String, step: Int, consent: EventReceiptConsent, poll: OsPermissionPoll) {
        val vm = PermissionsViewModel(
            { poll }, Prefs(consent), isDebugBuild = false, PermissionDeferrals(),
            screenEventsOffered = BuildConfig.WIDE_EVENT_RECEIPT_OFFERED,
            accessibilityTool = BuildConfig.ACCESSIBILITY_TOOL,
            readsUnboundPlatforms = BuildConfig.READS_UNBOUND_PLATFORMS,
        )
        val gate = vm.uiState.value
        render("setup/$name", window = true,
            assertion = { text(string(R.string.perm_step_format, step, 6)).assertIsDisplayed() }) {
            HomeBody(ScreenshotData.dashboard(), hasPermissions = if (gate.consentRead) gate.allGranted else null,
                screenReadingOff = PermissionType.Accessibility in gate.missing)
            PermissionsBottomSheet(onClosed = {}, onOpenPrivacy = {}, viewModel = vm)
        }
    }

    private fun home(path: String) = render(path, assertion = {
        text(string(R.string.dashboard_screen_show_bubble_button)).assertExists()
        text(string(DashboardR.string.dashboard_weekly_plan_pointer_action)).assertExists()
    }) { HomeBody(ScreenshotData.dashboard(), hasPermissions = true) }

    private fun recap(path: String, state: AnalyticsUiState, row: String) = render(path, assertion = {
        text(string(R.string.analytics_screen_title)).assertExists()
        text(row).assertExists()
    }) { RecapBody(state) }

    private fun day(path: String) = render(path, assertion = {
        text(Formats.money(ScreenshotData.day.economics.grossEarnings)).assertExists()
        text(Formats.money(ScreenshotData.day.shifts.first().earned)).assertExists()
    }) { DayContent(ScreenshotData.day, onBack = {}, onOpenSession = {}, onRetry = {}) }

    private fun shifts(path: String) = render(path, assertion = {
        text(string(R.string.shifts_title)).assertExists()
        compose.onNodeWithTag("shifts-row-s4").assertIsDisplayed()
    }) { ShiftsBody(ScreenshotData.shifts()) }

    private fun datePicker(path: String) = render(path, assertion = {
        text(string(R.string.recap_picker_month)).assertExists()
        text("Oct 26 to Nov 1").assertExists()
    }) {
        Box(Modifier.fillMaxSize().background(AppTheme.colors.bg), contentAlignment = Alignment.Center) {
            RecapDatePickerContent(
                state = rememberRecapDatePickerState(ScreenshotData.today, ScreenshotData.today),
                choices = listOf(DatePickerChoice.WEEK, DatePickerChoice.MONTH),
                today = ScreenshotData.today,
                onCancel = {},
                onWeek = {},
                onMonth = {},
            )
        }
    }

    private fun month(path: String, state: MonthUiState) = render(path, assertion = {
        text(string(R.string.month_title)).assertExists()
        text(Formats.money(requireNotNull(state.total))).assertExists()
        compose.onNodeWithTag("month-week-${state.weeks.first().monday}").assertExists()
    }) { MonthBody(state) }

    @Composable
    private fun MonthBody(state: MonthUiState) = MonthContent(
        uiState = state, onBack = {}, onStep = {}, onPickMonth = {}, onSelectMeasure = {}, onOpenWeek = {}, onRetry = {},
    )

    @Composable
    private fun ShiftsBody(state: ShiftsUiState) =
        ShiftsContent(uiState = state, onBack = {}, onOpenSession = { _, _ -> }, onVisibleWeeks = {}, onRetry = {})

    /**
     * Every text under [tag] is laid out whole: no line is ellipsized, none runs past the text's own width, and the
     * height fits. Not `hasVisualOverflow`: a cached paragraph can be wider than the measured text, and that slack reads
     * as a width overflow ("Sun, Oct 25" at 385 px in a 749 px slot did; `CashTipSheetTest` records the same quirk).
     */
    private fun assertNoTextOverflow(tag: String) {
        val texts = hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        compose.onAllNodes(texts, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            layouts.forEach { layout ->
                val text = layout.layoutInput.text
                assertFalse("'$text' under $tag overflows its height", layout.didOverflowHeight)
                for (line in 0 until layout.lineCount) {
                    assertFalse("'$text' under $tag is ellipsized", layout.isLineEllipsized(line))
                    assertTrue(
                        "'$text' under $tag runs past its width",
                        layout.getLineRight(line) <= layout.size.width + 1f,
                    )
                }
            }
        }
    }

    private fun playbook(path: String, state: PlaybookUiState, row: String) = render(path, assertion = {
        text(string(R.string.playbook_screen_title)).assertExists()
        text(row).assertExists()
    }) { PlaybookBody(state) }

    /** A tab's loading body: the spinner shows, since [render] runs two seconds of frames first. */
    private fun loading(path: String, content: @Composable () -> Unit) = render(path, assertion = {
        compose.onNodeWithContentDescription(string(R.string.common_loading)).assertExists()
    }, content = content)

    /** The rebuilding body shows, and no money figure does. */
    private fun rebuilding(path: String, content: @Composable () -> Unit) = render(path, assertion = {
        text(string(R.string.records_rebuilding_title)).assertExists()
        compose.onAllNodesWithText("$", substring = true).assertCountEquals(0)
    }, content = content)

    /** The failure pattern names what failed to load. */
    private fun failed(path: String, @StringRes what: Int, content: @Composable () -> Unit) = render(path, assertion = {
        text(string(DesignR.string.app_could_not_load, string(what))).assertExists()
        text(string(DesignR.string.app_retry)).assertExists()
    }, content = content)

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

    private fun render(path: String, window: Boolean = false, assertion: () -> Unit, content: @Composable () -> Unit) =
        compose.renderShot(theme, path, window, assertion, content)

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

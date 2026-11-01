package cloud.trotter.log.gig.ui.main.navigation

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.data.analytics.AnalyticsRepository
import cloud.trotter.log.gig.core.data.analytics.CorrectionRepository
import cloud.trotter.log.gig.core.data.location.OdometerRepository
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.strategy.StrategyRepository
import cloud.trotter.log.gig.core.data.vehicle.DashVehicleRepository
import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.log.gig.core.datastore.settings.StoredPreference
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindow
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.log.gig.domain.analytics.DailyEarnings
import cloud.trotter.log.gig.domain.analytics.DecisionEconomics
import cloud.trotter.log.gig.domain.analytics.EstimateVsReality
import cloud.trotter.log.gig.domain.analytics.GapStats
import cloud.trotter.log.gig.domain.analytics.OfferFilter
import cloud.trotter.log.gig.domain.analytics.OfferListing
import cloud.trotter.log.gig.domain.analytics.OfferOutcome
import cloud.trotter.log.gig.domain.analytics.PayMixParts
import cloud.trotter.log.gig.domain.analytics.PeriodEconomics
import cloud.trotter.log.gig.domain.analytics.PlatformEconomics
import cloud.trotter.log.gig.domain.analytics.TimeEconomics
import cloud.trotter.log.gig.domain.evaluation.SharedEconomy
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.vehicle.FixedCosts
import cloud.trotter.log.gig.domain.model.vehicle.GasCar
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import cloud.trotter.log.gig.domain.model.vehicle.VehicleProfile
import cloud.trotter.log.gig.domain.model.vehicle.WearProfile
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.feature.setup.wizard.WizardViewModel
import cloud.trotter.log.gig.state.vehicle.VehicleSwapLock
import cloud.trotter.log.gig.ui.main.MainScaffold
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsScreen
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsTab
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsViewModel
import cloud.trotter.log.gig.ui.main.settings.EconomySettingsScreen
import cloud.trotter.log.gig.ui.main.settings.EconomySettingsViewModel
import cloud.trotter.log.gig.ui.main.settings.garage.GarageWriteFailure
import cloud.trotter.log.gig.ui.main.settings.garage.VehicleDetailsScreen
import cloud.trotter.log.gig.ui.main.settings.garage.VehicleDetailsViewModel
import cloud.trotter.log.gig.ui.main.setup.wizard.WizardScreen
import cloud.trotter.log.gig.ui.main.setup.wizard.wizardViewModelForTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import cloud.trotter.log.gig.feature.setup.R as SetupR

/**
 * #1447 — the tab navigator over the app's real graph ([mainGraph]) and shell ([MainScaffold]), with
 * placeholder screens unless a test puts a real one in [realScreens]. A placeholder shows
 * `<route>:<n>`, a `rememberSaveable` counter a tap raises, so a test can tell a restored entry from a
 * fresh one. Operations go through [MainNavActions] and [deliver], as production's do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private lateinit var actions: MainNavActions

    /** Real screens by route; any other route renders [Placeholder]. */
    private var realScreens: Map<String, @Composable (NavBackStackEntry) -> Unit> = emptyMap()

    private val plan = Screen.WeeklyPlan.route
    private val dashboard = Screen.Dashboard.route
    private val analytics = Screen.Analytics.route
    private val playbook = Screen.Playbook.route

    // ── Harness ─────────────────────────────────────────────────────────

    private fun show() = compose.setContent { Shell() }

    @Composable
    private fun Shell() {
        GigLogTheme {
            val navController = rememberNavController()
            nav = navController
            val navActions = remember(navController) { MainNavActions(navController) }
            actions = navActions
            val entry by navController.currentBackStackEntryAsState()
            MainScaffold(
                selectedTab = TopLevel.fromRoute(entry?.destination?.route),
                onSelectTab = navActions::selectTab,
            ) { modifier ->
                NavHost(navController = navController, startDestination = dashboard, modifier = modifier) {
                    mainGraph { route, backStackEntry ->
                        val real = realScreens[route]
                        if (real != null) real(backStackEntry) else Placeholder(route)
                    }
                }
            }
        }
    }

    @Composable
    private fun Placeholder(route: String) {
        var counter by rememberSaveable { mutableIntStateOf(0) }
        Text("$route:$counter", Modifier.testTag(COUNTER).clickable { counter++ })
    }

    private fun ui(block: () -> Unit) {
        compose.runOnUiThread(block)
        compose.waitForIdle()
    }

    private fun back() = ui { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun bar(tab: TopLevel) {
        compose.onNode(tabNode and hasText(compose.activity.getString(tab.labelRes))).performClick()
        compose.waitForIdle()
    }

    private fun tap(times: Int) = repeat(times) {
        compose.onNodeWithTag(COUNTER).performClick()
        compose.waitForIdle()
    }

    private fun counter(): Int {
        val text = compose.onNodeWithTag(COUNTER).fetchSemanticsNode().config[SemanticsProperties.Text]
        return text.single().text.substringAfterLast(':').toInt()
    }

    private fun current(): String? = nav.currentDestination?.route

    /** The back stack's routes, the graph's first. Test-only use of the library-restricted getter. */
    @SuppressLint("RestrictedApi")
    private fun stack(): List<String?> = nav.currentBackStack.value.map { it.destination.route }

    private fun stackOf(vararg routes: String): List<String?> = listOf(nav.graph.route) + routes

    /** "Exit": nothing under the current entry and no enabled Back callback left in the activity. */
    private fun assertExits() {
        assertEquals(null, nav.previousBackStackEntry)
        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    private fun assertBarShown(shown: Boolean) {
        val tabs = compose.onAllNodes(tabNode).fetchSemanticsNodes()
        assertEquals(if (shown) TopLevel.entries.size else 0, tabs.size)
    }

    private fun deliverRoute(route: String) = ui { routeTarget(route)?.let { nav.deliver(it) } }

    // ── The graph ───────────────────────────────────────────────────────

    @Test fun `every graph route resolves`() {
        show()
        for (route in Screen.graphRoutes) assertTrue(route, nav.graph.findNode(route) != null)
    }

    // ── (a) Back from a tab ─────────────────────────────────────────────

    @Test fun `Back from Analytics returns Home, then leaves`() {
        show()
        bar(TopLevel.ANALYTICS)
        assertEquals(analytics, current())
        back()
        assertEquals(dashboard, current())
        assertExits()
    }

    @Test fun `Back from Playbook returns Home, then leaves`() {
        show()
        bar(TopLevel.PLAYBOOK)
        assertEquals(playbook, current())
        back()
        assertEquals(dashboard, current())
        assertExits()
    }

    // ── (b) Switching keeps each tab's place ────────────────────────────

    @Test fun `a tab switch keeps the tab it left`() {
        show()
        bar(TopLevel.ANALYTICS)
        tap(3)
        bar(TopLevel.PLAYBOOK)
        bar(TopLevel.ANALYTICS)
        assertEquals(analytics, current())
        assertEquals(3, counter())
    }

    @Test fun `Back from a tab reached through another tab is Home`() {
        show()
        bar(TopLevel.ANALYTICS)
        bar(TopLevel.PLAYBOOK)
        back()
        assertEquals(dashboard, current())
        assertEquals(stackOf(dashboard), stack())
    }

    // ── (c) The bar shows on the tab roots only ─────────────────────────

    @Test fun `the bar shows on the three tabs and nowhere below them`() {
        show()
        assertBarShown(true)
        bar(TopLevel.ANALYTICS); assertBarShown(true)
        bar(TopLevel.PLAYBOOK); assertBarShown(true)
        bar(TopLevel.HOME); assertBarShown(true)
        val below: List<() -> Unit> = listOf(
            actions::openRatings, { actions.openSession("s1") }, actions::openPlaybookPlan,
            actions::openSettings, actions::openWizard,
        )
        for (open in below) {
            ui(open)
            assertBarShown(false)
            back()
            assertBarShown(true)
        }
    }

    // ── (d) N1: a restored tab is never stacked over Home ───────────────

    @Test fun `the plan link from a parked tab leaves Home a lone root`() {
        show()
        bar(TopLevel.ANALYTICS)
        tap(3)
        deliverRoute(plan)
        assertEquals(stackOf(dashboard, playbook, plan), stack())
        back()
        assertEquals(playbook, current())
        bar(TopLevel.HOME)
        assertEquals(dashboard, current())
        assertEquals(stackOf(dashboard), stack())
        bar(TopLevel.ANALYTICS)
        assertEquals(3, counter())
    }

    @Test fun `after the plan link Back goes plan, Playbook, Home, out`() {
        show()
        bar(TopLevel.ANALYTICS)
        tap(3)
        deliverRoute(plan)
        back()
        back()
        assertEquals(dashboard, current())
        assertExits()
    }

    @Test fun `the plan link as the first action opens Playbook with the plan on top`() {
        show()
        deliverRoute(plan)
        assertEquals(stackOf(dashboard, playbook, plan), stack())
        back()
        back()
        assertEquals(dashboard, current())
        assertExits()
    }

    // ── (e) The plan from each root ─────────────────────────────────────

    @Test fun `the plan link from each tab opens Playbook with one plan on top`() {
        show()
        deliverRoute(plan)
        assertEquals(stackOf(dashboard, playbook, plan), stack())
        back(); back()

        bar(TopLevel.ANALYTICS)
        deliverRoute(plan)
        assertEquals(stackOf(dashboard, playbook, plan), stack())
        back(); back()

        bar(TopLevel.PLAYBOOK)
        deliverRoute(plan)
        assertEquals(stackOf(dashboard, playbook, plan), stack())
        deliverRoute(plan)
        assertEquals(stackOf(dashboard, playbook, plan), stack())
    }

    // ── (f) The bubble's links push over the current tab ────────────────

    @Test fun `bubble Settings and Add vehicle open over Playbook and Back returns there`() {
        show()
        bar(TopLevel.PLAYBOOK)
        deliverRoute(Screen.SettingsHome.route)
        deliverRoute(Screen.SettingsHome.route)
        assertEquals(stackOf(dashboard, playbook, Screen.SettingsHome.route), stack())
        back()
        assertEquals(playbook, current())
        deliverRoute(Screen.VehicleAdd.route)
        deliverRoute(Screen.VehicleAdd.route)
        assertEquals(stackOf(dashboard, playbook, Screen.VehicleAdd.route), stack())
        back()
        assertEquals(playbook, current())
    }

    // ── (g) Recap opens Analytics fresh ─────────────────────────────────

    @Test fun `Recap drops a parked Analytics, and the fresh one parks again`() {
        show()
        bar(TopLevel.ANALYTICS)
        tap(3)
        bar(TopLevel.HOME)
        ui { actions.openRecap() }
        assertEquals(analytics, current())
        assertEquals(0, counter())

        tap(1)
        bar(TopLevel.HOME)
        bar(TopLevel.ANALYTICS)
        assertEquals(1, counter())
        tap(1)
        bar(TopLevel.HOME)
        bar(TopLevel.ANALYTICS)
        assertEquals(2, counter())
    }

    // ── (h) Reselect ────────────────────────────────────────────────────

    @Test fun `reselecting the current tab keeps its entry`() {
        show()
        bar(TopLevel.ANALYTICS)
        val id = nav.currentBackStackEntry!!.id
        bar(TopLevel.ANALYTICS)
        assertEquals(id, nav.currentBackStackEntry!!.id)
        assertEquals(stackOf(dashboard, analytics), stack())
    }

    // ── (i) An unknown route ────────────────────────────────────────────

    @Test fun `an unknown route leaves the stack unchanged`() {
        show()
        bar(TopLevel.ANALYTICS)
        val before = stack()
        deliverRoute("not/a/route")
        deliverRoute(Screen.SessionDetail.route)
        assertEquals(before, stack())
    }

    // ── (k) A parked Playbook is replaced by the plan link ──────────────

    @Test fun `the plan link replaces a parked Playbook with a fresh one`() {
        show()
        bar(TopLevel.PLAYBOOK)
        tap(2)
        val parkedId = nav.currentBackStackEntry!!.id
        bar(TopLevel.HOME)
        ui { actions.openPlan() }
        assertEquals(stackOf(dashboard, playbook, plan), stack())
        assertNotEquals(parkedId, nav.getBackStackEntry(playbook).id)
        back()
        assertEquals(playbook, current())
        assertEquals(0, counter())
    }

    // ── (l) Off a tab root a link only pushes ───────────────────────────

    @Test fun `off a tab root the plan link opens over the current screen`() {
        show()
        ui { actions.openRatings() }
        deliverRoute(plan)
        assertEquals(stackOf(dashboard, Screen.Ratings.route, plan), stack())
        assertBarShown(false)
        back()
        assertEquals(Screen.Ratings.route, current())
    }

    @Test fun `off a tab root a tab link and the tab operations change nothing`() {
        show()
        bar(TopLevel.ANALYTICS)
        ui { actions.openSession("s1") }
        val before = stack()
        deliverRoute(analytics)
        deliverRoute(playbook)
        assertEquals(before, stack())
        ui { nav.selectTab(TopLevel.PLAYBOOK) }
        ui { nav.selectTab(TopLevel.HOME) }
        ui { nav.openTabFresh(TopLevel.PLAYBOOK, plan) }
        ui { actions.openRecap() }
        assertEquals(before, stack())
    }

    // ── (m) R1: a link never costs an editor its writes or the wizard its draft ──

    private val vehicle = Vehicle("car", "Original", 1, GasCar(year = "2020", make = "Honda", model = "Civic",
        wear = WearProfile(tireSetCost = 800.0), fixed = FixedCosts(purchasePrice = 20000.0)))

    @Test fun `the plan link over Vehicle details keeps its failed write and Retry`() {
        val repository: GarageRepository = mock()
        whenever(repository.garage).thenReturn(flowOf(Garage(listOf(vehicle), vehicle.id, true)))
        runBlocking {
            whenever(repository.updateProfile(any(), any<(VehicleProfile) -> VehicleProfile>()))
                .thenThrow(IllegalStateException())
                .thenAnswer { it.getArgument<(VehicleProfile) -> VehicleProfile>(1)(GasCar()); true }
        }
        val preferences: AppPreferencesRepository = mock {
            on { userEconomy } doReturn flowOf(UserEconomy())
            on { sharedEconomy } doReturn flowOf(SharedEconomy())
            on { vehicleLookupAllowed } doReturn flowOf(false)
        }
        val dashVehicles: DashVehicleRepository = mock { on { stamps } doReturn flowOf(emptyList()) }
        val readings: OdometerReadingRepository = mock { on { readings } doReturn flowOf(emptyList()) }
        val odometer: OdometerRepository = mock { on { totalMilesFlow } doReturn flowOf(null) }
        val lock: VehicleSwapLock = mock()
        val created = mutableListOf<VehicleDetailsViewModel>()
        realScreens = mapOf(Screen.VehicleEdit.route to { _ ->
            val vm = viewModel {
                VehicleDetailsViewModel(createSavedStateHandle(), repository, preferences,
                    Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC), lock, dashVehicles, readings, odometer)
                    .also { created += it }
            }
            VehicleDetailsScreen(onDone = actions::back, onAddVehicle = {}, viewModel = vm)
        })
        show()
        ui { actions.openVehicle(vehicle.id) }
        compose.waitUntil { created.isNotEmpty() && created[0].loaded.value && created[0].facts.value != null }
        val vm = created.single()
        ui { vm.setOilCost(25.0) }
        compose.waitUntil { vm.failure.value == GarageWriteFailure.RETRY }

        deliverRoute(plan)
        assertEquals(stackOf(dashboard, Screen.VehicleEdit.route, plan), stack())
        assertEquals(GarageWriteFailure.RETRY, vm.failure.value)

        back()
        assertEquals(Screen.VehicleEdit.route, current())
        assertSame(vm, created.single())
        compose.onNodeWithText(compose.activity.getString(R.string.vehicle_details_not_saved))
            .performScrollTo().assertIsDisplayed()
        // The inline Try again sits under the "Not saved." line, below the fold on a small screen.
        compose.onNodeWithText(compose.activity.getString(R.string.vehicle_details_retry)).performScrollTo().performClick()
        compose.waitUntil { vm.failure.value == GarageWriteFailure.NONE }
        runBlocking { verify(repository, times(2)).updateProfile(any(), any<(VehicleProfile) -> VehicleProfile>()) }
    }

    @Test fun `the plan link over Costs keeps its failed write and Retry`() {
        val garage: GarageRepository = mock { on { garage } doReturn flowOf(Garage(emptyList(), null, true)) }
        val preferences: AppPreferencesRepository = mock {
            on { userEconomy } doReturn flowOf(UserEconomy())
            on { isGasPriceAuto } doReturn flowOf(true)
        }
        runBlocking {
            whenever(preferences.updatePhoneLines(any(), anyOrNull()))
                .thenThrow(IllegalStateException())
                .thenReturn(StoredPreference(2, true))
        }
        val strategy: StrategyRepository = mock { on { evaluationConfig } doReturn MutableStateFlow(null) }
        val created = mutableListOf<EconomySettingsViewModel>()
        realScreens = mapOf(Screen.EconomySettings.route to { _ ->
            val vm = viewModel { EconomySettingsViewModel(preferences, strategy, garage).also { created += it } }
            EconomySettingsScreen(onBack = actions::back, onOpenGarage = {}, onOpenVehicle = {}, viewModel = vm)
        })
        show()
        ui { actions.open(Screen.EconomySettings.route) }
        compose.waitUntil { created.isNotEmpty() }
        val vm = created.single()
        ui { vm.setPhoneLines(3) }
        compose.waitUntil { vm.failure.value == GarageWriteFailure.RETRY }

        deliverRoute(plan)
        assertEquals(stackOf(dashboard, Screen.EconomySettings.route, plan), stack())
        assertEquals(GarageWriteFailure.RETRY, vm.failure.value)

        back()
        assertEquals(Screen.EconomySettings.route, current())
        assertSame(vm, created.single())
        compose.onNodeWithText(compose.activity.getString(R.string.vehicle_details_not_saved))
            .performScrollTo().assertIsDisplayed()
        // The inline Try again sits under the "Not saved." line, below the fold on a small screen.
        compose.onNodeWithText(compose.activity.getString(R.string.vehicle_details_retry)).performScrollTo().performClick()
        compose.waitUntil { vm.failure.value == GarageWriteFailure.NONE }
    }

    /**
     * The wizard keeps unfinished cost-field text in composition (`costsSession`), so a push drops it,
     * as on master; a value already saved to `WizardState` survives with the ViewModel.
     */
    @Test fun `the plan link over the wizard returns to its step with its saved values`() {
        val created = mutableListOf<WizardViewModel>()
        realScreens = mapOf(Screen.Wizard.route to { _ ->
            val vm = viewModel { wizardViewModelForTest().also { created += it } }
            WizardScreen(viewModel = vm, onComplete = actions::back, onExit = actions::back)
        })
        show()
        ui { actions.openWizard() }
        compose.onNodeWithText(compose.activity.getString(SetupR.string.wizard_bottom_bar_next)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Step 2 of", substring = true).assertIsDisplayed()
        val vm = created.single()
        ui { vm.updateGasPrice(4.25f) }

        deliverRoute(plan)
        assertEquals(stackOf(dashboard, Screen.Wizard.route, plan), stack())

        back()
        assertEquals(Screen.Wizard.route, current())
        assertSame(vm, created.single())
        compose.onNodeWithText("Step 2 of", substring = true).assertIsDisplayed()
        assertEquals(4.25f, vm.state.value.gasPrice, 0.001f)
    }

    // ── (j) Process death keeps the hub's place ─────────────────────────

    private val hubRepository: AnalyticsRepository = mock()
    private val hubPreferences: AppPreferencesRepository = mock()

    /** Holds the offers read back while false, as a slow Room query would after a restore. */
    private val offersReady = MutableStateFlow(true)

    /** `AnalyticsViewModelTest`'s stubs, every window read served, forty declined offers listed. */
    private fun stubHub() {
        val listings = (1L..40L).map { seq ->
            OfferListing(eventSequenceId = seq, platform = Platform.DoorDash, storeName = "Store $seq",
                decidedAt = seq, payAmount = 8.0, distanceMiles = 3.0, estDollarsPerHour = 18.0, score = 6.0,
                outcome = OfferOutcome.DECLINED)
        }
        // The Offers list sits in the More numbers tier (`OffersTab`), so the hub shows more.
        whenever(hubPreferences.analyticsShowMore).thenReturn(MutableStateFlow(true))
        whenever(hubPreferences.analyticsWindow).thenReturn(MutableStateFlow(AnalyticsWindowSelection.DEFAULT))
        with(hubRepository) {
            whenever(periodEconomics(any<AnalyticsWindow>(), anyOrNull(), any<ZoneId>()))
                .thenAnswer { flowOf(PeriodEconomics.EMPTY) }
            whenever(decisionEconomics(any<AnalyticsWindow>(), any<ZoneId>())).thenAnswer { flowOf(DecisionEconomics.EMPTY) }
            whenever(timeEconomics(any<AnalyticsWindow>(), any<ZoneId>())).thenAnswer { flowOf(TimeEconomics.EMPTY) }
            whenever(gapStats(any<AnalyticsWindow>(), any<ZoneId>())).thenAnswer { flowOf(GapStats.EMPTY) }
            whenever(dailyEarnings(any<AnalyticsWindow>(), any<ZoneId>())).thenAnswer { flowOf(emptyList<DailyEarnings>()) }
            whenever(noSessionDeliveries(any<AnalyticsWindow>(), any<ZoneId>())).thenReturn(flowOf(emptyList()))
            whenever(orphanOfferGroups(any<AnalyticsWindow>(), any<ZoneId>())).thenReturn(flowOf(emptyList()))
            whenever(payMixParts(any<AnalyticsWindow>(), any<ZoneId>())).thenAnswer { flowOf(PayMixParts.EMPTY) }
            whenever(platformEconomics(any<AnalyticsWindow>(), any<ZoneId>())).thenAnswer { flowOf(emptyList<PlatformEconomics>()) }
            whenever(estimateVsReality(any<AnalyticsWindow>(), any<ZoneId>())).thenAnswer { flowOf(EstimateVsReality.EMPTY) }
            whenever(offers(any<AnalyticsWindow>(), any(), any(), any(), any<ZoneId>()))
                .thenAnswer { inv -> offersReady.filter { it }.map { listings.take(inv.getArgument<Int>(2)) } }
            whenever(offerCount(any<AnalyticsWindow>(), any(), any<ZoneId>())).thenAnswer { flowOf(listings.size) }
            whenever(recentSessions(any())).thenReturn(flowOf(emptyList()))
        }
    }

    private class Owner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    @Test fun `process death keeps the hub's tab, filter, page and scroll`() {
        stubHub()
        val correctionRepository: CorrectionRepository = mock()
        val created = mutableListOf<AnalyticsViewModel>()
        realScreens = mapOf(Screen.Analytics.route to { _ ->
            val vm = viewModel {
                AnalyticsViewModel(hubRepository, correctionRepository, hubPreferences, createSavedStateHandle())
                    .also { created += it }
            }
            AnalyticsScreen(onOpenSettings = {}, onExportCsv = {}, onOpenSession = {}, viewModel = vm)
        })
        // The ViewModelStore a process death loses: the restored composition reads the replacement.
        var owner: ViewModelStoreOwner = Owner()
        val tester = StateRestorationTester(compose)
        tester.setContent {
            val storeOwner = remember { owner }
            CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) { Shell() }
        }

        bar(TopLevel.ANALYTICS)
        val first = created.last()
        ui {
            first.setTab(AnalyticsTab.Offers)
            first.setOfferFilter(OfferFilter.DECLINED)
            first.showAllOffers()
        }
        compose.onAllNodesWithText("Store 40", substring = true)[0].assertExists()
        compose.onNode(verticalScroll).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 600f) }
        compose.waitForIdle()
        assertEquals(600f, scrollPosition(), 0.5f)

        bar(TopLevel.PLAYBOOK)
        owner = Owner()
        offersReady.value = false
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(playbook, current())

        bar(TopLevel.ANALYTICS)
        val restored = created.last()
        assertNotSame(first, restored)
        // The offers read is still pending: nothing scrollable is composed to clamp the offset.
        compose.onNode(verticalScroll).assertDoesNotExist()
        offersReady.value = true
        compose.waitForIdle()
        assertEquals(AnalyticsTab.Offers, restored.uiState.value.selectedTab)
        assertEquals(OfferFilter.DECLINED, restored.offersFeed.value.filter)
        assertEquals(AnalyticsRepository.MAX_OFFER_PAGE.coerceAtMost(40), restored.offersFeed.value.offers.size)
        assertEquals(600f, scrollPosition(), 0.5f)

        ui { restored.setTab(AnalyticsTab.Time) }
        bar(TopLevel.HOME)
        bar(TopLevel.ANALYTICS)
        assertEquals(AnalyticsTab.Time, created.last().uiState.value.selectedTab)
    }

    private val verticalScroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun scrollPosition(): Float =
        compose.onNode(verticalScroll).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private companion object {
        const val COUNTER = "counter"
        val tabNode = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    }
}

package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleOdometerReading
import cloud.trotter.dashbuddy.domain.model.vehicle.LastOdometerReading
import cloud.trotter.dashbuddy.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.core.data.analytics.LedgerRepository
import cloud.trotter.dashbuddy.core.database.analytics.LedgerDao
import cloud.trotter.dashbuddy.core.database.analytics.ExpenseRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.FuelRecordEntity
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.SessionNet
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import cloud.trotter.dashbuddy.domain.analytics.ExpenseRecord
import cloud.trotter.dashbuddy.domain.analytics.FuelRecord
import cloud.trotter.dashbuddy.domain.analytics.SessionDetail
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.feature.bubble.hud.LogData
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.domain.evaluation.EvaluationConfig
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import cloud.trotter.dashbuddy.domain.model.offer.OfferQuoteBasis
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.feature.bubble.hud.OfferCardMapper
import cloud.trotter.dashbuddy.feature.bubble.hud.OfferCardModel
import org.mockito.kotlin.argumentCaptor
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import cloud.trotter.dashbuddy.core.data.chat.ChatRepository
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.fuel.FuelPriceRepository
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.model.bubble.BubbleSessionMode
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.feature.bubble.hud.sessionEarningsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId

/**
 * #693 — the bubble's post-dash idle surface reads the analytics read-model, not an in-memory
 * capture. The regression: the OLD `SessionSummary` scan was liveness-gated and silently dropped a
 * dash that ended while the bubble was collapsed, so the HUD showed the last dash it happened to
 * catch (the stale "last dash with money"). These prove the ViewModel now surfaces whatever
 * `recentSessions(1)` returns as the true last session — a $0/zero-delivery dash included — with no
 * `hasEarnings`-style filter, plus the gas quick-edit write path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BubbleViewModelTest {

    private val appStateRepository: AppStateRepository = mock()
    private val strategyRepository: StrategyRepository = mock()
    private val configFlow = MutableStateFlow<EvaluationConfig?>(EvaluationConfig())
    private val chatRepository: ChatRepository = mock()
    private val odometerRepository: OdometerRepository = mock()
    private val stateManager: StateManagerV2 = mock()
    private val appEventRepo: AppEventRepo = mock()
    private val appPreferencesRepository: AppPreferencesRepository = mock()
    private val fuelPriceRepository: FuelPriceRepository = mock()
    private val ledgerRepository: LedgerRepository = mock()
    private val goalFlow = MutableStateFlow<Double?>(null)
    private val todayFlow = MutableStateFlow(PeriodEconomics.EMPTY)
    private val analyticsRepository: AnalyticsRepository = mock()
    private val devSettingsRepository: DevSettingsRepository = mock()

    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC)

    /** The state the VM reads; tests mutate it to drive multi-app scenarios (#867). */
    private val state = MutableStateFlow(AppState())

    /** The dev-settings session-presentation switch (#867). */
    private val modeFlow = MutableStateFlow(BubbleSessionMode.FOLLOW_ACTIVE)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Init-time flow accessors — the VM builds several stateIn() flows in its property initializers.
        whenever(appStateRepository.goalMetMark).thenReturn(flowOf(null))
        whenever(stateManager.state).thenReturn(state)
        whenever(strategyRepository.evaluationConfig).thenReturn(configFlow)
        whenever(devSettingsRepository.bubbleSessionMode).thenReturn(modeFlow)
        whenever(appEventRepo.getMostRecentSessionId()).thenReturn(flowOf(null))
        whenever(appEventRepo.getEventsForSession(any())).thenReturn(flowOf(emptyList()))
        whenever(odometerRepository.sessionMilesFlow).thenReturn(flowOf(0.0))
        whenever(odometerRepository.totalMilesFlow).thenReturn(flowOf(null))
        whenever(appPreferencesRepository.odometerPrompts).thenReturn(flowOf(true))
        whenever(appPreferencesRepository.dailyKeptGoalDollars).thenReturn(goalFlow)
        whenever(analyticsRepository.periodEconomics(AnalyticsPeriod.TODAY)).thenReturn(todayFlow)
        whenever(ledgerRepository.sessionNet(any())).thenReturn(flowOf(null))
        whenever(ledgerRepository.expensesUnassignedOn(any(), any())).thenReturn(flowOf(emptyList()))
        whenever(ledgerRepository.fuelUnassignedOn(any(), any())).thenReturn(flowOf(emptyList()))
        whenever(appPreferencesRepository.glanceMode).thenReturn(flowOf(false))
        whenever(appPreferencesRepository.gasPrice).thenReturn(flowOf(3.50f))
        whenever(appPreferencesRepository.isGasPriceAuto).thenReturn(flowOf(true))
        whenever(appPreferencesRepository.fuelType).thenReturn(flowOf(FuelType.REGULAR))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun garage(): cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository {
        val repo: cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository = mock()
        whenever(repo.garage).thenReturn(flowOf(cloud.trotter.dashbuddy.core.data.vehicle.Garage(emptyList(), null, readable = true)))
        return repo
    }
    private fun dashVehicles(): cloud.trotter.dashbuddy.core.data.vehicle.DashVehicleRepository {
        val repo: cloud.trotter.dashbuddy.core.data.vehicle.DashVehicleRepository = mock()
        whenever(repo.stamps).thenReturn(flowOf(emptyList()))
        return repo
    }
    private fun readings(): cloud.trotter.dashbuddy.core.data.vehicle.OdometerReadingRepository {
        val repo: cloud.trotter.dashbuddy.core.data.vehicle.OdometerReadingRepository = mock()
        whenever(repo.readings).thenReturn(flowOf(emptyList()))
        return repo
    }
    private fun viewModel(
        garageRepository: GarageRepository = garage(),
        readingRepository: OdometerReadingRepository = readings(),
        ledger: LedgerRepository = ledgerRepository,
        testClock: Clock = clock,
        dashRepository: cloud.trotter.dashbuddy.core.data.vehicle.DashVehicleRepository = dashVehicles(),
    ) = BubbleViewModel(
        chatRepository = chatRepository,
        strategyRepository = strategyRepository,
        appStateRepository = appStateRepository,
        garageRepository = garageRepository, odometerReadingRepository = readingRepository,
        odometerRepository = odometerRepository,
        stateManager = stateManager,
        appEventRepo = appEventRepo,
        appPreferencesRepository = appPreferencesRepository,
        fuelPriceRepository = fuelPriceRepository,
        analyticsRepository = analyticsRepository,
        ledgerRepository = ledger,
        devSettingsRepository = devSettingsRepository,
        correctionRepository = mock(),
        vehicleSwapLock = mock(),
        dashVehicleRepository = dashRepository,
        clock = testClock,
    )

    @Test fun `dash vehicle follows focused stamp including retired vehicles and clears when unknown or unstamped`() = runTest(dispatcher) {
        stubNoDashes()
        val retired = Vehicle("old", "Malibu", 0, retiredAt = 1)
        val active = Vehicle("new", "Civic", 1)
        val garageRepo: GarageRepository = mock()
        whenever(garageRepo.garage).thenReturn(flowOf(Garage(listOf(active, retired), active.id, true)))
        val stampRepo: cloud.trotter.dashbuddy.core.data.vehicle.DashVehicleRepository = mock()
        val updates = MutableStateFlow(listOf(cloud.trotter.dashbuddy.domain.model.vehicle.DashVehicleStamp(
            "dash", retired.id, 10.0, 100, 1, cloud.trotter.dashbuddy.domain.model.vehicle.DashVehicleKind.START)))
        whenever(stampRepo.stamps).thenReturn(updates)
        state.value = earningsState("dash")
        val vm = viewModel(garageRepository = garageRepo, dashRepository = stampRepo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.vehicleChip.collect {} }
        runCurrent()
        assertNotNull(vm.vehicleChip.value)
        assertEquals(retired, vm.vehicleChip.value?.shown)
        assertEquals(listOf(active), vm.vehicleChip.value?.vehicles)
        assertEquals(active.id, vm.vehicleChip.value?.activeId)
        updates.value += updates.value.single().copy(vehicleId = active.id, sequenceId = 2,
            kind = cloud.trotter.dashbuddy.domain.model.vehicle.DashVehicleKind.SWAP)
        runCurrent()
        assertEquals(active, vm.vehicleChip.value?.shown)
        updates.value += updates.value.last().copy(vehicleId = null, sequenceId = 3)
        runCurrent()
        assertNull(vm.vehicleChip.value?.shown)
        state.value = earningsState("unstamped")
        runCurrent()
        assertNull(vm.vehicleChip.value?.shown)
        state.value = AppState()
        runCurrent()
        assertEquals(active, vm.vehicleChip.value?.shown)
    }

    @Test
    fun `garage and odometer derivations share one garage collection`() = runTest(dispatcher) {
        stubNoDashes()
        val repo: GarageRepository = mock()
        val updates = MutableStateFlow(Garage(emptyList(), null, readable = true))
        var collections = 0
        whenever(repo.garage).thenReturn(updates.onStart { collections++ })
        val vm = viewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.garage.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.odometerLoaded.collect {} }
        runCurrent()
        assertEquals(1, collections)
        val civic = Vehicle("civic", "Civic", 0)
        updates.value = Garage(listOf(civic), civic.id, readable = true)
        runCurrent()
        assertEquals(listOf(civic), vm.garage.value!!.vehicles)
        assertEquals(civic, vm.garage.value?.active)
        assertTrue(vm.odometerLoaded.value)
        assertTrue(vm.garage.value!!.readable)
        assertEquals(1, collections)
    }

    @Test
    fun `the garage is not loaded until its first emission`() = runTest(dispatcher) {
        stubNoDashes()
        val repo: GarageRepository = mock()
        val updates = MutableSharedFlow<Garage>()
        whenever(repo.garage).thenReturn(updates)
        val vm = viewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.garage.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.odometerLoaded.collect {} }
        runCurrent()
        assertFalse(vm.odometerLoaded.value)
        assertNull(vm.garage.value)

        val civic = Vehicle("civic", "Civic", 0)
        updates.emit(Garage(listOf(civic), civic.id, readable = true))
        runCurrent()
        assertTrue(vm.odometerLoaded.value)
        assertTrue(vm.garage.value!!.readable)
        assertEquals(listOf(civic), vm.garage.value!!.vehicles)
        assertEquals(civic, vm.garage.value?.active)
    }

    @Test
    fun `readings are not loaded until the first emission`() = runTest(dispatcher) {
        stubNoDashes()
        val civic = Vehicle("civic", "Civic", 0)
        val garage: GarageRepository = mock()
        whenever(garage.garage).thenReturn(flowOf(Garage(listOf(civic), civic.id, readable = true)))
        val updates = MutableSharedFlow<List<VehicleOdometerReading>>()
        val readings: OdometerReadingRepository = mock()
        whenever(readings.readings).thenReturn(updates)
        val vm = viewModel(garage, readings)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.odometerReadings.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.odometerLoaded.collect {} }
        runCurrent()
        assertNotNull(vm.garage.value)
        assertNull(vm.odometerReadings.value)
        assertFalse(vm.odometerLoaded.value)

        val last = LastOdometerReading(52_388, 123.5, 100)
        val rows = listOf(VehicleOdometerReading(civic.id, last, 1))
        updates.emit(rows)
        runCurrent()
        assertEquals(rows, vm.odometerReadings.value)
        assertTrue(vm.odometerLoaded.value)

        updates.emit(emptyList())
        runCurrent()
        assertEquals(emptyList<VehicleOdometerReading>(), vm.odometerReadings.value)
        assertTrue(vm.odometerLoaded.value)
    }

    @Test
    fun `an unreadable garage is loaded and exposes the failure`() = runTest(dispatcher) {
        stubNoDashes()
        val repo: GarageRepository = mock()
        val updates = MutableStateFlow(Garage(emptyList(), null, readable = false))
        whenever(repo.garage).thenReturn(updates)
        val vm = viewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.garage.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.odometerLoaded.collect {} }
        runCurrent()
        assertTrue(vm.odometerLoaded.value)
        assertFalse(vm.garage.value!!.readable)

        updates.value = Garage(emptyList(), null, readable = true)
        runCurrent()
        assertTrue(vm.odometerLoaded.value)
        assertTrue(vm.garage.value!!.readable)
    }

    @Test
    fun `each vehicle in hand selects its own reading without a derived last reading flow`() = runTest(dispatcher) {
        stubNoDashes()
        val civic = Vehicle("civic", "Civic", 0)
        val truck = Vehicle("truck", "Truck", 0)
        val cars = listOf(civic, truck)
        val garageUpdates = MutableStateFlow(Garage(cars, civic.id, readable = true))
        val garage: GarageRepository = mock()
        whenever(garage.garage).thenReturn(garageUpdates)
        val civicReading = LastOdometerReading(52_388, 123.5, 100)
        val truckReading = LastOdometerReading(90_000, 223.5, 200)
        val readings: OdometerReadingRepository = mock()
        var collections = 0
        whenever(readings.readings).thenReturn(flowOf(listOf(
            VehicleOdometerReading(civic.id, civicReading, 1),
            VehicleOdometerReading(truck.id, truckReading, 2),
        )).onStart { collections++ })
        val vm = viewModel(garage, readings)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.odometerReadings.collect {} }
        runCurrent() // the readings are loaded before the first garage frame is judged
        val frames = mutableListOf<Pair<String, LastOdometerReading?>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.garage.collect { snapshot ->
                // The host derives last from the vehicle in this frame and its readings snapshot.
                snapshot?.active?.let { vehicle ->
                    frames += vehicle.id to VehicleOdometerReading.latestFor(vm.odometerReadings.value.orEmpty(), vehicle.id)
                }
            }
        }
        runCurrent()
        assertEquals(civic.id to civicReading, frames.last())
        frames.clear()
        garageUpdates.value = Garage(cars, truck.id, readable = true)
        runCurrent()
        assertEquals(listOf(truck.id to truckReading), frames)
        assertEquals(1, collections)
    }

    @Test fun `logData keeps all garage vehicles including retired and follows garage updates`() = runTest(dispatcher) {
        stubNoDashes()
        val active = Vehicle("new", "Civic", 0)
        val retired = Vehicle("old", "Malibu", 1, retiredAt = 2)
        val updates = MutableStateFlow(Garage(listOf(active, retired), active.id, true))
        val repo: GarageRepository = mock()
        whenever(repo.garage).thenReturn(updates)
        val vm = viewModel(garageRepository = repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.logData.collect {} }
        runCurrent()
        assertEquals(listOf(active, retired), vm.logData.value.vehicles)
        updates.value = updates.value.copy(vehicles = listOf(active.copy(name = "Renamed"), retired))
        runCurrent()
        assertEquals("Renamed", vm.logData.value.vehicles.first().name)
        assertEquals(retired, vm.logData.value.vehicles.last())
    }

    @Test
    fun `logData merges events deliveries and ledger rows for the displayed dash`() = runTest {
        stubNoDashes()
        val dash = session("session-dd", 10.0, 1)
        val delivery = DeliveryRecord(10, "session-dd", Platform.DoorDash, "job", "drop", "Panera Bread", 9_000,
            realizedPay = 10.0, payBasis = "DROP_SHARE", netProfit = 8.0, realizedMiles = 4.0,
            realizedMinutes = 20.0, tip = 5.0, basePay = 5.0, cashTip = 3.0)
        val event = AppEvent(AppEventType.DASH_PAUSED, 5_000, "session-dd")
        val expense = ExpenseRecord(20, "session-dd", 6_000, ExpenseCategory.FOOD, 11.4, null, null)
        val fuel = FuelRecord(30, "session-dd", 7_000, 31.9, 10.0, null, null)
        val expenses = MutableStateFlow(listOf(expense))
        val events = MutableStateFlow(listOf(event))
        whenever(appEventRepo.getMostRecentSessionId()).thenReturn(flowOf("session-dd"))
        whenever(appEventRepo.getEventsForSession("session-dd")).thenReturn(events)
        whenever(analyticsRepository.sessionDetail("session-dd")).thenReturn(flowOf(SessionDetail(dash, listOf(delivery))))
        whenever(ledgerRepository.expensesForSession("session-dd")).thenReturn(expenses)
        whenever(ledgerRepository.fuelForSession("session-dd")).thenReturn(flowOf(listOf(fuel)))
        whenever(analyticsRepository.sessionDetail("session-uber")).thenReturn(flowOf(null))
        whenever(ledgerRepository.expensesForSession("session-uber")).thenReturn(flowOf(emptyList()))
        val otherFuel = fuel.copy(eventSequenceId = 31, sessionId = "session-uber")
        whenever(ledgerRepository.fuelForSession("session-uber")).thenReturn(flowOf(listOf(otherFuel)))
        state.value = appState(Platform.DoorDash, *dualOnline)
        val vm = viewModel()
        runCurrent()
        // Building the ViewModel alone does not start the log's projection reads.
        verify(analyticsRepository, never()).sessionDetail(any())
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.logData.collect {} }
        runCurrent()
        assertEquals(LogData(listOf(event), listOf(delivery), listOf(expense), listOf(fuel)), vm.logData.value)
        expenses.value = listOf(expense.copy(voidedAt = 8_000))
        events.value = listOf(event, event.copy(occurredAt = 8_000))
        runCurrent()
        assertEquals(8_000L, vm.logData.value.expenses.single().voidedAt)
        assertEquals(2, vm.logData.value.events.size)
        vm.selectPlatform(Platform.Uber)
        runCurrent()
        assertEquals(LogData(fuel = listOf(otherFuel)), vm.logData.value)
        modeFlow.value = BubbleSessionMode.MERGED
        runCurrent()
        assertEquals(listOf(delivery), vm.logData.value.deliveries)
        assertEquals(listOf(fuel, otherFuel), vm.logData.value.fuel)
        assertEquals(2, vm.logData.value.events.size)
        modeFlow.value = BubbleSessionMode.FOLLOW_ACTIVE
        state.value = AppState() // At rest the durable most-recent dash remains displayed.
        runCurrent()
        assertEquals(listOf(fuel), vm.logData.value.fuel)
        assertEquals(listOf(delivery), vm.logData.value.deliveries)
        collector.cancel()
    }

    @Test
    fun `logData uses the live dash start day across midnight and at rest`() = runTest {
        checkUnassignedLog(hasDash = true, live = true)
    }

    @Test
    fun `logData at rest shows yesterday beside yesterday's dash and excludes today's fee`() = runTest {
        checkUnassignedLog(hasDash = true, live = false)
    }

    @Test
    fun `logData with no dash uses today and rolls over at local midnight`() = runTest {
        checkUnassignedLog(hasDash = false, live = false)
    }

    private suspend fun TestScope.checkUnassignedLog(hasDash: Boolean, live: Boolean) {
        stubNoDashes()
        val zone = ZoneId.systemDefault()
        val date = java.time.LocalDate.of(2026, 10, 18)
        val midnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val initialNow = if (live || !hasDash) midnight - 5 * 60_000L else midnight + 30 * 60_000L
        val tickingClock = object : Clock() {
            override fun getZone() = zone
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(initialNow + testScheduler.currentTime)
        }
        val start = midnight - 60 * 60_000L
        val expense = ExpenseRecordEntity(20, null, midnight - 10 * 60_000L, "FOOD", 11.4, null, null)
        val fillup = FuelRecordEntity(30, null, expense.occurredAt, 31.9, 10.0, null, null)
        val expenses = MutableStateFlow(listOf(expense))
        val fuel = MutableStateFlow(listOf(fillup))
        val dao: LedgerDao = mock()
        whenever(dao.expensesUnassignedBetween(any(), any())).thenAnswer { call ->
            val from = call.getArgument<Long>(0)
            val until = call.getArgument<Long>(1)
            expenses.map { rows -> rows.filter { it.occurredAt >= from && it.occurredAt < until } }
        }
        whenever(dao.fuelUnassignedBetween(any(), any())).thenAnswer { call ->
            val from = call.getArgument<Long>(0)
            val until = call.getArgument<Long>(1)
            fuel.map { rows -> rows.filter { it.occurredAt >= from && it.occurredAt < until } }
        }
        whenever(dao.expensesForSession(any())).thenReturn(flowOf(emptyList()))
        whenever(dao.fuelForSession(any())).thenReturn(flowOf(emptyList()))
        whenever(analyticsRepository.sessionDetail(any())).thenReturn(flowOf(null))
        if (hasDash) {
            whenever(appEventRepo.getMostRecentSessionId()).thenReturn(flowOf("session-dd"))
            whenever(appEventRepo.getEventsForSession("session-dd")).thenReturn(
                // A late/recovered event timestamp must not replace the payload's actual start day.
                flowOf(listOf(AppEvent(
                    AppEventType.DASH_START, midnight + 1, "session-dd",
                    SessionStartPayload("session-dd", Platform.DoorDash.wire, start, "recovery", "test"),
                ))),
            )
        }
        if (live) state.value = earningsState("session-dd", startedAt = start)
        val vm = viewModel(ledger = LedgerRepository(appEventRepo, dao, analyticsRepository), testClock = tickingClock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.logData.collect {} }
        runCurrent()
        assertEquals(listOf(20L), vm.logData.value.expenses.map { it.eventSequenceId })
        assertEquals(listOf(30L), vm.logData.value.fuel.map { it.eventSequenceId })

        // A 00:20 fee belongs to the next day, even beside a dash still live from yesterday.
        if (live || !hasDash) advanceTimeBy(35 * 60_000L)
        expenses.value += expense.copy(eventSequenceId = 21, occurredAt = midnight + 20 * 60_000L)
        fuel.value += fillup.copy(eventSequenceId = 31, occurredAt = midnight + 20 * 60_000L)
        runCurrent()
        val expenseId = if (hasDash) 20L else 21L
        val fuelId = if (hasDash) 30L else 31L
        assertEquals(listOf(expenseId), vm.logData.value.expenses.map { it.eventSequenceId })
        assertEquals(listOf(fuelId), vm.logData.value.fuel.map { it.eventSequenceId })

        expenses.value = expenses.value.map { it.copy(voidedAt = tickingClock.millis()) }
        fuel.value = fuel.value.map { it.copy(voidedAt = tickingClock.millis()) }
        runCurrent()
        assertEquals(tickingClock.millis(), vm.logData.value.expenses.single().voidedAt)
        assertEquals(tickingClock.millis(), vm.logData.value.fuel.single().voidedAt)
        if (live) {
            state.value = AppState()
            runCurrent()
            assertEquals(20L, vm.logData.value.expenses.single().eventSequenceId)
            assertEquals(30L, vm.logData.value.fuel.single().eventSequenceId)
            // Starting a new dash switches the unassigned window to its own day.
            state.value = earningsState("next-dash", startedAt = midnight + 25 * 60_000L)
            runCurrent()
            assertEquals(21L, vm.logData.value.expenses.single().eventSequenceId)
            assertEquals(31L, vm.logData.value.fuel.single().eventSequenceId)
        }
    }

    @Test
    fun `log data and cards share one events read per session`() = runTest {
        stubNoDashes()
        modeFlow.value = BubbleSessionMode.MERGED
        state.value = appState(Platform.DoorDash, *dualOnline)
        val subscriptions = mutableMapOf<String, Int>()
        val events = dualOnline.associate { (_, sessionId) -> sessionId to MutableStateFlow<List<AppEvent>>(emptyList()) }
        dualOnline.forEach { (_, sessionId) ->
            whenever(appEventRepo.getEventsForSession(sessionId)).thenReturn(events.getValue(sessionId).onStart {
                subscriptions[sessionId] = subscriptions.getOrDefault(sessionId, 0) + 1
            })
            whenever(analyticsRepository.sessionDetail(sessionId)).thenReturn(flowOf(null))
            whenever(ledgerRepository.expensesForSession(sessionId)).thenReturn(flowOf(emptyList()))
            whenever(ledgerRepository.fuelForSession(sessionId)).thenReturn(flowOf(emptyList()))
        }
        val vm = viewModel()
        val cardsCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.cards.collect {} }
        runCurrent()
        val logCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.logData.collect {} }
        runCurrent()
        assertEquals(mapOf("session-dd" to 1, "session-uber" to 1), subscriptions)
        val event = AppEvent(AppEventType.DASH_PAUSED, 5_000, "session-dd")
        events.getValue("session-dd").value = listOf(event)
        runCurrent()
        assertEquals(listOf(event), vm.logData.value.events)
        assertEquals(mapOf("session-dd" to 1, "session-uber" to 1), subscriptions)
        verify(appEventRepo).getEventsForSession("session-dd")
        verify(appEventRepo).getEventsForSession("session-uber")
        cardsCollector.cancel()
        logCollector.cancel()
    }

    @Test
    fun `sessionNet follows the displayed session`() = runTest {
        val last = session("last", 10.0, 1)
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(listOf(last)))
        val lastNet = SessionNet("last", 8.0, 0.0, 0.0)
        val ddNet = lastNet.copy(sessionId = "session-dd", deliveredNet = 18.0)
        val uberFlow = MutableStateFlow(lastNet.copy(sessionId = "session-uber", deliveredNet = 28.0))
        whenever(ledgerRepository.sessionNet("last")).thenReturn(flowOf(lastNet))
        whenever(ledgerRepository.sessionNet("session-dd")).thenReturn(flowOf(ddNet))
        whenever(ledgerRepository.sessionNet("session-uber")).thenReturn(uberFlow)
        // The event log points somewhere else: the resting net must use recentSessions instead.
        whenever(appEventRepo.getMostRecentSessionId()).thenReturn(flowOf("different-chat-session"))
        val vm = viewModel()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.sessionNet.collect {} }
        runCurrent()
        assertEquals(lastNet, vm.sessionNet.value)
        state.value = appState(Platform.DoorDash, *dualOnline)
        runCurrent()
        assertEquals(ddNet, vm.sessionNet.value)
        vm.selectPlatform(Platform.Uber)
        runCurrent()
        assertEquals(uberFlow.value, vm.sessionNet.value)
        uberFlow.value = uberFlow.value.copy(cashTips = 5.0)
        runCurrent()
        assertEquals(uberFlow.value, vm.sessionNet.value)
        state.value = AppState()
        runCurrent()
        assertEquals(lastNet, vm.sessionNet.value)
        job.cancel()
    }

    @Test
    fun `dailyGoal follows the preference`() = runTest {
        stubNoDashes()
        val vm = viewModel()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.dailyGoal.collect {} }
        runCurrent()
        assertNull(vm.dailyGoal.value)
        goalFlow.value = 150.0
        runCurrent()
        assertEquals(150.0, vm.dailyGoal.value!!, 0.0)
        goalFlow.value = null
        runCurrent()
        assertNull(vm.dailyGoal.value)
        job.cancel()
    }

    @Test
    fun `goal day follows the injected fixed clock`() = runTest {
        stubNoDashes()
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.goalDay.collect {} }
        runCurrent()
        assertEquals(clock.instant().atZone(ZoneId.systemDefault()).toLocalDate(), vm.goalDay.value!!.date)
    }

    @Test
    fun `todayProfit reads today's period net`() = runTest {
        stubNoDashes()
        val vm = viewModel()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.todayProfit.collect {} }
        runCurrent()
        todayFlow.value = PeriodEconomics.EMPTY.copy(netProfit = 71.2, grossEarnings = 99.0)
        runCurrent()
        assertEquals(71.2, vm.todayProfit.value!!, 0.0)
        todayFlow.value = todayFlow.value.copy(netProfit = 82.5)
        runCurrent()
        assertEquals(82.5, vm.todayProfit.value!!, 0.0)
        verify(analyticsRepository).periodEconomics(AnalyticsPeriod.TODAY)
        job.cancel()
    }

    private fun session(
        id: String,
        earnings: Double?,
        deliveries: Int,
        endedAt: Long? = 1_000L,
    ) = SessionRecord(
        sessionId = id,
        platform = Platform.DoorDash,
        startedAt = 0L,
        endedAt = endedAt,
        reportedEarnings = earnings,
        reportedDurationMillis = endedAt,
        miles = 4.2,
        deliveries = deliveries,
        jobsCompleted = deliveries,
        offersReceived = 3,
        offersAccepted = 1,
        offersDeclined = 2,
        offersTimeout = 0,
    )

    @Test
    fun `lastSession surfaces the most recent dash even when it earned zero`() = runTest {
        // The repro shape: the newest dash is a $0, zero-delivery unassign session.
        val zero = session(id = "session-doordash-1783294721320-15", earnings = 0.0, deliveries = 0)
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(listOf(zero)))

        val vm = viewModel()
        val job = launch { vm.lastSession.collect {} }
        advanceUntilIdle()

        assertEquals(zero, vm.lastSession.value)
        job.cancel()
    }

    @Test
    fun `lastSession is null when there are no dashes yet`() = runTest {
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(emptyList()))

        val vm = viewModel()
        val job = launch { vm.lastSession.collect {} }
        advanceUntilIdle()

        assertNull(vm.lastSession.value)
        job.cancel()
    }

    @Test
    fun `setGasPrice writes a manual override to the economy settings store`() = runTest {
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(emptyList()))
        val vm = viewModel()

        vm.setGasPrice(3.49f)
        advanceUntilIdle()

        verify(appPreferencesRepository).updateGasPriceManual(eq(3.49f))
    }

    // --- #722 mode-adaptive gas quick-edit: refresh (AUTO) vs Resume auto (MANUAL) ---

    @Test
    fun `refreshGasPrice routes through the one fetch path and stays auto`() = runTest {
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(emptyList()))
        whenever(fuelPriceRepository.fetchAndSaveCurrentGasPrice(any())).thenReturn(Result.success(3.60f))
        val vm = viewModel()

        vm.refreshGasPrice()
        advanceUntilIdle()

        verify(fuelPriceRepository).fetchAndSaveCurrentGasPrice(eq(FuelType.REGULAR))
        // The AUTO-mode refresh never calls the manual OR resume-auto write paths — staying auto
        // is a side effect of NOT touching the auto flag, not an explicit write.
        verify(appPreferencesRepository, never()).updateGasPriceManual(any())
        verify(appPreferencesRepository, never()).updateGasPriceAuto(any())
    }

    @Test
    fun `refreshGasPrice resets the loading flag once the fetch completes`() = runTest {
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(emptyList()))
        whenever(fuelPriceRepository.fetchAndSaveCurrentGasPrice(any())).thenReturn(Result.success(3.60f))
        val vm = viewModel()

        assertFalse(vm.isGasPriceRefreshing.value)
        vm.refreshGasPrice()
        advanceUntilIdle()
        assertFalse(vm.isGasPriceRefreshing.value)
    }

    @Test
    fun `refreshGasPrice failure signals a transient error, no exception propagates`() = runTest {
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(emptyList()))
        whenever(fuelPriceRepository.fetchAndSaveCurrentGasPrice(any()))
            .thenReturn(Result.failure(IllegalStateException("Location not available")))
        val vm = viewModel()

        val job = launch { vm.gasPriceRefreshFailed.collect {} }
        vm.refreshGasPrice()
        advanceUntilIdle()

        assertFalse(vm.isGasPriceRefreshing.value)
        job.cancel()
    }

    @Test
    fun `resumeAutoGasPrice routes through fetchAndResumeAutoGasPrice, the atomic auto-flip inverse`() = runTest {
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(emptyList()))
        whenever(fuelPriceRepository.fetchAndResumeAutoGasPrice(any())).thenReturn(Result.success(3.55f))
        val vm = viewModel()

        vm.resumeAutoGasPrice()
        advanceUntilIdle()

        verify(fuelPriceRepository).fetchAndResumeAutoGasPrice(eq(FuelType.REGULAR))
        // Resume-auto never routes through the manual (stepper) write path.
        verify(appPreferencesRepository, never()).updateGasPriceManual(any())
    }

    // ===========================================================================================
    // #867 — multi-app session presentation (follow / pin / merge + the labeling pledge)
    // ===========================================================================================

    private fun appState(active: Platform?, vararg live: Pair<Platform, String>) = AppState(
        regions = Regions(
            flow = FlowRegion(activePlatform = active),
            platforms = live.mapIndexed { index, (platform, sessionId) ->
                platform to PlatformRegion(
                    platform = platform,
                    mode = Mode.Online,
                    session = Session(sessionId = sessionId, startedAt = 1_000L * (index + 1)),
                )
            }.toMap(),
        ),
    )

    private val dualOnline = arrayOf(
        Platform.DoorDash to "session-dd",
        Platform.Uber to "session-uber",
    )

    /** Keeps every WhileSubscribed flow hot for the duration of a test. */
    private fun TestScope.subscribe(vm: BubbleViewModel) = launch {
        launch { vm.focusedPlatform.collect {} }
        launch { vm.switchablePlatforms.collect {} }
        launch { vm.displayedPlatformLabel.collect {} }
        launch { vm.messages.collect {} }
        launch { vm.cards.collect {} }
    }

    private fun stubNoDashes() {
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(emptyList()))
        whenever(chatRepository.getMessages(any())).thenReturn(flowOf(emptyList()))
    }

    private fun earningsState(id: String, earnings: Double? = null, startedAt: Long = 100L) = AppState(
        regions = Regions(
            flow = FlowRegion(activePlatform = Platform.DoorDash),
            platforms = mapOf(
                Platform.DoorDash to PlatformRegion(
                    platform = Platform.DoorDash,
                    mode = Mode.Online,
                    session = Session(id, startedAt = startedAt, runningEarnings = earnings),
                ),
            ),
        ),
    )

    @Test
    fun `never-parsed session earnings render the placeholder (#1041)`() = runTest {
        stubNoDashes()
        state.value = earningsState("session-1")
        val vm = viewModel()
        assertNull(vm.sessionEarnings.value)
        val job = launch { vm.sessionEarnings.collect {} }
        advanceUntilIdle()

        assertNull(vm.sessionEarnings.value)
        assertEquals(EMPTY_VALUE, sessionEarningsText(vm.sessionEarnings.value))

        state.value = earningsState("session-1", 12.50)
        advanceUntilIdle()
        assertEquals(Formats.money(12.50), sessionEarningsText(vm.sessionEarnings.value))
        job.cancel()
    }

    @Test
    fun `a new session resets carried earnings to null (#1041)`() = runTest {
        stubNoDashes()
        state.value = earningsState("session-1", 12.50)
        val vm = viewModel()
        val job = launch { vm.sessionEarnings.collect {} }
        advanceUntilIdle()
        assertEquals(12.50, vm.sessionEarnings.value ?: Double.NaN, 0.0001)

        state.value = earningsState("session-2")
        advanceUntilIdle()
        assertNull(vm.sessionEarnings.value)
        assertEquals(EMPTY_VALUE, sessionEarningsText(vm.sessionEarnings.value))

        state.value = earningsState("session-2", 7.25)
        advanceUntilIdle()
        assertEquals(7.25, vm.sessionEarnings.value ?: Double.NaN, 0.0001)
        job.cancel()
    }

    @Test
    fun `a new session immediately shows its own existing figure (#1041)`() = runTest {
        stubNoDashes()
        state.value = earningsState("session-1", 12.50)
        val vm = viewModel()
        val emissions = mutableListOf<Double?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.sessionEarnings.collect { emissions.add(it) }
        }
        runCurrent()
        assertEquals(12.50, vm.sessionEarnings.value ?: Double.NaN, 0.0001)
        emissions.clear()

        state.value = earningsState("session-2", 7.25)
        runCurrent()
        assertEquals(listOf(7.25), emissions)
        assertEquals(Formats.money(7.25), sessionEarningsText(vm.sessionEarnings.value))
        job.cancel()
    }

    @Test
    fun `session earnings restart keeps the cached figure without a placeholder emission (#1041)`() = runTest {
        stubNoDashes()
        state.value = earningsState("session-1", 12.50)
        val vm = viewModel()
        val firstJob = launch { vm.sessionEarnings.collect {} }
        runCurrent()
        assertEquals(12.50, vm.sessionEarnings.value ?: Double.NaN, 0.0001)

        firstJob.cancel()
        runCurrent()
        advanceTimeBy(6_000L)
        runCurrent()
        assertEquals(12.50, vm.sessionEarnings.value ?: Double.NaN, 0.0001)

        // Eager collection catches even a transient null that StateFlow could otherwise conflate.
        val emissions = mutableListOf<Double?>()
        val restartedJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.sessionEarnings.collect { emissions.add(it) }
        }
        runCurrent()
        assertEquals(listOf(12.50), emissions)

        state.value = earningsState("session-1", 16.70)
        runCurrent()
        assertEquals(listOf(12.50, 16.70), emissions)
        restartedJob.cancel()
    }

    @Test
    fun `session earnings carry the last non-null figure including a read zero (#1041)`() = runTest {
        stubNoDashes()
        state.value = earningsState("session-1", 12.50)
        val vm = viewModel()
        val job = launch { vm.sessionEarnings.collect {} }
        advanceUntilIdle()

        state.value = earningsState("session-1")
        advanceUntilIdle()
        assertEquals(12.50, vm.sessionEarnings.value ?: Double.NaN, 0.0001)

        state.value = earningsState("session-1", 0.0)
        advanceUntilIdle()
        assertEquals(Formats.money(0.0), sessionEarningsText(vm.sessionEarnings.value))
        state.value = earningsState("session-1")
        advanceUntilIdle()
        assertEquals(0.0, vm.sessionEarnings.value ?: Double.NaN, 0.0001)
        job.cancel()
    }

    @Test
    fun `follow-active tracks the platform that produced the last frame`() = runTest {
        stubNoDashes()
        state.value = appState(Platform.DoorDash, *dualOnline)
        val vm = viewModel()
        val job = subscribe(vm)
        advanceUntilIdle()
        assertEquals(Platform.DoorDash, vm.focusedPlatform.value)

        // The multi-app flip the issue documented: a DoorDash frame, then an Uber frame.
        state.value = appState(Platform.Uber, *dualOnline)
        advanceUntilIdle()

        assertEquals(Platform.Uber, vm.focusedPlatform.value)
        verify(chatRepository).getMessages(eq("session-uber"))
        job.cancel()
    }

    @Test
    fun `a switcher pick holds the surface against an active-platform flip`() = runTest {
        stubNoDashes()
        state.value = appState(Platform.DoorDash, *dualOnline)
        val vm = viewModel()
        val job = subscribe(vm)
        advanceUntilIdle()

        vm.selectPlatform(Platform.Uber)
        advanceUntilIdle()
        assertEquals(Platform.Uber, vm.focusedPlatform.value)

        // A DoorDash frame arrives — the pin must NOT be dragged away by it.
        state.value = appState(Platform.DoorDash, *dualOnline)
        advanceUntilIdle()

        assertEquals(Platform.Uber, vm.focusedPlatform.value)
        job.cancel()
    }

    @Test
    fun `the live card is dropped while pinned away from the active platform`() = runTest {
        stubNoDashes()
        state.value = appState(Platform.DoorDash, *dualOnline)
        val vm = viewModel()
        val job = subscribe(vm)
        advanceUntilIdle()

        vm.selectPlatform(Platform.Uber)
        advanceUntilIdle()

        // The live card is built from the GLOBAL FlowRegion, which describes DoorDash's screen —
        // rendering it under an Uber pin is exactly the mislabeling #867 is about (and it is what
        // carries the offer Accept/Decline row).
        assertNull(vm.cards.value.stack.active)
        job.cancel()
    }

    @Test
    fun `re-picking the displayed platform releases the pin`() = runTest {
        stubNoDashes()
        state.value = appState(Platform.DoorDash, *dualOnline)
        val vm = viewModel()
        val job = subscribe(vm)
        advanceUntilIdle()

        vm.selectPlatform(Platform.Uber)
        advanceUntilIdle()
        vm.selectPlatform(Platform.Uber)
        advanceUntilIdle()

        assertEquals(Platform.DoorDash, vm.focusedPlatform.value)
        job.cancel()
    }

    @Test
    fun `follow-active releases the pin when the picked dash ends`() = runTest {
        stubNoDashes()
        state.value = appState(Platform.DoorDash, *dualOnline)
        val vm = viewModel()
        val job = subscribe(vm)
        advanceUntilIdle()

        vm.selectPlatform(Platform.Uber)
        advanceUntilIdle()
        assertEquals(Platform.Uber, vm.focusedPlatform.value)

        // Uber goes offline (its region's session clears) → the temporary pin lapses.
        state.value = appState(Platform.DoorDash, Platform.DoorDash to "session-dd")
        advanceUntilIdle()
        assertEquals(Platform.DoorDash, vm.focusedPlatform.value)

        // …and it stays released when Uber dashes again — the difference from PINNED.
        state.value = appState(Platform.DoorDash, *dualOnline)
        advanceUntilIdle()
        assertEquals(Platform.DoorDash, vm.focusedPlatform.value)
        job.cancel()
    }

    @Test
    fun `pinned survives the picked dash ending and re-takes effect on the next one`() = runTest {
        stubNoDashes()
        modeFlow.value = BubbleSessionMode.PINNED
        state.value = appState(Platform.DoorDash, *dualOnline)
        val vm = viewModel()
        val job = subscribe(vm)
        advanceUntilIdle()

        vm.selectPlatform(Platform.Uber)
        advanceUntilIdle()
        assertEquals(Platform.Uber, vm.focusedPlatform.value)

        // Uber stops dashing: nothing live to show for the pin, so the display follows…
        state.value = appState(Platform.DoorDash, Platform.DoorDash to "session-dd")
        advanceUntilIdle()
        assertEquals(Platform.DoorDash, vm.focusedPlatform.value)

        // …but the pin was never dropped, so Uber's next dash re-takes the surface.
        state.value = appState(Platform.DoorDash, *dualOnline)
        advanceUntilIdle()
        assertEquals(Platform.Uber, vm.focusedPlatform.value)
        job.cancel()
    }

    @Test
    fun `merged folds every live dash into one stack`() = runTest {
        stubNoDashes()
        modeFlow.value = BubbleSessionMode.MERGED
        state.value = appState(Platform.DoorDash, *dualOnline)
        val vm = viewModel()
        val job = subscribe(vm)
        advanceUntilIdle()

        verify(appEventRepo).getEventsForSession(eq("session-dd"))
        verify(appEventRepo).getEventsForSession(eq("session-uber"))
        // The switcher would be inert in this mode, so it is hidden…
        assertEquals(emptyList<Platform>(), vm.switchablePlatforms.value)
        // …and the chat stays on ONE session (the followed one), badged in the header.
        verify(chatRepository).getMessages(eq("session-dd"))
        job.cancel()
    }

    @Test
    fun `labels and the switcher appear only once two dashes are live`() = runTest {
        stubNoDashes()
        state.value = appState(Platform.DoorDash, Platform.DoorDash to "session-dd")
        val vm = viewModel()
        val job = subscribe(vm)
        advanceUntilIdle()

        assertNull(vm.displayedPlatformLabel.value)
        assertEquals(emptyList<Platform>(), vm.switchablePlatforms.value)

        state.value = appState(Platform.DoorDash, *dualOnline)
        advanceUntilIdle()

        assertEquals(Platform.DoorDash.shortName, vm.displayedPlatformLabel.value)
        assertEquals(listOf(Platform.DoorDash, Platform.Uber), vm.switchablePlatforms.value)
        job.cancel()
    }

    @Test
    fun `an unknown stored mode is decoded by the repository, so the VM just follows`() = runTest {
        // Fail-closed decode lives in DevSettingsRepository (BubbleSessionModeTest covers it); the
        // VM's contract is that it renders whatever mode it is handed, starting at the default.
        stubNoDashes()
        state.value = appState(Platform.DoorDash, *dualOnline)
        val vm = viewModel()
        val job = subscribe(vm)
        advanceUntilIdle()

        assertEquals(Platform.DoorDash, vm.focusedPlatform.value)
        job.cancel()
    }
    @Test
    fun `combined offer is never written`() = runTest {
        stubNoDashes()
        val platform = Platform.DoorDash
        val parsed = ParsedOffer("real-addon", payAmount = 7.0, distanceMiles = 2.1,
            quoteBasis = OfferQuoteBasis.INCREMENTAL,
            orders = listOf(ParsedOrder(0, OrderType.PICKUP, "New", 1, false, emptySet())))
        val pending = PendingOffer(parsed.offerHash, ParsedFields.OfferFields(parsedOffer = parsed),
            presentedAt = 1_000, returnFlow = Flow.TaskPickupNavigation, sourceRuleId = "doordash.screen.offer")
        val active = Job("job", listOf("Old"), "before", startedAt = 0,
            acceptedOffers = listOf(AcceptedOfferEconomics("before", payAmount = 10.0, netPay = 8.0,
                estMinutes = 20.0, distanceMiles = 5.0, storeHints = listOf("Old"), acceptedAt = 0)))
        val region = PlatformRegion(platform, Mode.Online, Session("dash", 0),
            activeJob = active, pendingOffers = listOf(pending))
        state.value = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.OfferPresented, activePlatform = platform), platforms = mapOf(platform to region)))
        val vm = viewModel()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.focusedPlatform.collect {} }
        runCurrent()
        var evaluatedHash: String? = null
        val displayed = OfferCardMapper.map(pending, active, emptyList(), vm.evaluationConfig.value) { offer, config ->
            evaluatedHash = offer.offerHash
            OfferEvaluator().evaluate(offer, config)
        }
        assertTrue(displayed is OfferCardModel.Combined)
        assertEquals("combined:real-addon", evaluatedHash)
        vm.acceptOffer()
        val dispatched = argumentCaptor<Observation>()
        verify(stateManager).dispatch(dispatched.capture())
        val action = dispatched.firstValue as Observation.UiInput
        assertEquals(OfferIntent.ACCEPT, action.action)
        assertEquals("real-addon", action.offerHash)
        assertEquals(platform, action.targetPlatform)
        assertEquals(pending, state.value.regions.platforms[platform]?.presentedOffer())
        collector.cancel()
    }
    @Test fun `cash tip picker lists every row of the requested dash across midnight`() = runTest(dispatcher) {
        stubNoDashes()
        val zone = java.time.ZoneId.systemDefault()
        val day = Instant.parse("2026-10-18T00:00:00Z").atZone(zone).toLocalDate()
        fun row(id: Long, date: java.time.LocalDate) = DeliveryRecord(
            id, "dash", Platform.DoorDash, "job$id", "task$id", "Store", date.atStartOfDay(zone).toInstant().toEpochMilli(),
            8.0, "OFFER_PAY", 6.0, null, null, null, null)
        val today = row(2, day)
        val yesterday = row(1, day.minusDays(1))
        whenever(analyticsRepository.deliveriesForSession("dash")).thenReturn(flowOf(listOf(yesterday, today)))
        val vm = viewModel()
        var seen: List<DeliveryRecord>? = null
        val reader = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.cashTipDeliveries("dash").collect { seen = it }
        }
        runCurrent()
        assertEquals(listOf(yesterday, today), seen)
        verify(analyticsRepository).deliveriesForSession("dash")
        reader.cancel()
    }

}

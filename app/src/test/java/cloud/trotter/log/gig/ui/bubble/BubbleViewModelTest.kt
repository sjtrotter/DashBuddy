package cloud.trotter.log.gig.ui.bubble

import cloud.trotter.log.gig.domain.offer.story.*
import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import cloud.trotter.log.gig.domain.model.vehicle.VehicleOdometerReading
import cloud.trotter.log.gig.domain.model.vehicle.LastOdometerReading
import cloud.trotter.log.gig.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.log.gig.core.data.state.AppStateRepository
import cloud.trotter.log.gig.core.data.analytics.LedgerRepository
import cloud.trotter.log.gig.core.database.analytics.LedgerDao
import cloud.trotter.log.gig.core.database.analytics.ExpenseRecordEntity
import cloud.trotter.log.gig.core.database.analytics.FuelRecordEntity
import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.ExpenseCategory
import cloud.trotter.log.gig.domain.analytics.ExpenseRecord
import cloud.trotter.log.gig.domain.analytics.FuelRecord
import cloud.trotter.log.gig.domain.analytics.SessionDetail
import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartPayload
import cloud.trotter.log.gig.feature.bubble.hud.LogData
import cloud.trotter.log.gig.core.data.analytics.AnalyticsRepository
import cloud.trotter.log.gig.core.data.analytics.HudShiftRepository
import cloud.trotter.log.gig.core.data.strategy.StrategyRepository
import cloud.trotter.log.gig.domain.evaluation.EvaluationConfig
import cloud.trotter.log.gig.domain.evaluation.OfferEvaluator
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.model.order.OrderType
import cloud.trotter.log.gig.domain.model.order.ParsedOrder
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.AcceptedOfferEconomics
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.OfferIntent
import cloud.trotter.log.gig.feature.bubble.hud.OfferCardMapper
import cloud.trotter.log.gig.feature.bubble.hud.OfferCardModel
import org.mockito.kotlin.argumentCaptor
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import cloud.trotter.log.gig.core.data.chat.ChatRepository
import cloud.trotter.log.gig.core.data.event.AppEventRepo
import cloud.trotter.log.gig.core.data.fuel.FuelPriceRepository
import cloud.trotter.log.gig.core.data.location.OdometerRepository
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.state.StateManagerV2
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.model.bubble.BubbleSessionMode
import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
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
    private val analyticsRepository: AnalyticsRepository = mock()
    private val hudShiftRepository: HudShiftRepository = mock()
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
        whenever(devSettingsRepository.addOnSpeech).thenReturn(MutableStateFlow(AddOnSpeech.JOB_TOTALS))
        whenever(devSettingsRepository.bubbleSessionMode).thenReturn(modeFlow)
        whenever(appEventRepo.getMostRecentSessionId()).thenReturn(flowOf(null))
        whenever(appEventRepo.getEventsForSession(any())).thenReturn(flowOf(emptyList()))
        whenever(odometerRepository.sessionMilesFlow).thenReturn(flowOf(0.0))
        whenever(odometerRepository.totalMilesFlow).thenReturn(flowOf(null))
        whenever(appPreferencesRepository.odometerPrompts).thenReturn(flowOf(true))
        whenever(appPreferencesRepository.dailyGoalDollars).thenReturn(goalFlow)
        whenever(hudShiftRepository.latestShift(any())).thenReturn(flowOf(null))
        whenever(hudShiftRepository.endedShiftsBetween(any(), any())).thenReturn(flowOf(emptyList()))
        whenever(analyticsRepository.sessionDetail(any())).thenReturn(flowOf(null))
        whenever(ledgerRepository.expensesUnassignedOn(any(), any())).thenReturn(flowOf(emptyList()))
        whenever(ledgerRepository.fuelUnassignedOn(any(), any())).thenReturn(flowOf(emptyList()))
        whenever(appPreferencesRepository.glanceMode).thenReturn(flowOf(false))
        whenever(appPreferencesRepository.gasPrice).thenReturn(flowOf(3.50f))
        whenever(appPreferencesRepository.isGasPriceAuto).thenReturn(flowOf(true))
        whenever(appPreferencesRepository.fuelType).thenReturn(flowOf(FuelType.REGULAR))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun garage(): cloud.trotter.log.gig.core.data.vehicle.GarageRepository {
        val repo: cloud.trotter.log.gig.core.data.vehicle.GarageRepository = mock()
        whenever(repo.garage).thenReturn(flowOf(cloud.trotter.log.gig.core.data.vehicle.Garage(emptyList(), null, readable = true)))
        return repo
    }
    private fun dashVehicles(): cloud.trotter.log.gig.core.data.vehicle.DashVehicleRepository {
        val repo: cloud.trotter.log.gig.core.data.vehicle.DashVehicleRepository = mock()
        whenever(repo.stamps).thenReturn(flowOf(emptyList()))
        return repo
    }
    private fun readings(): cloud.trotter.log.gig.core.data.vehicle.OdometerReadingRepository {
        val repo: cloud.trotter.log.gig.core.data.vehicle.OdometerReadingRepository = mock()
        whenever(repo.readings).thenReturn(flowOf(emptyList()))
        return repo
    }
    private fun viewModel(
        garageRepository: GarageRepository = garage(),
        readingRepository: OdometerReadingRepository = readings(),
        ledger: LedgerRepository = ledgerRepository,
        testClock: Clock = clock,
        dashRepository: cloud.trotter.log.gig.core.data.vehicle.DashVehicleRepository = dashVehicles(),
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
        hudShiftRepository = hudShiftRepository,
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
        val stampRepo: cloud.trotter.log.gig.core.data.vehicle.DashVehicleRepository = mock()
        val updates = MutableStateFlow(listOf(cloud.trotter.log.gig.domain.model.vehicle.DashVehicleStamp(
            "dash", retired.id, 10.0, 100, 1, cloud.trotter.log.gig.domain.model.vehicle.DashVehicleKind.START)))
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
            kind = cloud.trotter.log.gig.domain.model.vehicle.DashVehicleKind.SWAP)
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
        val vm = viewModel(ledger = LedgerRepository(appEventRepo, dao), testClock = tickingClock)
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

    // ── #1443: the displayed shift ──────────────────────────────────────────────────────────

    private fun record(
        id: String,
        platform: Platform = Platform.DoorDash,
        startedAt: Long = 1_000L,
        endedAt: Long? = 5_000L,
        reported: Double? = null,
        deliveries: Int = 2,
    ) = SessionRecord(
        sessionId = id, platform = platform, startedAt = startedAt, endedAt = endedAt,
        reportedEarnings = reported, reportedDurationMillis = null, miles = 4.2,
        deliveries = deliveries, jobsCompleted = deliveries, offersReceived = 3, offersAccepted = 2,
        offersDeclined = 1, offersTimeout = 0,
    )

    private fun detailOf(row: SessionRecord, vararg pays: Double?, cash: Double = 0.0) = SessionDetail(
        row,
        pays.mapIndexed { i, pay ->
            DeliveryRecord(i.toLong(), row.sessionId, row.platform, "job-$i", "task-$i", null, 2_000L + i, pay,
                "DROP_SHARE", null, null, null, null, null, cashTip = cash.takeIf { i == 0 && it > 0.0 })
        },
    )

    private fun shiftState(active: Platform?, vararg regions: PlatformRegion) = AppState(
        regions = Regions(flow = FlowRegion(activePlatform = active), platforms = regions.associateBy { it.platform }),
    )

    private fun liveRegion(platform: Platform, id: String, running: Double? = null, startedAt: Long = 6_000L,
        mode: Mode = Mode.Online) = PlatformRegion(platform = platform, mode = mode,
        session = Session(id, startedAt = startedAt, runningEarnings = running))

    private fun restingRegion(platform: Platform, lastEnd: Long?) =
        PlatformRegion(platform = platform, mode = Mode.Offline, lastSessionEndedAt = lastEnd)

    private fun TestScope.collectShift(vm: BubbleViewModel) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.displayedShift.collect {} }

    @Test
    fun `the live shift shows the larger of the running total and recorded pay, with its cash apart`() = runTest {
        stubNoDashes()
        val open = record("live", endedAt = null)
        val detail = MutableStateFlow<SessionDetail?>(detailOf(open, 12.0, 8.0, cash = 3.0))
        whenever(analyticsRepository.sessionDetail("live")).thenReturn(detail)
        state.value = shiftState(Platform.DoorDash, liveRegion(Platform.DoorDash, "live", running = 12.5))
        val vm = viewModel()
        collectShift(vm)
        runCurrent()
        var shift = vm.displayedShift.value!!
        assertTrue(shift.live)
        assertEquals("live", shift.sessionId)
        assertEquals(20.0, shift.earnings!!, 0.0) // recorded pay ahead of a lagging running total
        assertEquals(3.0, shift.cashTips!!, 0.0)
        state.value = shiftState(Platform.DoorDash, liveRegion(Platform.DoorDash, "live", running = 31.0))
        runCurrent()
        shift = vm.displayedShift.value!!
        assertEquals(31.0, shift.earnings!!, 0.0) // the app's own total has overtaken it
        assertEquals(3.0, shift.cashTips!!, 0.0) // never folded into the figure
    }

    @Test
    fun `nothing priced shows the em dash and a priced zero or offer-pay row shows a figure`() = runTest {
        stubNoDashes()
        val open = record("live", endedAt = null)
        val detail = MutableStateFlow<SessionDetail?>(detailOf(open))
        whenever(analyticsRepository.sessionDetail("live")).thenReturn(detail)
        state.value = shiftState(Platform.DoorDash, liveRegion(Platform.DoorDash, "live"))
        val vm = viewModel()
        collectShift(vm)
        runCurrent()
        assertNull(vm.displayedShift.value!!.earnings)
        detail.value = detailOf(open, null, null)
        runCurrent()
        assertNull(vm.displayedShift.value!!.earnings)
        detail.value = detailOf(open, 0.0)
        runCurrent()
        assertEquals(0.0, vm.displayedShift.value!!.earnings!!, 0.0)
        detail.value = SessionDetail(open, detailOf(open, 7.0).deliveries.map { it.copy(payBasis = "OFFER_PAY") })
        runCurrent()
        assertEquals(7.0, vm.displayedShift.value!!.earnings!!, 0.0)
    }

    @Test
    fun `switching platforms while the new detail withholds its first emission shows no foreign figure or cash`() = runTest {
        stubNoDashes()
        val dd = record("session-dd", endedAt = null)
        whenever(analyticsRepository.sessionDetail("session-dd")).thenReturn(flowOf(detailOf(dd, 15.0, cash = 4.0)))
        val uberDetail = MutableSharedFlow<SessionDetail?>()
        whenever(analyticsRepository.sessionDetail("session-uber")).thenReturn(uberDetail)
        state.value = shiftState(Platform.DoorDash,
            liveRegion(Platform.DoorDash, "session-dd", running = 10.0, startedAt = 1_000L),
            liveRegion(Platform.Uber, "session-uber", startedAt = 2_000L))
        val vm = viewModel()
        collectShift(vm)
        runCurrent()
        assertEquals(15.0, vm.displayedShift.value!!.earnings!!, 0.0)
        assertEquals(4.0, vm.displayedShift.value!!.cashTips!!, 0.0)
        vm.selectPlatform(Platform.Uber)
        runCurrent()
        var shift = vm.displayedShift.value!!
        assertEquals(Platform.Uber, shift.platform)
        assertEquals("session-uber", shift.sessionId)
        assertNull(shift.earnings)
        assertNull(shift.cashTips)
        uberDetail.emit(detailOf(record("session-uber", Platform.Uber, endedAt = null), 6.0))
        runCurrent()
        shift = vm.displayedShift.value!!
        assertEquals(6.0, shift.earnings!!, 0.0)
        assertEquals(0.0, shift.cashTips!!, 0.0)
    }

    @Test
    fun `an ended shift on the focused platform shows its own figures, chip, stats and cash target`() = runTest {
        // A (DoorDash) is live and started after B (Uber) ended; the HUD is focused on Uber.
        val b = record("b", Platform.Uber, startedAt = 1_000L, endedAt = 5_000L, reported = 22.0, deliveries = 4)
        val a = record("a", Platform.DoorDash, startedAt = 6_000L, endedAt = null, deliveries = 1)
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(listOf(a)))
        whenever(chatRepository.getMessages(any())).thenReturn(flowOf(emptyList()))
        whenever(hudShiftRepository.latestShift(Platform.Uber)).thenReturn(flowOf(b))
        whenever(hudShiftRepository.latestShift(Platform.DoorDash)).thenReturn(flowOf(a))
        whenever(analyticsRepository.sessionDetail("b")).thenReturn(flowOf(detailOf(b, 18.0, cash = 2.5)))
        whenever(analyticsRepository.sessionDetail("a")).thenReturn(flowOf(detailOf(a, 40.0, cash = 9.0)))
        state.value = shiftState(Platform.Uber,
            liveRegion(Platform.DoorDash, "a", running = 40.0, startedAt = 6_000L),
            restingRegion(Platform.Uber, lastEnd = 5_000L))
        val vm = viewModel()
        collectShift(vm)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.focusedRegion.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.lastSession.collect {} }
        runCurrent()
        val shift = vm.displayedShift.value!!
        assertEquals(Platform.Uber, shift.platform)
        assertEquals("b", shift.sessionId) // the cash-entry target
        assertFalse(shift.live)
        assertEquals(22.0, shift.earnings!!, 0.0)
        assertEquals(2.5, shift.cashTips!!, 0.0)
        assertEquals(b, shift.record)
        val hud = cloud.trotter.log.gig.feature.bubble.hud.HudStateMapper.map(
            vm.focusedRegion.value, Flow.Idle, Platform.Uber, emptyList(), shift, 0.0, vm.lastSession.value,
            null, null, hasActiveCard = false, hasOfferCard = false, logOpen = false,
            gas = cloud.trotter.log.gig.feature.bubble.hud.GasRowModel(null, true), odometerLoaded = true)
        assertEquals(Platform.Uber, hud.platform)
        val resting = hud.resting!!
        assertEquals(4, resting.deliveries)
        assertEquals(b.startedAt, resting.startedAt)
        assertEquals(22.0, hud.hero.earnings!!, 0.0)
    }

    @Test
    fun `resting during an end grace shows the live session`() = runTest {
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(listOf(record("older", reported = 50.0))))
        whenever(chatRepository.getMessages(any())).thenReturn(flowOf(emptyList()))
        whenever(hudShiftRepository.latestShift(Platform.DoorDash)).thenReturn(flowOf(record("older", reported = 50.0)))
        whenever(analyticsRepository.sessionDetail("graced")).thenReturn(flowOf(null))
        state.value = shiftState(Platform.DoorDash,
            liveRegion(Platform.DoorDash, "graced", running = 17.0, mode = Mode.Offline))
        val vm = viewModel()
        collectShift(vm)
        runCurrent()
        val shift = vm.displayedShift.value!!
        assertTrue(shift.live)
        assertEquals("graced", shift.sessionId)
        assertEquals(17.0, shift.earnings!!, 0.0)
    }

    @Test
    fun `a session that left state with its row still open shows the em dash, never the older shift`() = runTest {
        val older = record("older", endedAt = 5_000L, reported = 50.0)
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(listOf(older)))
        whenever(chatRepository.getMessages(any())).thenReturn(flowOf(emptyList()))
        whenever(hudShiftRepository.latestShift(Platform.DoorDash))
            .thenReturn(flowOf(record("new", startedAt = 6_000L, endedAt = null)))
        whenever(analyticsRepository.sessionDetail("older")).thenReturn(flowOf(detailOf(older, 50.0, cash = 5.0)))
        state.value = shiftState(Platform.DoorDash, restingRegion(Platform.DoorDash, lastEnd = 9_000L))
        val vm = viewModel()
        collectShift(vm)
        runCurrent()
        val shift = vm.displayedShift.value!!
        assertNull(shift.sessionId)
        assertNull(shift.earnings)
        assertNull(shift.cashTips)
        assertNull(shift.record)
    }

    @Test
    fun `a shift whose start and stop folds are both withheld shows the em dash across a resubscription`() = runTest {
        val older = record("older", endedAt = 5_000L, reported = 50.0)
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(listOf(older)))
        whenever(chatRepository.getMessages(any())).thenReturn(flowOf(emptyList()))
        val latest = MutableStateFlow<SessionRecord?>(older)
        whenever(hudShiftRepository.latestShift(Platform.DoorDash)).thenReturn(latest)
        whenever(analyticsRepository.sessionDetail("older")).thenReturn(flowOf(detailOf(older, 50.0, cash = 5.0)))
        val just = record("just", startedAt = 6_000L, endedAt = 9_000L, reported = 31.0)
        whenever(analyticsRepository.sessionDetail("just")).thenReturn(flowOf(detailOf(just, 30.0, cash = 1.0)))
        state.value = shiftState(Platform.DoorDash, restingRegion(Platform.DoorDash, lastEnd = 9_000L))
        val vm = viewModel()
        val first = collectShift(vm)
        runCurrent()
        assertNull(vm.displayedShift.value!!.sessionId)
        assertNull(vm.displayedShift.value!!.earnings)
        first.cancel()
        advanceTimeBy(6_000L)
        runCurrent()
        val emissions = mutableListOf<cloud.trotter.log.gig.feature.bubble.hud.DisplayedShift?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.displayedShift.collect { emissions += it } }
        runCurrent()
        assertTrue(emissions.all { it?.sessionId == null && it?.earnings == null && it?.cashTips == null })
        latest.value = just
        runCurrent()
        val shift = vm.displayedShift.value!!
        assertEquals("just", shift.sessionId)
        assertEquals(31.0, shift.earnings!!, 0.0)
        assertEquals(1.0, shift.cashTips!!, 0.0)
    }

    @Test
    fun `a resubscription never replays a shown shift after a newer shift ended with both folds withheld`() = runTest {
        // A is shown at rest; the bubble stops collecting past the sharing timeout; B starts and ends while the
        // projector holds both of B's folds, so the latest row is still A; the bubble reopens.
        val a = record("a", endedAt = 5_000L, reported = 50.0)
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(listOf(a)))
        whenever(chatRepository.getMessages(any())).thenReturn(flowOf(emptyList()))
        whenever(hudShiftRepository.latestShift(Platform.DoorDash)).thenReturn(flowOf(a))
        whenever(analyticsRepository.sessionDetail("a")).thenReturn(flowOf(detailOf(a, 50.0, cash = 5.0)))
        state.value = shiftState(Platform.DoorDash, restingRegion(Platform.DoorDash, lastEnd = 5_000L))
        val vm = viewModel()
        val first = collectShift(vm)
        runCurrent()
        assertEquals("a", vm.displayedShift.value!!.sessionId)
        assertEquals(50.0, vm.displayedShift.value!!.earnings!!, 0.0)
        first.cancel()
        advanceTimeBy(6_000L)
        runCurrent()
        state.value = shiftState(Platform.DoorDash, liveRegion(Platform.DoorDash, "b", running = 20.0, startedAt = 6_000L))
        state.value = shiftState(Platform.DoorDash, restingRegion(Platform.DoorDash, lastEnd = 9_000L))
        val emissions = mutableListOf<cloud.trotter.log.gig.feature.bubble.hud.DisplayedShift?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.displayedShift.collect { emissions += it } }
        runCurrent()
        assertTrue(emissions.isNotEmpty())
        assertTrue(emissions.none { it?.sessionId == "a" || it?.earnings != null || it?.cashTips != null })
        assertNull(vm.displayedShift.value!!.sessionId)
    }

    @Test
    fun `switching platforms at rest while the new latest-row read withholds its first emission is loading`() = runTest {
        val dd = record("dd", reported = 40.0)
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(listOf(dd)))
        whenever(chatRepository.getMessages(any())).thenReturn(flowOf(emptyList()))
        whenever(hudShiftRepository.latestShift(Platform.DoorDash)).thenReturn(flowOf(dd))
        whenever(hudShiftRepository.latestShift(Platform.Uber)).thenReturn(MutableSharedFlow())
        whenever(analyticsRepository.sessionDetail("dd")).thenReturn(flowOf(detailOf(dd, 40.0, cash = 6.0)))
        state.value = shiftState(Platform.DoorDash, restingRegion(Platform.DoorDash, lastEnd = 5_000L))
        val vm = viewModel()
        collectShift(vm)
        runCurrent()
        assertEquals("dd", vm.displayedShift.value!!.sessionId)
        assertEquals(6.0, vm.displayedShift.value!!.cashTips!!, 0.0)
        state.value = shiftState(Platform.Uber, restingRegion(Platform.DoorDash, lastEnd = 5_000L))
        runCurrent()
        val shift = vm.displayedShift.value!!
        assertEquals(Platform.Uber, shift.platform)
        assertNull(shift.sessionId)
        assertNull(shift.earnings)
        assertNull(shift.cashTips)
        assertNull(shift.record)
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
    fun `todayEarned counts each app's shifts once, ignores the switcher and follows an unfocused platform`() = runTest {
        stubNoDashes()
        val today = clock.millis()
        whenever(hudShiftRepository.endedShiftsBetween(any(), any())).thenReturn(flowOf(listOf(
            cloud.trotter.log.gig.domain.analytics.EndedShiftPay("ended", today, 30.0, 20.0),
            // Ended in the read model but still live in state: counted once, with its live figure.
            cloud.trotter.log.gig.domain.analytics.EndedShiftPay("session-dd", today, 99.0, 12.0),
        )))
        val ddRow = record("session-dd", endedAt = null)
        whenever(analyticsRepository.sessionDetail("session-dd")).thenReturn(flowOf(detailOf(ddRow, 12.0, cash = 8.0)))
        whenever(analyticsRepository.sessionDetail("session-uber")).thenReturn(flowOf(null))
        state.value = shiftState(Platform.DoorDash,
            liveRegion(Platform.DoorDash, "session-dd", running = 10.0, startedAt = today),
            liveRegion(Platform.Uber, "session-uber", running = 5.0, startedAt = today))
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.todayEarned.collect {} }
        runCurrent()
        assertEquals(47.0, vm.todayEarned.value!!, 0.0) // 12 (recorded ahead of 10) + 5 + 30; no cash
        vm.selectPlatform(Platform.Uber)
        runCurrent()
        assertEquals(47.0, vm.todayEarned.value!!, 0.0)
        state.value = shiftState(Platform.Uber,
            liveRegion(Platform.DoorDash, "session-dd", running = 25.0, startedAt = today),
            liveRegion(Platform.Uber, "session-uber", running = 5.0, startedAt = today))
        runCurrent()
        assertEquals(60.0, vm.todayEarned.value!!, 0.0)
    }

    @Test
    fun `todayEarned is zero with nothing known and resets at local midnight`() = runTest {
        stubNoDashes()
        val zone = ZoneId.systemDefault()
        val midnight = java.time.LocalDate.of(2026, 10, 18).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val initialNow = midnight - 5 * 60_000L
        val tickingClock = object : Clock() {
            override fun getZone() = zone
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(initialNow + testScheduler.currentTime)
        }
        val ended = listOf(cloud.trotter.log.gig.domain.analytics.EndedShiftPay("ended", midnight - 3_600_000L, 20.0, null))
        whenever(hudShiftRepository.endedShiftsBetween(any(), any())).thenAnswer { call ->
            val from = call.getArgument<Long>(0)
            val until = call.getArgument<Long>(1)
            flowOf(ended.filter { it.startedAt >= from && it.startedAt < until })
        }
        whenever(analyticsRepository.sessionDetail("late")).thenReturn(flowOf(null))
        val vm = viewModel(testClock = tickingClock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.goalDay.collect {} }
        runCurrent()
        assertEquals(20.0, vm.goalDay.value!!.earned!!, 0.0)
        state.value = shiftState(Platform.DoorDash,
            liveRegion(Platform.DoorDash, "late", running = 10.0, startedAt = midnight - 60_000L))
        runCurrent()
        assertEquals(30.0, vm.goalDay.value!!.earned!!, 0.0)
        advanceTimeBy(6 * 60_000L)
        runCurrent()
        // Yesterday's shift, ended or still live, adds nothing to the new day.
        assertEquals(java.time.LocalDate.of(2026, 10, 19), vm.goalDay.value!!.date)
        assertEquals(0.0, vm.goalDay.value!!.earned!!, 0.0)
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
        val displayed = mapOffer(pending, active, vm.evaluationConfig.value) { offer, config ->
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

private fun mapOffer(pending: cloud.trotter.log.gig.domain.state.PendingOffer,
    job: cloud.trotter.log.gig.domain.state.Job?, config: cloud.trotter.log.gig.domain.evaluation.EvaluationConfig?,
    evaluate: (cloud.trotter.log.gig.domain.model.offer.ParsedOffer, cloud.trotter.log.gig.domain.evaluation.EvaluationConfig) -> cloud.trotter.log.gig.domain.evaluation.OfferEvaluation = cloud.trotter.log.gig.domain.evaluation.OfferEvaluator()::evaluate) =
    OfferCardMapper.map(OfferStories.build(pending, job, config, pending.platform, evaluate = evaluate), pending, job)

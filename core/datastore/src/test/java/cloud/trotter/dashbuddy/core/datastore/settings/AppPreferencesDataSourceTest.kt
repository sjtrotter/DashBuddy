package cloud.trotter.dashbuddy.core.datastore.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.dashbuddy.core.datastore.strategy.StrategyDataSource
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.LearnedTimeConstants
import cloud.trotter.dashbuddy.domain.evaluation.TimeConstantPair
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #318 — the driving/glance-mode pref: defaults false, and a write round-trips through the
 * same DataStore + flow the app reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppPreferencesDataSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newSource(dispatcher: TestDispatcher, fileName: String): AppPreferencesDataSource {
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + Job()),
            produceFile = { File(tmp.root, fileName) },
        )
        return AppPreferencesDataSource(ds)
    }

    @Test
    fun `odometer prompts default true and round trip`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "odometer-prompts.preferences_pb")
        assertEquals(true, source.odometerPrompts.first())
        source.setOdometerPrompts(false)
        assertEquals(false, source.odometerPrompts.first())
        source.setOdometerPrompts(true)
        assertEquals(true, source.odometerPrompts.first())
    }

    @Test
    fun `daily kept goal round trips and clears to null`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "daily-goal.preferences_pb")
        assertNull(source.dailyKeptGoalDollars.first())
        source.setDailyKeptGoalDollars(150.25)
        assertEquals(150.25, source.dailyKeptGoalDollars.first()!!, 0.0)
        source.setDailyKeptGoalDollars(null)
        assertNull(source.dailyKeptGoalDollars.first())
    }

    @Test
    fun `analytics show more defaults false and round trips`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "analytics-more.preferences_pb")
        assertEquals(false, source.analyticsShowMore.first())
        source.setAnalyticsShowMore(true)
        assertEquals(true, source.analyticsShowMore.first())
        source.setAnalyticsShowMore(false)
        assertEquals(false, source.analyticsShowMore.first())
    }

    @Test
    fun `vehicle lookup defaults false and round trips`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "vehicle-lookup.preferences_pb")
        assertEquals(false, source.vehicleLookupAllowed.first())
        source.setVehicleLookupAllowed(true)
        assertEquals(true, source.vehicleLookupAllowed.first())
        source.setVehicleLookupAllowed(false)
        assertEquals(false, source.vehicleLookupAllowed.first())
    }

    @Test
    fun `gas price auto refresh defaults to false`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "gas-default.preferences_pb")
        assertEquals(false, source.isGasPriceAuto.first())
    }

    @Test
    fun `glanceMode defaults to false`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = newSource(dispatcher, "prefs1.preferences_pb")

        assertEquals(false, source.glanceMode.first())
    }

    @Test
    fun `setGlanceMode round-trips through the flow`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = newSource(dispatcher, "prefs2.preferences_pb")

        source.setGlanceMode(true)
        advanceUntilIdle()
        assertEquals(true, source.glanceMode.first())

        source.setGlanceMode(false)
        advanceUntilIdle()
        assertEquals(false, source.glanceMode.first())
    }

    // #722 — the bubble's mode-adaptive gas quick-edit write paths.

    @Test
    fun `updateGasPriceManual writes the price and disables auto in one atomic edit`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = newSource(dispatcher, "prefs3.preferences_pb")

        source.updateGasPriceManual(3.29f)
        advanceUntilIdle()

        assertEquals(3.29f, source.gasPrice.first())
        assertEquals(false, source.isGasPriceAuto.first())
    }

    @Test
    fun `updateGasPriceAuto writes the price and re-enables auto in one atomic edit`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = newSource(dispatcher, "prefs4.preferences_pb")

        // Start manual (as the stepper would leave it), then "Resume auto" should flip both back.
        source.updateGasPriceManual(3.29f)
        advanceUntilIdle()

        source.updateGasPriceAuto(3.61f)
        advanceUntilIdle()

        assertEquals(3.61f, source.gasPrice.first())
        assertEquals(true, source.isGasPriceAuto.first())
    }

    // #428 Half B — the spoken-offer language override.

    @Test
    fun `ttsLanguageTag defaults to null (follow system)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = newSource(dispatcher, "prefs-tts1.preferences_pb")

        assertEquals(null, source.ttsLanguageTag.first())
    }

    @Test
    fun `setTtsLanguageTag round-trips and clears on null`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = newSource(dispatcher, "prefs-tts2.preferences_pb")

        source.setTtsLanguageTag("es")
        advanceUntilIdle()
        assertEquals("es", source.ttsLanguageTag.first())

        // null clears back to the system-locale default.
        source.setTtsLanguageTag(null)
        advanceUntilIdle()
        assertEquals(null, source.ttsLanguageTag.first())
    }

    @Test fun `time overrides set and clear independently and reject invalid inputs`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "time.preferences_pb")
        source.setTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, 2.5)
        assertEquals(setOf(EconomyField.AVG_MIN_PER_MILE.name), source.userSetEconomyFields.first())
        assertNull(source.basePickupMin.first())
        source.setTimeConstantOverride(EconomyField.BASE_PICKUP_MIN, 0.0)
        source.setTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, null)
        assertNull(source.avgMinPerMile.first())
        assertEquals(0.0, source.basePickupMin.first()!!, 0.0)
        assertEquals(setOf(EconomyField.BASE_PICKUP_MIN.name), source.userSetEconomyFields.first())
        source.setTimeConstantOverride(EconomyField.BASE_PICKUP_MIN, null)
        assertTrue(source.userSetEconomyFields.first().isEmpty())
        for ((field, value) in listOf(EconomyField.AVG_MIN_PER_MILE to 0.0,
            EconomyField.AVG_MIN_PER_MILE to Double.NaN, EconomyField.BASE_PICKUP_MIN to -1.0,
            EconomyField.BASE_PICKUP_MIN to Double.POSITIVE_INFINITY, EconomyField.TIRE_COST to 3.0)) {
            assertTrue(runCatching { source.setTimeConstantOverride(field, value) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun `vehicle changes and resetting defaults preserve the entire learned strategy store`() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { File(tmp.root, "learned.preferences_pb") })
        val strategy = StrategyDataSource(ds)
        val prefs = newSource(StandardTestDispatcher(testScheduler), "economy.preferences_pb")
        val platforms = Platform.entries.filter { it != Platform.Unknown }
        strategy.replaceTimeConstants(platforms.associateWith { LearnedTimeConstants(TimeConstantPair(2.0, 8.0), 40) })
        platforms.forEach { strategy.recordShopRate(it, 20, 20.0); strategy.recordItemsPerUnitRatio(it, 20, 16) }
        strategy.setAllowShopping(false)
        val entireSnapshot = ds.data.first().asMap()
        prefs.updateTimeConstants(4.0, 12.0)
        prefs.updateVehicleClass(VehicleClass.E_BIKE.name)
        assertEquals(entireSnapshot, ds.data.first().asMap())
        prefs.resetEconomyDefaults()
        assertEquals(entireSnapshot, ds.data.first().asMap())
        assertNull(prefs.avgMinPerMile.first())
        assertNull(prefs.basePickupMin.first())
    }

    @Test
    fun `hud pill shows earnings defaults true and round trips`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "hudPillShowsEarnings.preferences_pb")
        assertEquals(true, source.hudPillShowsEarnings.first())
        source.setHudPillShowsEarnings(false)
        assertEquals(false, source.hudPillShowsEarnings.first())
        source.setHudPillShowsEarnings(true)
        assertEquals(true, source.hudPillShowsEarnings.first())
    }

    @Test fun `manual price undo restores 350 and auto on together`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "gas-undo.preferences_pb")
        source.updateGasPriceAuto(3.50f)
        val previous = source.updateGasPriceManual(3.75f)
        assertEquals(false, source.isGasPriceAuto.first())
        assertTrue(source.restoreGasPriceManual(previous,
            StoredGasPrice(StoredPreference(3.75f, false), StoredPreference(false, false))))
        assertEquals(3.50f, source.gasPrice.first())
        assertEquals(true, source.isGasPriceAuto.first())
    }

    @Test fun `shared restores check value marker and submission validity inside edit`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "cas.preferences_pb")
        val previous = source.updatePhoneTotal(100.0)
        val expected = StoredPreference(100.0, true)
        assertEquals(false, source.restorePhoneTotal(previous, expected) { false })
        assertEquals(100.0, source.phonePlanTotal.first())
        // Same value with a different marker must also fail CAS.
        assertTrue(source.restorePhoneTotal(StoredPreference(100.0, false), expected))
        assertEquals(false, source.restorePhoneTotal(previous, expected))
        assertEquals(100.0, source.phonePlanTotal.first())
        assertTrue(source.userSetEconomyFields.first().isEmpty())
        source.updatePhoneTotal(110.0)
        assertEquals(false, source.restorePhoneTotal(previous, expected))
        assertEquals(110.0, source.phonePlanTotal.first())
        val lines = source.updatePhoneLines(3)
        source.updatePhoneLines(4)
        assertEquals(false, source.restorePhoneLines(lines, StoredPreference(3, true)))
        val share = source.updatePhoneShare(40.0)
        source.updatePhoneShare(50.0)
        assertEquals(false, source.restorePhoneShare(share, StoredPreference(40.0, true)))
        val tariff = source.updateElectricityPrice(.25)
        source.updateElectricityPrice(.30)
        assertEquals(false, source.restoreElectricityPrice(tariff, StoredPreference(.25, true)))
        val time = source.setTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, 4.0)
        source.setTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, null)
        assertEquals(false, source.restoreTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, time, StoredPreference(4.0, true)))
    }

    @Test fun `capture survives failure after datastore transform without recapturing`() = runTest {
        val delegate = PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { File(tmp.root, "capture.preferences_pb") })
        var failOnce = true
        val source = AppPreferencesDataSource(object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = delegate.data
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) =
                delegate.updateData {
                    val updated = transform(it)
                    if (failOnce) { failOnce = false; throw java.io.IOException("failed disk write") }
                    updated
                }
        })
        val capture = SaveCapture<StoredPreference<Double>>()
        assertTrue(runCatching { source.updatePhoneTotal(100.0, capture) }.exceptionOrNull() is java.io.IOException)
        assertNull(source.phonePlanTotal.first())
        // An intervening stored state exposes accidental recapture on retry.
        source.updatePhoneTotal(90.0)
        val previous = source.updatePhoneTotal(100.0, capture)
        assertEquals(StoredPreference<Double>(null, false), previous)
        assertTrue(source.restorePhoneTotal(previous, StoredPreference(100.0, true)))
        assertNull(source.phonePlanTotal.first())
        assertTrue(source.userSetEconomyFields.first().isEmpty())
    }

}

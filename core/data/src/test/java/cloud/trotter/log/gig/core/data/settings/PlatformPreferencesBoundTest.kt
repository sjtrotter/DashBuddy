package cloud.trotter.log.gig.core.data.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.gig.core.datastore.settings.PlatformPreferencesDataSource
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * #170 — gig.log reads only the platforms it ships rules for ([Platform.bound]). A saved preference from an
 * older install that still enables Instacart or Walmart Spark must not make the listeners, filters or
 * pipelines read them, and installed-app detection offers only bound platforms.
 */
@RunWith(RobolectricTestRunner::class)
class PlatformPreferencesBoundTest {

    @get:Rule val tmp = TemporaryFolder()

    private val unbound = Platform.entries.filter { it.packageName != null && !it.bound }

    @Test
    fun `the registry keeps unbound platforms as entries but reads only the bound ones`() {
        assertEquals(listOf(Platform.Instacart, Platform.WalmartSpark), unbound)
        assertEquals(Platform.boundPlatforms.mapNotNull { it.packageName }.toSet(), Platform.watchedPackages)
    }

    @Test
    fun `a saved unbound platform is ignored and enables no package`() = runTest {
        val source = PlatformPreferencesDataSource(
            PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { tmp.newFile("platforms.preferences_pb") }),
        )
        source.setEnabledPlatforms((Platform.boundPlatforms.take(1) + unbound).map { it.wire }.toSet())
        val repo = PlatformPreferencesRepository(source, RuntimeEnvironment.getApplication(), backgroundScope)

        val enabled = repo.enabledPlatforms.first { it.isNotEmpty() }
        assertEquals(Platform.boundPlatforms.take(1).toSet(), enabled)
        repo.enabledPackages.first { it == Platform.boundPlatforms.take(1).mapNotNull { p -> p.packageName }.toSet() }
    }

    @Test
    fun `installed-app detection offers only bound platforms`() = runTest {
        val app = RuntimeEnvironment.getApplication()
        (unbound + Platform.boundPlatforms.take(1)).forEach { platform ->
            shadowOf(app.packageManager).installPackage(
                PackageInfo().apply {
                    packageName = platform.packageName
                    applicationInfo = ApplicationInfo().apply { packageName = platform.packageName }
                },
            )
        }
        val source = PlatformPreferencesDataSource(
            PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { tmp.newFile("detect.preferences_pb") }),
        )
        val repo = PlatformPreferencesRepository(source, app, backgroundScope)
        assertEquals(Platform.boundPlatforms.take(1).toSet(), repo.detectInstalledPlatforms())
    }

    @Test
    fun `readablePlatforms drops unknown and unbound wires`() {
        val wires = Platform.entries.map { it.wire }.toSet() + "not_a_platform"
        assertEquals(Platform.boundPlatforms.toSet(), PlatformPreferencesRepository.readablePlatforms(wires))
    }
}

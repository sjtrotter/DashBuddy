package cloud.trotter.log.gig.core.data.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.gig.core.datastore.settings.PlatformPreferencesDataSource
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.CoroutineScope
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
 * #170 — a build reads only [Platform.readable]: the platforms it ships rules for, plus, in the debug build only
 * (`readsUnboundPlatforms`), the registry platforms with no ruleset. Outside debug, a saved preference from an
 * older install that still enables Instacart or Walmart Spark must not make the listeners, filters or pipelines
 * read them, and installed-app detection offers only bound platforms. In debug both are readable.
 */
@RunWith(RobolectricTestRunner::class)
class PlatformPreferencesBoundTest {

    @get:Rule val tmp = TemporaryFolder()

    private val unbound = Platform.entries.filter { it.packageName != null && !it.bound }
    private val firstBound = Platform.boundPlatforms.take(1).toSet()

    private fun repo(scope: CoroutineScope, file: String, readsUnbound: Boolean) = PlatformPreferencesRepository(
        PlatformPreferencesDataSource(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { tmp.newFile(file) }),
        ),
        RuntimeEnvironment.getApplication(),
        scope,
        readsUnbound,
    )

    private fun install(platforms: Collection<Platform>) {
        val pm = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        platforms.forEach { platform ->
            val pkg = platform.packageName!!
            pm.installPackage(
                PackageInfo().apply {
                    packageName = pkg
                    applicationInfo = ApplicationInfo().apply { packageName = pkg }
                },
            )
        }
    }

    /** Saves the wires of [platforms] as enabled, the way an older install (or a debug tester) left them. */
    private suspend fun saveEnabled(repo: PlatformPreferencesRepository, platforms: Set<Platform>) {
        platforms.forEach { repo.setEnabled(it, true) }
    }

    @Test
    fun `the registry keeps unbound platforms as entries and reads them only when asked`() {
        assertEquals(listOf(Platform.Instacart, Platform.WalmartSpark), unbound)
        assertEquals(Platform.boundPlatforms, Platform.readable(includeUnbound = false))
        assertEquals(Platform.boundPlatforms + unbound, Platform.readable(includeUnbound = true))
        assertEquals(Platform.boundPlatforms.mapNotNull { it.packageName }.toSet(), Platform.watchedPackages)
    }

    @Test
    fun `outside debug a saved unbound platform is ignored and enables no package`() = runTest {
        val repo = repo(backgroundScope, "platforms.preferences_pb", readsUnbound = false)
        saveEnabled(repo, firstBound + unbound)

        assertEquals(firstBound, repo.enabledPlatforms.first { it.isNotEmpty() })
        repo.enabledPackages.first { it == firstBound.mapNotNull { p -> p.packageName }.toSet() }
    }

    @Test
    fun `in debug a saved unbound platform is enabled`() = runTest {
        val repo = repo(backgroundScope, "debug.preferences_pb", readsUnbound = true)
        saveEnabled(repo, firstBound + unbound)

        val expected = firstBound + unbound
        assertEquals(expected, repo.enabledPlatforms.first { it.size == expected.size })
        repo.enabledPackages.first { it == expected.mapNotNull { p -> p.packageName }.toSet() }
    }

    @Test
    fun `installed-app detection follows the build's readable set`() = runTest {
        install(unbound + firstBound)
        assertEquals(firstBound, repo(backgroundScope, "detect.preferences_pb", readsUnbound = false).detectInstalledPlatforms())
        assertEquals(
            firstBound + unbound,
            repo(backgroundScope, "detect-debug.preferences_pb", readsUnbound = true).detectInstalledPlatforms(),
        )
    }

    @Test
    fun `readablePlatforms drops unknown wires and, outside debug, unbound ones`() {
        val wires = Platform.entries.map { it.wire }.toSet() + "not_a_platform"
        assertEquals(Platform.boundPlatforms.toSet(), PlatformPreferencesRepository.readablePlatforms(wires, includeUnbound = false))
        assertEquals(
            Platform.readable(includeUnbound = true).toSet(),
            PlatformPreferencesRepository.readablePlatforms(wires, includeUnbound = true),
        )
    }
}

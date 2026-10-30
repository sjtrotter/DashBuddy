package cloud.trotter.log.gig.core.datastore.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SimplePrefsDeclarationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `odometer prompts declaration`() = runTest {
        assertDeclared(
            SimplePrefs.odometerPrompts, booleanPreferencesKey("odometer_prompts"),
            default = true, seed = false, written = true,
        )
    }

    @Test
    fun `analytics show more declaration`() = runTest {
        assertDeclared(
            SimplePrefs.analyticsShowMore, booleanPreferencesKey("analytics_show_more"),
            default = false, seed = true, written = false,
        )
    }

    @Test
    fun `glance mode declaration`() = runTest {
        assertDeclared(
            SimplePrefs.glanceMode, booleanPreferencesKey("glance_mode"),
            default = false, seed = true, written = false,
        )
    }

    @Test
    fun `daily kept goal declaration`() = runTest {
        assertDeclared(
            SimplePrefs.dailyKeptGoalDollars, doublePreferencesKey("daily_kept_goal_dollars"),
            default = null, seed = 150.25, written = 200.50,
        )
    }

    @Test
    fun `app theme declaration`() = runTest {
        assertDeclared(
            SimplePrefs.appTheme, stringPreferencesKey("app_theme"),
            default = null, seed = "dark", written = "light",
        )
    }

    @Test
    fun `tts language tag declaration`() = runTest {
        assertDeclared(
            SimplePrefs.ttsLanguageTag, stringPreferencesKey("tts_language_tag"),
            default = null, seed = "es", written = "fr",
        )
    }

    @Test
    fun `simple preference key names are unique`() {
        val names = listOf(
            SimplePrefs.odometerPrompts.key.name,
            SimplePrefs.analyticsShowMore.key.name,
            SimplePrefs.glanceMode.key.name,
            SimplePrefs.dailyKeptGoalDollars.key.name,
            SimplePrefs.appTheme.key.name,
            SimplePrefs.ttsLanguageTag.key.name,
        )
        assertEquals(names.size, names.toSet().size)
    }

    private suspend fun <T, S : Any> TestScope.assertDeclared(
        p: Pref<T, S>,
        rawKey: Preferences.Key<S>,
        default: T,
        seed: S,
        written: T,
    ) {
        val ds = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "${rawKey.name}.preferences_pb") },
        )
        val source = AppPreferencesDataSource(ds)
        assertEquals(rawKey.name, p.key.name)
        assertEquals(default, p.default)
        assertEquals(default, source.read(p).first())

        ds.edit { it[rawKey] = seed }
        assertEquals(seed, source.read(p).first())

        source.write(p, written)
        assertEquals(written, ds.data.first()[rawKey])
        assertEquals(written, source.read(p).first())

        if (default == null) {
            source.write(p, default)
            assertFalse(ds.data.first().contains(rawKey))
            assertNull(ds.data.first()[rawKey])
            assertNull(source.read(p).first())
        }
    }
}

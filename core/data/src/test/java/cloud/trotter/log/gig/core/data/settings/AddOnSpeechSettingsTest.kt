package cloud.trotter.log.gig.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.log.gig.domain.offer.story.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AddOnSpeechSettingsTest {
    @Test fun `default persistence and unknown setting select one presentation policy`() = runTest {
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(data.value).also { data.value = it }
        }
        val source = DevSettingsDataSource(store)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DevSettingsRepository(source, true, dispatcher)
        runCurrent()
        val story = StoryFixtures.addOn()
        assertEquals(AddOnSpeech.JOB_TOTALS, repo.addOnSpeech.value)
        assertEquals(StoryBasis.ADD_ON_JOB, story.forPresentation(repo.addOnSpeech.value).basis)
        repo.setAddOnSpeech(AddOnSpeech.DELTAS_ONLY)
        runCurrent()
        assertEquals(story.added, story.forPresentation(repo.addOnSpeech.value).facts)
        val reopened = DevSettingsRepository(source, true, dispatcher)
        runCurrent()
        assertEquals(AddOnSpeech.DELTAS_ONLY, reopened.addOnSpeech.value)
        source.setAddOnSpeech("unknown")
        runCurrent()
        assertEquals(AddOnSpeech.JOB_TOTALS, repo.addOnSpeech.value)
        repo.setAddOnSpeech(AddOnSpeech.JOB_TOTALS)
        runCurrent()
        assertEquals(StoryBasis.ADD_ON_JOB, story.forPresentation(repo.addOnSpeech.value).basis)
    }
}

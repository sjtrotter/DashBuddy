package cloud.trotter.log.gig.state.effects

import android.content.res.Configuration
import cloud.trotter.log.gig.domain.offer.story.*
import cloud.trotter.log.gig.domain.format.Formats
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class OfferSpeechTest {
    private val originalLocale = java.util.Locale.getDefault()
    @org.junit.Before fun setLocale() { java.util.Locale.setDefault(java.util.Locale.US) }
    @org.junit.After fun restoreLocale() { java.util.Locale.setDefault(originalLocale) }

    private fun speak(s: OfferStory, language: String): String {
        val context = RuntimeEnvironment.getApplication()
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return OfferSpeech.speak(s, context.createConfigurationContext(config).resources)
    }
    @Test fun `English exact offer sentences`() {
        assertEquals("Offer. H-E-B. Estimated net: 22 dollars. 12.9 miles. 45 minutes. 22 dollars an hour.", speak(StoryFixtures.total(), "en"))
        assertEquals("Offer. H-E-B. Pay: 12 dollars. Distance unknown.", speak(StoryFixtures.distanceUnknown(), "en"))
        assertEquals("Offer. H-E-B. Pay unknown.", speak(StoryFixtures.payUnknown(), "en"))
        assertEquals("Add-on. H-E-B. Adds 6 dollars. Job estimated net: 30 dollars. 15.2 miles. 58 minutes. 31 dollars an hour.", speak(StoryFixtures.addOn().forPresentation(AddOnSpeech.JOB_TOTALS), "en"))
        assertEquals("Add-on. H-E-B. Adds 6 dollars. 3.1 more miles. 8 more minutes.", speak(StoryFixtures.addOn().forPresentation(AddOnSpeech.DELTAS_ONLY), "en"))
        assertEquals("Offer. H-E-B. Blocked store. Estimated net: 22 dollars. 12.9 miles. 45 minutes. 22 dollars an hour.", speak(StoryFixtures.blocked(), "en"))
        assertEquals("Offer. H-E-B. Estimated loss: 3 dollars. 12.9 miles. 45 minutes. 22 dollars an hour.", speak(StoryFixtures.loss(), "en"))
        assertEquals("Offer. H-E-B. Estimated loss: under 1 dollar. 12.9 miles. 45 minutes. 22 dollars an hour.", speak(StoryFixtures.loss(-0.75), "en"))
    }
    @Test fun `Spanish exact offer sentences`() {
        assertEquals("Oferta. H-E-B. Neto estimado: 22 dólares. 12.9 millas. 45 minutos. 22 dólares por hora.", speak(StoryFixtures.total(), "es"))
        assertEquals("Oferta. H-E-B. Pago: 12 dólares. Distancia desconocida.", speak(StoryFixtures.distanceUnknown(), "es"))
        assertEquals("Oferta. H-E-B. Pago desconocido.", speak(StoryFixtures.payUnknown(), "es"))
        assertEquals("Pedido adicional. H-E-B. Suma 6 dólares. Neto estimado del trabajo: 30 dólares. 15.2 millas. 58 minutos. 31 dólares por hora.", speak(StoryFixtures.addOn().forPresentation(AddOnSpeech.JOB_TOTALS), "es"))
        assertEquals("Pedido adicional. H-E-B. Suma 6 dólares. 3.1 millas más. 8 minutos más.", speak(StoryFixtures.addOn().forPresentation(AddOnSpeech.DELTAS_ONLY), "es"))
        assertEquals("Oferta. H-E-B. Tienda bloqueada. Neto estimado: 22 dólares. 12.9 millas. 45 minutos. 22 dólares por hora.", speak(StoryFixtures.blocked(), "es"))
        assertEquals("Oferta. H-E-B. Pérdida estimada: 3 dólares. 12.9 millas. 45 minutos. 22 dólares por hora.", speak(StoryFixtures.loss(), "es"))
        assertEquals("Oferta. H-E-B. Pérdida estimada: menos de 1 dólar. 12.9 millas. 45 minutos. 22 dólares por hora.", speak(StoryFixtures.loss(-0.75), "es"))
    }
    @Test fun `spoken net is always the whole dollars of the net fact`() {
        val random = java.util.Random(1442)
        val amounts = listOf(22.99, 21.9999, -3.75, -0.75, 0.0, 1.0) + List(500) { random.nextDouble() * 400 - 200 }
        for (language in listOf("en", "es")) for (value in amounts) {
            val s = StoryFixtures.total().copy(store = null, facts = listOf(Fact(FactKind.NET, value), Fact(FactKind.PAY, 24.5)))
            val line = speak(s, language)
            val spoken = Regex("[0-9]+").find(line)!!.value.toLong()
            val expected = abs(Formats.wholeDollars(s.fact(FactKind.NET)!!.value!!))
            if (value < 0 && expected == 0L) assertTrue(line.contains(if (language == "en") "under 1" else "menos de 1"))
            else assertEquals(line, expected, spoken)
        }
    }
    @Test fun `an unknown fact never becomes a numeric utterance`() {
        for (language in listOf("en", "es")) {
            val s = StoryFixtures.total().copy(store = null,
                facts = FactKind.entries.map { Fact(it, null) }, missing = setOf(Missing.PAY, Missing.DISTANCE))
            assertFalse(speak(s, language).any(Char::isDigit))
            for (kind in listOf(FactKind.NET, FactKind.MILES, FactKind.MINUTES, FactKind.PER_HOUR)) {
                val onlyUnknown = s.copy(facts = listOf(Fact(kind, null)), missing = emptySet())
                assertFalse(speak(onlyUnknown, language).any(Char::isDigit))
            }
        }
    }
    @Test fun `one preference clause at most in the shared order`() {
        val tail = "Estimated net: 22 dollars. 12.9 miles. 45 minutes. 22 dollars an hour."
        val all = StoryFixtures.netLimit().copy(rules = listOf(RuleHit.SHOPPING_OFF, RuleHit.BLOCKED_STORE, RuleHit.PROTECT_RATE))
        assertEquals("Offer. H-E-B. Blocked store. $tail", speak(all, "en"))
        assertEquals("Offer. H-E-B. Under your minimum net. $tail",
            speak(StoryFixtures.netLimit().copy(rules = listOf(RuleHit.PROTECT_RATE)), "en"))
        val distance = StoryFixtures.total().copy(limits = listOf(LimitHit(LimitKind.DISTANCE, 10.0)))
        assertEquals("Offer. H-E-B. Over your max distance. $tail", speak(distance, "en"))
        assertEquals("Oferta. H-E-B. Neto por debajo de tu mínimo. Neto estimado: 22 dólares. 12.9 millas. 45 minutos. 22 dólares por hora.",
            speak(StoryFixtures.netLimit(), "es"))
        assertEquals("Oferta. H-E-B. Más lejos que tu distancia máxima. Neto estimado: 22 dólares. 12.9 millas. 45 minutos. 22 dólares por hora.",
            speak(distance, "es"))
    }
    @Test fun `an items limit is never spoken`() {
        for (language in listOf("en", "es")) {
            val items = StoryFixtures.total().copy(limits = listOf(LimitHit(LimitKind.ITEMS, 30.0)))
            assertEquals(speak(StoryFixtures.total(), language), speak(items, language))
        }
        assertEquals(speak(StoryFixtures.netLimit(), "en"), speak(StoryFixtures.netLimit().copy(
            limits = listOf(LimitHit(LimitKind.NET, 7.0), LimitHit(LimitKind.ITEMS, 30.0))), "en"))
    }
    @Test fun `protect rate is written only and requotes add no spoken lead`() {
        for (language in listOf("en", "es")) {
            assertEquals(speak(StoryFixtures.total(), language),
                speak(StoryFixtures.total(requote = true).copy(rules = listOf(RuleHit.PROTECT_RATE)), language))
        }
    }
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `persisted add on setting selects exact English and Spanish speech`() = kotlinx.coroutines.test.runTest {
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) =
                transform(data.value).also { data.value = it }
        }
        val settings = cloud.trotter.log.gig.core.data.settings.DevSettingsRepository(
            cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource(store), true,
            kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        testScheduler.runCurrent()
        val story = StoryFixtures.addOn()
        assertEquals("Add-on. H-E-B. Adds 6 dollars. Job estimated net: 30 dollars. 15.2 miles. 58 minutes. 31 dollars an hour.",
            speak(story.forPresentation(settings.addOnSpeech.value), "en"))
        settings.setAddOnSpeech(AddOnSpeech.DELTAS_ONLY)
        testScheduler.runCurrent()
        assertEquals("Add-on. H-E-B. Adds 6 dollars. 3.1 more miles. 8 more minutes.",
            speak(story.forPresentation(settings.addOnSpeech.value), "en"))
        assertEquals("Pedido adicional. H-E-B. Suma 6 dólares. 3.1 millas más. 8 minutos más.",
            speak(story.forPresentation(settings.addOnSpeech.value), "es"))
    }
}

package cloud.trotter.log.gig.core.datastore.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** Maps a preference value to its stored type; a null encoding removes the key. */
class Codec<T, S : Any>(
    val decode: (S) -> T,
    val encode: (T) -> S?,
)

/** A single-key preference with a read-time default for an absent key. */
class Pref<T, S : Any>(
    val key: Preferences.Key<S>,
    val default: T,
    val codec: Codec<T, S>,
)

fun <S : Any> scalar(): Codec<S, S> = Codec({ it }, { it })

fun <S : Any> optional(): Codec<S?, S> = Codec({ it }, { it })

object SimplePrefs {
    val odometerPrompts = Pref(booleanPreferencesKey("odometer_prompts"), true, scalar<Boolean>())
    val analyticsShowMore = Pref(booleanPreferencesKey("analytics_show_more"), false, scalar<Boolean>())

    // Driving / glance mode (#318) — bumps the HUD's LocalGlance multiplier.
    val glanceMode = Pref(booleanPreferencesKey("glance_mode"), false, scalar<Boolean>())
    val dailyKeptGoalDollars = Pref(doublePreferencesKey("daily_kept_goal_dollars"), null, optional<Double>())
    val appTheme = Pref(stringPreferencesKey("app_theme"), null, optional<String>())

    // Spoken-offer (TTS) language override (#428 Half B) — a BCP-47 tag (e.g. "es").
    // Absent/null ⇒ follow the system locale; present ⇒ force that language for both the
    // engine voice and the spoken word resolution.
    val ttsLanguageTag = Pref(stringPreferencesKey("tts_language_tag"), null, optional<String>())
}

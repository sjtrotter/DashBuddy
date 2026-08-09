package cloud.trotter.dashbuddy.state.effects

import android.speech.tts.TextToSpeech

/**
 * The one seam through which [TtsEffectHandler] obtains a speech engine (#991).
 *
 * Before #991 the handler constructed its `TextToSpeech` inline in `init`, exactly once, which is
 * why a dropped engine binder was permanent. Recovery means constructing a *second* one, and a
 * construction site that a test can observe is what makes "a failed utterance rebuilds the engine"
 * assertable without a device or a Robolectric TTS harness.
 *
 * The production binding is a one-liner in `AppModule`; the fake in tests hands back a stub and
 * keeps the listener so the test can deliver `onInit` itself (the real callback is asynchronous).
 */
fun interface TtsEngineFactory {
    /** Build an engine that reports readiness to [onInit]. */
    fun create(onInit: TextToSpeech.OnInitListener): TextToSpeech
}

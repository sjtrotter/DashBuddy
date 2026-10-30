package cloud.trotter.log.gig.di

import android.app.NotificationManager
import android.content.Context
import android.speech.tts.TextToSpeech
import cloud.trotter.log.gig.BuildConfig
import cloud.trotter.log.gig.GigLogApplication
import cloud.trotter.log.gig.core.data.log.LogScrubber
import cloud.trotter.log.gig.core.pipeline.SensitiveTextMarkers
import cloud.trotter.log.gig.core.state.EffectExecutor
import cloud.trotter.log.gig.core.state.MetadataProvider
import cloud.trotter.log.gig.domain.pipeline.LocaleBoundaryReporter
import cloud.trotter.log.gig.domain.pipeline.RecognitionHealthReporter
import cloud.trotter.log.gig.locale.LocaleBoundaryNotifier
import cloud.trotter.log.gig.notice.RecognitionHealthNotifier
import cloud.trotter.log.gig.state.effects.SideEffectEngine
import cloud.trotter.log.gig.state.effects.TtsEngineFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {

    @Binds
    abstract fun bindEffectExecutor(impl: SideEffectEngine): EffectExecutor

    /**
     * #938: the sensor layer (`:core:pipeline`) reports its English-locale boundary through a
     * `:domain` contract; `:app` supplies the implementation because the notice needs the
     * DataStore flag and a notification, neither of which `:core:pipeline` may reach.
     */
    @Binds
    abstract fun bindLocaleBoundaryReporter(impl: LocaleBoundaryNotifier): LocaleBoundaryReporter

    /**
     * #937: the same inversion for the recognition-health alarm — `:core:pipeline` measures the
     * UNKNOWN rate and decides, `:app` owns the notification it can't reach.
     */
    @Binds
    abstract fun bindRecognitionHealthReporter(
        impl: RecognitionHealthNotifier,
    ): RecognitionHealthReporter
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Named("appVersionName")
    fun provideAppVersionName(): String = BuildConfig.VERSION_NAME

    @Provides
    @Singleton
    fun provideMetadataProvider(): MetadataProvider =
        MetadataProvider { GigLogApplication.createMetadata() }

    @Provides
    @Singleton
    fun provideNotificationManager(@ApplicationContext context: Context): NotificationManager {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * #991: the speech engine's one construction site. A factory rather than an inline
     * `TextToSpeech(context, listener)` in the handler is what makes the engine *rebuildable*
     * after its binder dies — and observable from a unit test without a device.
     */
    @Provides
    @Singleton
    fun provideTtsEngineFactory(@ApplicationContext context: Context): TtsEngineFactory =
        TtsEngineFactory { onInit -> TextToSpeech(context, onInit) }

    /**
     * The fail-closed PII scrub for the shareable log sink (#551, Principle 7). `:app` is the only
     * module that sees both `:core:data` (where [LogScrubber]/`LogRepository` live) and
     * `:core:pipeline` (the [SensitiveTextMarkers] SSOT), so the binding is wired here — keeping ONE
     * marker definition with no new module edge. `findMarker` is itself fail-closed (a normalization
     * throw returns a sentinel, not null), and `LogRepository` additionally guards throws + unbound.
     */
    @Provides
    @Singleton
    fun provideLogScrubber(): LogScrubber =
        LogScrubber { line -> SensitiveTextMarkers.findMarker(line) }

}
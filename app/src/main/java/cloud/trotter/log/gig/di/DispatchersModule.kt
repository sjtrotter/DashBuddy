package cloud.trotter.log.gig.di

import cloud.trotter.log.gig.domain.di.ApplicationScope
import cloud.trotter.log.gig.domain.di.DefaultDispatcher
import cloud.trotter.log.gig.domain.di.IoDispatcher
import cloud.trotter.log.gig.domain.capability.PrivacyDisclosure
import cloud.trotter.log.gig.domain.di.PrivacyNoticeUrl
import cloud.trotter.log.gig.domain.di.ReadsUnboundPlatforms
import cloud.trotter.log.gig.domain.di.WideEventReceiptOffered
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton
import cloud.trotter.log.gig.BuildConfig
import javax.inject.Named
import timber.log.Timber

/**
 * Bindings for the injectable dispatchers so coroutine-owning singletons are
 * testable with virtual time (#341/#352), plus the app-lifetime scope for
 * long-lived shared flows (#356). The qualifiers all live in `:domain`
 * (`domain.di`); the data-layer hygiene pass (#364) migrates the remaining
 * hardcoded scopes onto these.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * App-lifetime scope hosting the sensing upstream (`PipelineV2.shareIn`)
     * and rule loading. The exception handler is the last-resort backstop
     * (#430): the SupervisorJob already isolates child failures from each
     * other, but without a handler an uncaught one still reaches the default
     * handler and can kill the process. The pipeline's own `supervised`
     * retry should make this unreachable for sensing — if this line ever
     * logs, something escaped supervision.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, t ->
                    Timber.e(t, "ApplicationScope: uncaught coroutine failure (isolated)")
                },
        )

    /** Build-variant flag for layer modules (#364) — they must not borrow
     *  another module's BuildConfig for it. */
    @Provides
    @Named("isDebug")
    fun provideIsDebug(): Boolean = BuildConfig.DEBUG

    /**
     * #1414 — whether this build offers Screen events (false in the Play build), from the build table in
     * `app/build.gradle.kts`. Provided here because a layer or feature module must not read `:app`'s
     * BuildConfig: the listener, the permission flow and Settings all take this one value.
     */
    @Provides
    @WideEventReceiptOffered
    fun provideWideEventReceiptOffered(): Boolean = BuildConfig.WIDE_EVENT_RECEIPT_OFFERED

    /**
     * #170 — the privacy page's "Technical details" link: PRIVACY.md as of this build's commit, or master for a
     * build with no clean commit (`BuildConfig.PRIVACY_REF`).
     */
    /**
     * #170 — whether this build also reads the registry platforms that ship no ruleset (true in debug only, so a
     * tester's unrecognized screens from those apps reach the census), from the build table. The listener's
     * subscription, the enabled-platform preference, the platform list and the screen reading card all take it.
     */
    @Provides
    @ReadsUnboundPlatforms
    fun provideReadsUnboundPlatforms(): Boolean = BuildConfig.READS_UNBOUND_PLATFORMS

    @Provides
    @PrivacyNoticeUrl
    fun providePrivacyNoticeUrl(): String = PrivacyDisclosure.urlAt(BuildConfig.PRIVACY_REF)
}

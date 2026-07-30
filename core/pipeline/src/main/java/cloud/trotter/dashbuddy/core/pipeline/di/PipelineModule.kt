package cloud.trotter.dashbuddy.core.pipeline.di

import android.content.Context
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.core.pipeline.CachingPlatformAppVersions
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.PlatformAppVersions
import cloud.trotter.dashbuddy.core.pipeline.ReplayMetadataProviderImpl
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.core.pipeline.rules.ScreenRedactionSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PipelineModule {

    // CaptureBus is bound in :core:data's per-variant CaptureBusModule
    // (debug → DiskCaptureBus, release → NoOpCaptureBus, #346).

    @Binds @Singleton
    abstract fun bindReplayMetadataProvider(impl: ReplayMetadataProviderImpl): ReplayMetadataProvider

    // #598: the capture stage's rule-declared redaction seam is the interpreter
    // that owns the loaded rulesets.
    @Binds @Singleton
    abstract fun bindScreenRedactionSource(impl: JsonRuleInterpreter): ScreenRedactionSource

    companion object {

        /**
         * #937: the observed platform app's `versionName`.
         *
         * The `PackageManager` call is supplied as a lambda so the caching + fail-open logic in
         * [CachingPlatformAppVersions] stays a plain unit test — Android lives only at this DI
         * edge. `getPackageInfo` throws `NameNotFoundException` for an unknown package, which
         * the resolver treats as "unresolvable" and caches negatively.
         */
        @Provides @Singleton
        fun providePlatformAppVersions(
            @ApplicationContext context: Context,
            stats: PipelineStats,
        ): PlatformAppVersions = CachingPlatformAppVersions(
            lookup = { pkg ->
                @Suppress("DEPRECATION") // getPackageInfo(String, Int) — the flags overload is API 33+
                context.packageManager.getPackageInfo(pkg, 0).versionName
            },
            stats = stats,
        )
    }
}

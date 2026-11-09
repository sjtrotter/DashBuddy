package cloud.trotter.log.gig.domain.di

import javax.inject.Qualifier

/**
 * #170 — qualifies the `Boolean` that says whether this build also reads the registry platforms that ship no
 * ruleset (`Platform.readable(includeUnbound = true)`): true only in the debug (tester) build, so a tester's
 * unrecognized screens from those apps reach the census. Per build type in `app/build.gradle.kts`; `:app`'s
 * `DispatchersModule` provides it from `BuildConfig.READS_UNBOUND_PLATFORMS`, like [WideEventReceiptOffered].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ReadsUnboundPlatforms

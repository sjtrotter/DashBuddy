package cloud.trotter.log.gig.domain.di

import javax.inject.Qualifier

/**
 * #1414 — qualifies the `Boolean` that says whether this build offers Screen events (wide accessibility event
 * receipt). Per build type in `app/build.gradle.kts`; `:app`'s `DispatchersModule` provides it from
 * `BuildConfig.WIDE_EVENT_RECEIPT_OFFERED`. In `:domain`, like [IoDispatcher], so the layer and feature
 * modules that enforce or show the option take it without reading `:app`'s BuildConfig.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WideEventReceiptOffered

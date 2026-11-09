package cloud.trotter.log.gig.domain.di

import javax.inject.Qualifier

/**
 * #170 — qualifies the `String` link to the privacy notice the in-app screens open: PRIVACY.md as of this build's
 * commit (`PrivacyDisclosure.urlAt`). `:app`'s `DispatchersModule` provides it from `BuildConfig.PRIVACY_REF`. In
 * `:domain`, like [WideEventReceiptOffered], so the feature modules take it without reading `:app`'s BuildConfig.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PrivacyNoticeUrl

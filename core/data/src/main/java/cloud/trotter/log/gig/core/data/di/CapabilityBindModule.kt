package cloud.trotter.log.gig.core.data.di

import cloud.trotter.log.gig.core.data.capability.RuleCapabilityRepository
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the domain-facing consent contract (#417) to its :core:data
 * implementation. The rule loader (`:core:pipeline`) reconciles through the
 * interface; the side-effect engine gates through it at fire time.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CapabilityBindModule {

    @Binds
    @Singleton
    abstract fun bindRuleCapabilityGrants(
        impl: RuleCapabilityRepository,
    ): RuleCapabilityGrants
}

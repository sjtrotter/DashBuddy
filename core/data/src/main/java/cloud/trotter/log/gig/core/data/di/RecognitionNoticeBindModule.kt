package cloud.trotter.log.gig.core.data.di

import cloud.trotter.log.gig.core.data.pipeline.RecognitionNoticeRepository
import cloud.trotter.log.gig.domain.pipeline.RecognitionNoticeStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the recognition notice store (#1560) to its `:core:data` implementation. The incident owner
 * (`RecognitionHealthMonitor`, `:core:pipeline`) reads and writes through the `:domain` interface.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RecognitionNoticeBindModule {

    @Binds
    @Singleton
    abstract fun bindRecognitionNoticeStore(
        impl: RecognitionNoticeRepository,
    ): RecognitionNoticeStore
}

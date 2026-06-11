package cloud.trotter.dashbuddy.core.data.di

import cloud.trotter.dashbuddy.core.data.capture.CaptureBus
import cloud.trotter.dashbuddy.core.data.capture.DiskCaptureBus
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Debug variant: captures persist to disk — the INBOX triage / golden-corpus
 * workflow depends on them. Release binds [cloud.trotter.dashbuddy.core.data.capture.NoOpCaptureBus]
 * instead (#346).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CaptureBusModule {

    @Binds
    @Singleton
    abstract fun bindCaptureBus(impl: DiskCaptureBus): CaptureBus
}

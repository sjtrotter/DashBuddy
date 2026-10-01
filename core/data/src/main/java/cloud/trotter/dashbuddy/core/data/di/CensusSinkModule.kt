package cloud.trotter.dashbuddy.core.data.di

import cloud.trotter.dashbuddy.domain.capture.CensusSink
import cloud.trotter.dashbuddy.core.data.capture.NoOpCensusSink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Unlike `CaptureBus`, both variants bind NoOp until M2/M3 (#1138) bind a disk queue behind consent.
 * Main-source binding keeps census publishing inert in the field (#1146).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CensusSinkModule {

    @Binds
    @Singleton
    abstract fun bindCensusSink(impl: NoOpCensusSink): CensusSink
}

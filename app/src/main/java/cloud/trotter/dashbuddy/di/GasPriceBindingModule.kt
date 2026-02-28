package cloud.trotter.dashbuddy.di

import cloud.trotter.dashbuddy.data.gas.GasPriceDataSource
import cloud.trotter.dashbuddy.data.gas.eia.EiaGasPriceDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class GasPriceBindingModule {
    @Binds
    abstract fun bindGasPriceDataSource(
        eiaImpl: EiaGasPriceDataSource
    ): GasPriceDataSource
}
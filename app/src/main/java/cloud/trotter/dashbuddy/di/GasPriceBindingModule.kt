package cloud.trotter.dashbuddy.di

import cloud.trotter.dashbuddy.core.network.fuel.price.eia.EiaFuelPrice
import cloud.trotter.dashbuddy.domain.provider.FuelPriceDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class GasPriceBindingModule {
    @Binds
    abstract fun bindGasPriceDataSource(
        eiaImpl: EiaFuelPrice
    ): FuelPriceDataSource
}
package cloud.trotter.log.gig.core.network.di

import cloud.trotter.log.gig.core.network.fuel.price.eia.EiaFuelPrice
import cloud.trotter.log.gig.domain.provider.FuelPriceDataSource
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
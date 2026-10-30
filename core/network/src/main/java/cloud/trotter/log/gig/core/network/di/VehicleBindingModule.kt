package cloud.trotter.log.gig.core.network.di

import cloud.trotter.log.gig.core.network.vehicle.efficiency.epa.EpaVehicleDataSource
import cloud.trotter.log.gig.domain.provider.VehicleEfficiencyDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class VehicleBindingModule {
    @Binds
    abstract fun bindVehicleEfficiencyDataSource(
        epaImpl: EpaVehicleDataSource
    ): VehicleEfficiencyDataSource
}

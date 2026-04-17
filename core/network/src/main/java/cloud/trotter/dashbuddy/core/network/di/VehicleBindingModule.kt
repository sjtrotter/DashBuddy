package cloud.trotter.dashbuddy.core.network.di

import cloud.trotter.dashbuddy.core.network.vehicle.efficiency.epa.EpaVehicleDataSource
import cloud.trotter.dashbuddy.domain.provider.VehicleEfficiencyDataSource
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

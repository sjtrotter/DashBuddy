package cloud.trotter.dashbuddy.di

import android.app.NotificationManager
import android.content.Context
import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.core.state.EffectExecutor
import cloud.trotter.dashbuddy.core.state.MetadataProvider
import cloud.trotter.dashbuddy.state.effects.SideEffectEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {

    @Binds
    abstract fun bindEffectExecutor(impl: SideEffectEngine): EffectExecutor
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Named("appVersionName")
    fun provideAppVersionName(): String = BuildConfig.VERSION_NAME

    @Provides
    @Singleton
    fun provideMetadataProvider(): MetadataProvider =
        MetadataProvider { DashBuddyApplication.createMetadata() }

    @Provides
    @Singleton
    fun provideNotificationManager(@ApplicationContext context: Context): NotificationManager {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

}
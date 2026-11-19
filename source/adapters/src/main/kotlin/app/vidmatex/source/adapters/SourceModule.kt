package app.vidmatex.source.adapters

import app.vidmatex.core.common.SettingsRepository
import app.vidmatex.core.network.RetrofitFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object SourceModule {

    @Provides @Singleton
    fun provideYouTubeAdapter(factory: RetrofitFactory, settings: SettingsRepository) = YouTubeAdapter(factory, settings)

    @Provides @Singleton
    fun provideFacebookAdapter(factory: RetrofitFactory, settings: SettingsRepository) = FacebookAdapter(factory, settings)

    @Provides @Singleton
    fun provideTikTokAdapter(factory: RetrofitFactory, settings: SettingsRepository) = TikTokAdapter(factory, settings)

    @Provides @Singleton
    fun provideDirectUrlAdapter(client: OkHttpClient) = DirectUrlAdapter(client)
}

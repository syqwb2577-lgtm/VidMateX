package app.vidmatex.feature.settings.security

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the [SecretCipher] used at the DataStore boundary for resolver/Whisper
 * API keys. The Keystore implementation needs no Application context; the same
 * singleton is shared by the settings repository.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsSecurityModule {

    @Provides
    @Singleton
    fun provideSecretCipher(): SecretCipher = AndroidKeystoreSecretCipher()
}

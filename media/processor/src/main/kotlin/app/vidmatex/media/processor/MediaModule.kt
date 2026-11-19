package app.vidmatex.media.processor

import app.vidmatex.core.network.RetrofitFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for :media:processor. Interfaces are bound to their implementations; the
 * concrete translators and the registry are provided explicitly (LibreTranslate is
 * constructed lazily per-settings inside the registry, never as a singleton).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {

    @Binds
    @Singleton
    abstract fun bindSubtitleParser(impl: SubtitleParserImpl): SubtitleParser

    @Binds
    @Singleton
    abstract fun bindFFmpegProcessor(impl: FFmpegProcessorImpl): FFmpegProcessor

    /**
     * STT binding is [SettingsAwareSpeechToText]: reads whisperEndpoint from settings at
     * call time and delegates to a per-call [WhisperSpeechToText] when configured, else
     * [NoOpSpeechToText] (clear recoverable message). NoOpSpeechToText remains available
     * as the fallback implementation.
     */
    @Binds
    @Singleton
    abstract fun bindSpeechToText(impl: SettingsAwareSpeechToText): SpeechToText

    companion object {
        @Provides
        @Singleton
        fun provideMlKitTranslator(): MlKitTranslator = MlKitTranslator()

        @Provides
        @Singleton
        fun provideNoOpTranslator(): NoOpTranslator = NoOpTranslator()

        @Provides
        @Singleton
        fun provideTranslatorRegistry(
            mlKit: MlKitTranslator,
            noOp: NoOpTranslator,
            retrofitFactory: RetrofitFactory,
        ): TranslatorRegistry = TranslatorRegistry(mlKit, noOp, retrofitFactory)
    }
}

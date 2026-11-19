package app.vidmatex

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.vidmatex.transcription.AutoTranscribeObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Implements [Configuration.Provider] so WorkManager is
 * initialised on-demand with [HiltWorkerFactory], which is REQUIRED for @HiltWorker
 * workers (DownloadWorker, EmbedWorker in :feature:downloads) to be constructed by Hilt.
 * The default WorkManagerInitializer is removed via tools:node="remove" in the manifest.
 */
@HiltAndroidApp
class VidMateXApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var autoTranscribeObserver: AutoTranscribeObserver

    override fun onCreate() {
        super.onCreate()
        autoTranscribeObserver.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}

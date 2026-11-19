package app.vidmatex.feature.downloads

import android.app.Application
import androidx.room.Room
import androidx.work.WorkManager
import app.vidmatex.core.common.DownloadManager
import app.vidmatex.core.database.ApiConfigRefDao
import app.vidmatex.core.database.AppDatabase
import app.vidmatex.core.database.DownloadDao
import app.vidmatex.core.database.MediaMetadataDao
import app.vidmatex.core.database.PendingTranscriptionDao
import app.vidmatex.core.database.PlaybackProgressDao
import app.vidmatex.core.database.SubtitleRecordDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {
    @Provides
    @Singleton
    fun provideDatabase(app: Application): AppDatabase =
        Room.databaseBuilder(app, AppDatabase::class.java, AppDatabase.DB_NAME)
            // MIGRATION_1_2: additive `resumable` + `trackKind` columns on `downloads`.
            // MIGRATION_2_3: strictly additive four lookup/history tables
            // (media_metadata, subtitle_records, playback_progress, api_config_refs);
            // the existing `downloads` table is NOT altered (no integrity-verdict column
            // added here — integrity stays in worker state/UI).
            // MIGRATION_3_4: strictly additive — `downloads.orphaned` column + unique
            // index on subtitle_records(mediaId, trackId). User data preserved.
            // MIGRATION_4_5: strictly additive — `pending_transcriptions` table + unique
            // index on dedupKey (durable auto-transcription queue). User data preserved.
            // There is deliberately NO fallbackToDestructiveMigration() anywhere in this
            // builder; a missing registered migration would surface as a Room crash instead
            // of silently wiping user history.
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
            )
            .build()

    @Provides
    fun provideDownloadDao(db: AppDatabase): DownloadDao = db.downloadDao()

    @Provides
    fun provideMediaMetadataDao(db: AppDatabase): MediaMetadataDao = db.mediaMetadataDao()

    @Provides
    fun provideSubtitleRecordDao(db: AppDatabase): SubtitleRecordDao = db.subtitleRecordDao()

    @Provides
    fun providePlaybackProgressDao(db: AppDatabase): PlaybackProgressDao = db.playbackProgressDao()

    @Provides
    fun provideApiConfigRefDao(db: AppDatabase): ApiConfigRefDao = db.apiConfigRefDao()

    @Provides
    fun providePendingTranscriptionDao(db: AppDatabase): PendingTranscriptionDao =
        db.pendingTranscriptionDao()

    @Provides
    @Singleton
    fun provideWorkManager(app: Application): WorkManager = WorkManager.getInstance(app)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadManagerBindsModule {
    @Binds
    @Singleton
    abstract fun bindDownloadManager(impl: DownloadManagerImpl): DownloadManager
}

package app.vidmatex
// =====================================================================================
// DEVICE-GAP MARKER (written, compile-ready, NOT executed in this environment)
// -------------------------------------------------------------------------------------
// No Android emulator/device exists in this sandbox. These tests are source-ready and
// compile-correct; run on a device/API 24–36 emulator with:
//   ./gradlew :app:connectedDebugAndroidTest --tests "app.vidmatex.AppStartupHiltInstrumentedTest"
//
// Covers task item (A) §1: app startup + Hilt DI initialization via HiltAndroidRule.
// Verifies the @HiltAndroidApp VidMateXApplication graph builds, HiltWorkerFactory is
// injectable (required for @HiltWorker DownloadWorker/EmbedWorker), and the on-demand
// WorkManager Configuration.Provider returns a non-null configuration.
// =====================================================================================
import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppStartupHiltInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Test
    fun application_isHiltAndroidApp_andGraphInjects() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(
            "Application must be @HiltAndroidApp VidMateXApplication for Hilt DI + WorkManager on-demand init",
            app is VidMateXApplication,
        )
        hiltRule.inject()
        assertNotNull("HiltWorkerFactory must be injectable (DownloadWorker/EmbedWorker are @HiltWorker)", workerFactory)
    }

    @Test
    fun workManagerConfigurationProvider_buildsNonNullConfig() {
        val app = ApplicationProvider.getApplicationContext<Application>() as VidMateXApplication
        hiltRule.inject()
        // workManagerConfiguration getter uses the injected workerFactory; must not throw.
        val config = app.workManagerConfiguration
        assertNotNull("Configuration.Provider.workManagerConfiguration must be non-null", config)
        // On-demand init: default WorkManagerInitializer is removed in the manifest
        // (tools:node="remove"), so the app-supplied factory is the only one.
        assertTrue(
            "minimumLoggingLevel must be a valid android.util.Log level (2..7)",
            config.minimumLoggingLevel in android.util.Log.VERBOSE..android.util.Log.ASSERT,
        )
    }
}

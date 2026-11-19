package app.vidmatex.worker
// =====================================================================================
// DEVICE-GAP MARKER (written, compile-ready, NOT executed in this environment)
// -------------------------------------------------------------------------------------
// No Android emulator/device exists in this sandbox. These tests are source-ready and
// compile-correct; run on a device/API 24–36 emulator with:
//   ./gradlew :app:connectedDebugAndroidTest --tests "app.vidmatex.worker.WorkManagerInitInstrumentedTest"
//
// Covers task item (A) §4: DownloadWorker + WorkManager initialization via
// WorkManagerTestInitHelper. Verifies the test WorkManager initializes, a
// OneTimeWorkRequest for the @HiltWorker DownloadWorker can be constructed and
// enqueued (state ENQUEUED), and the default initializer is removed (on-demand init
// via VidMateXApplication.Configuration.Provider — asserted in
// AppStartupHiltInstrumentedTest).
// =====================================================================================
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.vidmatex.feature.downloads.DownloadWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkManagerInitInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .build(),
        )
    }

    // No explicit teardown: the test-scoped WorkManager initialized by
    // WorkManagerTestInitHelper is process-scoped; androidx.work:work-testing
    // exposes no public closeTestWorkManager() API.

    @Test
    fun testWorkManager_initializesAndIsSingleton() {
        val wm = WorkManager.getInstance(context)
        assertNotNull("WorkManager must initialize via WorkManagerTestInitHelper", wm)
        assertEquals(
            "WorkManager.getInstance must return the same singleton",
            wm,
            WorkManager.getInstance(context),
        )
    }

    @Test
    fun downloadWorkerRequest_enqueuesWithStateEnqueued() {
        val wm = WorkManager.getInstance(context)
        // DownloadWorker is @HiltWorker; constructing the WorkRequest does NOT
        // instantiate the worker (HiltWorkerFactory does that at run time).
        val request = OneTimeWorkRequestBuilder<DownloadWorker>().build()
        wm.enqueue(request).result.get()
        val info = wm.getWorkInfoById(request.id).get()
        assertNotNull(info)
        assertEquals(
            "Freshly enqueued DownloadWorker must be in ENQUEUED state (constraints unmet until TestDriver marks them)",
            WorkInfo.State.ENQUEUED,
            info.state,
        )
    }

    @Test
    fun testDriver_canMarkConstraintsMet() {
        val wm = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<DownloadWorker>().build()
        wm.enqueue(request).result.get()
        val driver = WorkManagerTestInitHelper.getTestDriver(context)
        assertNotNull("TestDriver must be available after initializeTestWorkManager", driver)
        // Marking constraints met moves the worker toward RUNNING (worker construction
        // needs the HiltWorkerFactory — exercised on a real device with Hilt).
        driver?.setAllConstraintsMet(request.id)
        val info = wm.getWorkInfoById(request.id).get()
        assertNotNull(info)
    }
}

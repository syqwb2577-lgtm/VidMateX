package app.vidmatex.feature.history
// =====================================================================================
// DEVICE-GAP MARKER (written, compile-ready, NOT executed in this environment)
// -------------------------------------------------------------------------------------
// No Android emulator/device exists in this sandbox. These tests are source-ready and
// compile-correct; run on a device/API 24–36 emulator with:
//   ./gradlew :feature:history:connectedDebugAndroidTest
//
// Covers task item (A): feature-module instrumented tests. Renders HistoryScreen with a
// stub DownloadManager (empty history) and asserts the empty-state composable appears.
// Per-state row actions (Retry/Delete/Preview/Open/Share) are covered by the manual
// device guide §9.
// =====================================================================================
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.vidmatex.core.common.AppResult
import app.vidmatex.core.common.DownloadManager
import app.vidmatex.core.model.DownloadItem
import app.vidmatex.core.model.DownloadSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryScreenRenderInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val emptyDownloadManager = object : DownloadManager {
        override fun observeAll(): Flow<List<DownloadItem>> = flowOf(emptyList())
        override fun observe(id: String): Flow<DownloadItem?> = flowOf(null)
        override suspend fun enqueue(spec: DownloadSpec): AppResult<String> = AppResult.Error("stub")
        override suspend fun pause(id: String) {}
        override suspend fun resume(id: String) {}
        override suspend fun cancel(id: String) {}
        override suspend fun retry(id: String) {}
        override suspend fun delete(id: String, deleteFile: Boolean) {}
        override suspend fun clearHistory() {}
    }

    @Test
    fun emptyHistory_rendersEmptyState() {
        val viewModel = HistoryViewModel(emptyDownloadManager, context)
        composeRule.setContent {
            HistoryScreen(viewModel = viewModel, onPreview = null)
        }
        composeRule.waitForIdle()
        val emptyTitle = context.getString(R.string.history_empty_title_all)
        composeRule.onNodeWithText(emptyTitle).assertIsDisplayed()
    }

    @Test
    fun historyScreen_composesWithPreviewCallback() {
        val viewModel = HistoryViewModel(emptyDownloadManager, context)
        var previewInvoked = false
        composeRule.setContent {
            HistoryScreen(
                viewModel = viewModel,
                onPreview = { _, _, _ -> previewInvoked = true },
            )
        }
        composeRule.waitForIdle()
        // Empty history → no preview button; callback must NOT have been invoked.
        assert(!previewInvoked)
    }
}

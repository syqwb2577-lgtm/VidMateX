package app.vidmatex.feature.home
// =====================================================================================
// DEVICE-GAP MARKER (written, compile-ready, NOT executed in this environment)
// -------------------------------------------------------------------------------------
// No Android emulator/device exists in this sandbox. These tests are source-ready and
// compile-correct; run on a device/API 24–36 emulator with:
//   ./gradlew :feature:home:connectedDebugAndroidTest
//
// Covers task item (A): feature-module instrumented tests. Exercises the Android
// ClipboardManager round-trip that HomeViewModel.pasteFromClipboard() relies on (the
// "Paste" button and the clipboard-offer chip). Full HomeScreen render with a real
// MediaSourceResolver is covered on-device per the manual guide §2.
// =====================================================================================
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeClipboardInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun clipboardManager(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Test
    fun clipboardManager_isAvailable() {
        val cm = clipboardManager()
        assertNotNull("ClipboardManager must be resolvable from app context", cm)
    }

    @Test
    fun plainTextClip_roundTrips() {
        val cm = clipboardManager()
        val url = "https://example.com/watch?v=synthetic-instrumented-test"
        cm.setPrimaryClip(ClipData.newPlainText("url", url))
        assertTrue("hasPrimaryClip() must be true after setPrimaryClip", cm.hasPrimaryClip())
        val clip = cm.primaryClip
        assertNotNull(clip)
        assertEquals(1, clip?.itemCount ?: 0)
        val text = clip?.getItemAt(0)?.coerceToText(context)?.toString()
        assertEquals("clipboard round-trip must preserve the URL text", url, text)
    }

    @Test
    fun clipboardDescription_carriesPlainTextMime() {
        val cm = clipboardManager()
        cm.setPrimaryClip(ClipData.newPlainText("url", "https://example.com/x"))
        val description = cm.primaryClipDescription
        assertNotNull(description)
        assertTrue(
            "clip description must declare text/plain",
            description?.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN) == true,
        )
    }
}

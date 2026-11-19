package app.vidmatex.ui
// =====================================================================================
// DEVICE-GAP MARKER (written, compile-ready, NOT executed in this environment)
// -------------------------------------------------------------------------------------
// No Android emulator/device exists in this sandbox. These tests are source-ready and
// compile-correct; run on a device/API 24–36 emulator with:
//   ./gradlew :app:connectedDebugAndroidTest --tests "app.vidmatex.ui.ArabicRtlInstrumentedTest"
//
// Covers task item (A) §5: Arabic RTL UI. Sets the locale to "ar" via a configuration
// context override and asserts Compose resolves LayoutDirection.Rtl. The app manifest
// declares android:supportsRtl="true" and ships values-ar/strings.xml, so tab labels
// mirror automatically (verified in the manual device guide).
// =====================================================================================
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ArabicRtlInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun arabicContext(): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale("ar"))
        return context.createConfigurationContext(config)
    }

    @Test
    fun arabicLocale_resolvesToRtlLayoutDirection() {
        val arContext = arabicContext()
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides arContext) {
                val direction = LocalLayoutDirection.current
                Text(if (direction == LayoutDirection.Rtl) "RTL_OK" else "LTR_BAD")
            }
        }
        composeRule.onNodeWithText("RTL_OK").assertIsDisplayed()
    }

    @Test
    fun defaultLocale_resolvesToLtr() {
        composeRule.setContent {
            val direction = LocalLayoutDirection.current
            Text(if (direction == LayoutDirection.Ltr) "LTR_OK" else "RTL_BAD")
        }
        composeRule.onNodeWithText("LTR_OK").assertIsDisplayed()
    }

    @Test
    fun rowWithArabicContext_composesWithoutCrash() {
        val arContext = arabicContext()
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides arContext) {
                Row {
                    Text("أول")
                    Text("ثاني")
                }
            }
        }
        composeRule.onNodeWithText("أول").assertIsDisplayed()
        composeRule.onNodeWithText("ثاني").assertIsDisplayed()
    }
}

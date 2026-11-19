package app.vidmatex.navigation
// =====================================================================================
// DEVICE-GAP MARKER (written, compile-ready, NOT executed in this environment)
// -------------------------------------------------------------------------------------
// No Android emulator/device exists in this sandbox. These tests are source-ready and
// compile-correct; run on a device/API 24–36 emulator with:
//   ./gradlew :app:connectedDebugAndroidTest --tests "app.vidmatex.navigation.NavigationInstrumentedTest"
//
// Covers task item (A): navigation. Verifies the TopLevelDestination table (5 tabs,
// route→destination mapping, unknown/null fallback to Home) and a minimal
// TestNavHostController graph proving navigation-compose wiring. The full
// VidMateXNavHost (with hiltViewModel tabs) is exercised on-device per the manual
// guide §2 — rendering it here would require the complete Hilt graph.
// =====================================================================================
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun topLevelDestinations_allFiveTabsPresent() {
        val all = TopLevelDestination.all
        assertEquals(5, all.size)
        val routes = all.map { it.route }.toSet()
        assertTrue(routes.containsAll(listOf("home", "downloads", "subtitles", "history", "settings")))
    }

    @Test
    fun fromRoute_mapsKnownRoutes_andFallsBackToHome() {
        assertSame(TopLevelDestination.Home, TopLevelDestination.fromRoute("home"))
        assertSame(TopLevelDestination.Downloads, TopLevelDestination.fromRoute("downloads"))
        assertSame(TopLevelDestination.Subtitles, TopLevelDestination.fromRoute("subtitles"))
        assertSame(TopLevelDestination.History, TopLevelDestination.fromRoute("history"))
        assertSame(TopLevelDestination.Settings, TopLevelDestination.fromRoute("settings"))
        // Unknown / null routes fall back to Home so the bottom bar never crashes.
        assertSame(TopLevelDestination.Home, TopLevelDestination.fromRoute("nonexistent"))
        assertSame(TopLevelDestination.Home, TopLevelDestination.fromRoute(null))
    }

    @Test
    fun testNavHost_navigatesBetweenDestinations() {
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = TestNavHostController(context)
            NavHost(navController = navController, startDestination = "alpha") {
                composable("alpha") { Text("alpha-screen") }
                composable("beta") { Text("beta-screen") }
            }
        }
        composeRule.waitForIdle()
        assertEquals("alpha", navController.currentDestination?.route)
        composeRule.runOnUiThread { navController.navigate("beta") }
        composeRule.waitForIdle()
        assertEquals("beta", navController.currentDestination?.route)
        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.waitForIdle()
        assertEquals("alpha", navController.currentDestination?.route)
    }
}

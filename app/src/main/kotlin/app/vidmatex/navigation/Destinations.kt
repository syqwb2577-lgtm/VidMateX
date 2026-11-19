package app.vidmatex.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.ui.graphics.vector.ImageVector
import app.vidmatex.R

/** Top-level tab destinations. Labels are string resources so RTL/Arabic works automatically. */
sealed class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
) {
    data object Home : TopLevelDestination("home", R.string.nav_home, Icons.Filled.Home)
    data object Downloads : TopLevelDestination("downloads", R.string.nav_downloads, Icons.Filled.Download)
    data object Subtitles : TopLevelDestination("subtitles", R.string.nav_subtitles, Icons.Filled.Subtitles)
    data object History : TopLevelDestination("history", R.string.nav_history, Icons.Filled.History)
    data object Settings : TopLevelDestination("settings", R.string.nav_settings, Icons.Filled.Settings)

    companion object {
        val all: List<TopLevelDestination> = listOf(Home, Downloads, Subtitles, History, Settings)
        fun fromRoute(route: String?): TopLevelDestination =
            all.firstOrNull { it.route == route } ?: Home
    }
}

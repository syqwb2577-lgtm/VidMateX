package app.vidmatex.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import app.vidmatex.feature.downloads.DownloadsScreen
import app.vidmatex.feature.history.HistoryScreen
import app.vidmatex.feature.home.HomeScreen
import app.vidmatex.feature.settings.SettingsScreen
import app.vidmatex.feature.subtitles.SubtitlesScreen
import app.vidmatex.player.VideoPreviewScreen

/** A pending in-app preview (local file URI or allowed http(s) URL + display title).
 *  [subtitlePath] carries an optional downloaded sidecar subtitle (SRT/VTT) so the
 *  preview player can render captions. */
private data class PreviewRequest(
    val uri: String,
    val title: String?,
    val subtitlePath: String? = null,
    val subtitleMime: String? = null,
)

/**
 * Top-level navigation host.
 *
 * Uses [AnimatedContent] (fade crossfade) between tabs instead of a plain NavHost so tab
 * switches are animated on navigation-compose 2.7.x (which lacks AnimatedNavHost). A
 * [rememberNavController] is still created and reserved for future in-tab sub-navigation.
 * RTL is handled automatically by Compose layout; Arabic strings live in values-ar/.
 *
 * Destinations covered: Home, Downloads, Subtitles, History, Settings (bottom tabs) and
 * VideoPreview (full-screen overlay shown when the user taps Preview on a completed
 * download from Downloads or History; the row's `subtitlePath` is forwarded when present).
 */
@Composable
fun VidMateXNavHost() {
    val navController = rememberNavController()
    var current by remember { mutableStateOf<TopLevelDestination>(TopLevelDestination.Home) }
    var preview by remember { mutableStateOf<PreviewRequest?>(null) }

    // Downloads rows carry (uri, title, subtitlePath?); the optional sidecar subtitle lets
    // the in-app preview render captions. MIME is inferred from the file extension by the
    // player, so no app-level callback signature change is required (G-4).
    val onPreviewDownload: (String, String?, String?) -> Unit = { uri, title, subtitlePath ->
        preview = PreviewRequest(uri = uri, title = title, subtitlePath = subtitlePath)
    }
    val onPreviewHistory: (String?, String?, String?) -> Unit = { uri, title, subtitlePath ->
        if (!uri.isNullOrBlank()) {
            preview = PreviewRequest(uri = uri, title = title, subtitlePath = subtitlePath)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.all.forEach { dest ->
                    NavigationBarItem(
                        selected = current.route == dest.route,
                        onClick = { current = dest },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(stringResourceSafe(dest.labelRes)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "tab_crossfade",
            modifier = Modifier.padding(innerPadding)
        ) { destination ->
            when (destination) {
                TopLevelDestination.Home -> HomeScreen(viewModel = hiltViewModel())
                TopLevelDestination.Downloads -> DownloadsScreen(viewModel = hiltViewModel(), onPreview = onPreviewDownload)
                TopLevelDestination.Subtitles -> SubtitlesScreen(
                    viewModel = hiltViewModel(),
                    // C1: "Open Settings → Whisper endpoint" switches to the Settings tab.
                    onOpenSettings = { current = TopLevelDestination.Settings },
                )
                TopLevelDestination.History -> HistoryScreen(viewModel = hiltViewModel(), onPreview = onPreviewHistory)
                TopLevelDestination.Settings -> SettingsScreen(viewModel = hiltViewModel())
            }
        }
    }
    // Full-screen preview overlay (covers tabs + bottom bar).
    preview?.let { req ->
        VideoPreviewScreen(
            mediaUri = req.uri,
            title = req.title,
            subtitlePath = req.subtitlePath,
            subtitleMime = req.subtitleMime,
            onClose = { preview = null },
        )
    }
}

@Composable
private fun stringResourceSafe(resId: Int): String =
    androidx.compose.ui.res.stringResource(resId)

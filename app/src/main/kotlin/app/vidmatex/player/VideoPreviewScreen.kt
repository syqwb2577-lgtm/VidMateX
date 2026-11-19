package app.vidmatex.player

import android.content.Context
import android.view.ViewGroup
import kotlin.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.vidmatex.R

/**
 * In-app preview player (§3) for a completed download (local `file://`/`content://` URI)
 * or an allowed http(s) direct stream. Every visible control drives
 * [Media3PlayerController] real logic:
 *  - play/pause, seek ∓10 s, absolute seek bar;
 *  - speed ladder menu (0.25x–2x) applied via `PlaybackParameters`;
 *  - audio-track and subtitle-track selectors populated from the controller's track seam;
 *  - captions on/off toggle; caption delay +/- (half-second steps) through [SubtitleDelay];
 *  - typed error card (network/timeout/unsupported/permission/file/unknown) and buffering
 *    spinner from [Media3PlayerController.PlayerState].
 *
 * Lifecycle: ON_STOP pauses; screen dispose releases the controller (position saved to
 * the [PositionStore]). TRUE immersive fullscreen (system-bar hiding, sensor orientation
 * lock) is device-verification-pending; the toggle here maximizes the Compose surface.
 *
 * @param mediaUri local file/content URI or https stream URL string
 * @param title display title shown in the app bar
 * @param subtitlePath optional sidecar SRT/VTT path/URI
 * @param subtitleMime optional explicit subtitle MIME; inferred from extension when null
 * @param subtitleLanguage optional ISO language tag for the caption track
 * @param onClose callback when the user dismisses the preview
 */
@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VideoPreviewScreen(
    mediaUri: String,
    title: String?,
    onClose: () -> Unit,
    subtitlePath: String? = null,
    subtitleMime: String? = null,
    subtitleLanguage: String? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { Media3PlayerController(context) }
    val state by controller.state.collectAsStateWithLifecycle()

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    // Volume initialised from the controller state so a restored level is reflected.
    var volume by remember { mutableFloatStateOf(controller.state.value.volume) }
    var retryKey by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var audioMenuExpanded by remember { mutableStateOf(false) }
    var subtitleMenuExpanded by remember { mutableStateOf(false) }
    val activity = remember(context) { context.findActivity() }
    // True immersive fullscreen (system-bar hiding) is device-verification-pending; here we
    // maximize the Compose surface and lock orientation from [OrientationPolicy] via a real
    // Activity call (portrait video stays portrait, wide/long-form leans landscape).
    fun applyFullscreen(enabled: Boolean) {
        fullscreen = enabled
        if (enabled) {
            val rec = OrientationPolicy.recommend(state.videoWidthPx, state.videoHeightPx, state.durationMs)
            activity?.requestedOrientation = if (rec.preferLandscape)
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Prepare the media (and optional sidecar) once per key; retryKey re-prepares after error.
    LaunchedEffect(mediaUri, subtitlePath, subtitleMime, retryKey) {
        controller.prepare(
            url = mediaUri,
            subtitleUri = subtitlePath,
            subtitleMime = subtitleMime,
            label = title,
            subtitleLanguage = subtitleLanguage,
            playWhenReady = true,
        )
    }

    // Poll the position for the seek bar; the controller persists throttled saves internally.
    LaunchedEffect(Unit) {
        while (true) {
            positionMs = controller.currentPositionMs()
            durationMs = controller.durationMs()
            kotlinx.coroutines.delay(500)
        }
    }

    // Pause on ON_STOP; release (final position save + ExoPlayer.release) on dispose.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.release()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.preview_title),
                        maxLines = 1,
                    )
                },
                actions = {
                    IconButton(onClick = { applyFullscreen(!fullscreen) }) {
                        Icon(
                            if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = stringResource(
                                if (fullscreen) R.string.preview_fullscreen_exit
                                else R.string.preview_fullscreen
                            ),
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.preview_close))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (fullscreen) Modifier.fillMaxSize() else Modifier.height(240.dp))
                    .padding(8.dp),
            ) {
                AndroidView(
                    factory = { ctx: Context ->
                        PlayerView(ctx).apply {
                            player = controller.player()
                            useController = false // custom controls below drive the controller
                            subtitleView?.visibility = android.view.View.VISIBLE
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (state.isBuffering && state.error == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.error?.let { error ->
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                errorCategoryLabel(error),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { retryKey++ }) {
                                    Icon(Icons.Default.Replay10, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_retry))
                                }
                                TextButton(onClick = controller::clearError) {
                                    Text(stringResource(R.string.preview_error_dismiss))
                                }
                            }
                        }
                    }
                }
            }

            // Transport row: seek back, play/pause, seek forward, speed, tracks, captions.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { controller.seekBy(-10_000L) }) {
                    Icon(Icons.Default.Replay10, contentDescription = stringResource(R.string.preview_seek_back))
                }
                IconButton(onClick = { if (state.isPlaying) controller.pause() else controller.play() }) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying)
                            stringResource(R.string.preview_pause)
                        else
                            stringResource(R.string.preview_play),
                        modifier = Modifier.width(36.dp).height(36.dp),
                    )
                }
                IconButton(onClick = { controller.seekBy(10_000L) }) {
                    Icon(Icons.Default.Forward10, contentDescription = stringResource(R.string.preview_seek_forward))
                }

                // Speed menu (real PlaybackParameters rate).
                Box {
                    TextButton(onClick = { speedMenuExpanded = true }) {
                        Text(PlaybackSpeed.label(state.speed))
                    }
                    DropdownMenu(expanded = speedMenuExpanded, onDismissRequest = { speedMenuExpanded = false }) {
                        PlaybackSpeed.LADDER.forEach { rate ->
                            DropdownMenuItem(
                                text = { Text(PlaybackSpeed.label(rate)) },
                                onClick = {
                                    controller.setSpeed(rate)
                                    speedMenuExpanded = false
                                },
                                trailingIcon = {
                                    if (kotlin.math.abs(rate - state.speed) < 0.0001f) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                            )
                        }
                    }
                }

                // Audio track selector (populated from the track seam; disabled when empty).
                Box {
                    TextButton(
                        onClick = { if (state.audioTracks.isNotEmpty()) audioMenuExpanded = true },
                        enabled = state.audioTracks.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.preview_audio_track))
                    }
                    DropdownMenu(expanded = audioMenuExpanded, onDismissRequest = { audioMenuExpanded = false }) {
                        state.audioTracks.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(TrackSelectionPolicy.displayLabel(option)) },
                                onClick = {
                                    controller.selectAudioTrack(option.key)
                                    audioMenuExpanded = false
                                },
                                trailingIcon = {
                                    if (option.selected) Icon(Icons.Default.Check, contentDescription = null)
                                },
                            )
                        }
                    }
                }

                // Subtitle track selector + Off.
                Box {
                    TextButton(
                        onClick = { if (state.subtitleTracks.isNotEmpty()) subtitleMenuExpanded = true },
                        enabled = state.subtitleTracks.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Subtitles, contentDescription = stringResource(R.string.preview_subtitle_track))
                    }
                    DropdownMenu(expanded = subtitleMenuExpanded, onDismissRequest = { subtitleMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.preview_track_off)) },
                            onClick = {
                                controller.disableSubtitles()
                                subtitleMenuExpanded = false
                            },
                            trailingIcon = {
                                if (TrackSelectionPolicy.selectedIndex(state.subtitleTracks) == TrackSelectionPolicy.OFF_INDEX) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            },
                        )
                        state.subtitleTracks.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(TrackSelectionPolicy.displayLabel(option)) },
                                onClick = {
                                    controller.selectSubtitleTrack(option.key)
                                    subtitleMenuExpanded = false
                                },
                                trailingIcon = {
                                    if (option.selected) Icon(Icons.Default.Check, contentDescription = null)
                                },
                            )
                        }
                    }
                }

                // Global captions toggle; only meaningful when text tracks exist.
                IconButton(
                    onClick = { controller.setCaptionsEnabled(!state.captionsEnabled) },
                    enabled = state.subtitleTracks.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Default.Subtitles,
                        contentDescription = if (state.captionsEnabled)
                            stringResource(R.string.preview_cc_on)
                        else
                            stringResource(R.string.preview_cc_off),
                        tint = if (state.captionsEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Subtitle delay row (real SubtitleDelay clamp/format; state + restore entry).
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.preview_delay_label),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(88.dp),
                )
                IconButton(
                    onClick = { controller.setSubtitleDelayMs(state.subtitleDelayMs - SubtitleDelay.STEP_MS) },
                    enabled = state.subtitleDelayMs > SubtitleDelay.MIN_DELAY_MS,
                ) {
                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.preview_delay_minus))
                }
                Text(
                    SubtitleDelay.formatSigned(state.subtitleDelayMs),
                    style = MaterialTheme.typography.bodySmall,
                )
                IconButton(
                    onClick = { controller.setSubtitleDelayMs(state.subtitleDelayMs + SubtitleDelay.STEP_MS) },
                    enabled = state.subtitleDelayMs < SubtitleDelay.MAX_DELAY_MS,
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.preview_delay_plus))
                }
                if (state.subtitleDelayMs != 0L) {
                    TextButton(onClick = { controller.setSubtitleDelayMs(0L) }) {
                        Text(stringResource(R.string.preview_delay_reset))
                    }
                }
            }

            // Seek bar.
            if (durationMs > 0L) {
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                    onValueChange = { controller.seekTo(it.toLong()) },
                    valueRange = 0f..durationMs.toFloat(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // Volume.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.preview_volume),
                    Modifier.width(60.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = volume,
                    onValueChange = { v ->
                        volume = v
                        controller.setVolume(v)
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
            }

            if (state.subtitleTracks.isEmpty() && state.audioTracks.isEmpty()) {
                Text(
                    stringResource(R.string.preview_tracks_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else if (state.subtitleTracks.isNotEmpty()) {
                Text(
                    stringResource(R.string.preview_subtitle_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Map a typed [PlayerError] category to a localized, user-facing sentence. */
@Composable
private fun errorCategoryLabel(error: PlayerError): String = when (error.category) {
    PlayerErrorCategory.NETWORK -> stringResource(R.string.player_error_network)
    PlayerErrorCategory.TIMEOUT -> stringResource(R.string.player_error_timeout)
    PlayerErrorCategory.UNSUPPORTED_FORMAT -> stringResource(R.string.player_error_unsupported)
    PlayerErrorCategory.PERMISSION -> stringResource(R.string.player_error_permission)
    PlayerErrorCategory.FILE_MISSING -> stringResource(R.string.player_error_file_missing)
    PlayerErrorCategory.UNKNOWN -> stringResource(R.string.player_error_unknown)
}

/** Walk the context-wrapper chain to find the host Activity (for orientation requests). */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

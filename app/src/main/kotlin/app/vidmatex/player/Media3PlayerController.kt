package app.vidmatex.player

import android.content.Context
import android.net.Uri
import kotlin.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Integration wrapper around [ExoPlayer] for in-app preview playback (§3): local files
 * (`file://`, `content://`) and allowed https streams. UI-free; the Compose layer
 * observes [state] and calls the documented methods.
 *
 * Lifecycle contract (driven by `VideoPreviewScreen`):
 *  - `ON_STOP`  → UI calls [pause] (playback halts, position retained);
 *  - `ON_PAUSE` → playback remains paused; the save ticker stays alive until dispose;
 *  - dispose    → UI calls [release], which cancels the scope, saves the final position
 *                 through the [PositionStore], removes the listener and releases ExoPlayer.
 *
 * Subtitle delay note: Media3 1.3.1 exposes no public text-renderer offset API, so the
 * chosen delay is held in state, applied through [SubtitleDelay.shiftCue] to parsed cue
 * timing where a sidecar parser feeds cues, and restored with the position entry.
 * Renderer-level live offset on a decoded embedded track remains device-verified-pending
 * (see `app/AUDIT_CHECKLIST.md` §player).
 */
@OptIn(UnstableApi::class)
class Media3PlayerController(
    context: Context,
    private val positionStore: PositionStore = InMemoryPositionStore(),
) {
    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var currentMediaId: String? = null
    private var lastSavedPositionMs = 0L
    private var saveJobStarted = false
    @Volatile private var released = false

    /** Snapshot of Media3 groups used to resolve selection overrides. */
    private var audioGroups: List<Tracks.Group> = emptyList()
    private var textGroups: List<Tracks.Group> = emptyList()
    /** Stable option key → owning group, so multi-group track selection resolves correctly. */
    private var audioGroupByKey: Map<String, Tracks.Group> = emptyMap()
    private var textGroupByKey: Map<String, Tracks.Group> = emptyMap()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            _state.value = _state.value.copy(isBuffering = isLoading)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = _state.value.copy(
                isBuffering = playbackState == Player.STATE_BUFFERING,
                isReady = playbackState == Player.STATE_READY,
                isEnded = playbackState == Player.STATE_ENDED,
                durationMs = exoPlayer.duration.coerceAtLeast(0L),
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
            )
            if (playbackState == Player.STATE_ENDED) {
                markCompleted()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(
                isBuffering = false,
                error = PlayerErrorMapper.map(
                    errorCode = error.errorCode,
                    errorCodeName = error.errorCodeName,
                    reason = error.localizedMessage ?: error.message,
                ),
            )
        }

        override fun onTracksChanged(tracks: Tracks) {
            val (audio, text, audioMap, textMap) = tracks.toSeamOptions()
            audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            audioGroupByKey = audioMap
            textGroupByKey = textMap
            _state.value = _state.value.copy(audioTracks = audio, subtitleTracks = text)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            _state.value = _state.value.copy(
                videoWidthPx = videoSize.width,
                videoHeightPx = videoSize.height,
            )
        }
    }

    init {
        exoPlayer.addListener(listener)
        exoPlayer.playWhenReady = false
    }

    /**
     * Prepare playback of [url] (local `file:`/`content:` URI or https stream) with an
     * optional SRT/VTT sidecar. When [mediaId] matches a saved resume entry, position,
     * speed and subtitle delay are restored; completed entries are not resumed.
     */
    fun prepare(
        url: String,
        subtitleUri: String? = null,
        subtitleMime: String? = null,
        label: String? = null,
        subtitleLanguage: String? = null,
        playWhenReady: Boolean = true,
        mediaId: String? = null,
    ) {
        currentMediaId = mediaId ?: url
        lastSavedPositionMs = 0L
        _state.value = PlayerState(
            captionsEnabled = _state.value.captionsEnabled,
            speed = _state.value.speed,
            subtitleDelayMs = _state.value.subtitleDelayMs,
            volume = _state.value.volume,
        )
        val builder = MediaItem.Builder().setUri(url)
        if (!subtitleUri.isNullOrBlank()) {
            val mime = subtitleMime?.takeIf { it.isNotBlank() } ?: inferSubtitleMime(subtitleUri)
            if (mime != null) {
                val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUri))
                    .setMimeType(mime)
                    .apply { if (!subtitleLanguage.isNullOrBlank()) setLanguage(subtitleLanguage) }
                    .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                    .build()
                builder.setSubtitleConfigurations(listOf(subtitleConfig))
            }
        }
        exoPlayer.setMediaItem(builder.build())
        exoPlayer.playWhenReady = playWhenReady
        exoPlayer.prepare()
        startSaveTicker()
        scope.launch { restoreSavedState() }
    }

    private suspend fun restoreSavedState() {
        val id = currentMediaId ?: return
        val entry = positionStore.load(id) ?: return
        if (entry.completed) return
        if (entry.positionMs > 0L) exoPlayer.seekTo(entry.positionMs)
        if (entry.speed > 0f) applySpeed(PlaybackSpeed.nearestSupported(entry.speed))
        if (entry.subtitleDelayMs != 0L) {
            _state.value = _state.value.copy(subtitleDelayMs = entry.subtitleDelayMs)
        }
    }

    // --- Transport controls -------------------------------------------------

    fun play() {
        if (released) return
        exoPlayer.play()
    }

    fun pause() {
        if (released) return
        exoPlayer.pause()
    }

    /** Resume playback from the current position (alias of [play]). */
    fun resume() = play()

    /** Stop playback and clear the render surface; the player instance remains reusable. */
    fun stop() {
        if (released) return
        exoPlayer.stop()
        _state.value = _state.value.copy(
            isPlaying = false,
            isBuffering = false,
            isReady = false,
            positionMs = 0L,
            durationMs = 0L,
        )
    }

    /** Relative seek; delta may be negative. Clamped to `0..duration`. */
    fun seekBy(deltaMs: Long) {
        val target = (exoPlayer.currentPosition + deltaMs)
            .coerceIn(0L, exoPlayer.duration.coerceAtLeast(0L))
        seekTo(target)
    }

    /** Absolute seek in milliseconds, clamped to the known duration. */
    fun seekTo(positionMs: Long) {
        if (released) return
        val clamped = positionMs.coerceIn(0L, exoPlayer.duration.coerceAtLeast(0L))
        exoPlayer.seekTo(clamped)
        _state.value = _state.value.copy(positionMs = clamped)
    }

    fun currentPositionMs(): Long = exoPlayer.currentPosition.coerceAtLeast(0L)
    fun durationMs(): Long = exoPlayer.duration.coerceAtLeast(0L)

    // --- Speed --------------------------------------------------------------

    /** Apply a playback speed; non-ladder rates snap to the nearest supported step. */
    fun setSpeed(rate: Float) {
        if (released) return
        applySpeed(PlaybackSpeed.nearestSupported(rate))
    }

    private fun applySpeed(rate: Float) {
        exoPlayer.playbackParameters = PlaybackParameters(rate)
        _state.value = _state.value.copy(speed = rate)
    }

    // --- Volume -------------------------------------------------------------
    /** Set the linear player volume (0f..1f, clamped via [PlayerVolume]); device volume untouched. */
    fun setVolume(volume: Float) {
        if (released) return
        val clamped = PlayerVolume.clamp(volume)
        exoPlayer.volume = clamped
        _state.value = _state.value.copy(volume = clamped)
    }

    // --- Tracks (audio + subtitle seams) ------------------------------------

    /** Exposed audio tracks from the current [Tracks] snapshot (empty before tracks load). */
    fun audioTracks(): List<TrackOption> = _state.value.audioTracks

    /** Exposed subtitle/caption tracks (sidecar + embedded text formats). */
    fun subtitleTracks(): List<TrackOption> = _state.value.subtitleTracks

    /** Select an audio track by its stable [TrackOption.key]; unknown/unsupported keys ignored. */
    fun selectAudioTrack(key: String) {
        if (released) return
        val option = _state.value.audioTracks.firstOrNull { it.key == key } ?: return
        if (!option.supported) return
        val group = audioGroupByKey[key] ?: return
        // Keyed selection state: with multiple audio groups the same `index` can recur,
        // so the UI checkmark is resolved by stable key, not by within-group index.
        val updated = TrackSelectionPolicy.selectByKey(_state.value.audioTracks, key)
        _state.value = _state.value.copy(audioTracks = updated)
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.index))
            .build()
    }

    /**
     * Disable captions: clear all text-track overrides and disable the text renderer.
     * The tracks remain loaded; re-selecting one via [selectSubtitleTrack] re-enables.
     */
    fun disableSubtitles() {
        if (released) return
        val params = exoPlayer.trackSelectionParameters.buildUpon()
        textGroups.forEach { params.clearOverride(it.mediaTrackGroup) }
        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        _state.value = _state.value.copy(
            subtitleTracks = TrackSelectionPolicy.select(
                _state.value.subtitleTracks,
                TrackSelectionPolicy.OFF_INDEX,
            ),
            captionsEnabled = false,
        )
        exoPlayer.trackSelectionParameters = params.build()
    }

    /**
     * Select a subtitle/caption track by its stable [TrackOption.key]; unknown/unsupported
     * keys are ignored. Call [disableSubtitles] to turn captions off.
     */
    fun selectSubtitleTrack(key: String) {
        if (released) return
        val option = _state.value.subtitleTracks.firstOrNull { it.key == key } ?: return
        if (!option.supported) return
        val group = textGroupByKey[key] ?: return
        val params = exoPlayer.trackSelectionParameters.buildUpon()
        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.index))
        _state.value = _state.value.copy(
            subtitleTracks = TrackSelectionPolicy.selectByKey(_state.value.subtitleTracks, key),
            captionsEnabled = true,
        )
        exoPlayer.trackSelectionParameters = params.build()
    }

    /** Global captions on/off; the track remains loaded but unselected when disabled. */
    fun setCaptionsEnabled(enabled: Boolean) {
        if (released) return
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
        _state.value = _state.value.copy(captionsEnabled = enabled)
    }

    // --- Subtitle delay ------------------------------------------------------

    /**
     * Set the caption delay in milliseconds (clamped by [SubtitleDelay]). State and the
     * resume entry carry the value; cue-level shifting uses [SubtitleDelay.shiftCue].
     */
    fun setSubtitleDelayMs(delayMs: Long) {
        val clamped = SubtitleDelay.clamp(delayMs)
        _state.value = _state.value.copy(subtitleDelayMs = clamped)
    }

    // --- Position persistence ------------------------------------------------

    private fun startSaveTicker() {
        if (saveJobStarted) return
        saveJobStarted = true
        scope.launch {
            while (true) {
                delay(1_000L)
                val position = exoPlayer.currentPosition.coerceAtLeast(0L)
                val duration = exoPlayer.duration.coerceAtLeast(0L)
                if (position > 0L &&
                    PositionSavePolicy.shouldSave(position, lastSavedPositionMs, duration)
                ) {
                    lastSavedPositionMs = position
                    persistPosition(completed = PositionSavePolicy.isComplete(position, duration))
                }
            }
        }
    }

    private fun persistPosition(completed: Boolean) {
        val id = currentMediaId ?: return
        scope.launch { positionStore.save(currentEntry(completed)) }
        scope.launch { positionStore.prune(System.currentTimeMillis()) }
    }

    private suspend fun currentEntry(completed: Boolean): PositionEntry {
        val id = currentMediaId ?: error("prepare() must run before persistence")
        return PositionEntry(
            mediaId = id,
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
            durationMs = exoPlayer.duration.coerceAtLeast(0L),
            completed = completed,
            updatedAtMs = System.currentTimeMillis(),
            speed = _state.value.speed,
            subtitleDelayMs = _state.value.subtitleDelayMs,
        )
    }

    private fun markCompleted() {
        val id = currentMediaId ?: return
        val duration = exoPlayer.duration.coerceAtLeast(0L)
        scope.launch { positionStore.markCompleted(id, duration, System.currentTimeMillis()) }
    }

    /** Read-only access to the underlying player for the PlayerView host (UI layer). */
    fun player(): ExoPlayer = exoPlayer

    /** Dismiss a surfaced error after the user taps Dismiss; playback state untouched. */
    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /** Full teardown: save final position, cancel scope, release ExoPlayer. Idempotent. */
    fun release() {
        if (released) return
        released = true
        // Save synchronously before cancelling: the default in-memory store performs a
        // fast map write; the Room adapter (cross-module injection) must move this call
        // off the main dispatcher once the DAO is provided. runBlocking is acceptable
        // here because release() is a terminal, synchronous teardown (the UI calls it
        // from onDispose on the main thread and must not return before ExoPlayer is
        // released); the default store is non-blocking. Errors are swallowed so a
        // failing persistence write never prevents player release.
        runCatching {
            kotlinx.coroutines.runBlocking {
                currentMediaId?.let { positionStore.save(currentEntry(_state.value.isEnded)) }
            }
        }
        job.cancel()
        exoPlayer.removeListener(listener)
        exoPlayer.release()
    }

    // --- Internals -----------------------------------------------------------

    private data class SeamSnapshot(
        val audio: List<TrackOption>,
        val text: List<TrackOption>,
        val audioGroupByKey: Map<String, Tracks.Group>,
        val textGroupByKey: Map<String, Tracks.Group>,
    )

    private fun Tracks.toSeamOptions(): SeamSnapshot {
        val audio = mutableListOf<TrackOption>()
        val text = mutableListOf<TrackOption>()
        val audioMap = mutableMapOf<String, Tracks.Group>()
        val textMap = mutableMapOf<String, Tracks.Group>()
        var audioGroupIndex = 0
        var textGroupIndex = 0
        groups.forEach { group ->
            val (groupIndex, kind) = when (group.type) {
                C.TRACK_TYPE_AUDIO -> (audioGroupIndex++) to "audio"
                C.TRACK_TYPE_TEXT -> (textGroupIndex++) to "text"
                else -> return@forEach
            }
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = format.label?.takeIf { it.isNotBlank() }
                    ?: format.language?.takeIf { it.isNotBlank() }
                    ?: "Track ${i + 1}"
                // Unique key includes the group ordinal so two groups each exposing
                // track index 0 do not collide (fixes multi-group selection).
                val key = "$kind-g$groupIndex-t$i"
                val option = TrackOption(
                    key = key,
                    index = i,
                    type = if (group.type == C.TRACK_TYPE_AUDIO) SeamTrackType.AUDIO
                    else SeamTrackType.SUBTITLE,
                    label = label,
                    language = format.language,
                    selected = group.isTrackSelected(i),
                    supported = group.isTrackSupported(i),
                )
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> { audio += option; audioMap[key] = group }
                    C.TRACK_TYPE_TEXT -> { text += option; textMap[key] = group }
                }
            }
        }
        return SeamSnapshot(audio, text, audioMap, textMap)
    }

    /** Infer the sidecar caption MIME from the extension; unsupported extensions yield null. */
    private fun inferSubtitleMime(path: String): String? = when {
        path.endsWith(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
        path.endsWith(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
        else -> null
    }

    /** Snapshot of the transport + track UI state. */
    data class PlayerState(
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val isReady: Boolean = false,
        val isEnded: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val speed: Float = PlaybackSpeed.NORMAL_RATE,
        val captionsEnabled: Boolean = true,
        val subtitleDelayMs: Long = 0L,
        val audioTracks: List<TrackOption> = emptyList(),
        val subtitleTracks: List<TrackOption> = emptyList(),
        /** Linear player volume 0f..1f (device volume untouched); driven by [setVolume]. */
        val volume: Float = PlayerVolume.DEFAULT,
        /** Video track width/height in pixels (0 until the renderer reports a size). */
        val videoWidthPx: Int = 0,
        val videoHeightPx: Int = 0,
        val error: PlayerError? = null,
    )
}

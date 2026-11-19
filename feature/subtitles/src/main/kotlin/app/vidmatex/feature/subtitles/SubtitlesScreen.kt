package app.vidmatex.feature.subtitles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vidmatex.core.model.DownloadMode
import app.vidmatex.core.model.EmbedMode
import app.vidmatex.core.model.EmbedStyle
import app.vidmatex.core.model.SubtitleFormat
import app.vidmatex.core.model.SubtitleKind
import app.vidmatex.feature.subtitles.transcription.SourceLanguages
import app.vidmatex.feature.subtitles.transcription.TranscriptionState
import app.vidmatex.feature.subtitles.transcription.TranscriptionWorker

/** ASS &HAABBGGRR -> Compose Color. Invalid strings fall back to [default]. */
internal fun assColor(ass: String, default: Color): Color {
    val hex = ass.removePrefix("&H").padStart(8, '0')
    return try {
        val a = hex.substring(0, 2).toInt(16)
        val b = hex.substring(2, 4).toInt(16)
        val g = hex.substring(4, 6).toInt(16)
        val r = hex.substring(6, 8).toInt(16)
        Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = a / 255f)
    } catch (e: Exception) {
        default
    }
}

private fun langLabelRes(code: String): Int = when (code) {
    "ar" -> R.string.subtitles_lang_ar
    "en" -> R.string.subtitles_lang_en
    "fr" -> R.string.subtitles_lang_fr
    "es" -> R.string.subtitles_lang_es
    "de" -> R.string.subtitles_lang_de
    "tr" -> R.string.subtitles_lang_tr
    "zh" -> R.string.subtitles_lang_zh
    else -> R.string.subtitles_lang_en
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitlesScreen(
    viewModel: SubtitlesViewModel = hiltViewModel(),
    /** Optional navigation hook to the Settings tab (wired by the nav host; G shard). */
    onOpenSettings: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Path (a): system file picker for audio/video; grant URI copied to app cache by VM.
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onAudioUriPicked(uri, state.sourceLanguage)
    }
    // Standalone subtitle sidecar picker (.srt/.vtt/.ass); extension validated in VM.
    val subtitlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onSubtitleUriPicked(uri)
    }

    val retryLabel = stringResource(R.string.subtitles_error_retry)
    // G-3: error snackbar with a Retry action that re-runs the last identifiable
    // operation (resolve / refetch track / download); timeout dismissal clears the message.
    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = retryLabel,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.retryLastError()
        } else {
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.downloadEnqueued) {
        if (state.downloadEnqueued) {
            snackbarHostState.showSnackbar(context.getString(R.string.subtitles_download_queued))
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.subtitles_title),
                style = MaterialTheme.typography.headlineSmall,
            )

            // ---- Step 1: link input + resolve
            LinkInputSection(
                url = state.inputUrl,
                isResolving = state.isResolving,
                onUrlChange = viewModel::onUrlChange,
                onResolve = viewModel::resolve,
                onClear = viewModel::reset,
            )

            state.metadata?.let { metadata ->
                Text(
                    text = metadata.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // ---- Step 1b: subtitle track list (never mislabel automatic)
            if (state.subtitleTracks.isNotEmpty()) {
                TrackListSection(
                    tracks = state.subtitleTracks,
                    selectedId = state.selectedTrack?.trackId,
                    onSelect = viewModel::selectTrack,
                )
            } else if (state.metadata != null && !state.isResolving) {
                InfoCard(text = stringResource(R.string.subtitles_no_tracks))
            }

            // ---- Whisper transcription: language picker, file picker, progress/error ----
            TranscriptionSection(
                state = state,
                onPickFile = { audioPicker.launch(arrayOf("audio/*", "video/*")) },
                onSourceLanguage = viewModel::setSourceLanguage,
                onRetry = viewModel::retryTranscription,
                onDismissError = viewModel::dismissTranscriptionError,
                onCancel = viewModel::cancelTranscription,
                onOpenSettings = onOpenSettings,
            )
            // Soft config warning (e.g. no API key for a keyless self-hosted endpoint).
            state.transcriptionKeyWarning?.let { warning -> InfoCard(text = warning) }

            // ---- Standalone subtitle import (SRT/VTT/ASS, provenance HUMAN) ----
            SubtitleImportSection(
                importedName = state.importedTrackName,
                onImport = {
                    subtitlePicker.launch(
                        arrayOf("text/*", "application/x-subrip", "text/vtt", "application/octet-stream"),
                    )
                },
            )

            // ---- Completed-download hook: newest local audio/media file ready to transcribe ----
            state.completedAudioPath?.let { path ->
                CompletedAudioCard(
                    path = path,
                    onTranscribe = viewModel::transcribeDownloadedAudio,
                )
            }

            // ---- Step 2: preview pane (original + translated side by side when both exist)
            if (state.isFetchingSubtitle) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.subtitles_fetching_subtitle))
                }
            }
            if (state.cues.isNotEmpty()) {
                PreviewSection(
                    cues = state.cues.take(10),
                    shown = 10.coerceAtMost(state.cues.size),
                    total = state.cues.size,
                    label = stringResource(R.string.subtitles_preview_original),
                    editingEnabled = true,
                    onEditCue = viewModel::setCueOverride,
                )
            }
            val translatedCues = state.translatedCues
            if (translatedCues != null && state.translateEnabled) {
                PreviewSection(
                    cues = translatedCues.take(10),
                    shown = 10.coerceAtMost(translatedCues.size),
                    total = translatedCues.size,
                    label = stringResource(R.string.subtitles_preview_translated, state.targetLanguage.uppercase()),
                )
            }

            HorizontalDivider()

            // ---- Step 3: format + translation
            FormatSection(
                format = state.subtitleFormat,
                onFormat = viewModel::setFormat,
            )

            TranslationSection(
                enabled = state.translateEnabled,
                targetLanguage = state.targetLanguage,
                providerConfigured = state.providerConfigured,
                isTranslating = state.isTranslating,
                translationMessage = state.translationMessage,
                hasCues = state.totalCues > 0,
                saveOriginalSidecar = state.saveOriginalSubtitleSidecar,
                onToggle = viewModel::setTranslateEnabled,
                onTarget = viewModel::setTargetLanguage,
                onApply = viewModel::applyTranslation,
                onSidecarToggle = viewModel::setSaveOriginalSidecar,
            )

            if (state.totalCues > 0) {
                ShiftSection(
                    shiftMs = state.subtitleShiftMs,
                    onShift = viewModel::setShiftMs,
                )
            }

            HorizontalDivider()

            // ---- Step 4: download mode
            ModeSection(
                mode = state.downloadMode,
                onMode = viewModel::setDownloadMode,
                hasVideo = state.selectedVideoTrack != null,
            )

            // ---- Step 5: embed + style (only when a video is included)
            if (state.downloadMode != DownloadMode.SUBTITLE_ONLY) {
                EmbedSection(
                    embedMode = state.embedMode,
                    onEmbedMode = viewModel::setEmbedMode,
                    style = state.embedStyle,
                    onStyle = viewModel::updateStyle,
                )
            }

            HorizontalDivider()

            // ---- Step 6: download
            Button(
                onClick = viewModel::download,
                enabled = state.canDownload,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isDownloading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.subtitles_enqueueing))
                } else {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.subtitles_download))
                }
            }

            Spacer(Modifier.height(32.dp))
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

// ---------------------------------------------------------------- sections

@Composable
private fun LinkInputSection(
    url: String,
    isResolving: Boolean,
    onUrlChange: (String) -> Unit,
    onResolve: () -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        label = { Text(stringResource(R.string.subtitles_input_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            if (url.isNotBlank()) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.subtitles_clear)) }
            }
        },
    )
    Button(
        onClick = onResolve,
        enabled = url.isNotBlank() && !isResolving,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isResolving) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.subtitles_resolving))
        } else {
            Text(stringResource(R.string.subtitles_resolve))
        }
    }
}

@Composable
private fun TrackListSection(
    tracks: List<app.vidmatex.core.model.SubtitleTrack>,
    selectedId: String?,
    onSelect: (app.vidmatex.core.model.SubtitleTrack) -> Unit,
) {
    Text(stringResource(R.string.subtitles_select_track), style = MaterialTheme.typography.titleSmall)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tracks.forEach { track ->
            val selected = track.trackId == selectedId
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(track) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(track.displayLabel, style = MaterialTheme.typography.bodyLarge)
                        if (track.kind == SubtitleKind.AUTOMATIC) {
                            Text(
                                text = stringResource(R.string.subtitles_auto_generated),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (track.kind == SubtitleKind.HUMAN) {
                            Text(
                                text = stringResource(R.string.subtitles_imported_badge),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // G-3: static format label (previously a non-interactive AssistChip).
                    Text(
                        text = track.defaultFormat.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewSection(
    cues: List<app.vidmatex.core.model.SubtitleCue>,
    shown: Int,
    total: Int,
    label: String? = null,
    editingEnabled: Boolean = false,
    onEditCue: ((Int, String) -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (label != null) "$label · " + stringResource(R.string.subtitles_preview, shown, total)
            else stringResource(R.string.subtitles_preview, shown, total),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (editingEnabled && onEditCue != null) {
            TextButton(onClick = { editing = !editing }) {
                Text(
                    if (editing) stringResource(R.string.subtitles_edit_done)
                    else stringResource(R.string.subtitles_edit_cues),
                )
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cues.forEach { cue ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "${formatMs(cue.startMs)} → ${formatMs(cue.endMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(110.dp),
                    )
                    if (editing && onEditCue != null) {
                        OutlinedTextField(
                            value = cue.lines.joinToString("\n"),
                            onValueChange = { onEditCue(cue.index, it) },
                            modifier = Modifier.weight(1f),
                            minLines = 1,
                            maxLines = 3,
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            text = cue.lines.joinToString(" "),
                            style = MaterialTheme.typography.bodyMedium,
                            // Default text direction is content-based (FirstStrong), which
                            // handles Arabic/RTL cues automatically without a Compose-version-
                            // dependent TextDirection.Content constant.
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val h = ms / 3_600_000; val r = ms % 3_600_000
    val m = r / 60_000; val r2 = r % 60_000
    val s = r2 / 1000
    return "%02d:%02d:%02d".format(h, m, s)
}

@Composable
private fun FormatSection(format: SubtitleFormat, onFormat: (SubtitleFormat) -> Unit) {
    Text(stringResource(R.string.subtitles_format), style = MaterialTheme.typography.titleSmall)
    SegmentedRow(
        options = SubtitleFormat.entries.map { it to it.name },
        selected = format,
        onSelect = onFormat,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslationSection(
    enabled: Boolean,
    targetLanguage: String,
    providerConfigured: Boolean,
    isTranslating: Boolean,
    translationMessage: String?,
    hasCues: Boolean,
    saveOriginalSidecar: Boolean,
    onToggle: (Boolean) -> Unit,
    onTarget: (String) -> Unit,
    onApply: () -> Unit,
    onSidecarToggle: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.subtitles_translate), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
    if (enabled) {
        if (!providerConfigured) {
            InfoCard(text = stringResource(R.string.subtitles_translation_not_configured), isError = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.subtitles_target_language), Modifier.weight(1f))
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.width(180.dp),
            ) {
                OutlinedTextField(
                    value = stringResource(langLabelRes(targetLanguage)),
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor(),
                    singleLine = true,
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    TARGET_LANGUAGES.forEach { code ->
                        DropdownMenuItem(
                            text = { Text(stringResource(langLabelRes(code))) },
                            onClick = { onTarget(code); expanded = false },
                        )
                    }
                }
            }
        }
        if (providerConfigured) {
            TextButton(
                onClick = onApply,
                enabled = hasCues && !isTranslating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isTranslating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.subtitles_translating))
                } else {
                    Text(stringResource(R.string.subtitles_apply_translation))
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text(
                stringResource(R.string.subtitles_sidecar_original),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            Switch(checked = saveOriginalSidecar, onCheckedChange = onSidecarToggle)
        }
        translationMessage?.let {
            InfoCard(text = it)
        }
    }
}

@Composable
private fun ModeSection(
    mode: DownloadMode,
    onMode: (DownloadMode) -> Unit,
    hasVideo: Boolean,
) {
    Text(stringResource(R.string.subtitles_mode), style = MaterialTheme.typography.titleSmall)
    SegmentedRow(
        options = listOf(
            DownloadMode.VIDEO_ONLY to stringResource(R.string.subtitles_mode_video_only),
            DownloadMode.SUBTITLE_ONLY to stringResource(R.string.subtitles_mode_subtitle_only),
            DownloadMode.VIDEO_AND_SUBTITLE to stringResource(R.string.subtitles_mode_video_subtitle),
        ),
        selected = mode,
        onSelect = onMode,
        enabledFor = { opt -> opt != DownloadMode.VIDEO_ONLY || hasVideo },
    )
    if (!hasVideo && mode != DownloadMode.SUBTITLE_ONLY) {
        InfoCard(text = stringResource(R.string.subtitles_error_no_video), isError = true)
    }
}

@Composable
private fun EmbedSection(
    embedMode: EmbedMode,
    onEmbedMode: (EmbedMode) -> Unit,
    style: EmbedStyle,
    onStyle: ((EmbedStyle) -> EmbedStyle) -> Unit,
) {
    Text(stringResource(R.string.subtitles_embed), style = MaterialTheme.typography.titleSmall)
    SegmentedRow(
        options = listOf(
            EmbedMode.NONE to stringResource(R.string.subtitles_embed_off),
            EmbedMode.SOFT to stringResource(R.string.subtitles_embed_soft),
            EmbedMode.HARD to stringResource(R.string.subtitles_embed_hard),
        ),
        selected = embedMode,
        onSelect = onEmbedMode,
    )

    if (embedMode == EmbedMode.HARD) {
        Text(
            stringResource(R.string.subtitles_style),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )

        // Live preview of the burn-in style.
        Text(stringResource(R.string.subtitles_live_preview), style = MaterialTheme.typography.bodySmall)
        SubtitleStylePreview(style = style)

        // Font size
        Text(stringResource(R.string.subtitles_font_size, style.fontSize), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = style.fontSize.toFloat(),
            onValueChange = { onStyle { s -> s.copy(fontSize = it.toInt()) } },
            valueRange = 12f..48f,
            steps = 35,
        )

        // Outline width
        Text(stringResource(R.string.subtitles_outline_width, style.outlineWidth), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = style.outlineWidth,
            onValueChange = { onStyle { s -> s.copy(outlineWidth = it) } },
            valueRange = 0f..6f,
            steps = 11,
        )

        // Vertical margin
        Text(stringResource(R.string.subtitles_margin, style.marginV), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = style.marginV.toFloat(),
            onValueChange = { onStyle { s -> s.copy(marginV = it.toInt()) } },
            valueRange = 0f..120f,
            steps = 23,
        )

        // Color presets
        Text(stringResource(R.string.subtitles_color), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            ColorPresetButton(color = Color.White, assColor = "&H00FFFFFF", current = style.fontColor, onSelect = { onStyle { s -> s.copy(fontColor = it) } })
            ColorPresetButton(color = Color.Yellow, assColor = "&H0000FFFF", current = style.fontColor, onSelect = { onStyle { s -> s.copy(fontColor = it) } })
            ColorPresetButton(color = Color.Cyan, assColor = "&H00FFFF00", current = style.fontColor, onSelect = { onStyle { s -> s.copy(fontColor = it) } })
            ColorPresetButton(color = Color.Green, assColor = "&H0000FF00", current = style.fontColor, onSelect = { onStyle { s -> s.copy(fontColor = it) } })
        }

        // Background toggle
        val bgEnabled = style.backgroundColor.removePrefix("&H").take(2) != "00"
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.subtitles_background), Modifier.weight(1f))
            Switch(
                checked = bgEnabled,
                onCheckedChange = { onStyle { s -> s.copy(backgroundColor = if (it) "&H80000000" else "&H00000000") } },
            )
        }

        // Position (bottom = ASS alignment 2, top = 8)
        Text(stringResource(R.string.subtitles_position), style = MaterialTheme.typography.bodyMedium)
        SegmentedRow(
            options = listOf(
                2 to stringResource(R.string.subtitles_position_bottom),
                8 to stringResource(R.string.subtitles_position_top),
            ),
            selected = style.alignment,
            onSelect = { onStyle { s -> s.copy(alignment = it) } },
        )

        // Bold
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.subtitles_bold), Modifier.weight(1f))
            Switch(checked = style.bold, onCheckedChange = { onStyle { s -> s.copy(bold = it) } })
        }
    }
}

@Composable
private fun ColorPresetButton(
    color: Color,
    assColor: String,
    current: String,
    onSelect: (String) -> Unit,
) {
    val selected = assColor.equals(current, ignoreCase = true)
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable { onSelect(assColor) },
    )
}

/**
 * Renders a sample cue as it would appear burned into a video frame, using [EmbedStyle].
 * Position follows ASS alignment (2 = bottom-center, 8 = top-center). Outline is simulated
 * with stacked text shadows; background box with drawBehind.
 */
@Composable
private fun SubtitleStylePreview(style: EmbedStyle) {
    val textColor = assColor(style.fontColor, Color.White)
    val outlineColor = assColor(style.outlineColor, Color.Black)
    val bgColor = assColor(style.backgroundColor, Color.Transparent)
    val isTop = style.alignment == 8
    val sample = stringResource(R.string.subtitles_sample_cue)

    Card(
        Modifier
            .fillMaxWidth()
            .height(160.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            val outlinePx = style.outlineWidth * 2f
            Text(
                text = sample,
                color = textColor,
                fontSize = (style.fontSize * 0.7f).sp,
                fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    shadow = Shadow(color = outlineColor, offset = Offset(outlinePx, outlinePx), blurRadius = outlinePx),
                ),
                modifier = Modifier
                    .align(if (isTop) Alignment.TopCenter else Alignment.BottomCenter)
                    .padding(vertical = (style.marginV * 0.2f).dp.coerceAtLeast(4.dp))
                    .then(
                        if (bgColor.alpha > 0f) {
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(bgColor)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        } else Modifier
                    ),
            )
        }
    }
}

@Composable
private fun <T> SegmentedRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabledFor: (T) -> Boolean = { true },
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
                enabled = enabledFor(value),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Timing shift control: a slider (±5s coarse) plus ±100ms / ±1s stepper buttons and a
 * reset. Value is clamped to ±60s in the ViewModel. Applied to both original and
 * translated cues at download time (CONTRACTS §5).
 */
@Composable
private fun ShiftSection(shiftMs: Long, onShift: (Long) -> Unit) {
    Text(stringResource(R.string.subtitles_shift), style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onShift(shiftMs - 1000) }) { Text("-1s") }
        TextButton(onClick = { onShift(shiftMs - 100) }) { Text("-0.1s") }
        Text(
            stringResource(R.string.subtitles_shift_value, shiftMs),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(90.dp),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = { onShift(shiftMs + 100) }) { Text("+0.1s") }
        TextButton(onClick = { onShift(shiftMs + 1000) }) { Text("+1s") }
        TextButton(onClick = { onShift(0) }) { Text(stringResource(R.string.subtitles_shift_reset)) }
    }
    Slider(
        value = shiftMs.toFloat().coerceIn(-5000f, 5000f),
        onValueChange = { onShift(it.toLong()) },
        valueRange = -5000f..5000f,
        steps = 99,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.subtitles_shift_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Whisper speech-to-text entry point. Always visible; adapts to availability:
 *  - endpoint missing -> guidance card pointing to Settings (no dead/disabled button);
 *  - configured -> spoken-language dropdown (Auto + en/ar/fr/es/de/tr/zh), file picker
 *    button (any audio, any video MIME type), live progress card (percent + stage), success card,
 *    and an error card with retry/dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptionSection(
    state: SubtitlesUiState,
    onPickFile: () -> Unit,
    onSourceLanguage: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.subtitles_transcribe_title),
            style = MaterialTheme.typography.titleSmall,
        )
        if (!state.whisperAvailable) {
            InfoCard(
                text = stringResource(R.string.subtitles_transcribe_not_configured_hint),
                isError = true,
            )
            if (onOpenSettings != null) {
                TextButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.subtitles_whisper_open_settings))
                }
            }
            return
        }

        SourceLanguageDropdown(value = state.sourceLanguage, onSelect = onSourceLanguage)

        Button(
            onClick = onPickFile,
            enabled = state.transcriptionState != TranscriptionState.RUNNING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.MusicNote, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.subtitles_transcribe_pick_file))
        }
        Text(
            stringResource(R.string.subtitles_transcribe_pick_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (state.transcriptionState) {
            TranscriptionState.PREPARING_FILE,
            TranscriptionState.QUEUED,
            TranscriptionState.RUNNING -> TranscriptionProgressCard(state, onCancel)
            TranscriptionState.SUCCESS -> InfoCard(
                text = stringResource(R.string.subtitles_transcribe_success, state.transcriptionCueCount),
            )
            TranscriptionState.FAILED -> TranscriptionErrorCard(state, onRetry, onDismissError)
            TranscriptionState.CANCELED -> InfoCard(
                text = stringResource(R.string.subtitles_transcribe_canceled),
            )
            TranscriptionState.AWAITING_FILE, TranscriptionState.IDLE -> Unit
        }
    }
}

/** Spoken-language dropdown: Auto-detect (language field omitted) + explicit ISO codes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceLanguageDropdown(value: String, onSelect: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.subtitles_transcribe_source_language),
            Modifier.weight(1f),
        )
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.width(200.dp),
        ) {
            OutlinedTextField(
                value = if (SourceLanguages.isAuto(value)) {
                    stringResource(R.string.subtitles_lang_auto)
                } else {
                    stringResource(langLabelRes(value))
                },
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(),
                singleLine = true,
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.subtitles_lang_auto)) },
                    onClick = { onSelect(SourceLanguages.AUTO); expanded = false },
                )
                SourceLanguages.SUPPORTED.forEach { code ->
                    DropdownMenuItem(
                        text = { Text(stringResource(langLabelRes(code))) },
                        onClick = { onSelect(code); expanded = false },
                    )
                }
            }
        }
    }
}

/** Live transcription progress: percentage, stage label, linear bar, cancel action. */
@Composable
private fun TranscriptionProgressCard(state: SubtitlesUiState, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(
                        R.string.subtitles_transcribe_running,
                        state.transcriptionProgress.coerceIn(0, 100),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.subtitles_transcribe_cancel))
                }
            }
            Text(
                stageLabel(state.transcriptionStage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { state.transcriptionProgress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Failure card: friendly error with Retry (when a local path is retained) and Dismiss. */
@Composable
private fun TranscriptionErrorCard(
    state: SubtitlesUiState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    state.transcriptionError
                        ?: stringResource(R.string.subtitles_transcribe_unsupported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry, enabled = state.transcriptionPath != null) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.subtitles_transcribe_retry))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.subtitles_transcribe_dismiss))
                }
            }
        }
    }
}

@Composable
private fun stageLabel(stage: String?): String = when (stage) {
    TranscriptionWorker.STAGE_PREPARING ->
        stringResource(R.string.subtitles_transcribe_stage_preparing)
    TranscriptionWorker.STAGE_TRANSCRIBING ->
        stringResource(R.string.subtitles_transcribe_stage_transcribe)
    TranscriptionWorker.STAGE_WRITING ->
        stringResource(R.string.subtitles_transcribe_stage_write)
    else -> stringResource(R.string.subtitles_transcribe_queued)
}

@Composable
private fun InfoCard(text: String, isError: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (isError) Icons.Default.ErrorOutline else Icons.Default.Info,
                contentDescription = null,
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Standalone subtitle sidecar import (.srt/.vtt/.ass); provenance tag = HUMAN. */
@Composable
private fun SubtitleImportSection(importedName: String?, onImport: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.subtitles_import_subtitle),
            style = MaterialTheme.typography.titleSmall,
        )
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Info, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.subtitles_import_subtitle_action))
        }
        if (!importedName.isNullOrBlank()) {
            Text(
                stringResource(R.string.subtitles_imported_done, importedName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Completed-download hook: newest local audio/media file ready for one-tap transcription. */
@Composable
private fun CompletedAudioCard(path: String, onTranscribe: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.subtitles_downloaded_audio_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onTranscribe, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.MusicNote, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.subtitles_downloaded_audio_action))
            }
        }
    }
}

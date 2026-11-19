@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.vidmatex.feature.home

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vidmatex.core.model.DownloadMode
import app.vidmatex.core.model.MediaKind
import app.vidmatex.core.model.MediaTrack
import app.vidmatex.core.model.SourceType
import app.vidmatex.core.model.SubtitleTrack
import app.vidmatex.feature.home.R
import coil.compose.AsyncImage

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.dismissSnackbar()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.home_title)) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Link input + paste
            OutlinedTextField(
                value = state.link,
                onValueChange = viewModel::onLinkChange,
                label = { Text(stringResource(R.string.home_link_hint)) },
                trailingIcon = {
                    OutlinedButton(onClick = viewModel::pasteFromClipboard) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.home_action_paste))
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Clipboard offer + analyze
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = viewModel::analyze,
                    enabled = state.link.isNotBlank() && !state.isLoading
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.home_action_analyze))
                }
                if (state.clipboardUrl != null) {
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = viewModel::pasteFromClipboard) {
                        Text(stringResource(R.string.home_clipboard_offer))
                    }
                }
            }

            // Detected source badge (informational; intentionally non-clickable)
            state.detectedSource?.let { source ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = sourceLabel(source),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            // Loading
            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.home_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // G-3 initial empty state: nothing analyzed yet and no link entered.
            if (state.metadata == null && !state.isLoading && state.link.isBlank()) {
                HomeEmptyState()
            }
            // Resolved metadata card
            state.metadata?.let { metadata ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        if (!metadata.thumbnailUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = metadata.thumbnailUrl,
                                contentDescription = stringResource(R.string.home_cd_thumbnail),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(108.dp, 72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = metadata.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            metadata.author?.takeIf { it.isNotBlank() }?.let { author ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.home_author_by, author),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = formatDuration(metadata.durationSec),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Audio-only toggle (no video track, no mux; worker finalizes the audio file)
                FilterChip(
                    selected = state.audioOnly,
                    onClick = { viewModel.setAudioOnly(!state.audioOnly) },
                    label = { Text(stringResource(R.string.home_audio_only)) },
                    leadingIcon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                )
                if (state.audioOnly) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.home_audio_only_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
                // Video quality chips (disabled while audio-only is selected)
                if (state.videoTracks.isNotEmpty()) {
                    Text(
                        stringResource(R.string.home_quality_video),
                        style = MaterialTheme.typography.titleMedium
                    )
                    ChipRow {
                        state.videoTracks.forEach { track ->
                            QualityChip(
                                label = track.qualityLabel,
                                sub = track.estimatedBytes?.let {
                                    stringResource(R.string.home_estimated_mb, it / 1024.0 / 1024.0)
                                },
                                selected = state.selectedVideo?.trackId == track.trackId,
                                enabled = !state.audioOnly,
                                onClick = { viewModel.selectQuality(track) }
                            )
                        }
                    }
                }

                // Audio chips
                if (state.audioTracks.isNotEmpty()) {
                    Text(
                        stringResource(R.string.home_quality_audio),
                        style = MaterialTheme.typography.titleMedium
                    )
                    ChipRow {
                        state.audioTracks.forEach { track ->
                            QualityChip(
                                label = track.container.uppercase(),
                                sub = track.bitrateBps?.let {
                                    stringResource(R.string.home_bitrate_kbps, it / 1000)
                                },
                                selected = state.selectedAudio?.trackId == track.trackId,
                                onClick = { viewModel.selectQuality(track) }
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Subtitles (hidden for audio-only jobs)
                if (!state.audioOnly) {
                Text(
                    stringResource(R.string.home_subtitles),
                    style = MaterialTheme.typography.titleMedium
                )
                if (state.subtitles.isEmpty()) {
                    Text(
                        stringResource(R.string.home_subtitles_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        stringResource(R.string.home_subtitles_count, state.subtitles.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ChipRow {
                        QualityChip(
                            label = stringResource(R.string.home_subtitle_off),
                            sub = null,
                            selected = state.selectedSubtitle == null,
                            onClick = { viewModel.selectSubtitle(null) }
                        )
                        state.subtitles.forEach { sub ->
                            QualityChip(
                                label = sub.languageName,
                                sub = sub.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                                selected = state.selectedSubtitle?.trackId == sub.trackId,
                                onClick = { viewModel.selectSubtitle(sub) }
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Download mode segmented
                ModeSelector(
                    mode = state.mode,
                    onModeChange = viewModel::setMode
                )

                Spacer(Modifier.height(4.dp))
                }
                // Download button
                Button(
                    onClick = viewModel::download,
                    enabled = state.canDownload && !state.isEnqueuing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isEnqueuing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        if (state.audioOnly) stringResource(R.string.home_download_audio)
                        else stringResource(R.string.home_action_download)
                    )
                }
                if (!state.canDownload && state.metadata != null) {
                    Text(
                        if (state.audioOnly) stringResource(R.string.home_error_audio_selection)
                        else stringResource(R.string.home_select_quality_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // G-3 error card: Retry re-runs analyze with the current link; Dismiss clears
            // the error without discarding the entered text.
            state.error?.let { err ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = viewModel::analyze,
                                enabled = state.link.isNotBlank(),
                            ) {
                                Text(stringResource(R.string.home_error_retry))
                            }
                            TextButton(onClick = viewModel::dismissError) {
                                Text(stringResource(R.string.home_error_dismiss))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    val scrollState = androidx.compose.foundation.rememberScrollState()
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
    ) {
        content()
    }
}

@Composable
private fun QualityChip(
    label: String,
    sub: String?,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = { if (enabled) onClick() },
        label = {
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge)
                sub?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@Composable
private fun ModeSelector(
    mode: DownloadMode,
    onModeChange: (DownloadMode) -> Unit,
) {
    val options = listOf(
        DownloadMode.VIDEO_ONLY to stringResource(R.string.home_mode_video_only),
        DownloadMode.SUBTITLE_ONLY to stringResource(R.string.home_mode_subtitle_only),
        DownloadMode.VIDEO_AND_SUBTITLE to stringResource(R.string.home_mode_both),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onModeChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun sourceLabel(source: SourceType): String = when (source) {
    SourceType.YOUTUBE -> stringResource(R.string.home_source_youtube)
    SourceType.FACEBOOK -> stringResource(R.string.home_source_facebook)
    SourceType.TIKTOK -> stringResource(R.string.home_source_tiktok)
    SourceType.DIRECT_URL -> stringResource(R.string.home_source_direct)
    SourceType.UNKNOWN -> stringResource(R.string.home_source_unknown)
}

@Composable
private fun HomeEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp),
        )
        Text(
            stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.home_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatDuration(sec: Long?): String {
    if (sec == null || sec <= 0) return "—:—"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

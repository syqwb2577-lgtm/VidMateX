package app.vidmatex.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vidmatex.core.model.DownloadItem
import app.vidmatex.core.model.DownloadState
import app.vidmatex.feature.downloads.R
import coil.compose.AsyncImage

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = hiltViewModel(),
    /** (filePath, title, subtitlePath?) — the app-level preview renders the sidecar captions. */
    onPreview: ((String, String?, String?) -> Unit)? = null,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val dismissedErrors by viewModel.dismissedErrors.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DownloadFilter.values().forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { viewModel.setFilter(f) },
                    label = { Text(stringResource(f.labelRes)) },
                )
            }
        }
        // Compliance notice (requirement §3): platform pages remain metadata-only.
        Text(
            text = stringResource(R.string.dl_compliance_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            maxLines = 3,
        )

        when {
            // G-3: centered spinner while the first snapshot is pending (or active jobs run).
            isLoading && items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.dl_empty_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.dl_empty_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        DownloadCard(
                            item = item,
                            onPause = { viewModel.pause(item.id) },
                            onResume = { viewModel.resume(item.id) },
                            onCancel = { viewModel.cancel(item.id) },
                            onRetry = { viewModel.retry(item.id) },
                            onDelete = { viewModel.delete(item.id) },
                            onOpen = { viewModel.open(item) },
                            onShare = { viewModel.share(item) },
                            onDismissError = { viewModel.dismissError(item.id) },
                            errorDismissed = item.id in dismissedErrors,
                            onPreview = if (onPreview != null) {
                                item.filePath?.let { path -> { onPreview(path, item.title, item.subtitlePath) } }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onPreview: (() -> Unit)? = null,
    onDismissError: (() -> Unit)? = null,
    errorDismissed: Boolean = false,
) {
    // G-3: once dismissed, the error text is suppressed locally; row state/history persist.
    val shownItem = if (errorDismissed) item.copy(errorMessage = null) else item
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = item.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.qualityLabel?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (DownloadKinds.looksLikeAudioLabel(item.qualityLabel)) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.dl_badge_audio),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    StateBadge(item.state)
                }
                Spacer(Modifier.height(6.dp))
                if (item.state == DownloadState.ACTIVE || item.state == DownloadState.QUEUED) {
                    val indeterminate = item.state == DownloadState.QUEUED || item.totalBytes == null
                    if (indeterminate) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { item.progressPercent.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = progressLine(shownItem),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionButtons(
                        item = item,
                        onPause = onPause,
                        onResume = onResume,
                        onCancel = onCancel,
                        onRetry = onRetry,
                        onDelete = onDelete,
                        onOpen = onOpen,
                        onShare = onShare,
                        onPreview = onPreview,
                        onDismissError = onDismissError,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButtons(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onPreview: (() -> Unit)? = null,
    onDismissError: (() -> Unit)? = null,
) {
    when (item.state) {
        DownloadState.ACTIVE -> {
            IconButton(onClick = onPause) { Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.dl_cd_pause)) }
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dl_cd_cancel)) }
        }
        DownloadState.QUEUED -> {
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dl_cd_cancel)) }
        }
        DownloadState.PAUSED -> {
            IconButton(onClick = onResume) { Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.dl_cd_resume)) }
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dl_cd_cancel)) }
        }
        DownloadState.FAILED, DownloadState.CANCELLED -> {
            IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.dl_cd_retry)) }
            if (onDismissError != null) {
                TextButton(onClick = onDismissError) {
                    Text(stringResource(R.string.dl_error_dismiss))
                }
            }
        }
        DownloadState.COMPLETED -> {
            if (onPreview != null) {
                IconButton(onClick = onPreview) { Icon(Icons.Default.Preview, contentDescription = stringResource(R.string.dl_cd_preview)) }
            }
            IconButton(onClick = onOpen) { Icon(Icons.Default.OpenInNew, contentDescription = stringResource(R.string.dl_cd_open)) }
            IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.dl_cd_share)) }
        }
    }
    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.dl_cd_delete)) }
}

@Composable
private fun StateBadge(state: DownloadState) {
    val label = stringResource(
        when (state) {
            DownloadState.QUEUED -> R.string.dl_state_queued
            DownloadState.ACTIVE -> R.string.dl_state_active
            DownloadState.PAUSED -> R.string.dl_state_paused
            DownloadState.COMPLETED -> R.string.dl_state_completed
            DownloadState.FAILED -> R.string.dl_state_failed
            DownloadState.CANCELLED -> R.string.dl_state_cancelled
        }
    )
    val container = when (state) {
        DownloadState.QUEUED -> MaterialTheme.colorScheme.secondaryContainer
        DownloadState.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
        DownloadState.PAUSED -> MaterialTheme.colorScheme.secondaryContainer
        DownloadState.COMPLETED -> MaterialTheme.colorScheme.tertiaryContainer
        DownloadState.FAILED -> MaterialTheme.colorScheme.errorContainer
        DownloadState.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (state) {
        DownloadState.QUEUED -> MaterialTheme.colorScheme.onSecondaryContainer
        DownloadState.ACTIVE -> MaterialTheme.colorScheme.onPrimaryContainer
        DownloadState.PAUSED -> MaterialTheme.colorScheme.onSecondaryContainer
        DownloadState.COMPLETED -> MaterialTheme.colorScheme.onTertiaryContainer
        DownloadState.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        DownloadState.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun progressLine(item: DownloadItem): String = when (item.state) {
    DownloadState.ACTIVE -> buildString {
        val total = item.totalBytes
        if (total != null && total > 0) {
            append(
                stringResource(
                    R.string.dl_progress_bytes,
                    FormatUtils.formatBytes(item.downloadedBytes),
                    FormatUtils.formatBytes(total),
                )
            )
            append(" · ")
        } else if (item.progressPercent > 0) {
            append("${item.progressPercent}% · ")
        }
        if (item.speedBps > 0) append("${FormatUtils.formatSpeed(item.speedBps)} · ")
        append(item.etaSec?.let { stringResource(R.string.dl_notif_eta_left, FormatUtils.formatEta(it)) } ?: stringResource(R.string.dl_progress_downloading))
    }
    DownloadState.QUEUED -> stringResource(R.string.dl_progress_queued)
    DownloadState.PAUSED -> stringResource(R.string.dl_progress_paused_at, item.progressPercent)
    DownloadState.COMPLETED -> stringResource(R.string.dl_progress_saved, FormatUtils.formatBytes(item.totalBytes))
    DownloadState.FAILED -> item.errorMessage ?: stringResource(R.string.dl_state_failed)
    DownloadState.CANCELLED -> stringResource(R.string.dl_state_cancelled)
}

package app.vidmatex.feature.history

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vidmatex.core.model.DownloadItem
import app.vidmatex.core.model.DownloadState
import app.vidmatex.feature.history.R
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

/** A row in the history list: either a date header or a download card. */
private sealed interface HistoryRow {
    data class Header(val label: String) : HistoryRow
    data class Item(val download: DownloadItem) : HistoryRow
}

/**
 * History tab: ongoing (queued/active/paused) and finished downloads with progress,
 * speed, bytes, ETA, a state badge, expandable errors, and per-state actions. The list
 * refreshes reactively from `DownloadManager.observeAll()`.
 *
 * @param onPreview (uri, title, subtitlePath?) launches the in-app video preview; the
 *   optional sidecar subtitle path renders captions when present.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel(),
    onPreview: ((String?, String?, String?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // One-shot toast events (retry queued / file missing / no handler app).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.events
            .onEach { event ->
                if (event is HistoryEvent.Toast) {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
            .collect()
    }

    val rows = remember(uiState.items, context) { groupByDate(context, uiState.items) }
    val showLoading = uiState.isLoading && rows.isEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                actions = {
                    FilterChip(
                        selected = uiState.showFailed,
                        onClick = { viewModel.toggleShowFailed() },
                        label = { Text(stringResource(R.string.history_include_failed)) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            )
        },
    ) { padding ->
        when {
            showLoading -> LoadingState(modifier = Modifier.padding(padding))
            rows.isEmpty() -> EmptyState(modifier = Modifier.padding(padding))
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { row ->
                    when (row) {
                        is HistoryRow.Header -> "header-${row.label}"
                        is HistoryRow.Item -> "item-${row.download.id}"
                    }
                }) { row ->
                    when (row) {
                        is HistoryRow.Header -> DateHeader(label = row.label)
                        is HistoryRow.Item -> HistoryCard(
                            item = row.download,
                            onOpen = { viewModel.open(row.download) },
                            onShare = { viewModel.share(row.download) },
                            onDelete = { viewModel.delete(row.download.id) },
                            onRetry = { viewModel.retry(row.download.id) },
                            onPreview = if (onPreview != null && row.download.filePath != null) {
                                { onPreview(row.download.filePath, row.download.title, row.download.subtitlePath) }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.history_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DateHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.history_empty_title_all),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.history_empty_subtitle_all),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.8f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(
    item: DownloadItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onPreview: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var errorExpanded by remember { mutableStateOf(false) }
    val isFailed = item.state == DownloadState.FAILED || item.state == DownloadState.CANCELLED
    val inProgress = item.state == DownloadState.QUEUED ||
        item.state == DownloadState.ACTIVE ||
        item.state == DownloadState.PAUSED

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Thumbnail (or failed indicator).
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isFailed) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = stringResource(R.string.history_cd_failed),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        AsyncImage(
                            model = item.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                // Title + badge row.
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title.ifBlank { item.sourceUrl },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StateBadge(item.state)
                        item.qualityLabel?.let { label ->
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Progress bar for ongoing states.
            if (inProgress) {
                Spacer(Modifier.height(8.dp))
                val totalKnown = item.totalBytes?.let { it > 0 } == true
                val indeterminate = item.state == DownloadState.QUEUED || !totalKnown
                if (indeterminate) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    val pct = HistoryFormat.percent(
                        item.progressPercent,
                        item.downloadedBytes,
                        item.totalBytes,
                    )
                    LinearProgressIndicator(
                        progress = { pct / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Meta line: percent · speed · downloaded/total · ETA.
            Spacer(Modifier.height(6.dp))
            Text(
                text = metaLine(item),
                style = MaterialTheme.typography.bodySmall,
                color = if (isFailed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Expandable error details for failed/cancelled rows.
            if (!item.errorMessage.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { errorExpanded = !errorExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (errorExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(
                            if (errorExpanded) R.string.history_error_hide else R.string.history_error_show,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (errorExpanded) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Actions: failed/cancelled → Retry + Delete; completed → Preview/Open/Share;
            // ongoing → Delete. Long-press anywhere opens the full action menu.
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { if (item.state == DownloadState.COMPLETED) onOpen() },
                        onLongClick = { menuExpanded = true },
                    ),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (item.state) {
                    DownloadState.FAILED, DownloadState.CANCELLED -> {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.history_cd_retry))
                        }
                    }
                    DownloadState.COMPLETED -> {
                        if (onPreview != null) {
                            IconButton(onClick = onPreview) {
                                Icon(Icons.Default.Preview, contentDescription = stringResource(R.string.history_cd_preview))
                            }
                        }
                        IconButton(onClick = onOpen) {
                            Icon(Icons.Default.OpenInNew, contentDescription = stringResource(R.string.history_cd_open))
                        }
                        IconButton(onClick = onShare) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.history_cd_share))
                        }
                    }
                    else -> Unit
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.history_cd_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                Box {
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        if (onPreview != null && item.state == DownloadState.COMPLETED) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_action_preview)) },
                                onClick = { menuExpanded = false; onPreview() },
                                leadingIcon = { Icon(Icons.Default.Preview, null) },
                            )
                        }
                        if (item.state == DownloadState.COMPLETED) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_action_open)) },
                                onClick = { menuExpanded = false; onOpen() },
                                leadingIcon = { Icon(Icons.Default.OpenInNew, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_action_share)) },
                                onClick = { menuExpanded = false; onShare() },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                            )
                        }
                        if (isFailed) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_action_retry)) },
                                onClick = { menuExpanded = false; onRetry() },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_action_delete)) },
                            onClick = { menuExpanded = false; onDelete() },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StateBadge(state: DownloadState) {
    val labelRes = when (state) {
        DownloadState.QUEUED -> R.string.history_state_queued
        DownloadState.ACTIVE -> R.string.history_state_active
        DownloadState.PAUSED -> R.string.history_state_paused
        DownloadState.COMPLETED -> R.string.history_state_completed
        DownloadState.FAILED -> R.string.history_state_failed
        DownloadState.CANCELLED -> R.string.history_state_cancelled
    }
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
    Surface(color = container, shape = RoundedCornerShape(4.dp)) {
        Text(
            text = stringResource(labelRes),
            color = content,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Compose the small progress line: percent, speed, bytes, ETA (state-aware). */
@Composable
private fun metaLine(item: DownloadItem): String {
    val parts = mutableListOf<String>()
    when (item.state) {
        DownloadState.ACTIVE -> {
            val totalBytes = item.totalBytes
            val pct = HistoryFormat.percent(item.progressPercent, item.downloadedBytes, totalBytes)
            if (totalBytes != null && totalBytes > 0) parts += "$pct%"
            if (item.speedBps > 0) parts += HistoryFormat.formatSpeed(item.speedBps)
            if (totalBytes != null && totalBytes > 0) {
                parts += stringResource(
                    R.string.history_bytes_of,
                    HistoryFormat.formatBytes(item.downloadedBytes),
                    HistoryFormat.formatBytes(totalBytes),
                )
            }
            item.etaSec?.let { eta ->
                if (eta > 0) parts += stringResource(R.string.history_eta_left, HistoryFormat.formatEta(eta))
            }
        }
        DownloadState.QUEUED -> parts += stringResource(R.string.history_state_queued)
        DownloadState.PAUSED -> parts += stringResource(R.string.history_state_paused_at, item.progressPercent)
        DownloadState.COMPLETED -> parts += stringResource(
            R.string.history_bytes_saved,
            HistoryFormat.formatBytes(item.totalBytes ?: item.downloadedBytes),
        )
        DownloadState.FAILED -> parts += stringResource(R.string.history_state_failed)
        DownloadState.CANCELLED -> parts += stringResource(R.string.history_state_cancelled)
    }
    return parts.joinToString(" · ")
}

// --- helpers ---------------------------------------------------------------

/** Group records by calendar day into header + item rows. */
private fun groupByDate(context: android.content.Context, items: List<DownloadItem>): List<HistoryRow> {
    if (items.isEmpty()) return emptyList()
    val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val labelFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val now = System.currentTimeMillis()
    val rows = mutableListOf<HistoryRow>()
    var currentDay: String? = null
    for (item in items) {
        val day = dayFmt.format(Date(item.createdAtMs))
        if (day != currentDay) {
            currentDay = day
            // Bucket logic (today/yesterday/older) lives in pure HistoryFormat; only the
            // human label and the absolute date formatter touch Android resources.
            val label = when (HistoryFormat.dateBucket(item.createdAtMs, now)) {
                HistoryFormat.DateBucket.TODAY -> context.getString(R.string.history_today)
                HistoryFormat.DateBucket.YESTERDAY -> context.getString(R.string.history_yesterday)
                HistoryFormat.DateBucket.OLDER -> labelFmt.format(Date(item.createdAtMs))
            }
            rows += HistoryRow.Header(label)
        }
        rows += HistoryRow.Item(item)
    }
    return rows
}

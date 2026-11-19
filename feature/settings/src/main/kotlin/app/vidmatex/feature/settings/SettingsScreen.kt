@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.vidmatex.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vidmatex.core.model.AppLanguage
import app.vidmatex.core.model.EmbedMode
import app.vidmatex.core.model.ThemeMode
import app.vidmatex.core.model.TranslationProvider
import app.vidmatex.core.network.BackendUrlValidator
import kotlinx.coroutines.launch
import app.vidmatex.feature.settings.R

/** Which choice dialog is currently open, if any. */
private sealed interface DialogState {
    data object Theme : DialogState
    data object Language : DialogState
    data object Quality : DialogState
    data object EmbedMode : DialogState
    data object TranslationProvider : DialogState
    data object Privacy : DialogState
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val resolverTest by viewModel.resolverTest.collectAsStateWithLifecycle()
    val whisperTest by viewModel.whisperTest.collectAsStateWithLifecycle()
    val whisperReadiness by viewModel.whisperReadiness.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<DialogState?>(null) }

    // Password visibility toggles (UI-only state; keys remain persisted through the VM).
    var resolverKeyVisible by remember { mutableStateOf(false) }
    var whisperKeyVisible by remember { mutableStateOf(false) }

    // SAF folder picker: persist the permission so downloads can write later.
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.setDownloadDirUri(uri.toString())
        }
    }
    val languageRestartNote = stringResource(R.string.settings_language_restart_note)
    val savedMessage = stringResource(R.string.settings_saved_snackbar)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                actions = {
                    TextButton(onClick = {
                        viewModel.saveNow()
                        scope.launch { snackbarHostState.showSnackbar(savedMessage) }
                    }) {
                        Text(stringResource(R.string.settings_action_save))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ---- Appearance ----
            item { SectionHeader(R.string.settings_section_appearance) }
            item {
                PreferenceRow(
                    title = stringResource(R.string.settings_theme),
                    subtitle = themeLabel(settings.themeMode),
                    onClick = { dialog = DialogState.Theme }
                )
            }
            item {
                PreferenceRow(
                    title = stringResource(R.string.settings_language),
                    subtitle = languageLabel(settings.appLanguage),
                    onClick = { dialog = DialogState.Language }
                )
            }
            item {
                Text(
                    stringResource(R.string.settings_language_restart_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                )
            }
            // ---- Downloads ----
            item { SectionHeader(R.string.settings_section_downloads) }
            item {
                PreferenceRow(
                    title = stringResource(R.string.settings_download_folder),
                    subtitle = settings.downloadDirUri
                        ?.let { stringResource(R.string.settings_download_folder_set, it) }
                        ?: stringResource(R.string.settings_download_folder_none),
                    trailing = {
                        TextButton(onClick = { folderPicker.launch(null) }) {
                            Icon(Icons.Filled.Folder, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_download_folder_pick))
                        }
                    }
                )
            }
            item {
                PreferenceRow(
                    title = stringResource(R.string.settings_default_quality),
                    subtitle = qualityLabel(settings.defaultQualityHeight),
                    onClick = { dialog = DialogState.Quality }
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_require_wifi),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = settings.requireWifi,
                        onCheckedChange = viewModel::setRequireWifi
                    )
                }
            }
            item {
                PreferenceRow(
                    title = stringResource(R.string.settings_embed_mode),
                    subtitle = embedLabel(settings.defaultEmbedMode),
                    onClick = { dialog = DialogState.EmbedMode }
                )
            }
            // ---- Subtitles & translation ----
            item { SectionHeader(R.string.settings_section_subtitles) }
            item {
                var subLang by remember(settings.defaultSubtitleLanguage) {
                    mutableStateOf(settings.defaultSubtitleLanguage.orEmpty())
                }
                OutlinedTextField(
                    value = subLang,
                    onValueChange = {
                        subLang = it
                        viewModel.setDefaultSubtitleLanguage(it.trim().ifBlank { null })
                    },
                    label = { Text(stringResource(R.string.settings_default_subtitle_lang)) },
                    placeholder = { Text(stringResource(R.string.settings_subtitle_lang_none)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                PreferenceRow(
                    title = stringResource(R.string.settings_translation_provider),
                    subtitle = translationLabel(settings.translationProvider),
                    onClick = { dialog = DialogState.TranslationProvider }
                )
            }
            if (settings.translationProvider == TranslationProvider.LIBRE_TRANSLATE) {
                item {
                    var url by remember(settings.libreTranslateBaseUrl) {
                        mutableStateOf(settings.libreTranslateBaseUrl)
                    }
                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            viewModel.setLibreTranslateBaseUrl(it)
                        },
                        label = { Text(stringResource(R.string.settings_libretranslate_url)) },
                        placeholder = { Text(stringResource(R.string.settings_libretranslate_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Text(
                        stringResource(R.string.settings_libretranslate_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                    )
                }
            }
            // ---- Advanced / Resolver backend ----
            item { SectionHeader(R.string.settings_section_advanced) }
            item { SectionHeader(R.string.settings_section_resolver) }
            item {
                var resolverUrl by remember(settings.backendBaseUrl) {
                    mutableStateOf(settings.backendBaseUrl)
                }
                OutlinedTextField(
                    value = resolverUrl,
                    onValueChange = {
                        resolverUrl = it
                        viewModel.setBackendBaseUrl(it)
                    },
                    label = { Text(stringResource(R.string.settings_resolver_server_url)) },
                    placeholder = { Text(stringResource(R.string.settings_resolver_server_placeholder)) },
                    leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    supportingText = {
                        val liveInvalid = resolverUrl.isNotBlank() &&
                            !BackendUrlValidator.isValidHttpsBaseUrl(resolverUrl.trim())
                        val testInvalid = resolverTest is ResolverTestState.Error &&
                            (resolverTest as ResolverTestState.Error).kind ==
                            ResolverTestState.ErrorKind.INVALID_URL
                        if (liveInvalid || testInvalid) {
                            Text(
                                stringResource(R.string.settings_resolver_error_invalid_url),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Text(
                                stringResource(R.string.settings_resolver_hint),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                var resolverKey by remember(settings.resolverApiKey) {
                    mutableStateOf(settings.resolverApiKey)
                }
                OutlinedTextField(
                    value = resolverKey,
                    onValueChange = {
                        resolverKey = it
                        viewModel.setResolverApiKey(it)
                    },
                    label = { Text(stringResource(R.string.settings_resolver_api_key)) },
                    placeholder = { Text(stringResource(R.string.settings_resolver_api_key_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    trailingIcon = {
                        PasswordVisibilityIcon(
                            visible = resolverKeyVisible,
                            onToggle = { resolverKeyVisible = it }
                        )
                    },
                    visualTransformation = if (resolverKeyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // Clear-key flow: blank value removes the encrypted DataStore row.
            item {
                if (settings.resolverApiKey.isNotBlank()) {
                    TextButton(onClick = viewModel::clearResolverApiKey) {
                        Text(stringResource(R.string.settings_key_clear))
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = viewModel::testResolverConnection,
                        enabled = resolverTest !is ResolverTestState.Checking
                    ) {
                        if (resolverTest is ResolverTestState.Checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_resolver_test))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    ResolverStatusView(
                        resolverTest,
                        Modifier.weight(1f),
                        onRetest = viewModel::testResolverConnection,
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.settings_resolver_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                )
            }
            // ---- Whisper (audio transcription) ----
            item { SectionHeader(R.string.settings_section_whisper) }
            item {
                var whisperEndpoint by remember(settings.whisperEndpoint) {
                    mutableStateOf(settings.whisperEndpoint)
                }
                OutlinedTextField(
                    value = whisperEndpoint,
                    onValueChange = {
                        whisperEndpoint = it
                        viewModel.setWhisperEndpoint(it)
                    },
                    label = { Text(stringResource(R.string.settings_whisper_endpoint)) },
                    placeholder = { Text(stringResource(R.string.settings_whisper_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    supportingText = {
                        val liveInvalid = whisperEndpoint.isNotBlank() &&
                            !WhisperProbeUrlValidator.isValidProbeUrl(whisperEndpoint.trim())
                        val testInvalid = whisperTest is WhisperTestState.Error &&
                            (whisperTest as WhisperTestState.Error).kind ==
                            WhisperTestState.ErrorKind.INVALID_URL
                        if (liveInvalid || testInvalid) {
                            Text(
                                stringResource(R.string.settings_whisper_error_invalid_url),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                var whisperKey by remember(settings.whisperApiKey) {
                    mutableStateOf(settings.whisperApiKey)
                }
                OutlinedTextField(
                    value = whisperKey,
                    onValueChange = {
                        whisperKey = it
                        viewModel.setWhisperApiKey(it)
                    },
                    label = { Text(stringResource(R.string.settings_whisper_api_key)) },
                    placeholder = { Text(stringResource(R.string.settings_whisper_api_key_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    trailingIcon = {
                        PasswordVisibilityIcon(
                            visible = whisperKeyVisible,
                            onToggle = { whisperKeyVisible = it }
                        )
                    },
                    visualTransformation = if (whisperKeyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                if (settings.whisperApiKey.isNotBlank()) {
                    TextButton(onClick = viewModel::clearWhisperApiKey) {
                        Text(stringResource(R.string.settings_key_clear))
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val blockingMissing = whisperReadiness.blockingIssues.any { it == ReadinessIssue.ENDPOINT_MISSING }
                    val blockingInvalid = whisperReadiness.blockingIssues.any { it == ReadinessIssue.ENDPOINT_INVALID }
                    val configured = whisperReadiness.ready
                    Icon(
                        imageVector = if (configured) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = if (configured) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    val statusText = when {
                        blockingMissing -> stringResource(R.string.settings_whisper_blocking_missing)
                        blockingInvalid -> stringResource(R.string.settings_whisper_blocking_invalid)
                        configured && settings.whisperApiKey.isBlank() ->
                            stringResource(R.string.settings_whisper_key_warning)
                        configured -> stringResource(R.string.settings_whisper_status_configured)
                        else -> stringResource(R.string.settings_whisper_status_not_configured)
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (blockingMissing || blockingInvalid)
                            MaterialTheme.colorScheme.error
                        else if (configured) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        onClick = viewModel::testWhisperConnection,
                        enabled = whisperReadiness.blockingIssues.isEmpty() &&
                            whisperTest !is WhisperTestState.Checking,
                    ) {
                        if (whisperTest is WhisperTestState.Checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.settings_whisper_test))
                        }
                    }
                }
            }
            item { WhisperStatusView(whisperTest, onRetest = viewModel::testWhisperConnection) }
            item {
                Text(
                    stringResource(R.string.settings_whisper_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_auto_transcribe),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            stringResource(R.string.settings_auto_transcribe_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.autoTranscribe,
                        onCheckedChange = viewModel::setAutoTranscribe
                    )
                }
            }
            // ---- About ----
            item { SectionHeader(R.string.settings_section_about) }
            item {
                Text(
                    stringResource(R.string.settings_about_version),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
            item {
                PreferenceRow(
                    title = stringResource(R.string.settings_privacy),
                    onClick = { dialog = DialogState.Privacy }
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // ---- Dialogs ----
    when (val d = dialog) {
        DialogState.Theme -> ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = listOf(
                ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
            ),
            selected = settings.themeMode,
            onSelect = { viewModel.setThemeMode(it); dialog = null },
            onDismiss = { dialog = null }
        )
        DialogState.Language -> ChoiceDialog(
            title = stringResource(R.string.settings_language),
            options = listOf(
                AppLanguage.SYSTEM to stringResource(R.string.settings_language_system),
                AppLanguage.ENGLISH to stringResource(R.string.settings_language_english),
                AppLanguage.ARABIC to stringResource(R.string.settings_language_arabic),
            ),
            selected = settings.appLanguage,
            onSelect = {
                viewModel.setAppLanguage(it)
                dialog = null
                // No AppCompatDelegate dependency in this module; persist and inform restart.
                scope.launch { snackbarHostState.showSnackbar(languageRestartNote) }
            },
            onDismiss = { dialog = null }
        )
        DialogState.Quality -> ChoiceDialog(
            title = stringResource(R.string.settings_default_quality),
            options = qualityOptions(),
            selected = settings.defaultQualityHeight,
            onSelect = { viewModel.setDefaultQualityHeight(it); dialog = null },
            onDismiss = { dialog = null }
        )
        DialogState.EmbedMode -> ChoiceDialog(
            title = stringResource(R.string.settings_embed_mode),
            options = listOf(
                EmbedMode.NONE to stringResource(R.string.settings_embed_none),
                EmbedMode.SOFT to stringResource(R.string.settings_embed_soft),
                EmbedMode.HARD to stringResource(R.string.settings_embed_hard),
            ),
            selected = settings.defaultEmbedMode,
            onSelect = { viewModel.setDefaultEmbedMode(it); dialog = null },
            onDismiss = { dialog = null }
        )
        DialogState.TranslationProvider -> ChoiceDialog(
            title = stringResource(R.string.settings_translation_provider),
            options = listOf(
                TranslationProvider.NONE to stringResource(R.string.settings_translation_none),
                TranslationProvider.ML_KIT to stringResource(R.string.settings_translation_mlkit),
                TranslationProvider.LIBRE_TRANSLATE to stringResource(R.string.settings_translation_libretranslate),
            ),
            selected = settings.translationProvider,
            onSelect = { viewModel.setTranslationProvider(it); dialog = null },
            onDismiss = { dialog = null }
        )
        DialogState.Privacy -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.settings_privacy)) },
            text = { Text(stringResource(R.string.settings_privacy_text)) },
            confirmButton = {
                TextButton(onClick = { dialog = null }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
        null -> {}
    }
}

@Composable
private fun PasswordVisibilityIcon(visible: Boolean, onToggle: (Boolean) -> Unit) {
    IconButton(onClick = { onToggle(!visible) }) {
        Icon(
            imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = stringResource(
                if (visible) R.string.settings_hide_password else R.string.settings_show_password
            ),
        )
    }
}

@Composable
private fun ResolverStatusView(
    state: ResolverTestState,
    modifier: Modifier = Modifier,
    onRetest: () -> Unit = {},
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (state) {
            is ResolverTestState.Idle -> Text(
                stringResource(R.string.settings_resolver_test_not_tested),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is ResolverTestState.Checking -> Text(
                stringResource(R.string.settings_resolver_test_checking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is ResolverTestState.Ok -> {
                Icon(
                    Icons.Filled.CheckCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                val detail = if (state.version != null || state.resolvers.isNotEmpty()) {
                    stringResource(
                        R.string.settings_resolver_test_ok_detail,
                        state.version ?: "-",
                        state.resolvers.size
                    )
                } else {
                    stringResource(R.string.settings_resolver_test_ok)
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            is ResolverTestState.Error -> {
                Icon(
                    Icons.Filled.ErrorOutline, contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        resolverErrorMessage(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    // G-3: Retest re-invokes the existing resolver test function.
                    TextButton(onClick = onRetest) {
                        Text(stringResource(R.string.settings_action_retest))
                    }
                }
            }
        }
    }
}

@Composable
private fun resolverErrorMessage(state: ResolverTestState.Error): String = when (state.kind) {
    ResolverTestState.ErrorKind.NOT_CONFIGURED ->
        stringResource(R.string.settings_resolver_error_not_configured)
    ResolverTestState.ErrorKind.INVALID_URL ->
        stringResource(R.string.settings_resolver_error_invalid_url)
    ResolverTestState.ErrorKind.TIMEOUT ->
        stringResource(R.string.settings_resolver_error_timeout)
    ResolverTestState.ErrorKind.NO_NETWORK ->
        stringResource(R.string.settings_resolver_error_no_network)
    ResolverTestState.ErrorKind.HTTP_ERROR ->
        stringResource(R.string.settings_resolver_error_http, state.httpCode ?: 0)
    ResolverTestState.ErrorKind.UNKNOWN ->
        stringResource(R.string.settings_resolver_error_unknown)
}

@Composable
private fun WhisperStatusView(state: WhisperTestState, onRetest: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            is WhisperTestState.Idle -> {}
            is WhisperTestState.Checking -> Text(
                stringResource(R.string.settings_whisper_test_checking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp)
            )
            is WhisperTestState.Ok -> {
                Icon(
                    Icons.Filled.CheckCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.settings_whisper_test_ok),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            is WhisperTestState.Error -> {
                Icon(
                    Icons.Filled.ErrorOutline, contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        stringResource(
                            when (state.kind) {
                                WhisperTestState.ErrorKind.NOT_CONFIGURED -> R.string.settings_whisper_error_not_configured
                                WhisperTestState.ErrorKind.INVALID_URL -> R.string.settings_whisper_error_invalid_url
                                WhisperTestState.ErrorKind.UNREACHABLE -> R.string.settings_whisper_error_unreachable
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    // G-3: Retest re-invokes the existing Whisper test function.
                    TextButton(onClick = onRetest) {
                        Text(stringResource(R.string.settings_action_retest))
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == value,
                            onClick = { onSelect(value) }
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun SectionHeader(titleRes: Int) {
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    HorizontalDivider()
}

@Composable
private fun PreferenceRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

// ---- Label helpers ----

@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
}

@Composable
private fun languageLabel(lang: AppLanguage): String = when (lang) {
    AppLanguage.SYSTEM -> stringResource(R.string.settings_language_system)
    AppLanguage.ENGLISH -> stringResource(R.string.settings_language_english)
    AppLanguage.ARABIC -> stringResource(R.string.settings_language_arabic)
}

@Composable
private fun qualityLabel(height: Int?): String =
    height?.let { "${it}p" } ?: stringResource(R.string.settings_quality_auto)

@Composable
private fun qualityOptions(): List<Pair<Int?, String>> = listOf(
    null to stringResource(R.string.settings_quality_auto),
    144 to "144p",
    240 to "240p",
    360 to "360p",
    480 to "480p",
    720 to "720p",
    1080 to "1080p",
    1440 to "1440p",
    2160 to "2160p",
)

@Composable
private fun embedLabel(mode: EmbedMode): String = when (mode) {
    EmbedMode.NONE -> stringResource(R.string.settings_embed_none)
    EmbedMode.SOFT -> stringResource(R.string.settings_embed_soft)
    EmbedMode.HARD -> stringResource(R.string.settings_embed_hard)
}

@Composable
private fun translationLabel(provider: TranslationProvider): String = when (provider) {
    TranslationProvider.NONE -> stringResource(R.string.settings_translation_none)
    TranslationProvider.ML_KIT -> stringResource(R.string.settings_translation_mlkit)
    TranslationProvider.LIBRE_TRANSLATE -> stringResource(R.string.settings_translation_libretranslate)
}

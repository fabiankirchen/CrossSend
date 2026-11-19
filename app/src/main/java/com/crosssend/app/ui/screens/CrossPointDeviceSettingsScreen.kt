@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.crosssend.app.ui.screens

import android.content.res.Resources
import android.net.Uri
import android.webkit.WebView
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crosssend.app.R
import com.crosssend.app.network.CrossPointSettingItem
import com.crosssend.app.network.CrossPointStatus
import com.crosssend.app.network.RemoteDavItem
import com.crosssend.app.network.WifiConnectionState
import com.crosssend.app.ui.viewmodel.DeviceViewModel
import com.crosssend.app.ui.viewmodel.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class SettingOption(
    val value: String,
    val label: String
)

/**
 * Top-level device management screen for CrossPoint readers, hosting the SD card explorer,
 * reader hardware settings, SD plugin manager, plugin store, plugin configuration sandbox,
 * and local device Web UI.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CrossPointDeviceSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DeviceViewModel = viewModel()
) {
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val currentBaseUrl by viewModel.currentBaseUrl.collectAsStateWithLifecycle()
    val apSsid by viewModel.apSsid.collectAsStateWithLifecycle()
    val lanBaseUrl by viewModel.lanBaseUrl.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val settingsList by viewModel.settingsList.collectAsStateWithLifecycle()
    val isLoadingSettings by viewModel.isLoadingSettings.collectAsStateWithLifecycle()

    // Plugin & Store State
    val sdPlugins by viewModel.sdPlugins.collectAsStateWithLifecycle()
    val isLoadingPlugins by viewModel.isLoadingPlugins.collectAsStateWithLifecycle()
    val catalogPlugins by viewModel.catalogPlugins.collectAsStateWithLifecycle()
    val isLoadingCatalog by viewModel.isLoadingCatalog.collectAsStateWithLifecycle()
    val installingPluginName by viewModel.installingPluginName.collectAsStateWithLifecycle()
    val pluginStoreSearchQuery by viewModel.pluginStoreSearchQuery.collectAsStateWithLifecycle()
    val pluginStoreSelectedFilter by viewModel.pluginStoreSelectedFilter.collectAsStateWithLifecycle()
    val pluginToConfigure by viewModel.pluginToConfigure.collectAsStateWithLifecycle()
    val deviceWebUiUrl by viewModel.deviceWebUiUrl.collectAsStateWithLifecycle()

    // SD Card Explorer State
    val currentDirectory by viewModel.currentDirectory.collectAsStateWithLifecycle()
    val remoteFiles by viewModel.remoteFiles.collectAsStateWithLifecycle()
    val isLoadingFiles by viewModel.isLoadingFiles.collectAsStateWithLifecycle()
    val fileLoadError by viewModel.fileLoadError.collectAsStateWithLifecycle()
    val isUploadingFile by viewModel.isUploadingFile.collectAsStateWithLifecycle()
    val isDownloadingFile by viewModel.isDownloadingFile.collectAsStateWithLifecycle()
    val isUploadingFont by viewModel.isUploadingFont.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.uiEvents.collect { message ->
            snackbarHostState.showSnackbar(message.resolve(resources))
        }
    }

    // false = SD Card Explorer, true = Reader Settings & Plugins
    var showSettings by remember { mutableStateOf(false) }
    var showPluginStore by remember { mutableStateOf(false) }
    var activeDeviceWebView by remember { mutableStateOf<WebView?>(null) }

    var pendingFontUri by remember { mutableStateOf<Uri?>(null) }
    var pendingFontFileName by remember { mutableStateOf("") }
    var pendingFontFamily by remember { mutableStateOf("") }
    var itemToDelete by remember { mutableStateOf<RemoteDavItem?>(null) }
    var showAddressDialog by remember { mutableStateOf(false) }
    var addressInputText by remember { mutableStateOf(lanBaseUrl) }
    var ssidInputText by remember { mutableStateOf(apSsid) }
    var addressInputError by remember { mutableStateOf(false) }

    val uploadPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.uploadFileToCurrentDir(uri)
        }
    }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val safeFontName = viewModel.extractSafeFontFileName(uri)
            if (safeFontName != null) {
                pendingFontUri = uri
                pendingFontFileName = safeFontName
                pendingFontFamily = safeFontName.substringBeforeLast('.')
            }
        }
    }

    LaunchedEffect(isConnected, currentBaseUrl, showSettings) {
        if (isConnected) {
            if (showSettings) {
                viewModel.loadDeviceData()
            } else {
                viewModel.loadDirectory(currentDirectory)
            }
        }
    }

    var backProgress by remember { mutableFloatStateOf(0f) }

    // 1. Predictive Back for Plugin Config
    PredictiveBackHandler(enabled = pluginToConfigure != null) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { event -> backProgress = event.progress }
            viewModel.closePluginConfig()
        } catch (_: CancellationException) {
            backProgress = 0f
        } finally {
            backProgress = 0f
        }
    }

    // 2. Predictive Back for Device Web-UI
    PredictiveBackHandler(enabled = pluginToConfigure == null && deviceWebUiUrl != null) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { event -> backProgress = event.progress }
            if (activeDeviceWebView?.canGoBack() == true) {
                activeDeviceWebView?.goBack()
            } else {
                viewModel.closeDeviceWebUi()
            }
        } catch (_: CancellationException) {
            backProgress = 0f
        } finally {
            backProgress = 0f
        }
    }

    // 3. Predictive Back for Plugin Store
    PredictiveBackHandler(enabled = pluginToConfigure == null && deviceWebUiUrl == null && showPluginStore) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { event -> backProgress = event.progress }
            when {
                pluginStoreSearchQuery.isNotEmpty() -> viewModel.setPluginStoreSearchQuery("")
                pluginStoreSelectedFilter != 0 -> viewModel.setPluginStoreSelectedFilter(0)
                else -> showPluginStore = false
            }
        } catch (_: CancellationException) {
            backProgress = 0f
        } finally {
            backProgress = 0f
        }
    }

    // 4. Predictive Back from Settings back to SD-Card Explorer
    PredictiveBackHandler(enabled = pluginToConfigure == null && deviceWebUiUrl == null && !showPluginStore && !showAddressDialog && showSettings) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { event -> backProgress = event.progress }
            showSettings = false
        } catch (_: CancellationException) {
            backProgress = 0f
        } finally {
            backProgress = 0f
        }
    }

    // 5. Predictive Back inside subfolders of SD-Card Explorer
    PredictiveBackHandler(
        enabled = pluginToConfigure == null && deviceWebUiUrl == null && !showPluginStore && !showAddressDialog && !showSettings && currentDirectory != "/" && currentDirectory.isNotBlank()
    ) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { event -> backProgress = event.progress }
            viewModel.navigateUpDirectory()
        } catch (_: CancellationException) {
            backProgress = 0f
        } finally {
            backProgress = 0f
        }
    }

    val isSubpageActive = pluginToConfigure != null || deviceWebUiUrl != null || showPluginStore
    val effectsFloatSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatialOffsetSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        val activePlugin = pluginToConfigure
                        val activeWebUi = deviceWebUiUrl
                        val title = when {
                            activePlugin != null -> activePlugin.name
                            activeWebUi != null -> stringResource(R.string.dev_title_web_ui)
                            showPluginStore -> stringResource(R.string.dev_title_plugin_store)
                            showSettings -> stringResource(R.string.dev_title_device_settings)
                            else -> stringResource(R.string.dev_title_sd_card)
                        }
                        val subtitle = when {
                            activePlugin != null -> stringResource(R.string.dev_title_plugin_config, activePlugin.folderName)
                            activeWebUi != null -> activeWebUi.removePrefix("http://")
                            showPluginStore -> stringResource(R.string.dev_title_store_catalog)
                            showSettings -> if (isConnected) currentBaseUrl.removePrefix("http://") else stringResource(R.string.status_disconnected)
                            else -> if (currentDirectory.isBlank() || currentDirectory == "/") "/" else currentDirectory
                        }
                        AnimatedContent(
                            targetState = title to subtitle,
                            transitionSpec = {
                                (fadeIn(animationSpec = effectsFloatSpec) +
                                    slideInVertically(animationSpec = spatialOffsetSpec) { it / 3 })
                                    .togetherWith(
                                        fadeOut(animationSpec = effectsFloatSpec) +
                                            slideOutVertically(animationSpec = spatialOffsetSpec) { -it / 3 }
                                    )
                            },
                            label = "TopAppBarTitle"
                        ) { (currentTitle, currentSubtitle) ->
                            Column {
                                Text(
                                    text = currentTitle,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = currentSubtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                when {
                                    pluginToConfigure != null -> viewModel.closePluginConfig()
                                    deviceWebUiUrl != null -> {
                                        if (activeDeviceWebView?.canGoBack() == true) {
                                            activeDeviceWebView?.goBack()
                                        } else {
                                            viewModel.closeDeviceWebUi()
                                        }
                                    }
                                    showPluginStore -> {
                                        viewModel.setPluginStoreSearchQuery("")
                                        viewModel.setPluginStoreSelectedFilter(0)
                                        showPluginStore = false
                                    }
                                    showSettings -> showSettings = false
                                    currentDirectory != "/" && currentDirectory.isNotBlank() -> {
                                        viewModel.navigateUpDirectory()
                                    }
                                    else -> onNavigateBack()
                                }
                            },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.dev_action_back)
                            )
                        }
                    },
                    actions = {
                        val currentPlugin = pluginToConfigure
                        when {
                            currentPlugin != null -> {
                                IconButton(
                                    onClick = { viewModel.openPluginConfig(currentPlugin) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.dev_action_reload_plugin))
                                }
                            }
                            deviceWebUiUrl != null -> {
                                // Clean TopAppBar in Web-UI
                            }
                            showPluginStore -> {
                                IconButton(
                                    onClick = { viewModel.loadPluginCatalog() },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.dev_action_refresh_catalog))
                                }
                            }
                            showSettings -> {
                                IconButton(
                                    onClick = { viewModel.loadDeviceData() },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.dev_action_refresh))
                                }
                            }
                            else -> {
                                IconButton(
                                    onClick = { viewModel.loadDirectory(currentDirectory) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.dev_action_refresh))
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                if (isConnected && !isSubpageActive) {
                    PrimaryTabRow(
                        selectedTabIndex = if (showSettings) 1 else 0,
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Tab(
                            selected = !showSettings,
                            onClick = {
                                if (showSettings) {
                                    showSettings = false
                                }
                            },
                            text = { Text(stringResource(R.string.dev_tab_files), fontWeight = FontWeight.SemiBold) }
                        )
                        Tab(
                            selected = showSettings,
                            onClick = {
                                if (!showSettings) {
                                    showSettings = true
                                    if (settingsList.isEmpty()) {
                                        viewModel.loadDeviceData()
                                    }
                                }
                            },
                            text = { Text(stringResource(R.string.dev_tab_settings), fontWeight = FontWeight.SemiBold) }
                        )
                    }
                }

                AnimatedVisibility(visible = isSyncing) {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        floatingActionButton = {
            if (!showSettings && !isSubpageActive && isConnected) {
                ExtendedFloatingActionButton(
                    onClick = { uploadPickerLauncher.launch("*/*") },
                    icon = {
                        if (isUploadingFile) {
                            LoadingIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            Icon(Icons.Default.UploadFile, contentDescription = null)
                        }
                    },
                    text = {
                        Text(
                            if (isUploadingFile) stringResource(R.string.dev_uploading) else stringResource(R.string.dev_upload_file),
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    ) { paddingValues ->
        val activeDestinationKey = when {
            pluginToConfigure != null -> "PLUGIN_CONFIG"
            deviceWebUiUrl != null -> "WEB_UI"
            showPluginStore -> "PLUGIN_STORE"
            showSettings -> "SETTINGS"
            else -> "FILES"
        }

        AnimatedContent(
            targetState = activeDestinationKey,
            transitionSpec = {
                (fadeIn(effectsFloatSpec) + slideInHorizontally(spatialOffsetSpec) { it / 10 })
                    .togetherWith(
                        fadeOut(effectsFloatSpec) + slideOutHorizontally(spatialOffsetSpec) { -it / 10 }
                    )
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .graphicsLayer {
                    val scale = 1f - (backProgress * 0.03f)
                    scaleX = scale
                    scaleY = scale
                },
            label = "DeviceDestinationTransition"
        ) { destination ->
            when (destination) {
                "FILES" -> {
                    FileExplorerTab(
                        currentPath = currentDirectory,
                        items = remoteFiles,
                        isLoading = isLoadingFiles,
                        isDownloading = isDownloadingFile,
                        isConnected = isConnected,
                        connectionState = connectionState,
                        onNavigateTo = { path -> viewModel.loadDirectory(path) },
                        onDownloadRequested = { item -> viewModel.downloadRemoteItem(item) },
                        onDeleteRequested = { item -> itemToDelete = item },
                        onConnect = { viewModel.connectOrDiscover() },
                        onDirectHotspotConnect = { viewModel.connectToHotspot() },
                        onCancel = { viewModel.cancelConnectionAttempt() },
                        fileLoadError = fileLoadError,
                        onRetry = { viewModel.loadDirectory(currentDirectory) },
                        onChangeAddress = {
                            addressInputText = lanBaseUrl
                            ssidInputText = apSsid
                            addressInputError = false
                            showAddressDialog = true
                        }
                    )
                }

                "SETTINGS" -> {
                    NativeSettingsView(
                        isConnected = isConnected,
                        connectionState = connectionState,
                        currentBaseUrl = currentBaseUrl,
                        onSaveQuickAddress = { newUrl -> viewModel.updateQuickLanAddress(newUrl) },
                        status = status,
                        settingsList = settingsList,
                        isLoading = isLoadingSettings,
                        sdPlugins = sdPlugins,
                        isLoadingPlugins = isLoadingPlugins,
                        onTogglePlugin = { plugin, enabled -> viewModel.togglePlugin(plugin, enabled) },
                        onConfigurePlugin = { plugin -> viewModel.openPluginConfig(plugin) },
                        onOpenPluginStore = {
                            showPluginStore = true
                            if (catalogPlugins.isEmpty()) {
                                viewModel.loadPluginCatalog()
                            }
                        },
                        onInitPluginsDir = { viewModel.initPluginsDirectory() },
                        onNavigateToExplorer = { path ->
                            showSettings = false
                            viewModel.loadDirectory(path)
                        },
                        onUpdateSetting = { key, newValue -> viewModel.updateSetting(key, newValue) },
                        onConnect = { viewModel.connectOrDiscover() },
                        onDirectHotspotConnect = { viewModel.connectToHotspot() },
                        onCancel = { viewModel.cancelConnectionAttempt() },
                        onOpenDeviceWebUi = { viewModel.openDeviceWebUi() },
                        onChangeAddress = {
                            addressInputText = lanBaseUrl
                            ssidInputText = apSsid
                            addressInputError = false
                            showAddressDialog = true
                        },
                        isUploadingFont = isUploadingFont,
                        onUploadFont = { fontPickerLauncher.launch("*/*") }
                    )
                }

                "PLUGIN_STORE" -> {
                    PluginStoreSubpage(
                        catalogPlugins = catalogPlugins,
                        sdPlugins = sdPlugins,
                        isLoadingCatalog = isLoadingCatalog,
                        installingPluginName = installingPluginName,
                        isConnected = isConnected,
                        searchQuery = pluginStoreSearchQuery,
                        onSearchQueryChange = viewModel::setPluginStoreSearchQuery,
                        selectedFilter = pluginStoreSelectedFilter,
                        onSelectedFilterChange = viewModel::setPluginStoreSelectedFilter,
                        onRefreshCatalog = viewModel::loadPluginCatalog,
                        onInstallPlugin = viewModel::installCatalogPlugin,
                        onConfigurePlugin = viewModel::openPluginConfig
                    )
                }

                "PLUGIN_CONFIG" -> {
                    pluginToConfigure?.let { plugin ->
                        PluginConfigSubpage(
                            plugin = plugin,
                            currentBaseUrl = currentBaseUrl,
                            deviceStatus = status,
                            onSaveConfig = { newConfigJson ->
                                viewModel.savePluginConfig(plugin, newConfigJson)
                            }
                        )
                    }
                }

                "WEB_UI" -> {
                    deviceWebUiUrl?.let { url ->
                        DeviceWebUiSubpage(
                            webUiUrl = url,
                            onWebViewCreated = { activeDeviceWebView = it }
                        )
                    }
                }
            }
        }
    }

    // Vector Font Family Name Dialog
    pendingFontUri?.let { fontUri ->
        AlertDialog(
            onDismissRequest = { pendingFontUri = null },
            title = { Text(stringResource(R.string.dev_font_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = pendingFontFamily,
                    onValueChange = { pendingFontFamily = it },
                    label = { Text(stringResource(R.string.dev_font_family_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val fileName = pendingFontFileName
                        val family = pendingFontFamily.trim().ifBlank { fileName.substringBeforeLast('.') }
                        pendingFontUri = null
                        viewModel.uploadFont(fontUri, fileName, family)
                    }
                ) {
                    Text(stringResource(R.string.dev_btn_upload_font))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingFontUri = null }) {
                    Text(stringResource(R.string.dev_btn_cancel))
                }
            }
        )
    }

    // Quick Address & Wi-Fi Change Dialog
    if (showAddressDialog) {
        AlertDialog(
            onDismissRequest = { showAddressDialog = false },
            title = { Text(stringResource(R.string.dev_dialog_wifi_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(R.string.dev_dialog_wifi_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = ssidInputText,
                        onValueChange = { ssidInputText = it },
                        label = { Text(stringResource(R.string.dev_label_wifi_ssid)) },
                        placeholder = { Text(stringResource(R.string.dev_placeholder_wifi_ssid)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        FilterChip(
                            selected = ssidInputText == "Cross",
                            onClick = { ssidInputText = "Cross" },
                            label = { Text(stringResource(R.string.conn_chip_cross_universal)) }
                        )
                        FilterChip(
                            selected = ssidInputText == "CrossPlay",
                            onClick = { ssidInputText = "CrossPlay" },
                            label = { Text(stringResource(R.string.conn_chip_crossplay)) }
                        )
                        FilterChip(
                            selected = ssidInputText == "CrossPoint",
                            onClick = { ssidInputText = "CrossPoint" },
                            label = { Text(stringResource(R.string.conn_chip_crosspoint)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = addressInputText,
                        onValueChange = {
                            addressInputText = it
                            addressInputError = false
                        },
                        label = { Text(stringResource(R.string.dev_label_device_address)) },
                        placeholder = { Text(stringResource(R.string.dev_placeholder_device_address)) },
                        isError = addressInputError,
                        supportingText = if (addressInputError) {
                            { Text(stringResource(R.string.device_error_invalid_address)) }
                        } else {
                            null
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        FilterChip(
                            selected = addressInputText.contains("crossplay.local"),
                            onClick = {
                                addressInputText = "http://crossplay.local"
                                addressInputError = false
                            },
                            label = { Text("crossplay.local") }
                        )
                        FilterChip(
                            selected = addressInputText.contains("crosspoint.local"),
                            onClick = {
                                addressInputText = "http://crosspoint.local"
                                addressInputError = false
                            },
                            label = { Text("crosspoint.local") }
                        )
                        FilterChip(
                            selected = addressInputText.contains("192.168.4.1"),
                            onClick = {
                                addressInputText = "http://192.168.4.1"
                                addressInputError = false
                            },
                            label = { Text(stringResource(R.string.conn_chip_ap_ip)) }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.updateConnectionConfig(ssidInputText, addressInputText)) {
                        addressInputError = false
                        showAddressDialog = false
                        if (showSettings) {
                            viewModel.loadDeviceData()
                        } else {
                            viewModel.loadDirectory(currentDirectory)
                        }
                    } else {
                        addressInputError = true
                    }
                }) {
                    Text(stringResource(R.string.dev_btn_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddressDialog = false }) {
                    Text(stringResource(R.string.dev_btn_cancel))
                }
            }
        )
    }

    // Delete Confirmation Dialog for File Explorer
    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text(stringResource(R.string.dev_delete_dialog_title)) },
            text = { Text(stringResource(R.string.dev_delete_dialog_msg, item.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRemoteItem(item)
                        itemToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.dev_action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text(stringResource(R.string.dev_btn_cancel))
                }
            }
        )
    }
}

@Composable
internal fun CrossPointConnectionCard(
    connectionState: WifiConnectionState,
    onConnect: () -> Unit,
    onDirectHotspotConnect: () -> Unit,
    onChangeAddress: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnecting = connectionState is WifiConnectionState.ConnectingToAp ||
        connectionState is WifiConnectionState.SearchingOnLan
    val errorState = connectionState as? WifiConnectionState.Error

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = if (errorState != null) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                },
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (errorState != null) Icons.Default.Error else Icons.Default.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = if (errorState != null) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (errorState != null) {
                    stringResource(R.string.dev_connection_failed)
                } else {
                    stringResource(R.string.dev_not_connected_title)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(6.dp))

            if (errorState != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = errorState.message.ifBlank { stringResource(R.string.wifi_error_connection) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.dev_not_connected_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (isConnecting) {
                val statusText = if (connectionState is WifiConnectionState.SearchingOnLan) {
                    stringResource(R.string.dev_status_searching_lan)
                } else {
                    stringResource(R.string.dev_status_connecting_hotspot)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    LoadingIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dev_btn_cancel))
                }
            } else {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dev_btn_auto_connect))
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onDirectHotspotConnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dev_btn_direct_hotspot))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            TextButton(
                onClick = onChangeAddress,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.dev_btn_customize_connection))
            }
        }
    }
}

@Composable
private fun NativeSettingsView(
    isConnected: Boolean,
    connectionState: WifiConnectionState,
    currentBaseUrl: String,
    onSaveQuickAddress: (String) -> Boolean,
    status: CrossPointStatus?,
    settingsList: List<CrossPointSettingItem>,
    isLoading: Boolean,
    sdPlugins: List<SdPluginInfo>,
    isLoadingPlugins: Boolean,
    onTogglePlugin: (SdPluginInfo, Boolean) -> Unit,
    onConfigurePlugin: (SdPluginInfo) -> Unit,
    onOpenPluginStore: () -> Unit,
    onInitPluginsDir: () -> Unit,
    onNavigateToExplorer: (String) -> Unit,
    onUpdateSetting: (String, Any) -> Unit,
    onConnect: () -> Unit,
    onDirectHotspotConnect: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDeviceWebUi: () -> Unit = {},
    onChangeAddress: () -> Unit = {},
    isUploadingFont: Boolean = false,
    onUploadFont: () -> Unit = {}
) {
    val resources = LocalResources.current
    val isDe = LocalLocale.current.platformLocale.language == "de"

    var settingToPick by remember { mutableStateOf<Pair<CrossPointSettingItem, List<SettingOption>>?>(null) }
    var settingToEdit by remember { mutableStateOf<CrossPointSettingItem?>(null) }
    var editValueText by remember { mutableStateOf("") }
    var showPasswordText by remember { mutableStateOf(false) }
    var addressFieldText by remember(currentBaseUrl) { mutableStateOf(currentBaseUrl) }
    var addressFieldError by remember(currentBaseUrl) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!isConnected) {
            CrossPointConnectionCard(
                connectionState = connectionState,
                onConnect = onConnect,
                onDirectHotspotConnect = onDirectHotspotConnect,
                onChangeAddress = onChangeAddress,
                onCancel = onCancel
            )
        } else {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.dev_status_connected),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        status?.batteryPercent?.let { bat ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = stringResource(R.string.dev_status_battery, bat),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = addressFieldText,
                        onValueChange = {
                            addressFieldText = it
                            addressFieldError = false
                        },
                        label = { Text(stringResource(R.string.dev_label_device_address)) },
                        isError = addressFieldError,
                        supportingText = if (addressFieldError) {
                            { Text(stringResource(R.string.device_error_invalid_address)) }
                        } else {
                            null
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                addressFieldError = !onSaveQuickAddress(addressFieldText)
                            }
                        ),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    addressFieldError = !onSaveQuickAddress(addressFieldText)
                                },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = resources.getString(R.string.dev_btn_save)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = currentBaseUrl == "http://crosspoint.local",
                            onClick = {
                                addressFieldText = "http://crosspoint.local"
                                addressFieldError = !onSaveQuickAddress("http://crosspoint.local")
                            },
                            label = { Text("crosspoint.local") }
                        )
                        FilterChip(
                            selected = currentBaseUrl == "http://192.168.4.1",
                            onClick = {
                                addressFieldText = "http://192.168.4.1"
                                addressFieldError = !onSaveQuickAddress("http://192.168.4.1")
                            },
                            label = { Text(stringResource(R.string.conn_chip_ap_ip)) }
                        )
                    }

                    if (status != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        if (!status.deviceModel.isNullOrBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.dev_label_detected_model), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(status.deviceModel, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.dev_label_wifi_mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(status.wifiMode, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                        if (status.firmwareVersion != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.dev_label_firmware), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(status.firmwareVersion, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (status.freeHeap > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.dev_label_free_memory), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${status.freeHeap / 1024} KB", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onOpenDeviceWebUi,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.dev_btn_open_web_ui))
                    }
                }
            }
        }

        // Categorized Settings Cards
        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        } else if (settingsList.isNotEmpty()) {
            val categorized = remember(settingsList, resources) {
                categorizeSettings(settingsList, resources)
            }

            categorized.forEach { (categoryTitle, items) ->
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = categoryTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        items.forEachIndexed { index, setting ->
                            SettingItemRow(
                                setting = setting,
                                isDe = isDe,
                                onUpdateSetting = onUpdateSetting,
                                onRequestPick = { s, opts -> settingToPick = s to opts },
                                onRequestEdit = { s ->
                                    settingToEdit = s
                                    editValueText = if (s.value.toString() == "null") "" else s.value.toString()
                                    showPasswordText = false
                                }
                            )
                            if (index < items.size - 1) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // SD-Plugins Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.dev_plugins_section_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (sdPlugins.isNotEmpty()) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "${sdPlugins.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = onOpenPluginStore,
                            shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 40.dp)
                                .minimumInteractiveComponentSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.dev_btn_store), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.dev_plugins_section_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (isLoadingPlugins) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator(modifier = Modifier.size(24.dp))
                    }
                } else if (sdPlugins.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.dev_no_plugins_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.dev_no_plugins_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = onOpenPluginStore,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.dev_btn_plugin_store_github), style = MaterialTheme.typography.labelMedium)
                            }
                            FilledTonalButton(
                                onClick = onInitPluginsDir,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(stringResource(R.string.dev_btn_create_folder), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                } else {
                    sdPlugins.forEachIndexed { idx, plugin ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(MaterialTheme.shapes.extraSmall)
                                            .background(
                                                if (plugin.isEnabled) MaterialTheme.colorScheme.primaryContainer
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Extension,
                                            contentDescription = null,
                                            tint = if (plugin.isEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = plugin.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "v${plugin.version}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = plugin.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Switch(
                                    checked = plugin.isEnabled,
                                    onCheckedChange = { onTogglePlugin(plugin, it) }
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { onNavigateToExplorer("${plugin.parentDir.trimEnd('/')}/${plugin.folderName}") },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text(stringResource(R.string.dev_btn_files), style = MaterialTheme.typography.labelMedium)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                FilledTonalButton(
                                    onClick = { onConfigurePlugin(plugin) },
                                    shape = MaterialTheme.shapes.extraSmall,
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text(stringResource(R.string.dev_btn_configure), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        if (idx < sdPlugins.size - 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        // Vector Fonts Card (CrossPoint v2.0)
        if (isConnected && status?.vectorFonts == true) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.UploadFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.dev_fonts_section_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "v2.0",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.dev_fonts_section_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = onUploadFont,
                        enabled = !isUploadingFont,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isUploadingFont) {
                            LoadingIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dev_btn_uploading_font))
                        } else {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dev_btn_upload_font))
                        }
                    }
                }
            }
        }
    }

    // Single-Choice Option Picker Dialog
    settingToPick?.let { (setting, options) ->
        val currentStrVal = setting.value.toString().trim()

        AlertDialog(
            onDismissRequest = { settingToPick = null },
            title = { Text(localizeSettingName(setting.name, isDe), fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEach { opt ->
                        val isSelected = opt.value.equals(currentStrVal, ignoreCase = true) ||
                            opt.label.equals(currentStrVal, ignoreCase = true)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .selectable(
                                    selected = isSelected,
                                    onClick = {
                                        val outVal: Any = if (setting.value is Number) {
                                            opt.value.toIntOrNull() ?: opt.value
                                        } else {
                                            opt.value
                                        }
                                        onUpdateSetting(setting.key, outVal)
                                        settingToPick = null
                                    }
                                )
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = opt.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { settingToPick = null }) {
                    Text(stringResource(R.string.dev_btn_cancel))
                }
            }
        )
    }

    // Text Input Dialog (Username, Password, URL, Device Name)
    settingToEdit?.let { setting ->
        val isPassword = setting.key.contains("password", ignoreCase = true)

        AlertDialog(
            onDismissRequest = { settingToEdit = null },
            title = { Text(localizeSettingName(setting.name, isDe), fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                ) {
                    OutlinedTextField(
                        value = editValueText,
                        onValueChange = { editValueText = it },
                        label = { Text(localizeSettingName(setting.name, isDe)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (isPassword && !showPasswordText) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = if (isPassword) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                        trailingIcon = if (isPassword) {
                            {
                                IconButton(
                                    onClick = { showPasswordText = !showPasswordText },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = if (showPasswordText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = LocalResources.current.getString(
                                            if (showPasswordText) R.string.device_hide_password else R.string.device_show_password
                                        )
                                    )
                                }
                            }
                        } else {
                            null
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdateSetting(setting.key, editValueText.trim())
                        settingToEdit = null
                    }
                ) {
                    Text(stringResource(R.string.dev_btn_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { settingToEdit = null }) {
                    Text(stringResource(R.string.dev_btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingItemRow(
    setting: CrossPointSettingItem,
    isDe: Boolean,
    onUpdateSetting: (String, Any) -> Unit,
    onRequestPick: (CrossPointSettingItem, List<SettingOption>) -> Unit,
    onRequestEdit: (CrossPointSettingItem) -> Unit,
    modifier: Modifier = Modifier
) {
    // 1. Boolean Switch
    if (isBooleanSetting(setting)) {
        val isChecked = parseBooleanValue(setting.value)
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = localizeSettingName(setting.name, isDe),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Switch(
                checked = isChecked,
                onCheckedChange = { checked ->
                    val outVal: Any = when {
                        setting.options.size == 2 && setting.value is Number -> if (checked) 1 else 0
                        setting.value is Boolean -> checked
                        setting.value is Number || setting.value.toString() == "0" || setting.value.toString() == "1" -> if (checked) 1 else 0
                        else -> checked
                    }
                    onUpdateSetting(setting.key, outVal)
                }
            )
        }
        return
    }

    // 2. Option Picker
    val options = resolveSettingOptions(setting, isDe)
    if (!options.isNullOrEmpty()) {
        val currentStr = setting.value.toString().trim()
        val matchingOpt = options.firstOrNull { it.value.equals(currentStr, ignoreCase = true) }
        val displayLabel = matchingOpt?.label ?: getSettingDisplayLabel(setting, isDe)

        Row(
            modifier = modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onRequestPick(setting, options) }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = localizeSettingName(setting.name, isDe),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayLabel,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        return
    }

    // 3. Number Slider
    val isNumber = setting.type == "value" || setting.type == "int" ||
        ((setting.value is Number || setting.value.toString().toIntOrNull() != null) && !isBooleanSetting(setting) && setting.options.isEmpty())

    if (isNumber) {
        val rawNum = (setting.value as? Number)?.toFloat() ?: setting.value.toString().toFloatOrNull() ?: 0f
        val minVal = setting.min?.toFloat() ?: 0f
        val maxVal = setting.max?.toFloat() ?: 60f
        val safeMax = if (maxVal > minVal) maxVal else minVal + 1f
        val stepVal = setting.step?.toFloat() ?: 1f
        val steps = if (stepVal > 0f) (((safeMax - minVal) / stepVal).toInt() - 1).coerceAtLeast(0) else 0
        val coercedVal = rawNum.coerceIn(minVal, safeMax)
        val sliderState = rememberSliderState(
            value = coercedVal,
            steps = steps,
            trackRange = minVal..safeMax
        )
        LaunchedEffect(coercedVal) {
            if (abs(sliderState.value - coercedVal) > 0.001f) {
                sliderState.value = coercedVal
            }
        }

        val unit = when {
            setting.key.contains("margin", true) -> " px"
            setting.key.contains("timeout", true) || setting.key.contains("sleep", true) -> stringResource(R.string.dev_unit_minutes)
            setting.key.contains("size", true) -> " pt"
            else -> ""
        }

        Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(localizeSettingName(setting.name, isDe), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    text = "${sliderState.value.toInt()}$unit",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                state = sliderState,
                onValueChange = { newVal ->
                    sliderState.value = newVal
                },
                onValueChangeFinished = {
                    val newVal = sliderState.value
                    val rounded = if (stepVal > 0f) {
                        (((newVal - minVal) / stepVal).roundToInt() * stepVal + minVal)
                    } else {
                        newVal
                    }
                    onUpdateSetting(setting.key, rounded.toInt())
                }
            )
        }
        return
    }

    // 4. Text Setting
    val isPassword = setting.key.contains("password", ignoreCase = true)
    val valStr = setting.value.toString().trim()
    val notSetLabel = stringResource(R.string.dev_value_not_set)
    val displayVal = when {
        isPassword && valStr.isNotBlank() && valStr != "null" -> "••••••••"
        valStr.isBlank() || valStr == "null" -> notSetLabel
        else -> valStr
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onRequestEdit(setting) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = localizeSettingName(setting.name, isDe),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = displayVal,
                style = MaterialTheme.typography.bodySmall,
                color = if (displayVal == notSetLabel) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun isBooleanSetting(setting: CrossPointSettingItem): Boolean {
    val type = setting.type.lowercase(Locale.ROOT)
    if (type == "toggle" || type == "bool" || type == "boolean" || type == "checkbox" || type == "switch") return true
    if (setting.value is Boolean) return true

    if (setting.options.size == 2) {
        val o0 = setting.options[0].lowercase(Locale.ROOT)
        val o1 = setting.options[1].lowercase(Locale.ROOT)
        if ((o0 == "off" && o1 == "on") || (o0 == "disabled" && o1 == "enabled") ||
            (o0 == "aus" && o1 == "ein") || (o0 == "aus" && o1 == "an")) {
            return true
        }
    }

    val strVal = setting.value.toString().trim().lowercase(Locale.ROOT)
    if (strVal == "true" || strVal == "false") return true

    val key = setting.key.lowercase(Locale.ROOT)
    val name = setting.name.lowercase(Locale.ROOT)

    if (setting.options.isEmpty() && (strVal == "0" || strVal == "1" || strVal == "" || strVal == "null")) {
        if (name.startsWith("show") || name.startsWith("use") || name.startsWith("clear") ||
            name.startsWith("move") || name.startsWith("enable") || name.startsWith("disable") ||
            name.contains("hidden") || name.contains("battery") || name.contains("style") ||
            name.contains("daylight") || name.contains("header") ||
            key.contains("invert") || key.contains("battery") || key.contains("wifi_sleep") ||
            key.contains("clock_enable") || key.contains("daylight") || key.contains("header")
        ) {
            return true
        }
    }

    return false
}

private fun parseBooleanValue(value: Any): Boolean {
    return when (value) {
        is Boolean -> value
        is Number -> value.toInt() == 1
        else -> {
            val s = value.toString().trim().lowercase(Locale.ROOT)
            s == "true" || s == "1"
        }
    }
}

private fun resolveSettingOptions(setting: CrossPointSettingItem, isDe: Boolean): List<SettingOption>? {
    val key = setting.key.lowercase(Locale.ROOT)
    val name = setting.name.lowercase(Locale.ROOT)

    if (setting.options.isNotEmpty()) {
        return setting.options.mapIndexed { idx, opt ->
            val label = localizeOption(opt, isDe)
            val optVal = if (setting.value is Number || setting.value.toString().toIntOrNull() != null) {
                idx.toString()
            } else {
                opt
            }
            SettingOption(optVal, label)
        }
    }

    if (key.contains("matching") || name.contains("document matching")) {
        return if (isDe) {
            listOf(
                SettingOption("0", "Dateiname (Filename)"),
                SettingOption("1", "Buchtitel (Title)"),
                SettingOption("2", "Vollständiger Pfad")
            )
        } else {
            listOf(
                SettingOption("0", "Filename"),
                SettingOption("1", "Book Title"),
                SettingOption("2", "Full Path")
            )
        }
    }
    if (key.contains("metadata") && (key.contains("send") || name.contains("send document metadata"))) {
        return if (isDe) {
            listOf(
                SettingOption("0", "Deaktiviert"),
                SettingOption("1", "Nur Lesefortschritt"),
                SettingOption("2", "Vollständige Metadaten")
            )
        } else {
            listOf(
                SettingOption("0", "Disabled"),
                SettingOption("1", "Reading Progress Only"),
                SettingOption("2", "Full Metadata")
            )
        }
    }
    if (key.contains("sync") && (key.contains("behavior") || name.contains("sync behavior"))) {
        return if (isDe) {
            listOf(
                SettingOption("0", "Nur Abrufen (Pull)"),
                SettingOption("1", "Nur Senden (Push)"),
                SettingOption("2", "Beidseitig (2-Wege-Sync)")
            )
        } else {
            listOf(
                SettingOption("0", "Pull Only"),
                SettingOption("1", "Push Only"),
                SettingOption("2", "Two-Way Sync")
            )
        }
    }

    if (key == "clock" || name == "clock" || key.contains("clock_format")) {
        return if (isDe) {
            listOf(
                SettingOption("0", "Aus"),
                SettingOption("1", "12-Stunden-Format"),
                SettingOption("2", "24-Stunden-Format")
            )
        } else {
            listOf(
                SettingOption("0", "Off"),
                SettingOption("1", "12-Hour Format"),
                SettingOption("2", "24-Hour Format")
            )
        }
    }

    if (key.contains("sleep") && (key.contains("time") || key.contains("timeout")) || name.contains("time to sleep")) {
        return if (isDe) {
            listOf(
                SettingOption("5", "5 Minuten"),
                SettingOption("10", "10 Minuten"),
                SettingOption("15", "15 Minuten"),
                SettingOption("30", "30 Minuten"),
                SettingOption("0", "Nie (Aus)")
            )
        } else {
            listOf(
                SettingOption("5", "5 Minutes"),
                SettingOption("10", "10 Minutes"),
                SettingOption("15", "15 Minutes"),
                SettingOption("30", "30 Minutes"),
                SettingOption("0", "Never (Off)")
            )
        }
    }

    if (key.contains("rotation") || key.contains("orientation")) {
        return if (isDe) {
            listOf(
                SettingOption("0", "Hochformat (0°)"),
                SettingOption("1", "Querformat (90°)"),
                SettingOption("2", "Kopfstehend (180°)"),
                SettingOption("3", "Querformat (270°)")
            )
        } else {
            listOf(
                SettingOption("0", "Portrait (0°)"),
                SettingOption("1", "Landscape (90°)"),
                SettingOption("2", "Inverted Portrait (180°)"),
                SettingOption("3", "Inverted Landscape (270°)")
            )
        }
    }

    if (key.contains("timezone") || key.contains("tz") || key.contains("gmt") || name.contains("timezone") || name.contains("zeitzone")) {
        return if (isDe) {
            listOf(
                SettingOption("-12", "UTC-12:00 (Bakerinsel)"),
                SettingOption("-11", "UTC-11:00 (Samoa)"),
                SettingOption("-10", "UTC-10:00 (Hawaii)"),
                SettingOption("-9", "UTC-09:00 (Alaska)"),
                SettingOption("-8", "UTC-08:00 (Pazifik / Los Angeles)"),
                SettingOption("-7", "UTC-07:00 (Mountain / Denver)"),
                SettingOption("-6", "UTC-06:00 (Central / Chicago)"),
                SettingOption("-5", "UTC-05:00 (Eastern / New York)"),
                SettingOption("-4", "UTC-04:00 (Atlantik / Santiago)"),
                SettingOption("-3", "UTC-03:00 (Buenos Aires, São Paulo)"),
                SettingOption("-2", "UTC-02:00 (Mittelatlantik)"),
                SettingOption("-1", "UTC-01:00 (Azoren, Kap Verde)"),
                SettingOption("0", "UTC±00:00 (London, Dublin, Lissabon)"),
                SettingOption("1", "UTC+01:00 (Berlin, Paris, Rom, Wien)"),
                SettingOption("2", "UTC+02:00 (Kairo, Helsinki, Athen)"),
                SettingOption("3", "UTC+03:00 (Moskau, Istanbul, Riad)"),
                SettingOption("4", "UTC+04:00 (Dubai, Baku)"),
                SettingOption("5", "UTC+05:00 (Karatschi, Taschkent)"),
                SettingOption("6", "UTC+06:00 (Dhaka, Almaty)"),
                SettingOption("7", "UTC+07:00 (Bangkok, Jakarta)"),
                SettingOption("8", "UTC+08:00 (Singapur, Peking, Perth)"),
                SettingOption("9", "UTC+09:00 (Tokio, Seoul)"),
                SettingOption("10", "UTC+10:00 (Sydney, Melbourne)"),
                SettingOption("11", "UTC+11:00 (Nouméa, Salomonen)"),
                SettingOption("12", "UTC+12:00 (Auckland, Fidschi)"),
                SettingOption("13", "UTC+13:00 (Tonga)"),
                SettingOption("14", "UTC+14:00 (Kiritimati)")
            )
        } else {
            listOf(
                SettingOption("-12", "UTC-12:00 (Baker Island)"),
                SettingOption("-11", "UTC-11:00 (Samoa)"),
                SettingOption("-10", "UTC-10:00 (Hawaii)"),
                SettingOption("-9", "UTC-09:00 (Alaska)"),
                SettingOption("-8", "UTC-08:00 (Pacific / Los Angeles)"),
                SettingOption("-7", "UTC-07:00 (Mountain / Denver)"),
                SettingOption("-6", "UTC-06:00 (Central / Chicago)"),
                SettingOption("-5", "UTC-05:00 (Eastern / New York)"),
                SettingOption("-4", "UTC-04:00 (Atlantic / Santiago)"),
                SettingOption("-3", "UTC-03:00 (Buenos Aires, São Paulo)"),
                SettingOption("-2", "UTC-02:00 (Mid-Atlantic)"),
                SettingOption("-1", "UTC-01:00 (Azores, Cape Verde)"),
                SettingOption("0", "UTC±00:00 (London, Dublin, Lisbon)"),
                SettingOption("1", "UTC+01:00 (Berlin, Paris, Rome, Vienna)"),
                SettingOption("2", "UTC+02:00 (Cairo, Helsinki, Athens)"),
                SettingOption("3", "UTC+03:00 (Moscow, Istanbul, Riyadh)"),
                SettingOption("4", "UTC+04:00 (Dubai, Baku)"),
                SettingOption("5", "UTC+05:00 (Karachi, Tashkent)"),
                SettingOption("6", "UTC+06:00 (Dhaka, Almaty)"),
                SettingOption("7", "UTC+07:00 (Bangkok, Jakarta)"),
                SettingOption("8", "UTC+08:00 (Singapore, Beijing, Perth)"),
                SettingOption("9", "UTC+09:00 (Tokyo, Seoul)"),
                SettingOption("10", "UTC+10:00 (Sydney, Melbourne)"),
                SettingOption("11", "UTC+11:00 (Noumea, Solomon Islands)"),
                SettingOption("12", "UTC+12:00 (Auckland, Fiji)"),
                SettingOption("13", "UTC+13:00 (Tonga)"),
                SettingOption("14", "UTC+14:00 (Kiritimati)")
            )
        }
    }

    return null
}

private fun getSettingDisplayLabel(setting: CrossPointSettingItem, isDe: Boolean): String {
    val strVal = setting.value.toString().trim()
    val key = setting.key.lowercase(Locale.ROOT)

    if (setting.options.isNotEmpty()) {
        val intIdx = strVal.toIntOrNull()
        if (intIdx != null && intIdx in setting.options.indices) {
            return localizeOption(setting.options[intIdx], isDe)
        }
        val match = setting.options.firstOrNull { it.equals(strVal, ignoreCase = true) }
        if (match != null) {
            return localizeOption(match, isDe)
        }
    }

    return when {
        key.contains("matching") -> when (strVal) {
            "0" -> if (isDe) "Dateiname" else "File Name"
            "1" -> if (isDe) "Buchtitel" else "Book Title"
            "2" -> if (isDe) "Pfad" else "Path"
            else -> strVal
        }
        key.contains("metadata") -> when (strVal) {
            "0" -> if (isDe) "Deaktiviert" else "Disabled"
            "1" -> if (isDe) "Fortschritt" else "Progress"
            "2" -> if (isDe) "Vollständig" else "Complete"
            else -> strVal
        }
        key.contains("sync") && key.contains("behavior") -> when (strVal) {
            "0" -> if (isDe) "Nur Abrufen (Pull)" else "Pull only"
            "1" -> if (isDe) "Nur Senden (Push)" else "Push only"
            "2" -> if (isDe) "2-Wege" else "Two-way"
            else -> strVal
        }
        key == "clock" || key.contains("clock_format") -> when (strVal) {
            "0" -> if (isDe) "Aus" else "Off"
            "1" -> "12h"
            "2" -> "24h"
            else -> strVal
        }
        key.contains("sleep") && (key.contains("time") || key.contains("timeout")) -> when (strVal) {
            "0" -> if (isDe) "Nie" else "Never"
            else -> if (isDe) "$strVal Min." else "$strVal min."
        }
        else -> strVal.ifBlank { "-" }
    }
}

private fun localizeOption(rawOption: String, isDe: Boolean): String {
    if (!isDe) {
        return rawOption
    }
    val opt = rawOption.trim()
    return when (opt.lowercase(Locale.ROOT)) {
        "classic" -> "Klassisch (Classic)"
        "lyra" -> "Lyra"
        "lyra extended" -> "Lyra Erweitert"
        "roundedraff" -> "Rounded Raff"
        "dark" -> "Dunkel (Schwarz)"
        "light" -> "Hell (Weiß)"
        "custom" -> "Eigenes Bild"
        "cover" -> "Aktuelles Buch-Cover"
        "cover + custom" -> "Cover, sonst Bild"
        "none" -> "Keiner"
        "quick resume" -> "Schnell-Fortsetzen"
        "transparent" -> "Transparent"
        "fit" -> "Einpassen (Fit)"
        "crop" -> "Zuschneiden (Crop)"
        "contrast" -> "Hoher Kontrast"
        "inverted" -> "Invertiert"
        "never" -> "Nie"
        "in reader" -> "Nur beim Lesen"
        "always" -> "Immer"
        "1 page" -> "Jede Seite"
        "5 pages" -> "Alle 5 Seiten"
        "10 pages" -> "Alle 10 Seiten"
        "15 pages" -> "Alle 15 Seiten"
        "30 pages" -> "Alle 30 Seiten"
        "noto serif" -> "Noto Serif (Standard)"
        "noto sans" -> "Noto Sans"
        "12 pt" -> "12 pt"
        "14 pt" -> "14 pt"
        "16 pt" -> "16 pt (Standard)"
        "18 pt" -> "18 pt"
        "tight" -> "Eng (Tight)"
        "normal" -> "Normal"
        "wide" -> "Weit (Wide)"
        "extra wide" -> "Sehr weit"
        "justify" -> "Blocksatz"
        "left" -> "Linksbündig"
        "center" -> "Zentriert"
        "right" -> "Rechtsbündig"
        "book's style" -> "Buch-Standard"
        "portrait" -> "Hochformat (0°)"
        "landscape cw" -> "Querformat (90°)"
        "portrait 180°" -> "Kopfstehend (180°)"
        "landscape ccw" -> "Querformat (270°)"
        "display" -> "Bilder anzeigen"
        "placeholder" -> "Platzhalter anzeigen"
        "suppress" -> "Bilder ausblenden"
        "list" -> "Listenansicht"
        "toolbar" -> "Symbolleiste"
        "prev/next" -> "Oben: Zurück / Unten: Vor"
        "next/prev" -> "Oben: Vor / Unten: Zurück"
        "disabled" -> "Deaktiviert"
        "off" -> "Aus"
        "on" -> "Ein"
        "tap" -> "Tippen (Tap)"
        "swipe" -> "Wischen (Swipe)"
        "inverted tap" -> "Invertiertes Tippen"
        "chapter skip" -> "Kapitel überspringen"
        "orientation change" -> "Ausrichtung drehen"
        "kosync" -> "KOSync ausführen"
        "bookmark" -> "Lesezeichen"
        "dictionary" -> "Wörterbuch"
        "ignore" -> "Ignorieren"
        "sleep" -> "Standby"
        "page turn" -> "Seite umblättern"
        "refresh screen" -> "Bildschirm auffrischen"
        "footnotes" -> "Fußnoten"
        "confirm" -> "Bestätigen"
        else -> rawOption
    }
}

private fun localizeSettingName(rawName: String, isDe: Boolean): String {
    if (!isDe) {
        return rawName
    }
    return when (rawName) {
        "Sleep Screen" -> "Ruhebildschirm"
        "Sleep Screen Cover Mode" -> "Cover-Skalierung im Standby"
        "Sleep Screen Cover Filter" -> "Cover-Filter im Standby"
        "Quick Resume on Timeout" -> "Schnell-Fortsetzen bei Timeout"
        "Hide Battery %" -> "Akkustand in %"
        "Refresh Frequency" -> "Bildschirm-Auffrischungsrate"
        "UI Theme" -> "Design-Theme"
        "Night Mode" -> "Nachtmodus (Invertieren)"
        "Reader Font Family" -> "Schriftart im Reader"
        "Reader Font Size" -> "Schriftgröße"
        "Line Spacing" -> "Zeilenabstand"
        "Screen Margin" -> "Seitenrand"
        "Paragraph Alignment" -> "Textausrichtung"
        "Embedded Style" -> "Buch-eigenes Stylesheet nutzen"
        "Focus Reading" -> "Fokus-Lesemodus (Bionic)"
        "Hyphenation" -> "Silbentrennung"
        "Reading Orientation" -> "Leseausrichtung"
        "Extra Paragraph Spacing" -> "Zusätzlicher Absatzabstand"
        "Text Anti-Aliasing" -> "Text-Kantenglättung"
        "Images" -> "Bilder in Büchern"
        "Reader Menu Style" -> "Menüstil im Reader"
        "Side Button Layout (Reader)" -> "Seitentasten-Belegung"
        "Touch Reader Controls" -> "Touch-Gesten beim Lesen"
        "Long-Press Button Behavior" -> "Langer Tastendruck"
        "Long-Press Menu" -> "Langer Druck Menüfunktion"
        "Short Power Button Click" -> "Kurzer Power-Tastendruck"
        "Tilt Page Turn" -> "Umblättern durch Neigen"
        "Quick-Return from Footnotes" -> "Schnell-Rückkehr aus Fußnoten"
        "Time to Sleep" -> "Automatischer Ruhezustand nach"
        "Show Hidden Files" -> "Versteckte Dateien anzeigen"
        "Use Book Metadata" -> "Buch-Metadaten nutzen"
        "Clear Read Books from Recent List" -> "Gelesene Bücher aus Verlauf entfernen"
        "Move Finished Books to Read Folder" -> "Gelesene Bücher in 'read'-Ordner verschieben"
        "Download Folder" -> "Download-Verzeichnis"
        "Send Document Metadata" -> "Dokument-Metadaten an Sync senden"
        "Document Matching" -> "Dokumenten-Abgleich"
        "Sync Behavior" -> "Synchronisations-Richtung"
        "Clock" -> "Uhranzeige"
        "Clock Format" -> "Uhrzeit-Format"
        "Time Zone" -> "Zeitzone"
        "Daylight Saving" -> "Sommerzeit (DST)"
        "Show in Header" -> "In Kopfzeile anzeigen"
        "Battery" -> "Batterie-Anzeige"
        "KOReader Username" -> "KOReader Benutzername"
        "KOReader Password" -> "KOReader Passwort"
        "Sync Server URL" -> "KOReader Server URL"
        "Bluetooth Page Turner" -> "Bluetooth-Blätterfernbedienung"
        "Kerning" -> "Kerning (Buchstabenabstand)"
        "Dictionary" -> "Wörterbuch (StarDict)"
        "Highlights & Clippings" -> "Markierungen & Notizen"
        "Sync Clippings" -> "Markierungen synchronisieren"
        "Sync Bookmarks" -> "Lesezeichen synchronisieren"
        else -> rawName
    }
}

private fun categorizeSettings(
    list: List<CrossPointSettingItem>,
    resources: Resources
): Map<String, List<CrossPointSettingItem>> {
    val cloudItems = mutableListOf<CrossPointSettingItem>()
    val readerItems = mutableListOf<CrossPointSettingItem>()
    val displayItems = mutableListOf<CrossPointSettingItem>()
    val controlItems = mutableListOf<CrossPointSettingItem>()
    val systemItems = mutableListOf<CrossPointSettingItem>()
    val otherItems = mutableListOf<CrossPointSettingItem>()

    for (item in list) {
        val cat = item.category.trim().lowercase(Locale.ROOT)
        val k = item.key.lowercase(Locale.ROOT)
        val n = item.name.lowercase(Locale.ROOT)

        when {
            cat == "cloud" || k.contains("koreader") || k.contains("sync") || n.contains("koreader") || n.contains("matching") -> {
                cloudItems.add(item)
            }
            cat == "reader" || k.contains("font") || k.contains("spacing") || k.contains("margin") || k.contains("alignment") || k.contains("reading") || k.contains("hyphenation") ||
                k.contains("kerning") || k.contains("dictionary") || k.contains("stardict") || k.contains("clipping") || k.contains("highlight") || k.contains("bookmark") || k.contains("footnote") || k.contains("ruby") -> {
                readerItems.add(item)
            }
            cat == "display" || k.contains("theme") || k.contains("screen") || k.contains("refresh") || k.contains("invert") -> {
                displayItems.add(item)
            }
            cat == "controls" || k.contains("button") || k.contains("touch") || k.contains("tilt") || k.contains("press") ||
                k.contains("bluetooth") || k.contains("bt_") || k.contains("turner") || k.contains("remote") || k.contains("gesture") -> {
                controlItems.add(item)
            }
            cat == "system" || k.contains("sleep") || k.contains("timeout") || k.contains("battery") || k.contains("clock") || k.contains("timezone") || k.contains("hidden") || k.contains("recent") || k.contains("readfolder") || k.contains("finished") -> {
                systemItems.add(item)
            }
            else -> otherItems.add(item)
        }
    }

    val map = linkedMapOf<String, List<CrossPointSettingItem>>()
    if (cloudItems.isNotEmpty()) map[resources.getString(R.string.dev_settings_cat_cloud)] = cloudItems
    if (readerItems.isNotEmpty()) map[resources.getString(R.string.dev_settings_cat_reader)] = readerItems
    if (displayItems.isNotEmpty()) map[resources.getString(R.string.dev_settings_cat_display)] = displayItems
    if (controlItems.isNotEmpty()) map[resources.getString(R.string.dev_settings_cat_controls)] = controlItems
    if (systemItems.isNotEmpty()) map[resources.getString(R.string.dev_settings_cat_system)] = systemItems
    if (otherItems.isNotEmpty()) map[resources.getString(R.string.dev_settings_cat_other)] = otherItems

    return if (map.isNotEmpty()) map else list.groupBy { it.category }
}

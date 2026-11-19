package com.crosssend.app.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.CookieManager
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.converter.WebPageXtchRenderer
import com.crosssend.app.data.model.RenderMode
import com.crosssend.app.network.LocalDeviceHostValidator
import com.crosssend.app.network.WifiConnectionState
import com.crosssend.app.util.DebugDumpManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = CrossSendApp.instance
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val crosspointPrefs = remember { context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE) }
    var selectedDeviceModel by remember {
        mutableStateOf(crosspointPrefs.getString("device_model", "X4") ?: "X4")
    }
    var deviceMenuExpanded by remember { mutableStateOf(false) }
    var isRerenderingDocs by remember { mutableStateOf(false) }

    var webZoomPercent by remember { mutableIntStateOf(WebPageXtchRenderer.getWebZoomPercent(context)) }
    var webColorScheme by remember { mutableStateOf(WebPageXtchRenderer.getWebColorScheme(context)) }
    var lightenDarkPages by remember { mutableStateOf(WebPageXtchRenderer.isLightenDarkPagesEnabled(context)) }
    var showPageNumbers by remember { mutableStateOf(WebPageXtchRenderer.isPageNumbersEnabled(context)) }

    val domainPreferences by app.database.domainPreferenceDao().getAllFlow()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var ssidInput by remember { mutableStateOf(app.wifiManager.getApSsid()) }
    var urlInput by remember { mutableStateOf(app.wifiManager.getLanBaseUrl()) }
    var isUrlError by remember { mutableStateOf(false) }
    var isScanningLan by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var debugDumpsEnabled by remember { mutableStateOf(DebugDumpManager.isDebugDumpEnabled(context)) }
    val connectionState by app.wifiManager.connectionState.collectAsStateWithLifecycle()

    val currentProfile = remember(selectedDeviceModel) {
        EInkImageOptimizer.Companion.CrossPointHardwareProfile.fromModelString(selectedDeviceModel)
    }

    val appVersionName = remember(context) {
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "—"
        } catch (_: Exception) {
            "—"
        }
    }

    fun validateAndPersistConnection(): Boolean {
        val normalized = LocalDeviceHostValidator.normalizeLocalDeviceUrl(urlInput)
        if (normalized == null) {
            isUrlError = true
            scope.launch {
                snackbarHostState.showSnackbar(resources.getString(R.string.error_invalid_local_device_url))
            }
            return false
        }
        val lanUpdated = app.wifiManager.setLanBaseUrl(normalized)
        val webDavUpdated = app.webDavClient.updateBaseUrl(normalized)
        if (!lanUpdated || !webDavUpdated) {
            isUrlError = true
            scope.launch {
                snackbarHostState.showSnackbar(resources.getString(R.string.error_invalid_local_device_url))
            }
            return false
        }
        isUrlError = false
        urlInput = normalized
        app.wifiManager.setApSsid(ssidInput.trim())
        return true
    }

    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = deviceMenuExpanded || showPrivacyDialog) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { event ->
                backProgress = event.progress
            }
            if (showPrivacyDialog) {
                showPrivacyDialog = false
            } else if (deviceMenuExpanded) {
                deviceMenuExpanded = false
            }
        } catch (_: CancellationException) {
            backProgress = 0f
        } finally {
            backProgress = 0f
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.settings_screen_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.settings_screen_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.dev_action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .graphicsLayer {
                    val scale = 1f - (backProgress * 0.03f)
                    scaleX = scale
                    scaleY = scale
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Reader Hardware Profile (ExposedDropdownMenuBox)
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_section_device),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_device_dropdown_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    ExposedDropdownMenuBox(
                        expanded = deviceMenuExpanded,
                        onExpandedChange = { deviceMenuExpanded = !deviceMenuExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = "${currentProfile.displayName} (${currentProfile.screenWidth}×${currentProfile.screenHeight})",
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            label = { Text(stringResource(R.string.settings_device_dropdown_label)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = deviceMenuExpanded)
                            },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )

                        ExposedDropdownMenu(
                            expanded = deviceMenuExpanded,
                            onDismissRequest = { deviceMenuExpanded = false }
                        ) {
                            EInkImageOptimizer.Companion.CrossPointHardwareProfile.entries.forEach { profile ->
                                val selected = profile == currentProfile
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = profile.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                            )
                                            Text(
                                                text = "${profile.screenWidth}×${profile.screenHeight} px · ${profile.diagonalInches}″",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    trailingIcon = if (selected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    } else null,
                                    onClick = {
                                        val prevDimensions = currentProfile.screenWidth to currentProfile.screenHeight
                                        val newDimensions = profile.screenWidth to profile.screenHeight
                                        selectedDeviceModel = profile.id
                                        EInkImageOptimizer.saveSelectedHardwareProfile(context, profile)
                                        deviceMenuExpanded = false
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                resources.getString(R.string.device_hardware_profile_saved, profile.displayName)
                                            )
                                        }

                                        if (prevDimensions != newDimensions) {
                                            app.applicationScope.launch {
                                                withContext(Dispatchers.Main) {
                                                    isRerenderingDocs = true
                                                }
                                                try {
                                                    val updated = app.documentProcessor.rerenderOriginalLayoutDocumentsForDeviceChange()
                                                    if (updated > 0) {
                                                        withContext(Dispatchers.Main) {
                                                            snackbarHostState.showSnackbar(
                                                                resources.getQuantityString(R.plurals.settings_rerender_done, updated, updated)
                                                            )
                                                        }
                                                    }
                                                } finally {
                                                    withContext(Dispatchers.Main) {
                                                        isRerenderingDocs = false
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = isRerenderingDocs) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LoadingIndicator(modifier = Modifier.size(18.dp))
                            Text(
                                text = stringResource(
                                    R.string.settings_rerendering_banner,
                                    currentProfile.screenWidth,
                                    currentProfile.screenHeight
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // 2. Original Website Layout (XTCH) Preferences
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Web,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_section_original_layout),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.settings_xtch_zoom_label, webZoomPercent),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val zoomOptions = listOf(90, 100, 110, 120)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        zoomOptions.forEachIndexed { idx, zoomVal ->
                            SegmentedButton(
                                selected = webZoomPercent == zoomVal,
                                onClick = {
                                    webZoomPercent = zoomVal
                                    WebPageXtchRenderer.setWebZoomPercent(context, zoomVal)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = zoomOptions.size)
                            ) {
                                Text("${zoomVal}%", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.settings_xtch_color_scheme),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.settings_xtch_color_scheme_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val schemeOptions = WebPageXtchRenderer.WebColorScheme.entries
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        schemeOptions.forEachIndexed { idx, scheme ->
                            val isDark = scheme == WebPageXtchRenderer.WebColorScheme.DARK
                            SegmentedButton(
                                selected = webColorScheme == scheme,
                                onClick = {
                                    webColorScheme = scheme
                                    WebPageXtchRenderer.setWebColorScheme(context, scheme)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = schemeOptions.size),
                                icon = {
                                    SegmentedButtonDefaults.Icon(active = webColorScheme == scheme) {
                                        Icon(
                                            imageVector = if (isDark) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                                            contentDescription = null,
                                            modifier = Modifier.size(SegmentedButtonDefaults.IconSize)
                                        )
                                    }
                                }
                            ) {
                                Text(
                                    text = stringResource(
                                        if (isDark) R.string.settings_xtch_color_scheme_dark
                                        else R.string.settings_xtch_color_scheme_light
                                    ),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = webColorScheme == WebPageXtchRenderer.WebColorScheme.LIGHT) {
                        Column {
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = stringResource(R.string.settings_xtch_lighten_dark),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_xtch_lighten_dark_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = lightenDarkPages,
                                    onCheckedChange = { enabled ->
                                        lightenDarkPages = enabled
                                        WebPageXtchRenderer.setLightenDarkPagesEnabled(context, enabled)
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = stringResource(R.string.settings_xtch_page_numbers),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.settings_xtch_page_numbers_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = showPageNumbers,
                            onCheckedChange = { enabled ->
                                showPageNumbers = enabled
                                WebPageXtchRenderer.setPageNumbersEnabled(context, enabled)
                            }
                        )
                    }
                }
            }

            // 3. Saved Domain Rules
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_section_domains),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (domainPreferences.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_domains_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        domainPreferences.forEachIndexed { index, pref ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = pref.host,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (pref.renderMode == RenderMode.ORIGINAL_LAYOUT) {
                                            stringResource(R.string.preview_mode_original)
                                        } else {
                                            stringResource(R.string.preview_mode_reader)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (pref.renderMode == RenderMode.ORIGINAL_LAYOUT) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            app.database.domainPreferenceDao().deleteByHost(pref.host)
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.settings_domain_reset),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. CrossPoint Wi-Fi / LAN Configuration
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_section_connection),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.conn_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = ssidInput,
                        onValueChange = { ssidInput = it },
                        label = { Text(stringResource(R.string.conn_ssid_label)) },
                        placeholder = { Text(stringResource(R.string.conn_ssid_placeholder)) },
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
                            selected = ssidInput.equals("Cross", ignoreCase = true),
                            onClick = { ssidInput = "Cross" },
                            label = { Text(stringResource(R.string.conn_chip_cross_universal)) }
                        )
                        FilterChip(
                            selected = ssidInput.equals("CrossPlay", ignoreCase = true),
                            onClick = { ssidInput = "CrossPlay" },
                            label = { Text(stringResource(R.string.conn_chip_crossplay)) }
                        )
                        FilterChip(
                            selected = ssidInput.equals("CrossPoint", ignoreCase = true),
                            onClick = { ssidInput = "CrossPoint" },
                            label = { Text(stringResource(R.string.conn_chip_crosspoint)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = {
                            urlInput = it
                            isUrlError = false
                        },
                        label = { Text(stringResource(R.string.conn_url_label)) },
                        placeholder = { Text(stringResource(R.string.conn_url_placeholder)) },
                        isError = isUrlError,
                        supportingText = if (isUrlError) {
                            { Text(stringResource(R.string.error_invalid_local_device_url)) }
                        } else {
                            null
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        FilterChip(
                            selected = false,
                            onClick = {
                                if (!isScanningLan) {
                                    scope.launch {
                                        isScanningLan = true
                                        val found = app.wifiManager.autoDetectCrossPoint(preferredUrl = urlInput)
                                        isScanningLan = false
                                        if (found != null) {
                                            urlInput = found
                                            isUrlError = false
                                        }
                                    }
                                }
                            },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isScanningLan) {
                                        LoadingIndicator(modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.conn_scanning_lan))
                                    } else {
                                        Icon(Icons.Default.WifiFind, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.conn_scan_lan))
                                    }
                                }
                            }
                        )
                        FilterChip(
                            selected = urlInput == "http://crosspoint.local",
                            onClick = {
                                urlInput = "http://crosspoint.local"
                                isUrlError = false
                            },
                            label = { Text("crosspoint.local") }
                        )
                        FilterChip(
                            selected = urlInput == "http://192.168.4.1",
                            onClick = {
                                urlInput = "http://192.168.4.1"
                                isUrlError = false
                            },
                            label = { Text(stringResource(R.string.conn_chip_ap_ip)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    val isBusy = connectionState is WifiConnectionState.ConnectingToAp ||
                        connectionState is WifiConnectionState.SearchingOnLan
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                if (validateAndPersistConnection()) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(resources.getString(R.string.dev_settings_saved))
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            Text(stringResource(R.string.dev_btn_save))
                        }
                        Button(
                            enabled = !isBusy,
                            onClick = {
                                if (validateAndPersistConnection()) {
                                    scope.launch {
                                        app.wifiManager.connectOrDiscover(app.webDavClient)
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            if (isBusy) {
                                LoadingIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(stringResource(R.string.conn_button_connect))
                        }
                    }
                }
            }

            // 5. Browser Sessions & Diagnostics
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Build,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_section_browser_diagnostics),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.settings_clear_cookies),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_clear_cookies_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    FilledTonalButton(
                        onClick = {
                            try {
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.removeAllCookies(null)
                                cookieManager.flush()
                            } catch (_: Exception) {
                            }
                            scope.launch {
                                snackbarHostState.showSnackbar(resources.getString(R.string.settings_cookies_cleared))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text(stringResource(R.string.settings_clear_cookies))
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 14.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = stringResource(R.string.settings_debug_dumps_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.settings_debug_dumps_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = debugDumpsEnabled,
                            onCheckedChange = { enabled ->
                                debugDumpsEnabled = enabled
                                DebugDumpManager.setDebugDumpEnabled(context, enabled)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    DebugDumpManager.clearAllDumps(context)
                                }
                                snackbarHostState.showSnackbar(resources.getString(R.string.settings_debug_dumps_cleared))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text(stringResource(R.string.settings_clear_debug_dumps))
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 14.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.settings_app_version_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = appVersionName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 6. About & Privacy Notice
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.PrivacyTip,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_section_privacy),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_privacy_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = { showPrivacyDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text(stringResource(R.string.settings_privacy_button))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.PrivacyTip,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.settings_privacy_dialog_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(R.string.settings_privacy_dialog_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showPrivacyDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.settings_privacy_dialog_close))
                }
            }
        )
    }
}

package com.crosssend.app.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.CookieManager
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.converter.WebPageXtchRenderer
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.RenderMode
import com.crosssend.app.data.settings.AppSettings
import com.crosssend.app.data.settings.AppThemeMode
import com.crosssend.app.data.settings.ConversionPreset
import com.crosssend.app.data.settings.ShareFlowMode
import com.crosssend.app.network.LocalDeviceHostValidator
import com.crosssend.app.network.WifiConnectionState
import com.crosssend.app.ui.viewmodel.AppSettingsViewModel
import com.crosssend.app.util.DebugDumpManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppSettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val app = CrossSendApp.instance
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val domainPreferences by viewModel.domainPreferences.collectAsStateWithLifecycle()
    val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsStateWithLifecycle()
    val connectionState by app.wifiManager.connectionState.collectAsStateWithLifecycle()

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

    var articlesFolderInput by remember { mutableStateOf(settings.articlesFolder) }
    var booksFolderInput by remember { mutableStateOf(settings.booksFolder) }
    var textsFolderInput by remember { mutableStateOf(settings.textsFolder) }
    LaunchedEffect(settings.articlesFolder, settings.booksFolder, settings.textsFolder) {
        articlesFolderInput = settings.articlesFolder
        booksFolderInput = settings.booksFolder
        textsFolderInput = settings.textsFolder
    }

    var ssidInput by remember { mutableStateOf(app.wifiManager.getApSsid()) }
    var urlInput by remember { mutableStateOf(app.wifiManager.getLanBaseUrl()) }
    var isUrlError by remember { mutableStateOf(false) }
    var isScanningLan by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var debugDumpsEnabled by remember { mutableStateOf(DebugDumpManager.isDebugDumpEnabled(context)) }

    val currentProfile = remember(selectedDeviceModel) {
        EInkImageOptimizer.Companion.CrossPointHardwareProfile.fromModelString(selectedDeviceModel)
    }

    // Material 3 Expressive slider states synchronized with repository updates
    val fontScaleSliderState = rememberSliderState(
        value = settings.epubFontScale,
        steps = 9,
        trackRange = AppSettings.MIN_FONT_SCALE..AppSettings.MAX_FONT_SCALE
    )
    LaunchedEffect(settings.epubFontScale) {
        if (abs(fontScaleSliderState.value - settings.epubFontScale) > 0.005f) {
            fontScaleSliderState.value = settings.epubFontScale
        }
    }

    val lineHeightSliderState = rememberSliderState(
        value = settings.epubLineHeight,
        steps = 10,
        trackRange = AppSettings.MIN_LINE_HEIGHT..AppSettings.MAX_LINE_HEIGHT
    )
    LaunchedEffect(settings.epubLineHeight) {
        if (abs(lineHeightSliderState.value - settings.epubLineHeight) > 0.005f) {
            lineHeightSliderState.value = settings.epubLineHeight
        }
    }

    val marginSliderState = rememberSliderState(
        value = settings.epubMarginEm,
        steps = 14,
        trackRange = AppSettings.MIN_MARGIN_EM..AppSettings.MAX_MARGIN_EM
    )
    LaunchedEffect(settings.epubMarginEm) {
        if (abs(marginSliderState.value - settings.epubMarginEm) > 0.005f) {
            marginSliderState.value = settings.epubMarginEm
        }
    }

    val imageMaxDimSliderState = rememberSliderState(
        value = settings.imageMaxDim.toFloat(),
        steps = 57,
        trackRange = AppSettings.MIN_IMAGE_DIM.toFloat()..AppSettings.MAX_IMAGE_DIM.toFloat()
    )
    LaunchedEffect(settings.imageMaxDim) {
        if (abs(imageMaxDimSliderState.value - settings.imageMaxDim.toFloat()) > 1f) {
            imageMaxDimSliderState.value = settings.imageMaxDim.toFloat()
        }
    }

    val jpegQualitySliderState = rememberSliderState(
        value = settings.imageJpegQuality.toFloat(),
        steps = 0,
        trackRange = AppSettings.MIN_JPEG_QUALITY.toFloat()..AppSettings.MAX_JPEG_QUALITY.toFloat()
    )
    LaunchedEffect(settings.imageJpegQuality) {
        if (abs(jpegQualitySliderState.value - settings.imageJpegQuality.toFloat()) > 0.5f) {
            jpegQualitySliderState.value = settings.imageJpegQuality.toFloat()
        }
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
                .navigationBarsPadding()
                .graphicsLayer {
                    val scale = 1f - (backProgress * 0.03f)
                    scaleX = scale
                    scaleY = scale
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Reading Presets & Live Mini E-Ink Preview
            SettingsSectionCard(
                title = stringResource(R.string.settings_section_presets),
                icon = Icons.Outlined.AutoAwesome
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ConversionPreset.entries.forEach { preset ->
                        val selected = settings.preset == preset
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (preset != ConversionPreset.CUSTOM) {
                                    viewModel.applyPreset(preset)
                                }
                            },
                            label = {
                                Text(
                                    text = stringResource(
                                        when (preset) {
                                            ConversionPreset.READING_FOCUS -> R.string.settings_preset_reading_focus
                                            ConversionPreset.MAGAZINE -> R.string.settings_preset_magazine
                                            ConversionPreset.ORIGINAL_LAYOUT -> R.string.settings_preset_original_layout
                                            ConversionPreset.CUSTOM -> R.string.settings_preset_custom
                                        }
                                    )
                                )
                            },
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else null
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.settings_preview_eink_label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                MiniEInkPreviewFrame(
                    renderMode = settings.defaultRenderMode,
                    fontScale = fontScaleSliderState.value,
                    lineHeight = lineHeightSliderState.value,
                    marginEm = marginSliderState.value,
                    imageMaxDim = imageMaxDimSliderState.value.roundToInt(),
                    profile = currentProfile,
                    webZoomPercent = webZoomPercent,
                    webColorScheme = webColorScheme
                )
            }

            // Section 2: Format & Conversion (EPUB Typography, Image Compression & XTCH WebView)
            SettingsSectionCard(
                title = stringResource(R.string.settings_section_format_conversion),
                icon = Icons.Outlined.Tune
            ) {
                Text(
                    text = stringResource(R.string.settings_default_format_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                val formatModes = listOf(RenderMode.READER, RenderMode.ORIGINAL_LAYOUT)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    formatModes.forEachIndexed { idx, mode ->
                        val selected = settings.defaultRenderMode == mode
                        SegmentedButton(
                            selected = selected,
                            onClick = { viewModel.setDefaultRenderMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = formatModes.size)
                        ) {
                            Text(
                                text = stringResource(
                                    if (mode == RenderMode.ORIGINAL_LAYOUT) {
                                        R.string.preview_mode_original
                                    } else {
                                        R.string.preview_mode_reader
                                    }
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_epub_typography_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Font Scale Slider
                SettingsSliderHeader(
                    label = stringResource(
                        R.string.settings_slider_font_scale,
                        (fontScaleSliderState.value * 100f).roundToInt()
                    )
                )
                Slider(
                    state = fontScaleSliderState,
                    onValueChange = { fontScaleSliderState.value = it },
                    onValueChangeFinished = {
                        val snapped = (((fontScaleSliderState.value - AppSettings.MIN_FONT_SCALE) / 0.05f)
                            .roundToInt() * 0.05f + AppSettings.MIN_FONT_SCALE)
                        fontScaleSliderState.value = snapped.coerceIn(AppSettings.MIN_FONT_SCALE, AppSettings.MAX_FONT_SCALE)
                        viewModel.setEpubFontScale(fontScaleSliderState.value)
                    }
                )

                // Line Height Slider
                SettingsSliderHeader(
                    label = stringResource(
                        R.string.settings_slider_line_height,
                        lineHeightSliderState.value
                    )
                )
                Slider(
                    state = lineHeightSliderState,
                    onValueChange = { lineHeightSliderState.value = it },
                    onValueChangeFinished = {
                        val snapped = (((lineHeightSliderState.value - AppSettings.MIN_LINE_HEIGHT) / 0.05f)
                            .roundToInt() * 0.05f + AppSettings.MIN_LINE_HEIGHT)
                        lineHeightSliderState.value = snapped.coerceIn(AppSettings.MIN_LINE_HEIGHT, AppSettings.MAX_LINE_HEIGHT)
                        viewModel.setEpubLineHeight(lineHeightSliderState.value)
                    }
                )

                // Page Margin Slider
                SettingsSliderHeader(
                    label = stringResource(
                        R.string.settings_slider_margins,
                        marginSliderState.value
                    )
                )
                Slider(
                    state = marginSliderState,
                    onValueChange = { marginSliderState.value = it },
                    onValueChangeFinished = {
                        val snapped = (((marginSliderState.value - AppSettings.MIN_MARGIN_EM) / 0.1f)
                            .roundToInt() * 0.1f + AppSettings.MIN_MARGIN_EM)
                        marginSliderState.value = snapped.coerceIn(AppSettings.MIN_MARGIN_EM, AppSettings.MAX_MARGIN_EM)
                        viewModel.setEpubMarginEm(marginSliderState.value)
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                Text(
                    text = stringResource(R.string.settings_image_optimization_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Image Max Dimension Slider
                SettingsSliderHeader(
                    label = stringResource(
                        R.string.settings_slider_image_res,
                        imageMaxDimSliderState.value.roundToInt()
                    )
                )
                Slider(
                    state = imageMaxDimSliderState,
                    onValueChange = { imageMaxDimSliderState.value = it },
                    onValueChangeFinished = {
                        val snapped = (((imageMaxDimSliderState.value - AppSettings.MIN_IMAGE_DIM) / 10f)
                            .roundToInt() * 10 + AppSettings.MIN_IMAGE_DIM)
                            .coerceIn(AppSettings.MIN_IMAGE_DIM, AppSettings.MAX_IMAGE_DIM)
                        imageMaxDimSliderState.value = snapped.toFloat()
                        viewModel.setImageMaxDim(snapped)
                    }
                )

                // JPEG Quality Slider
                SettingsSliderHeader(
                    label = stringResource(
                        R.string.settings_slider_jpeg_quality,
                        jpegQualitySliderState.value.roundToInt()
                    )
                )
                Slider(
                    state = jpegQualitySliderState,
                    onValueChange = { jpegQualitySliderState.value = it },
                    onValueChangeFinished = {
                        val snapped = jpegQualitySliderState.value.roundToInt()
                            .coerceIn(AppSettings.MIN_JPEG_QUALITY, AppSettings.MAX_JPEG_QUALITY)
                        jpegQualitySliderState.value = snapped.toFloat()
                        viewModel.setImageJpegQuality(snapped)
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                Text(
                    text = stringResource(R.string.settings_section_original_layout),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(10.dp))

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
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_xtch_lighten_dark),
                            description = stringResource(R.string.settings_xtch_lighten_dark_desc),
                            checked = lightenDarkPages,
                            onCheckedChange = { enabled ->
                                lightenDarkPages = enabled
                                WebPageXtchRenderer.setLightenDarkPagesEnabled(context, enabled)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_xtch_page_numbers),
                    description = stringResource(R.string.settings_xtch_page_numbers_desc),
                    checked = showPageNumbers,
                    onCheckedChange = { enabled ->
                        showPageNumbers = enabled
                        WebPageXtchRenderer.setPageNumbersEnabled(context, enabled)
                    }
                )
            }

            // Section 3: Saved Website Rules
            SettingsSectionCard(
                title = stringResource(R.string.settings_section_domains),
                icon = Icons.Outlined.Language
            ) {
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = pref.host,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = pref.renderMode == RenderMode.ORIGINAL_LAYOUT,
                                onClick = { viewModel.toggleDomainRule(pref) },
                                label = {
                                    Text(
                                        text = stringResource(
                                            if (pref.renderMode == RenderMode.ORIGINAL_LAYOUT) {
                                                R.string.preview_mode_original
                                            } else {
                                                R.string.preview_mode_reader
                                            }
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                }
                            )
                            IconButton(
                                onClick = { viewModel.deleteDomainRule(pref.host) }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.settings_domain_reset),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.settings_domain_toggle_mode),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            // Section 4: Share Flow, Target Folders & Storage
            SettingsSectionCard(
                title = stringResource(R.string.settings_section_workflow_storage),
                icon = Icons.Outlined.FolderCopy
            ) {
                Text(
                    text = stringResource(R.string.settings_share_flow_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                val shareModes = ShareFlowMode.entries
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    shareModes.forEachIndexed { idx, mode ->
                        SegmentedButton(
                            selected = settings.shareFlowMode == mode,
                            onClick = { viewModel.setShareFlowMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = shareModes.size)
                        ) {
                            Text(
                                text = stringResource(
                                    when (mode) {
                                        ShareFlowMode.SMART_SHEET -> R.string.settings_share_flow_smart_sheet
                                        ShareFlowMode.AUTO_SEND_COUNTDOWN -> R.string.settings_share_flow_countdown
                                        ShareFlowMode.INSTANT_BACKGROUND -> R.string.settings_share_flow_instant
                                    }
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        when (settings.shareFlowMode) {
                            ShareFlowMode.SMART_SHEET -> R.string.settings_share_flow_desc_smart
                            ShareFlowMode.AUTO_SEND_COUNTDOWN -> R.string.settings_share_flow_desc_countdown
                            ShareFlowMode.INSTANT_BACKGROUND -> R.string.settings_share_flow_desc_instant
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 14.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                Text(
                    text = stringResource(R.string.settings_folders_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = articlesFolderInput,
                    onValueChange = { articlesFolderInput = it },
                    label = { Text(stringResource(R.string.settings_folder_articles)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = booksFolderInput,
                    onValueChange = { booksFolderInput = it },
                    label = { Text(stringResource(R.string.settings_folder_books)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = textsFolderInput,
                    onValueChange = { textsFolderInput = it },
                    label = { Text(stringResource(R.string.settings_folder_texts)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = {
                        viewModel.setFolder(DocumentType.ARTICLE, articlesFolderInput)
                        viewModel.setFolder(DocumentType.BOOK, booksFolderInput)
                        viewModel.setFolder(DocumentType.TEXT, textsFolderInput)
                        scope.launch {
                            snackbarHostState.showSnackbar(resources.getString(R.string.settings_folders_saved))
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.dev_btn_save))
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 14.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_strip_tracking_title),
                    description = stringResource(R.string.settings_strip_tracking_desc),
                    checked = settings.stripTrackingParams,
                    onCheckedChange = { viewModel.setStripTrackingParams(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_auto_delete_sync_title),
                    description = stringResource(R.string.settings_auto_delete_sync_desc),
                    checked = settings.autoDeleteLocalAfterSync,
                    onCheckedChange = { viewModel.setAutoDeleteLocalAfterSync(it) }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 14.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                val cacheFormatted = formatBytes(cacheSizeBytes, locale)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.settings_cache_usage_label, cacheFormatted),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    )
                    OutlinedButton(
                        enabled = cacheSizeBytes > 0L,
                        onClick = {
                            viewModel.clearAppCache { freed ->
                                scope.launch {
                                    val freedLabel = formatBytes(freed, locale)
                                    snackbarHostState.showSnackbar(
                                        resources.getString(R.string.settings_cache_cleared_toast, freedLabel)
                                    )
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.settings_clear_cache_btn))
                    }
                }
            }

            // Section 5a: Appearance & Theme
            SettingsSectionCard(
                title = stringResource(R.string.settings_theme_label),
                icon = Icons.Outlined.Palette
            ) {
                val themes = AppThemeMode.entries
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    themes.forEachIndexed { idx, mode ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = themes.size)
                        ) {
                            Text(
                                text = stringResource(
                                    when (mode) {
                                        AppThemeMode.SYSTEM -> R.string.settings_theme_system
                                        AppThemeMode.LIGHT -> R.string.settings_theme_light
                                        AppThemeMode.DARK -> R.string.settings_theme_dark
                                    }
                                ),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            // Section 5b: Reader Hardware Profile (ExposedDropdownMenuBox)
            SettingsSectionCard(
                title = stringResource(R.string.settings_section_device),
                icon = Icons.Outlined.Devices
            ) {
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

            // Section 5c: CrossPoint Wi-Fi / LAN Connection
            SettingsSectionCard(
                title = stringResource(R.string.settings_section_connection),
                icon = Icons.Outlined.Wifi
            ) {
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

            // Section 5d: Browser Sessions & Diagnostics
            SettingsSectionCard(
                title = stringResource(R.string.settings_section_browser_diagnostics),
                icon = Icons.Outlined.Build
            ) {
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
                        } catch (_: Exception) {}
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

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_debug_dumps_title),
                    description = stringResource(R.string.settings_debug_dumps_desc),
                    checked = debugDumpsEnabled,
                    onCheckedChange = { enabled ->
                        debugDumpsEnabled = enabled
                        DebugDumpManager.setDebugDumpEnabled(context, enabled)
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                DebugDumpManager.clearAllDumps(context)
                            }
                            viewModel.refreshCacheSize()
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

            // Section 5e: About & Privacy Notice
            SettingsSectionCard(
                title = stringResource(R.string.settings_section_privacy),
                icon = Icons.Outlined.PrivacyTip
            ) {
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

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SettingsSliderHeader(
    label: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(top = 4.dp)
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun MiniEInkPreviewFrame(
    renderMode: RenderMode,
    fontScale: Float,
    lineHeight: Float,
    marginEm: Float,
    imageMaxDim: Int,
    profile: EInkImageOptimizer.Companion.CrossPointHardwareProfile,
    webZoomPercent: Int,
    webColorScheme: WebPageXtchRenderer.WebColorScheme,
    modifier: Modifier = Modifier
) {
    val isDarkXtch = renderMode == RenderMode.ORIGINAL_LAYOUT &&
        webColorScheme == WebPageXtchRenderer.WebColorScheme.DARK
    val paperColor = if (isDarkXtch) Color(0xFF171717) else Color(0xFFF4F3EE)
    val inkColor = if (isDarkXtch) Color(0xFFEAEAE4) else Color(0xFF141414)
    val borderColor = if (isDarkXtch) Color(0xFF3E3E3A) else Color(0xFFBDBBB2)

    val zoomFactor = if (renderMode == RenderMode.ORIGINAL_LAYOUT) {
        webZoomPercent / 100f
    } else {
        fontScale
    }
    val horizontalInset = if (renderMode == RenderMode.ORIGINAL_LAYOUT) {
        12.dp
    } else {
        (10f + marginEm * 14f).dp
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = paperColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalInset, vertical = 12.dp)
        ) {
            Text(
                text = if (renderMode == RenderMode.ORIGINAL_LAYOUT) {
                    stringResource(
                        R.string.settings_preview_badge_xtch,
                        profile.screenWidth,
                        profile.screenHeight,
                        webZoomPercent
                    )
                } else {
                    stringResource(
                        R.string.settings_preview_badge_epub,
                        imageMaxDim,
                        (fontScale * 100f).roundToInt()
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = inkColor.copy(alpha = 0.65f),
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = stringResource(R.string.settings_preview_sample_title),
                        color = inkColor,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = (14f * zoomFactor).sp,
                        lineHeight = (18f * zoomFactor).sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_preview_sample_body),
                        color = inkColor.copy(alpha = 0.88f),
                        fontFamily = FontFamily.Serif,
                        fontSize = (11f * zoomFactor).sp,
                        lineHeight = (11f * zoomFactor * lineHeight).sp
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long, locale: Locale): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(locale, "%.1f MB", mb)
        kb >= 1.0 -> String.format(locale, "%.0f KB", kb)
        else -> "$bytes B"
    }
}

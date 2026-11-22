package com.crosssend.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.crosssend.app.converter.DocumentProcessor
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.data.model.RenderMode
import com.crosssend.app.data.settings.AppThemeMode
import com.crosssend.app.ui.components.ArticleWebViewDialog
import com.crosssend.app.ui.screens.AppSettingsScreen
import com.crosssend.app.ui.screens.ArchiveScreen
import com.crosssend.app.ui.screens.CrossPointDeviceSettingsScreen
import com.crosssend.app.ui.screens.FeedsScreen
import com.crosssend.app.ui.screens.MainScreen
import com.crosssend.app.ui.theme.CrossSendTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            (application as? CrossSendApp)?.wifiManager?.verifyConnectionOnResume()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        val missingPermissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= 37 &&
                checkSelfPermission(PERMISSION_ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
            ) {
                add(PERMISSION_ACCESS_LOCAL_NETWORK)
            }
        }
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }

        val app = application as CrossSendApp

        setContent {
            val appSettings by app.settingsRepository.settings.collectAsStateWithLifecycle()
            val darkTheme = when (appSettings.themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            CrossSendTheme(darkTheme = darkTheme) {
                val resources = LocalResources.current
                Surface(modifier = Modifier.fillMaxSize()) {
                    val pendingWebReq by app.pendingWebViewRequest.collectAsStateWithLifecycle()

                    CrossSendNavHost()

                    pendingWebReq?.let { req ->
                        ArticleWebViewDialog(
                            initialUrl = req.url,
                            initialTitle = req.title,
                            isOriginalLayoutSession = req.rerenderOriginalLayoutDocId != null,
                            onDismiss = {
                                app.pendingWebViewRequest.value = null
                            },
                            onImportArticle = { html, finalUrl ->
                                app.pendingWebViewRequest.value = null
                                val isXtchRerender = req.rerenderOriginalLayoutDocId != null
                                val toastText = resources.getString(
                                    if (isXtchRerender) R.string.proc_progress_rendering_layout
                                    else R.string.webview_processing_toast
                                )
                                android.widget.Toast.makeText(this@MainActivity, toastText, android.widget.Toast.LENGTH_SHORT).show()
                                app.applicationScope.launch {
                                    val normalizedFinalUrl = normalizeHttpToHttps(finalUrl)
                                    val targetDocId = req.rerenderOriginalLayoutDocId
                                    if (targetDocId != null) {
                                        app.rerenderingDocId.value = targetDocId
                                        try {
                                            val existing = app.database.documentDao().getById(targetDocId)
                                            val fallbackUrl = normalizeHttpToHttps(existing?.sourceUrl ?: req.url)
                                            val targetUrl = if (EInkImageOptimizer.isSafeHttpsExternalUrl(normalizedFinalUrl)) {
                                                normalizedFinalUrl
                                            } else {
                                                fallbackUrl
                                            }
                                            if (EInkImageOptimizer.isSafeHttpsExternalUrl(targetUrl)) {
                                                app.documentProcessor.processWebUrlOriginalLayout(
                                                    url = targetUrl,
                                                    fallbackTitle = existing?.title ?: req.title,
                                                    author = existing?.author,
                                                    existingDoc = existing
                                                )
                                            }
                                        } finally {
                                            if (app.rerenderingDocId.value == targetDocId) {
                                                app.rerenderingDocId.value = null
                                            }
                                        }
                                    } else if (EInkImageOptimizer.isSafeHttpsExternalUrl(normalizedFinalUrl) &&
                                        app.documentProcessor.resolveRenderModeForUrl(normalizedFinalUrl) == RenderMode.ORIGINAL_LAYOUT
                                    ) {
                                        app.documentProcessor.processWebUrlOriginalLayout(
                                            url = normalizedFinalUrl,
                                            fallbackTitle = req.title
                                        )
                                    } else {
                                        app.documentProcessor.processHtmlContent(html, baseUrl = normalizedFinalUrl, subject = req.title)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as? CrossSendApp)?.wifiManager?.verifyConnectionOnResume()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val token = intent?.getStringExtra(EXTRA_INTERNAL_TOKEN)
        val rawWebUrl = intent?.getStringExtra(EXTRA_WEBVIEW_URL)?.trim()
        if (token == CrossSendApp.INTERNAL_NOTIFICATION_TOKEN && !rawWebUrl.isNullOrBlank()) {
            val normalizedWebUrl = normalizeHttpToHttps(rawWebUrl)
            if (EInkImageOptimizer.isSafeHttpsExternalUrl(normalizedWebUrl)) {
                (application as? CrossSendApp)?.pendingWebViewRequest?.value = WebArticleLoginRequest(
                    url = normalizedWebUrl,
                    title = intent.getStringExtra(EXTRA_WEBVIEW_TITLE)
                )
            }
        }
    }

    private fun normalizeHttpToHttps(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return if (trimmed.startsWith("http://", ignoreCase = true)) {
            DocumentProcessor.normalizeExternalHttpsUrl(trimmed)
        } else {
            trimmed
        }
    }

    companion object {
        private const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        const val EXTRA_INTERNAL_TOKEN = "extra_internal_token"
        const val EXTRA_WEBVIEW_URL = "com.crosssend.app.EXTRA_WEBVIEW_URL"
        const val EXTRA_WEBVIEW_TITLE = "com.crosssend.app.EXTRA_WEBVIEW_TITLE"
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CrossSendNavHost() {
    val navController = rememberNavController()
    val spatialOffsetSpec = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val spatialFloatSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    NavHost(
        navController = navController,
        startDestination = "main",
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { (it * 0.18f).toInt() },
                animationSpec = spatialOffsetSpec
            ) + fadeIn(tween(250))
        },
        exitTransition = {
            scaleOut(
                targetScale = 0.96f,
                animationSpec = spatialFloatSpec
            ) + fadeOut(tween(200))
        },
        popEnterTransition = {
            scaleIn(
                initialScale = 0.96f,
                animationSpec = spatialFloatSpec
            ) + fadeIn(tween(250))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = spatialOffsetSpec
            ) + fadeOut(tween(200))
        }
    ) {
        composable("main") {
            MainScreen(
                onNavigateToFeeds = { navController.navigate("feeds") },
                onNavigateToDevice = { navController.navigate("device") },
                onNavigateToSettings = { navController.navigate("app_settings") },
                onNavigateToArchive = { navController.navigate("archive") }
            )
        }
        composable("archive") {
            ArchiveScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("feeds") {
            FeedsScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable("device") {
            CrossPointDeviceSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("app_settings") {
            AppSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

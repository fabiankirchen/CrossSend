package com.crosssend.app.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import com.crosssend.app.R
import com.crosssend.app.converter.DocumentProcessor
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.converter.WebPageXtchRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArticleWebViewDialog(
    initialUrl: String,
    modifier: Modifier = Modifier,
    initialTitle: String? = null,
    isOriginalLayoutSession: Boolean = false,
    onDismiss: () -> Unit,
    onImportArticle: (html: String, finalUrl: String) -> Unit
) {
    val normalizedInitialUrl = remember(initialUrl) {
        val trimmed = initialUrl.trim()
        if (trimmed.startsWith("http://", ignoreCase = true)) {
            DocumentProcessor.normalizeExternalHttpsUrl(trimmed)
        } else {
            trimmed
        }
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember(normalizedInitialUrl) { mutableStateOf(normalizedInitialUrl) }
    var pageTitle by remember { mutableStateOf(initialTitle ?: "") }
    var isLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableFloatStateOf(0f) }
    var isImporting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("crosssend_prefs", Context.MODE_PRIVATE) }
    val useDarkWebMode = remember(context) {
        WebPageXtchRenderer.getWebColorScheme(context) == WebPageXtchRenderer.WebColorScheme.DARK
    }
    var allowThirdPartyCookies by remember {
        mutableStateOf(prefs.getBoolean("allow_third_party_cookies", false))
    }

    PredictiveBackHandler { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { }
            if (webView?.canGoBack() == true) {
                webView?.goBack()
            } else {
                onDismiss()
            }
        } catch (_: CancellationException) {
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                CookieManager.getInstance().flush()
            } catch (_: Exception) {}
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            webView = null
        }
    }

    Dialog(
        onDismissRequest = {
            if (webView?.canGoBack() == true) {
                webView?.goBack()
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Scaffold(
            modifier = modifier.imePadding(),
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = if (pageTitle.isNotBlank()) pageTitle else (initialTitle ?: stringResource(R.string.webview_default_title)),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = currentUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.webview_close))
                            }
                        },
                        actions = {
                            Button(
                                onClick = {
                                    isImporting = true
                                    webView?.evaluateJavascript(
                                        "(function() { return document.documentElement.outerHTML; })();"
                                    ) { rawResult ->
                                        isImporting = false
                                        val html = try {
                                            if (rawResult != null && rawResult != "null") {
                                                JSONTokener(rawResult).nextValue().toString()
                                            } else ""
                                        } catch (_: Exception) {
                                            rawResult ?: ""
                                        }
                                        try {
                                            CookieManager.getInstance().flush()
                                        } catch (_: Exception) {}
                                        if (html.isNotBlank() || isOriginalLayoutSession) {
                                            onImportArticle(html, webView?.url ?: currentUrl)
                                        }
                                    }
                                },
                                enabled = !isImporting,
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                if (isImporting) {
                                    LoadingIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                } else {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    stringResource(
                                        if (isOriginalLayoutSession) R.string.webview_rerender_xtch_button
                                        else R.string.webview_import_button
                                    )
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )

                    // Helper banner informing user about persistent login / cookie session
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (isOriginalLayoutSession) R.string.webview_xtch_session_hint
                                else R.string.webview_login_hint
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = allowThirdPartyCookies,
                            onClick = {
                                val next = !allowThirdPartyCookies
                                allowThirdPartyCookies = next
                                prefs.edit { putBoolean("allow_third_party_cookies", next) }
                                webView?.let { wv ->
                                    try {
                                        val cm = CookieManager.getInstance()
                                        cm.setAcceptThirdPartyCookies(wv, next)
                                        cm.flush()
                                    } catch (_: Exception) {}
                                    wv.reload()
                                }
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.webview_third_party_cookies),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }

                    if (isLoading) {
                        LinearWavyProgressIndicator(
                            progress = { loadProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(WebPageXtchRenderer.createWebViewThemeContext(ctx, useDarkWebMode)).apply {
                            setBackgroundColor(
                                if (useDarkWebMode) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = false
                                allowContentAccess = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                setSupportMultipleWindows(false)
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                cacheMode = WebSettings.LOAD_DEFAULT
                                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                                WebPageXtchRenderer.applyWebViewColorScheme(this, useDarkWebMode)
                            }

                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, allowThirdPartyCookies)

                            webViewClient = object : WebViewClient() {
                                private fun forbiddenResponse(): WebResourceResponse {
                                    return WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        403,
                                        "Forbidden",
                                        emptyMap(),
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val reqUrlStr = request?.url?.toString() ?: return true
                                    return !EInkImageOptimizer.isSafeHttpsExternalUrl(reqUrlStr)
                                }

                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    val reqUri = request?.url ?: return forbiddenResponse()
                                    val scheme = reqUri.scheme?.lowercase(Locale.ROOT) ?: return forbiddenResponse()
                                    val reqUrlStr = reqUri.toString()
                                    if (scheme == "data" || scheme == "blob" || reqUrlStr == "about:blank") {
                                        return null
                                    }
                                    if (!EInkImageOptimizer.isSafeHttpsExternalUrl(reqUrlStr)) {
                                        return forbiddenResponse()
                                    }
                                    val host = reqUri.host ?: return forbiddenResponse()
                                    return try {
                                        EInkImageOptimizer.ssrfSafeDns.lookup(host)
                                        null
                                    } catch (_: Exception) {
                                        forbiddenResponse()
                                    }
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                    url?.let { currentUrl = it }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                    url?.let { currentUrl = it }
                                    view?.title?.let { if (it.isNotBlank()) pageTitle = it }
                                    try {
                                        CookieManager.getInstance().flush()
                                    } catch (_: Exception) {}
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadProgress = newProgress / 100f
                                    if (newProgress >= 100) {
                                        isLoading = false
                                    }
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    super.onReceivedTitle(view, title)
                                    title?.let { if (it.isNotBlank()) pageTitle = it }
                                }
                            }

                            if (EInkImageOptimizer.isSafeHttpsExternalUrl(normalizedInitialUrl)) {
                                loadUrl(normalizedInitialUrl)
                            }
                            webView = this
                        }
                    }
                )
            }
        }
    }
}

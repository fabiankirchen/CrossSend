package com.crosssend.app.converter

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.net.toUri
import com.crosssend.app.CrossSendApp
import com.crosssend.app.util.DateUtils
import com.crosssend.app.util.SafeLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class XtchRenderResult(
    val pageCount: Int,
    val title: String?,
    val excerpt: String?,
    val colorCoverFile: File?,
    val leadImageUrl: String? = null,
    val publishedDate: Date? = null
)

internal data class LayoutBox(
    val top: Int,
    val bottom: Int,
    val isKeepBlock: Boolean = false
)

internal data class BgLayer(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
    val isRoot: Boolean = false
)

internal data class MediaRect(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int
)

/**
 * Renders live web pages in a hardware-backed offscreen VirtualDisplay Chromium WebView
 * sized to the target CrossPoint reader's aspect ratio, computes DOM-aware page breaks,
 * and encodes every page into 2-bit grayscale XTH blobs inside an .xtch file.
 */
object WebPageXtchRenderer {
    private const val TAG = "WebPageXtchRenderer"
    private const val MAX_PAGES = 120
    private const val CSS_MOBILE_WIDTH = 360f
    private const val MIN_MOBILE_CSS_WIDTH = 320f
    private const val MAX_MOBILE_CSS_WIDTH = 480f
    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    private const val PREF_WEB_ZOOM = "xtch_web_zoom_percent"
    private const val PREF_LIGHTEN_DARK = "xtch_lighten_dark_pages"
    private const val PREF_SHOW_PAGE_NUMBERS = "xtch_show_page_numbers"
    private const val PREF_WEB_COLOR_SCHEME = "xtch_web_color_scheme"

    enum class WebColorScheme(val prefValue: String) {
        LIGHT("light"),
        DARK("dark");

        companion object {
            fun fromPrefValue(value: String?): WebColorScheme =
                entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) } ?: LIGHT
        }
    }

    internal data class ZoomViewportConfig(
        val cssWidth: Float,
        val textZoomPercent: Int
    )

    internal fun computeZoomViewportConfig(zoomPercent: Int): ZoomViewportConfig {
        val clampedZoom = zoomPercent.coerceIn(75, 140)
        val desiredCssW = CSS_MOBILE_WIDTH * 100f / clampedZoom.toFloat()
        val effectiveCssW = desiredCssW.coerceIn(MIN_MOBILE_CSS_WIDTH, MAX_MOBILE_CSS_WIDTH)
        val layoutScale = CSS_MOBILE_WIDTH / effectiveCssW
        val residualTextZoom = (clampedZoom.toFloat() / layoutScale).roundToInt().coerceIn(85, 130)
        return ZoomViewportConfig(
            cssWidth = effectiveCssW,
            textZoomPercent = residualTextZoom
        )
    }

    fun getWebZoomPercent(context: Context): Int {
        return context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .getInt(PREF_WEB_ZOOM, 100)
            .coerceIn(75, 140)
    }

    fun setWebZoomPercent(context: Context, percent: Int) {
        context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .edit {
                putInt(PREF_WEB_ZOOM, percent.coerceIn(75, 140))
            }
    }

    fun isLightenDarkPagesEnabled(context: Context): Boolean {
        return context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_LIGHTEN_DARK, true)
    }

    fun setLightenDarkPagesEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .edit {
                putBoolean(PREF_LIGHTEN_DARK, enabled)
            }
    }

    fun isPageNumbersEnabled(context: Context): Boolean {
        return context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_SHOW_PAGE_NUMBERS, false)
    }

    fun setPageNumbersEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .edit {
                putBoolean(PREF_SHOW_PAGE_NUMBERS, enabled)
            }
    }

    fun getWebColorScheme(context: Context): WebColorScheme {
        val raw = context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .getString(PREF_WEB_COLOR_SCHEME, WebColorScheme.LIGHT.prefValue)
        return WebColorScheme.fromPrefValue(raw)
    }

    fun setWebColorScheme(context: Context, scheme: WebColorScheme) {
        context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .edit {
                putString(PREF_WEB_COLOR_SCHEME, scheme.prefValue)
            }
    }

    fun createWebViewThemeContext(baseContext: Context, useDarkMode: Boolean): Context {
        val themeRes = if (useDarkMode) {
            android.R.style.Theme_Material_NoActionBar
        } else {
            android.R.style.Theme_Material_Light_NoActionBar
        }
        val wrapped = ContextThemeWrapper(baseContext, themeRes)
        val baseUiMode = baseContext.resources.configuration.uiMode
        val nightFlag = if (useDarkMode) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val overrideConfig = Configuration().apply {
            uiMode = (baseUiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightFlag
        }
        wrapped.applyOverrideConfiguration(overrideConfig)
        return wrapped
    }

    fun applyWebViewColorScheme(settings: WebSettings, useDarkMode: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.isAlgorithmicDarkeningAllowed = useDarkMode
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            settings.forceDark = if (useDarkMode) {
                WebSettings.FORCE_DARK_ON
            } else {
                WebSettings.FORCE_DARK_OFF
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun renderUrlToXtch(
        context: Context,
        url: String,
        outputFile: File,
        title: String,
        author: String?
    ): XtchRenderResult? {
        require(EInkImageOptimizer.isSafeHttpsExternalUrl(url)) {
            "Blocked unsafe URL for XTCH render: ${com.crosssend.app.util.SafeLogger.redactUrl(url)}"
        }
        val (targetW, targetH) = EInkImageOptimizer.getTargetScreenDimensions(context)
        val zoomPercent = getWebZoomPercent(context)
        val zoomConfig = computeZoomViewportConfig(zoomPercent)
        val lightenDark = isLightenDarkPagesEnabled(context)
        val showPageNumbers = isPageNumbersEnabled(context)
        val webColorScheme = getWebColorScheme(context)
        val useDarkWebMode = webColorScheme == WebColorScheme.DARK
        val effectiveLightenDark = if (useDarkWebMode) false else lightenDark
        val pageBaseColor = if (useDarkWebMode) Color.BLACK else Color.WHITE
        val topMarginTargetPx = (targetH * 0.0225f).roundToInt().coerceIn(8, 24)
        val bottomMarginTargetPx = if (showPageNumbers) {
            (targetH * 0.032f).roundToInt().coerceIn(22, 32)
        } else {
            (targetH * 0.0175f).roundToInt().coerceIn(6, 20)
        }
        val usableTargetH = (targetH - topMarginTargetPx - bottomMarginTargetPx).coerceAtLeast(100)
        val deviceFallbackLang = java.util.Locale.getDefault().language.ifBlank { "en" }

        val parsedHost = url.toUri().host.orEmpty()
        withContext(Dispatchers.IO) {
            if (parsedHost.isNotBlank()) {
                EInkImageOptimizer.ssrfSafeDns.lookup(parsedHost)
            }
        }

        val pageStreamFile = File.createTempFile("xtch_stream_", ".bin", context.cacheDir)
        val pageOffsets = mutableListOf<Long>()
        val pageSizes = mutableListOf<Int>()
        val pageFooterBgColors = mutableListOf<Int>()
        var pageTitle: String? = null
        var excerpt: String? = null
        var leadImageUrl: String? = null
        var publishedDate: Date? = null

        try {
            val rendered = withContext(Dispatchers.Main) {
            val activeActivity = CrossSendApp.instance.currentActivityRef?.get()?.takeIf {
                !it.isFinishing && !it.isDestroyed
            }
            val decorView = activeActivity?.window?.decorView as? ViewGroup
            val hostContext = activeActivity ?: context.applicationContext

            val metrics = hostContext.resources.displayMetrics
            val effectiveCssWidth = zoomConfig.cssWidth
            val virtualDensity = (targetW.toFloat() / effectiveCssWidth).coerceAtLeast(1f)
            val virtualDensityDpi = (virtualDensity * 160f).roundToInt().coerceIn(120, 480)

            val cssW = effectiveCssWidth.roundToInt()
            val cssH = (effectiveCssWidth * usableTargetH.toFloat() / targetW.toFloat()).roundToInt().coerceAtLeast(200)
            var viewW = targetW
            var viewH = usableTargetH

            var imageReader: ImageReader? = null
            var virtualDisplay: VirtualDisplay? = null
            var presentation: Presentation? = null
            var fallbackContainer: FrameLayout? = null
            var webView: WebView? = null
            val frameSignal = AtomicReference<CompletableDeferred<Unit>?>(null)

            fun createConfiguredWebView(wvContext: Context): WebView {
                return WebView(createWebViewThemeContext(wvContext, useDarkWebMode)).apply {
                    setBackgroundColor(pageBaseColor)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    try {
                        val allowThirdPartyCookies = hostContext
                            .getSharedPreferences("crosssend_prefs", Context.MODE_PRIVATE)
                            .getBoolean("allow_third_party_cookies", false)
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, allowThirdPartyCookies)
                    } catch (_: Exception) {}
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadsImagesAutomatically = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setSupportMultipleWindows(false)
                        useWideViewPort = false
                        loadWithOverviewMode = false
                        textZoom = zoomConfig.textZoomPercent
                        cacheMode = WebSettings.LOAD_DEFAULT
                        userAgentString = MOBILE_USER_AGENT
                        applyWebViewColorScheme(this, useDarkWebMode)
                    }
                    onResume()
                }
            }

            fun attachVirtualDisplay(
                widthPx: Int,
                heightPx: Int,
                dpi: Int,
                usePresentationContextForWebView: Boolean
            ): Boolean {
                return try {
                    val dm = hostContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                        ?: return false
                    val reader = ImageReader.newInstance(widthPx, heightPx, PixelFormat.RGBA_8888, 3)
                    reader.setOnImageAvailableListener({ r ->
                        if (frameSignal.get() != null) {
                            frameSignal.getAndSet(null)?.complete(Unit)
                        } else {
                            // Drain unrequested frames cleanly so BufferQueue never stalls
                            try {
                                r.acquireLatestImage()?.close()
                            } catch (_: Exception) {}
                        }
                    }, Handler(Looper.getMainLooper()))
                    val vd = dm.createVirtualDisplay(
                        "CrossSendXtchRender",
                        widthPx,
                        heightPx,
                        dpi,
                        reader.surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    )
                    val presentationThemeRes = if (useDarkWebMode) {
                        android.R.style.Theme_Material_NoActionBar
                    } else {
                        android.R.style.Theme_Material_Light_NoActionBar
                    }
                    val pres = Presentation(hostContext, vd.display, presentationThemeRes)
                    val wv = createConfiguredWebView(if (usePresentationContextForWebView) pres.context else hostContext)
                    val root = FrameLayout(pres.context).apply {
                        setBackgroundColor(pageBaseColor)
                        addView(wv, FrameLayout.LayoutParams(widthPx, heightPx))
                    }
                    pres.setContentView(root)
                    pres.show()
                    imageReader = reader
                    virtualDisplay = vd
                    presentation = pres
                    webView = wv
                    true
                } catch (t: Throwable) {
                    SafeLogger.w(TAG, "VirtualDisplay setup failed (${widthPx}x$heightPx @ ${dpi}dpi)", t)
                    try { webView?.destroy() } catch (_: Exception) {}
                    try { presentation?.dismiss() } catch (_: Exception) {}
                    try { virtualDisplay?.release() } catch (_: Exception) {}
                    try { imageReader?.close() } catch (_: Exception) {}
                    webView = null
                    presentation = null
                    virtualDisplay = null
                    imageReader = null
                    false
                }
            }

            fun releaseVirtualDisplayAndWebView() {
                try { webView?.stopLoading() } catch (_: Exception) {}
                try { webView?.destroy() } catch (_: Exception) {}
                try { presentation?.dismiss() } catch (_: Exception) {}
                try { virtualDisplay?.release() } catch (_: Exception) {}
                try { imageReader?.close() } catch (_: Exception) {}
                webView = null
                presentation = null
                virtualDisplay = null
                imageReader = null
            }

            var renderContextMode = "detached"
            try {
                // Prefer a 1:1 VirtualDisplay sized to targetW x usableTargetH with Presentation context;
                // verify Chromium actually honors the virtual display's density (some Android builds lock
                // WebView DPR to the primary display, which would otherwise shrink CSS viewport to ~160px).
                var presentationAttached = attachVirtualDisplay(
                    widthPx = viewW,
                    heightPx = viewH,
                    dpi = virtualDensityDpi,
                    usePresentationContextForWebView = true
                )
                if (presentationAttached) {
                    renderContextMode = "virtualDisplay"
                    val probeWv = webView!!
                    val initWidthSpec = View.MeasureSpec.makeMeasureSpec(viewW, View.MeasureSpec.EXACTLY)
                    val initHeightSpec = View.MeasureSpec.makeMeasureSpec(viewH, View.MeasureSpec.EXACTLY)
                    probeWv.measure(initWidthSpec, initHeightSpec)
                    probeWv.layout(0, 0, viewW, viewH)
                    awaitAnimationFrames(probeWv)

                    val metricsProbe = evaluateJsString(
                        probeWv,
                        "JSON.stringify({dpr: Number(window.devicePixelRatio || 0), w: Math.round(window.innerWidth || 0)})"
                    )
                    var actualDpr = 0f
                    var actualInnerW = 0
                    if (!metricsProbe.isNullOrBlank()) {
                        try {
                            val mJson = JSONObject(metricsProbe)
                            actualDpr = mJson.optDouble("dpr", 0.0).toFloat()
                            actualInnerW = mJson.optInt("w", 0)
                        } catch (_: Exception) {}
                    }
                    val honoredVirtualDensity = actualDpr > 0.5f &&
                        kotlin.math.abs(actualDpr - virtualDensity) <= 0.08f &&
                        (actualInnerW <= 0 || actualInnerW in (cssW - 4)..(cssW + 4))

                    if (!honoredVirtualDensity) {
                        val effectiveDensity = if (actualDpr > 0.5f) actualDpr else metrics.density.coerceAtLeast(1f)
                        val effectiveDpi = (effectiveDensity * 160f).roundToInt().coerceAtLeast(160)
                        SafeLogger.d(
                            TAG,
                            "WebView ignored 1:1 VirtualDisplay density (dpr=$actualDpr, innerW=$actualInnerW, expected=$virtualDensity); switching to ${effectiveDensity}x supersampled viewport"
                        )
                        releaseVirtualDisplayAndWebView()
                        viewW = (cssW * effectiveDensity).roundToInt().coerceAtLeast(targetW)
                        viewH = (cssH * effectiveDensity).roundToInt().coerceAtLeast(usableTargetH)
                        presentationAttached = attachVirtualDisplay(
                            widthPx = viewW,
                            heightPx = viewH,
                            dpi = effectiveDpi,
                            usePresentationContextForWebView = false
                        )
                        renderContextMode = if (presentationAttached) "virtualDisplay" else "detached"
                    }
                }

                if (!presentationAttached && decorView != null) {
                    val fallbackDensity = metrics.density.coerceAtLeast(1f)
                    viewW = (cssW * fallbackDensity).roundToInt().coerceAtLeast(targetW)
                    viewH = (cssH * fallbackDensity).roundToInt().coerceAtLeast(usableTargetH)
                    val wv = createConfiguredWebView(hostContext)
                    val container = FrameLayout(hostContext).apply {
                        layoutParams = ViewGroup.LayoutParams(viewW, viewH)
                        translationZ = -1f
                        addView(wv, FrameLayout.LayoutParams(viewW, viewH))
                    }
                    decorView.addView(container, 0)
                    fallbackContainer = container
                    webView = wv
                    renderContextMode = "decorView"
                } else if (!presentationAttached && webView == null) {
                    val fallbackDensity = metrics.density.coerceAtLeast(1f)
                    viewW = (cssW * fallbackDensity).roundToInt().coerceAtLeast(targetW)
                    viewH = (cssH * fallbackDensity).roundToInt().coerceAtLeast(usableTargetH)
                    webView = createConfiguredWebView(hostContext)
                }

                val activeWebView = webView ?: return@withContext false

                val widthSpec = View.MeasureSpec.makeMeasureSpec(viewW, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(viewH, View.MeasureSpec.EXACTLY)
                activeWebView.measure(widthSpec, heightSpec)
                activeWebView.layout(0, 0, viewW, viewH)

                val pageLoaded = CompletableDeferred<Boolean>()
                val currentProgress = AtomicInteger(0)
                activeWebView.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        currentProgress.set(newProgress)
                        val curUrl = view?.url
                        if (newProgress >= 96 && !curUrl.isNullOrBlank() && curUrl != "about:blank" && !pageLoaded.isCompleted) {
                            pageLoaded.complete(true)
                        }
                    }
                }
                activeWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val reqUrl = request?.url?.toString() ?: return true
                        return !EInkImageOptimizer.isSafeHttpsExternalUrl(reqUrl)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUri = request?.url ?: return forbiddenResponse()
                        val scheme = reqUri.scheme?.lowercase() ?: return forbiddenResponse()
                        if (scheme == "data" || scheme == "blob") return null
                        val reqUrlStr = reqUri.toString()
                        if (!EInkImageOptimizer.isSafeHttpsExternalUrl(reqUrlStr)) {
                            return forbiddenResponse()
                        }
                        val host = reqUri.host ?: return forbiddenResponse()
                        if (isTrackerHost(host)) {
                            return emptyNoContentResponse()
                        }
                        return try {
                            EInkImageOptimizer.ssrfSafeDns.lookup(host)
                            null
                        } catch (_: Exception) {
                            forbiddenResponse()
                        }
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        if (loadedUrl != null && loadedUrl != "about:blank" && currentProgress.get() >= 80 && !pageLoaded.isCompleted) {
                            pageLoaded.complete(true)
                        }
                    }
                }

                val safeHost = url.toUri().host ?: "(unknown)"
                SafeLogger.d(TAG, "Loading page ($renderContextMode, ${viewW}x$viewH px, target ${targetW}x$targetH) from host=$safeHost")
                activeWebView.loadUrl(url)
                withTimeoutOrNull(18_000L) { pageLoaded.await() }
                evaluateJsString(
                    activeWebView,
                    buildPreparePageJs(cssW, cssH, effectiveLightenDark, deviceFallbackLang, darkMode = useDarkWebMode)
                )
                awaitAnimationFrames(activeWebView)
                waitForWebAssets(activeWebView)
                evaluateJsString(
                    activeWebView,
                    buildPreparePageJs(cssW, cssH, effectiveLightenDark, deviceFallbackLang, darkMode = useDarkWebMode)
                )
                awaitAnimationFrames(activeWebView)
                awaitVisualState(activeWebView)
                delay(300L)

                val layoutJsonStr = evaluateJsString(
                    activeWebView,
                    buildPrepareAndMeasureJs(cssW, cssH)
                )

                val parsedBoxes = mutableListOf<LayoutBox>()
                val parsedBgLayers = mutableListOf<BgLayer>()
                val parsedMediaRects = mutableListOf<MediaRect>()
                var measuredCssH = cssH
                var actualCssW = cssW
                var measuredDpr = 0f
                var measuredVvScale = 1f
                var measuredViewportCssW = 0f
                var layoutPlan: PageLayoutPlan? = null
                val staticCuts = mutableListOf<Pair<Int, Int>>()
                if (!layoutJsonStr.isNullOrBlank()) {
                    try {
                        val json = JSONObject(layoutJsonStr)
                        pageTitle = json.optString("title").takeIf { it.isNotBlank() }
                        excerpt = json.optString("excerpt").takeIf { it.isNotBlank() }
                        leadImageUrl = json.optString("leadImage")
                            .takeIf { it.isNotBlank() && EInkImageOptimizer.isSafeHttpsExternalUrl(it) }
                        publishedDate = json.optString("publishedDate")
                            .takeIf { it.isNotBlank() }
                            ?.let { DateUtils.parseDate(it) }
                        measuredCssH = json.optInt("pageCssH", cssH).coerceAtLeast(100)
                        actualCssW = json.optInt("pageCssW", cssW).coerceAtLeast(100)
                        measuredDpr = json.optDouble("dpr", 0.0).toFloat()
                        measuredVvScale = json.optDouble("vvScale", 1.0).toFloat().let { if (it in 0.25f..4f) it else 1f }
                        measuredViewportCssW = json.optDouble("viewportCssW", 0.0).toFloat()
                        val totalCssH = json.optInt("totalHeight", measuredCssH).coerceAtLeast(measuredCssH)
                        val firstContentTop = json.optInt("firstContentTop", 0).coerceAtLeast(0)

                        val rawBoxes = json.optJSONArray("boxes") ?: JSONArray()
                        for (i in 0 until rawBoxes.length()) {
                            val b = rawBoxes.optJSONArray(i) ?: continue
                            val t = b.optInt(0, 0)
                            val bot = b.optInt(1, 0)
                            val kind = b.optInt(2, 0)
                            if (bot > t) {
                                parsedBoxes.add(LayoutBox(top = t, bottom = bot, isKeepBlock = (kind == 1)))
                            }
                        }

                        val rawBgLayers = json.optJSONArray("bgLayers") ?: JSONArray()
                        for (i in 0 until rawBgLayers.length()) {
                            val bl = rawBgLayers.optJSONArray(i) ?: continue
                            val t = bl.optInt(0, 0)
                            val bot = bl.optInt(1, 0)
                            val l = bl.optInt(2, 0)
                            val r = bl.optInt(3, 0)
                            val root = bl.optInt(4, 0) == 1
                            if (bot > t && r > l) {
                                parsedBgLayers.add(BgLayer(top = t, bottom = bot, left = l, right = r, isRoot = root))
                            }
                        }

                        val rawMediaRects = json.optJSONArray("mediaRects") ?: JSONArray()
                        for (i in 0 until rawMediaRects.length()) {
                            val mr = rawMediaRects.optJSONArray(i) ?: continue
                            val t = mr.optInt(0, 0)
                            val bot = mr.optInt(1, 0)
                            val l = mr.optInt(2, 0)
                            val r = mr.optInt(3, 0)
                            if (bot > t && r > l) {
                                parsedMediaRects.add(MediaRect(top = t, bottom = bot, left = l, right = r))
                            }
                        }

                        if (parsedBoxes.isNotEmpty()) {
                            layoutPlan = preparePageLayoutPlan(
                                totalHeight = totalCssH,
                                pageHeight = measuredCssH,
                                firstContentTop = firstContentTop,
                                rawBoxes = parsedBoxes
                            )
                        } else {
                            val ranges = json.optJSONArray("pages") ?: JSONArray()
                            for (i in 0 until minOf(ranges.length(), MAX_PAGES)) {
                                val pair = ranges.optJSONArray(i) ?: continue
                                val startCssY = pair.optInt(0, 0)
                                val endCssY = pair.optInt(1, startCssY + measuredCssH)
                                if (endCssY > startCssY) {
                                    staticCuts.add(startCssY to endCssY)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        SafeLogger.w(TAG, "Failed to parse layout cuts JSON", e)
                    }
                }

                if (layoutPlan == null && staticCuts.isEmpty()) {
                    staticCuts.add(0 to measuredCssH)
                }

                activeWebView.measure(widthSpec, heightSpec)
                activeWebView.layout(0, 0, viewW, viewH)
                delay(160L)

                val cssToViewScale = if (measuredDpr > 0.5f) {
                    measuredDpr * measuredVvScale
                } else {
                    viewW.toFloat() / actualCssW.toFloat()
                }
                val effectiveViewportCssW = measuredViewportCssW.takeIf { it >= 100f } ?: actualCssW.toFloat()
                val viewBitmap = createBitmap(viewW, viewH)
                val viewCanvas = Canvas(viewBitmap)
                val pageBitmap = createBitmap(targetW, targetH)
                val pageCanvas = Canvas(pageBitmap)
                val downsamplePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val xthScratch = XtcBuilder.XthScratchBuffers(targetW * targetH)
                var areaSrcBuf: IntArray? = null
                var areaHorizBuf: IntArray? = null
                var areaDstBuf: IntArray? = null

                try {
                    var currentCssY = layoutPlan?.initialStartY?.toFloat() ?: 0f
                    var pageIndex = 0
                    while (pageIndex < MAX_PAGES) {
                        val plannedEndCssY: Float
                        val isLastPage: Boolean
                        if (layoutPlan != null) {
                            val yInt = kotlin.math.floor(currentCssY).toInt().coerceAtLeast(0)
                            if (yInt >= layoutPlan.effectiveTotalH) break
                            val remaining = layoutPlan.effectiveTotalH - yInt
                            if (remaining <= measuredCssH) {
                                val hasVisibleTail = layoutPlan.validBoxes.any { it.bottom > yInt + 2 && it.top < layoutPlan.effectiveTotalH }
                                if (pageIndex > 0 && remaining < 24 && !(hasVisibleTail && remaining >= 6)) {
                                    break
                                }
                                plannedEndCssY = minOf(layoutPlan.effectiveTotalH, yInt + measuredCssH).toFloat()
                                isLastPage = true
                            } else {
                                val nextY = computeNextPageCut(
                                    y = yInt,
                                    pageHeight = measuredCssH,
                                    atomicKeepBlocks = layoutPlan.atomicKeepBlocks,
                                    mergedTextBoxes = layoutPlan.mergedTextBoxes,
                                    atomicBoxes = layoutPlan.atomicBoxes
                                )
                                plannedEndCssY = nextY.toFloat().coerceAtLeast(currentCssY + 16f)
                                isLastPage = false
                            }
                        } else {
                            if (pageIndex >= staticCuts.size) break
                            val range = staticCuts[pageIndex]
                            currentCssY = range.first.toFloat()
                            plannedEndCssY = range.second.toFloat()
                            isLastPage = (pageIndex == staticCuts.lastIndex)
                        }

                        val scrollYPx = kotlin.math.floor(currentCssY * cssToViewScale).toInt().coerceAtLeast(0)
                        val sliceExpectsContent = parsedBoxes.any { it.bottom > currentCssY + 4f && it.top < plannedEndCssY - 4f }

                        val reader = imageReader
                        var frameSignalForPage: CompletableDeferred<Unit>? = null
                        if (reader != null) {
                            drainImageReader(reader)
                            frameSignalForPage = CompletableDeferred()
                            frameSignal.set(frameSignalForPage)
                        }

                        activeWebView.scrollTo(0, scrollYPx)
                        activeWebView.invalidate()
                        presentation?.window?.decorView?.postInvalidate()
                        awaitAnimationFrames(activeWebView)
                        awaitVisualState(activeWebView)

                        if (scrollYPx > 0) {
                            val hidOverlays = evaluateJsString(activeWebView, buildHideScrollOverlaysJs()) == "1"
                            if (hidOverlays) {
                                if (reader != null) {
                                    drainImageReader(reader)
                                    frameSignalForPage = CompletableDeferred()
                                    frameSignal.set(frameSignalForPage)
                                    activeWebView.invalidate()
                                    presentation?.window?.decorView?.postInvalidate()
                                }
                                awaitAnimationFrames(activeWebView)
                                awaitVisualState(activeWebView)
                            }
                        }

                        var actualScrollCssY = evaluateJsString(activeWebView, "String(window.scrollY || window.pageYOffset || 0)")
                            ?.toFloatOrNull()
                            ?: (activeWebView.scrollY.toFloat() / cssToViewScale)

                        val needsCorrectiveScroll = actualScrollCssY > currentCssY + 0.75f ||
                            (actualScrollCssY < currentCssY - 1.5f && activeWebView.scrollY == scrollYPx)
                        if (needsCorrectiveScroll) {
                            val correctedScrollYPx = (scrollYPx + ((currentCssY - actualScrollCssY) * cssToViewScale).roundToInt())
                                .coerceAtLeast(0)
                            if (reader != null) {
                                drainImageReader(reader)
                                frameSignalForPage = CompletableDeferred()
                                frameSignal.set(frameSignalForPage)
                            }
                            activeWebView.scrollTo(0, correctedScrollYPx)
                            activeWebView.invalidate()
                            presentation?.window?.decorView?.postInvalidate()
                            awaitAnimationFrames(activeWebView)
                            awaitVisualState(activeWebView)
                            actualScrollCssY = evaluateJsString(activeWebView, "String(window.scrollY || window.pageYOffset || 0)")
                                ?.toFloatOrNull()
                                ?: (activeWebView.scrollY.toFloat() / cssToViewScale)
                        }

                        var capturedFromSurface = false
                        if (reader != null && frameSignalForPage != null) {
                            withTimeoutOrNull(450L) { frameSignalForPage.await() }
                            frameSignal.compareAndSet(frameSignalForPage, null)
                            delay(85L)
                            val image = try {
                                reader.acquireLatestImage()
                            } catch (_: Exception) {
                                null
                            }
                            if (image != null) {
                                try {
                                    capturedFromSurface = withContext(Dispatchers.Default) {
                                        copyImageToBitmap(image, viewBitmap, viewW, viewH)
                                    }
                                } finally {
                                    image.close()
                                }
                            }
                            if (capturedFromSurface && sliceExpectsContent && withContext(Dispatchers.Default) {
                                    countNonWhitePixelsSampled(viewBitmap, useDarkWebMode) == 0
                                }
                            ) {
                                // Compositor tile may still have been blank on first frame; wait briefly and retry
                                activeWebView.invalidate()
                                presentation?.window?.decorView?.postInvalidate()
                                awaitAnimationFrames(activeWebView)
                                delay(160L)
                                val retryImage = try {
                                    reader.acquireLatestImage()
                                } catch (_: Exception) {
                                    null
                                }
                                if (retryImage != null) {
                                    try {
                                        withContext(Dispatchers.Default) {
                                            copyImageToBitmap(retryImage, viewBitmap, viewW, viewH)
                                        }
                                    } finally {
                                        retryImage.close()
                                    }
                                }
                            }
                        } else {
                            delay(if (pageIndex == 0) 180L else 100L)
                        }

                        if (!capturedFromSurface) {
                            viewCanvas.drawColor(pageBaseColor)
                            activeWebView.draw(viewCanvas)
                            actualScrollCssY = activeWebView.scrollY.toFloat() / cssToViewScale
                        }

                        val srcTopViewPx = ((currentCssY - actualScrollCssY) * cssToViewScale)
                            .roundToInt()
                            .coerceIn(0, (viewH - 1).coerceAtLeast(0))
                        val plannedBotViewPx = ((plannedEndCssY - actualScrollCssY) * cssToViewScale)
                            .roundToInt()
                            .coerceIn(srcTopViewPx + 1, viewH)

                        val cutInsideMedia = parsedMediaRects.any {
                            it.top < plannedEndCssY - 2f && it.bottom > plannedEndCssY + 2f
                        }
                        val finalBotViewPx = if (!isLastPage && layoutPlan != null && !cutInsideMedia) {
                            withContext(Dispatchers.Default) {
                                refineCutRowOnBitmap(
                                    bitmap = viewBitmap,
                                    srcTopPx = srcTopViewPx,
                                    plannedBotPx = plannedBotViewPx,
                                    cssToViewScale = cssToViewScale
                                )
                            }
                        } else {
                            plannedBotViewPx
                        }
                        val actualEndCssY = if (finalBotViewPx != plannedBotViewPx) {
                            actualScrollCssY + (finalBotViewPx.toFloat() / cssToViewScale)
                        } else {
                            plannedEndCssY
                        }

                        val visibleViewH = (finalBotViewPx - srcTopViewPx).coerceIn(1, viewH - srcTopViewPx)
                        val visibleTargetH = ((visibleViewH.toFloat() / viewH.toFloat()) * usableTargetH)
                            .roundToInt()
                            .coerceIn(1, usableTargetH)

                        pageCanvas.drawColor(pageBaseColor)
                        val startCssInt = currentCssY.roundToInt()
                        val endCssInt = actualEndCssY.roundToInt()
                        val topBands = matchingBgBandsForEdge(
                            cutY = startCssInt,
                            isTopEdge = true,
                            pageHeight = measuredCssH,
                            bgLayers = parsedBgLayers
                        )
                        val botBands = matchingBgBandsForEdge(
                            cutY = endCssInt,
                            isTopEdge = false,
                            pageHeight = measuredCssH,
                            bgLayers = parsedBgLayers
                        )
                        paintContinuingMarginBackgrounds(
                            pageCanvas = pageCanvas,
                            viewBitmap = viewBitmap,
                            bands = topBands,
                            srcRowY = (srcTopViewPx + 1).coerceAtMost(finalBotViewPx - 1),
                            marginTopPx = 0,
                            marginBottomPx = topMarginTargetPx,
                            actualCssW = actualCssW,
                            viewW = viewW,
                            targetW = targetW
                        )
                        paintContinuingMarginBackgrounds(
                            pageCanvas = pageCanvas,
                            viewBitmap = viewBitmap,
                            bands = botBands,
                            srcRowY = (finalBotViewPx - 2).coerceAtLeast(srcTopViewPx),
                            marginTopPx = topMarginTargetPx + visibleTargetH,
                            marginBottomPx = targetH,
                            actualCssW = actualCssW,
                            viewW = viewW,
                            targetW = targetW
                        )
                        if (viewW == targetW && visibleViewH == visibleTargetH) {
                            pageCanvas.drawBitmap(
                                viewBitmap,
                                Rect(0, srcTopViewPx, viewW, finalBotViewPx),
                                Rect(0, topMarginTargetPx, targetW, topMarginTargetPx + visibleTargetH),
                                null
                            )
                        } else if (viewW > targetW || visibleViewH > visibleTargetH) {
                            val srcNeeded = viewW * visibleViewH
                            val srcBuf = areaSrcBuf?.takeIf { it.size >= srcNeeded }
                                ?: IntArray(srcNeeded).also { areaSrcBuf = it }
                            viewBitmap.getPixels(srcBuf, 0, viewW, 0, srcTopViewPx, viewW, visibleViewH)

                            val horizNeeded = targetW * visibleViewH
                            val horizBuf = areaHorizBuf?.takeIf { it.size >= horizNeeded }
                                ?: IntArray(horizNeeded).also { areaHorizBuf = it }

                            val dstNeeded = targetW * visibleTargetH
                            val dstBuf = areaDstBuf?.takeIf { it.size >= dstNeeded }
                                ?: IntArray(dstNeeded).also { areaDstBuf = it }

                            withContext(Dispatchers.Default) {
                                downsampleAreaArgb(
                                    srcPixels = srcBuf,
                                    srcWidth = viewW,
                                    srcHeight = visibleViewH,
                                    dstWidth = targetW,
                                    dstHeight = visibleTargetH,
                                    scratchHoriz = horizBuf,
                                    scratchOut = dstBuf
                                )
                            }
                            pageBitmap.setPixels(dstBuf, 0, targetW, 0, topMarginTargetPx, targetW, visibleTargetH)
                        } else {
                            pageCanvas.drawBitmap(
                                viewBitmap,
                                Rect(0, srcTopViewPx, viewW, finalBotViewPx),
                                Rect(0, topMarginTargetPx, targetW, topMarginTargetPx + visibleTargetH),
                                downsamplePaint
                            )
                        }

                        if (showPageNumbers) {
                            val sampleY = (targetH - bottomMarginTargetPx / 2).coerceIn(0, targetH - 1)
                            val xs = intArrayOf(
                                (targetW / 4).coerceIn(0, targetW - 1),
                                (targetW / 2).coerceIn(0, targetW - 1),
                                ((targetW * 3) / 4).coerceIn(0, targetW - 1)
                            )
                            val lumas = IntArray(3) { sIdx ->
                                val px = pageBitmap[xs[sIdx], sampleY]
                                (77 * Color.red(px) + 150 * Color.green(px) + 29 * Color.blue(px)) shr 8
                            }
                            lumas.sort()
                            val medianLuma = lumas[1]
                            val snappedBg = when {
                                medianLuma >= 210 -> Color.WHITE
                                medianLuma >= 110 -> Color.rgb(170, 170, 170)
                                else -> Color.BLACK
                            }
                            pageFooterBgColors.add(snappedBg)
                        }

                        if (pageIndex == 0 || isLastPage || pageIndex == MAX_PAGES - 1) {
                            val nonWhite = withContext(Dispatchers.Default) {
                                countNonWhitePixelsSampled(pageBitmap, useDarkWebMode)
                            }
                            SafeLogger.d(TAG, "Page ${pageIndex + 1} drawn (surface=$capturedFromSurface, nonWhite=$nonWhite)")
                        }

                        val pagePhotoRects = if (parsedMediaRects.isNotEmpty() && effectiveViewportCssW > 0f && viewH > 0) {
                            parsedMediaRects.mapNotNull { mr ->
                                if (mr.bottom <= currentCssY || mr.top >= actualEndCssY) {
                                    null
                                } else {
                                    val lPx = ((mr.left.toFloat() / effectiveViewportCssW) * targetW).roundToInt().coerceIn(0, targetW)
                                    val rPx = ((mr.right.toFloat() / effectiveViewportCssW) * targetW).roundToInt().coerceIn(0, targetW)
                                    val relTopView = ((maxOf(mr.top.toFloat(), currentCssY) - currentCssY) * cssToViewScale)
                                    val relBotView = ((minOf(mr.bottom.toFloat(), actualEndCssY) - currentCssY) * cssToViewScale)
                                    val tPx = (topMarginTargetPx + (relTopView / viewH.toFloat()) * usableTargetH)
                                        .roundToInt().coerceIn(topMarginTargetPx, topMarginTargetPx + visibleTargetH)
                                    val bPx = (topMarginTargetPx + (relBotView / viewH.toFloat()) * usableTargetH)
                                        .roundToInt().coerceIn(topMarginTargetPx, topMarginTargetPx + visibleTargetH)
                                    if (rPx > lPx && bPx > tPx) Rect(lPx, tPx, rPx, bPx) else null
                                }
                            }
                        } else emptyList()

                        val pageSize = withContext(Dispatchers.Default) {
                            val xthBlob = XtcBuilder.bitmapToXthPage(pageBitmap, pagePhotoRects, xthScratch)
                            withContext(Dispatchers.IO) {
                                FileOutputStream(pageStreamFile, true).use { it.write(xthBlob) }
                            }
                            xthBlob.size
                        }
                        val nextOffset = if (pageOffsets.isEmpty()) 0L else pageOffsets.last() + pageSizes.last().toLong()
                        pageOffsets.add(nextOffset)
                        pageSizes.add(pageSize)

                        if (isLastPage) break
                        currentCssY = maxOf(actualEndCssY, currentCssY + 16f)
                        pageIndex++
                    }
                } finally {
                    frameSignal.set(null)
                    viewBitmap.recycle()
                    pageBitmap.recycle()
                }
                true
            } catch (e: Exception) {
                val safeHost = url.toUri().host ?: "(unknown)"
                SafeLogger.e(TAG, "WebView XTCH render failed for host=$safeHost", e)
                false
            } finally {
                try { presentation?.dismiss() } catch (_: Exception) {}
                try { virtualDisplay?.release() } catch (_: Exception) {}
                try { imageReader?.close() } catch (_: Exception) {}
                try {
                    fallbackContainer?.let { container ->
                        (container.parent as? ViewGroup)?.removeView(container)
                    }
                } catch (_: Exception) {}
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                } catch (_: Exception) {}
            }
        }

            if (!rendered || pageSizes.isEmpty()) {
                return null
            }

            if (showPageNumbers && pageSizes.size > 1) {
                withContext(Dispatchers.IO) {
                    stampPageNumbersOnXthStreamFile(
                        pageStreamFile = pageStreamFile,
                        pageOffsets = pageOffsets,
                        pageSizes = pageSizes,
                        pageFooterBgColors = pageFooterBgColors,
                        targetW = targetW,
                        targetH = targetH,
                        bottomMarginTargetPx = bottomMarginTargetPx
                    )
                }
            }

            val effectiveTitle = title.ifBlank { pageTitle ?: "Article" }
            val built = withContext(Dispatchers.IO) {
                XtcBuilder.buildXtchFromPageFile(
                    outputFile = outputFile,
                    title = effectiveTitle,
                    author = author,
                    pageBlobFile = pageStreamFile,
                    pageSizes = pageSizes,
                    width = targetW,
                    height = targetH
                )
            }
            if (!built) {
                return null
            }

            SafeLogger.d(TAG, "Successfully built XTCH (${outputFile.length()} bytes, ${pageSizes.size} pages)")
            return XtchRenderResult(
                pageCount = pageSizes.size,
                title = effectiveTitle,
                excerpt = excerpt,
                colorCoverFile = null,
                leadImageUrl = leadImageUrl,
                publishedDate = publishedDate
            )
        } finally {
            try { pageStreamFile.delete() } catch (_: Exception) {}
        }
    }

    private fun drainImageReader(reader: ImageReader) {
        while (true) {
            val img = try {
                reader.acquireNextImage()
            } catch (_: Exception) {
                null
            } ?: break
            img.close()
        }
    }

    private fun copyImageToBitmap(image: Image, dest: Bitmap, width: Int, height: Int): Boolean {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            val pixelStride = plane.pixelStride.coerceAtLeast(4)
            val rowStride = plane.rowStride
            val rowPixels = rowStride / pixelStride
            if (rowPixels == width && pixelStride == 4) {
                dest.copyPixelsFromBuffer(buffer)
                return true
            }
            val paddedBitmap = createBitmap(maxOf(width, rowPixels), height)
            try {
                paddedBitmap.copyPixelsFromBuffer(buffer)
                val c = Canvas(dest)
                c.drawBitmap(paddedBitmap, Rect(0, 0, width, height), Rect(0, 0, width, height), null)
            } finally {
                paddedBitmap.recycle()
            }
            true
        } catch (e: Exception) {
            SafeLogger.w(TAG, "copyImageToBitmap failed", e)
            false
        }
    }

    private suspend fun awaitAnimationFrames(webView: WebView) {
        withTimeoutOrNull(250L) {
            repeat(2) {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                    webView.postOnAnimation {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
        }
    }

    private suspend fun awaitVisualState(webView: WebView) {
        withTimeoutOrNull(400L) {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                try {
                    webView.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    })
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private suspend fun waitForWebAssets(webView: WebView) {
        // Trigger lazy-loaded images and web fonts so they receive real dimensions before pagination measurement
        val activateLazyJs = """
            (function() {
                try {
                    window.__xtchFontsReady = false;
                    if (document.fonts && document.fonts.ready) {
                        document.fonts.ready.then(function() { window.__xtchFontsReady = true; })
                            .catch(function() { window.__xtchFontsReady = true; });
                    } else {
                        window.__xtchFontsReady = true;
                    }
                    var imgs = Array.prototype.slice.call(document.images || []);
                    for (var i = 0; i < imgs.length; i++) {
                        var img = imgs[i];
                        img.loading = 'eager';
                        img.decoding = 'sync';
                        img.removeAttribute('loading');
                        var dataSrc = img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || img.getAttribute('data-original');
                        if (dataSrc && (!img.getAttribute('src') || img.getAttribute('src').indexOf('data:') === 0)) {
                            img.setAttribute('src', dataSrc);
                        }
                        var dataSrcset = img.getAttribute('data-srcset');
                        if (dataSrcset && !img.getAttribute('srcset')) {
                            img.setAttribute('srcset', dataSrcset);
                        }
                    }
                    var sources = Array.prototype.slice.call(document.querySelectorAll('picture source[data-srcset]'));
                    for (var s = 0; s < sources.length; s++) {
                        if (!sources[s].getAttribute('srcset')) {
                            sources[s].setAttribute('srcset', sources[s].getAttribute('data-srcset'));
                        }
                    }
                } catch (e) {}
            })();
        """.trimIndent()
        evaluateJsString(webView, activateLazyJs)

        // Warmup scroll sweep so IntersectionObserver lazy sections and deferred images hydrate before layout measurement
        for (frac in floatArrayOf(0.25f, 0.55f, 0.85f, 0f)) {
            evaluateJsString(
                webView,
                "(function(){ var h = Math.max(document.documentElement.scrollHeight||0, document.body?document.body.scrollHeight:0); var y = Math.floor(h * $frac); try { window.scrollTo({ top: y, left: 0, behavior: 'instant' }); } catch (e) { window.scrollTo(0, y); } })();"
            )
            delay(140L)
        }
        evaluateJsString(webView, activateLazyJs)

        val js = """
            (function() {
                try {
                    var bodyLen = (document.body && document.body.innerText) ? document.body.innerText.trim().length : 0;
                    var imgs = Array.prototype.slice.call(document.images || []);
                    var pending = imgs.filter(function(i) {
                        var hasSrc = !!(i.currentSrc || i.getAttribute('src'));
                        return hasSrc && i.complete === false;
                    }).length;
                    var fontsOk = (window.__xtchFontsReady === true) || !document.fonts || document.fonts.status === 'loaded';
                    return (bodyLen > 40 && pending === 0 && fontsOk) ? "1" : "0";
                } catch (e) { return "1"; }
            })();
        """.trimIndent()
        for (attempt in 0 until 16) {
            val res = evaluateJsString(webView, js)
            if (res == "1") break
            delay(250L)
        }
    }

    private suspend fun evaluateJsString(webView: WebView, script: String): String? {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { raw ->
                val decoded = try {
                    if (raw == null || raw == "null") null
                    else JSONTokener(raw).nextValue()?.toString()
                } catch (_: Exception) {
                    raw
                }
                if (cont.isActive) cont.resume(decoded)
            }
        }
    }

    private fun countNonWhitePixelsSampled(bitmap: Bitmap, isDarkBackground: Boolean = false): Int {
        var count = 0
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return 0
        val stepX = maxOf(1, w / 48)
        val stepY = maxOf(1, h / 80)
        val rowPixels = IntArray(w)
        var y = 0
        while (y < h) {
            bitmap.getPixels(rowPixels, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val c = rowPixels[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val hasForeground = if (isDarkBackground) {
                    r > 10 || g > 10 || b > 10
                } else {
                    r < 245 || g < 245 || b < 245
                }
                if (hasForeground) count++
                x += stepX
            }
            y += stepY
        }
        return count
    }

    private val TRACKER_HOST_SUFFIXES = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googleadservices.com",
        "facebook.net",
        "connect.facebook.net",
        "pixel.facebook.com",
        "scorecardresearch.com",
        "quantserve.com",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "amazon-adsystem.com",
        "hotjar.com",
        "segment.io",
        "mixpanel.com",
        "amplitude.com",
        "newrelic.com",
        "sentry.io",
        "chartbeat.com",
        "omtrdc.net"
    )

    internal fun isTrackerHost(host: String): Boolean {
        val clean = host.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return false
        return TRACKER_HOST_SUFFIXES.any { suffix -> clean == suffix || clean.endsWith(".$suffix") }
    }

    private fun emptyNoContentResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

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

    private fun buildHideScrollOverlaysJs(): String {
        return """
            (function() {
                try {
                    var vh = window.innerHeight || 600;
                    var els = document.querySelectorAll('*');
                    var changed = false;
                    for (var i = 0; i < els.length; i++) {
                        var el = els[i];
                        var tag = (el.tagName || '').toLowerCase();
                        if (tag === 'html' || tag === 'body') continue;
                        var cs = window.getComputedStyle(el);
                        if (cs.position === 'fixed') {
                            var fr = el.getBoundingClientRect();
                            if (fr.height < vh * 0.70) {
                                if (cs.visibility !== 'hidden' || parseFloat(cs.opacity || '1') > 0.01) {
                                    changed = true;
                                }
                                el.style.setProperty('visibility', 'hidden', 'important');
                                el.style.setProperty('opacity', '0', 'important');
                                el.style.setProperty('pointer-events', 'none', 'important');
                            }
                        } else if (cs.position === 'sticky') {
                            var sr = el.getBoundingClientRect();
                            if (sr.height < vh * 0.40 && (sr.top <= 16 || sr.bottom >= vh - 16)) {
                                changed = true;
                                // Un-stick without collapsing flow height so measured Y-coordinates never shift
                                el.style.setProperty('position', 'relative', 'important');
                                el.style.setProperty('top', 'auto', 'important');
                                el.style.setProperty('bottom', 'auto', 'important');
                            }
                        }
                    }
                    return changed ? "1" : "0";
                } catch (e) { return "0"; }
            })();
        """.trimIndent()
    }

    internal fun buildPreparePageJs(
        fallbackCssW: Int,
        fallbackCssH: Int,
        lightenDark: Boolean,
        fallbackLang: String = "en",
        darkMode: Boolean = false
    ): String {
        val safeFallbackLang = fallbackLang.trim()
            .replace('_', '-')
            .filter { it in 'a'..'z' || it in 'A'..'Z' || it == '-' }
            .take(10)
            .ifBlank { "en" }
        val colorSchemeCssRule = if (!darkMode) ":root { color-scheme: light only !important; } " else ""
        return """
            (function() {
                try {
                    var rawViewportW = window.innerWidth || 0;
                    var actualViewportW = Math.floor(rawViewportW);
                    var pageW = (actualViewportW >= 180 && actualViewportW <= ($fallbackCssW + 16)) ? actualViewportW : $fallbackCssW;
                    var pageH = $fallbackCssH;

                    var metaVp = document.querySelector('meta[name="viewport"]');
                    if (!metaVp) {
                        metaVp = document.createElement('meta');
                        metaVp.setAttribute('name', 'viewport');
                        if (document.head) document.head.appendChild(metaVp);
                    }
                    if (metaVp) {
                        metaVp.setAttribute('content', 'width=' + pageW + ', initial-scale=1.0, maximum-scale=1.0, user-scalable=no');
                    }

                    ${if (!darkMode) """
                    if (!document.getElementById('__xtch_cs_meta')) {
                        var metaCs = document.createElement('meta');
                        metaCs.id = '__xtch_cs_meta';
                        metaCs.setAttribute('name', 'color-scheme');
                        metaCs.setAttribute('content', 'light only');
                        if (document.head) document.head.appendChild(metaCs);
                    }
                    """ else ""}

                    if (document.body && !document.documentElement.dataset.xtchBodyMarginChecked) {
                        document.documentElement.dataset.xtchBodyMarginChecked = '1';
                        var initBodyCs = window.getComputedStyle(document.body);
                        var origML = Math.min(16, Math.round(parseFloat(initBodyCs.marginLeft) || 0));
                        var origMR = Math.min(16, Math.round(parseFloat(initBodyCs.marginRight) || 0));
                        var origPL = parseFloat(initBodyCs.paddingLeft) || 0;
                        var origPR = parseFloat(initBodyCs.paddingRight) || 0;
                        if (origML >= 4 && origPL < 4) {
                            document.body.style.setProperty('padding-left', origML + 'px', 'important');
                        }
                        if (origMR >= 4 && origPR < 4) {
                            document.body.style.setProperty('padding-right', origMR + 'px', 'important');
                        }
                    }

                    if (!document.documentElement.getAttribute('lang') || !document.documentElement.getAttribute('lang').trim()) {
                        var metaLang = document.querySelector('meta[http-equiv="content-language"], meta[property="og:locale"]');
                        var detectedLang = (metaLang && metaLang.content) ? metaLang.content.trim().replace('_', '-').split(',')[0].trim() : '';
                        document.documentElement.setAttribute('lang', /^[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,4})?$/.test(detectedLang) ? detectedLang : '$safeFallbackLang');
                    }

                    if (!document.getElementById('__xtch_style')) {
                        var style = document.createElement('style');
                        style.id = '__xtch_style';
                        style.textContent =
                            '$colorSchemeCssRule' +
                            'html { width: ' + pageW + 'px !important; max-width: ' + pageW + 'px !important; ' +
                            'height: auto !important; min-height: 0 !important; max-height: none !important; ' +
                            'overflow-x: clip !important; overflow-y: visible !important; overflow-anchor: none !important; ' +
                            'scroll-behavior: auto !important; scroll-snap-type: none !important; ' +
                            'margin: 0 !important; padding: 0 !important; box-sizing: border-box !important; } ' +
                            'body { width: ' + pageW + 'px !important; max-width: ' + pageW + 'px !important; ' +
                            'height: auto !important; min-height: 0 !important; max-height: none !important; ' +
                            'overflow-x: clip !important; overflow-y: visible !important; overflow-anchor: none !important; ' +
                            'scroll-behavior: auto !important; scroll-snap-type: none !important; ' +
                            'margin: 0 !important; box-sizing: border-box !important; -webkit-font-smoothing: antialiased !important; } ' +
                            'main, article, section, header, nav, footer, aside, figure, blockquote, pre, ul, ol, p, h1, h2, h3, h4, h5, h6 { ' +
                            'max-width: 100% !important; box-sizing: border-box !important; } ' +
                            'main, article, header, nav { overflow-x: clip !important; } ' +
                            'body, p, li, dt, dd, blockquote, figcaption, h1, h2, h3, h4, h5, h6, a, code, td, th, div, span { ' +
                            'overflow-wrap: break-word !important; word-wrap: break-word !important; } ' +
                            'pre { white-space: pre-wrap !important; overflow-wrap: anywhere !important; } ' +
                            'p, li, dt, dd, blockquote, figcaption, td, th, h1, h2, h3, h4, h5, h6 { ' +
                            'hyphens: auto !important; -webkit-hyphens: auto !important; hyphenate-limit-chars: 6 3 2; } ' +
                            'code, pre, kbd, samp, var { hyphens: none !important; -webkit-hyphens: none !important; } ' +
                            'img, svg, video, iframe, table { max-width: 100% !important; height: auto !important; } ' +
                            '*::-webkit-scrollbar { display: none !important; }';
                        (document.head || document.documentElement).appendChild(style);
                    }

                    var clampTargets = document.querySelectorAll('main, article, section, header, footer, aside, div, p, h1, h2, h3, h4, h5, h6, figure, blockquote, pre, ul, ol, li, dl, dt, dd, span, a, code, figcaption, label, button, img, picture, svg, video, table, td, th');
                    for (var ci = 0; ci < clampTargets.length; ci++) {
                        var celClamp = clampTargets[ci];
                        var crClamp = celClamp.getBoundingClientRect();
                        var ccsClamp = null;
                        if (crClamp.width > 2 && crClamp.height > 2 && (crClamp.right > pageW + 1 || crClamp.left < -1)) {
                            ccsClamp = window.getComputedStyle(celClamp);
                            if (ccsClamp.display !== 'none' && ccsClamp.visibility !== 'hidden') {
                                if ((parseFloat(ccsClamp.marginLeft) || 0) < 0) {
                                    celClamp.style.setProperty('margin-left', '0px', 'important');
                                }
                                if ((parseFloat(ccsClamp.marginRight) || 0) < 0) {
                                    celClamp.style.setProperty('margin-right', '0px', 'important');
                                }
                                if (crClamp.left < -1 && ccsClamp.position === 'relative' && (parseFloat(ccsClamp.left) || 0) < 0) {
                                    celClamp.style.setProperty('left', '0px', 'important');
                                }
                                if (ccsClamp.whiteSpace === 'nowrap') {
                                    celClamp.style.setProperty('white-space', 'normal', 'important');
                                } else if (ccsClamp.whiteSpace === 'pre') {
                                    celClamp.style.setProperty('white-space', 'pre-wrap', 'important');
                                }
                                celClamp.style.setProperty('box-sizing', 'border-box', 'important');
                                celClamp.style.setProperty('min-width', '0px', 'important');
                                celClamp.style.setProperty('max-width', '100%', 'important');
                                var tagClamp = (celClamp.tagName || '').toLowerCase();
                                if (tagClamp === 'table' && crClamp.right > pageW + 1) {
                                    celClamp.style.setProperty('table-layout', 'fixed', 'important');
                                    celClamp.style.setProperty('width', '100%', 'important');
                                }
                                if (crClamp.right > pageW + 1) {
                                    var pClamp = celClamp.parentElement;
                                    if (pClamp && pClamp !== document.body && pClamp !== document.documentElement) {
                                        var pCsClamp = window.getComputedStyle(pClamp);
                                        if (pCsClamp.display === 'flex' || pCsClamp.display === 'inline-flex') {
                                            pClamp.style.setProperty('flex-wrap', 'wrap', 'important');
                                            celClamp.style.setProperty('flex-shrink', '1', 'important');
                                        } else if ((pCsClamp.display === 'grid' || pCsClamp.display === 'inline-grid') && pClamp.scrollWidth > pageW + 2) {
                                            pClamp.style.setProperty('grid-template-columns', 'minmax(0, 1fr)', 'important');
                                        }
                                    }
                                }
                            }
                        }
                        var tagVertClamp = (celClamp.tagName || '').toLowerCase();
                        if (/^(h[1-6]|p|li|dt|dd|figcaption|blockquote|div|span|a)$/.test(tagVertClamp) && crClamp.height >= 12 && crClamp.width >= 40) {
                            if (!ccsClamp) ccsClamp = window.getComputedStyle(celClamp);
                            if (ccsClamp.display !== 'none' && ccsClamp.visibility !== 'hidden' &&
                                (ccsClamp.overflowY === 'hidden' || ccsClamp.overflowY === 'clip') &&
                                ccsClamp.textOverflow !== 'ellipsis' &&
                                (!ccsClamp.webkitLineClamp || ccsClamp.webkitLineClamp === 'none')) {
                                if (celClamp.scrollHeight > celClamp.clientHeight + 2 && celClamp.scrollHeight <= celClamp.clientHeight * 1.6) {
                                    var vertTxtLen = (celClamp.innerText || '').trim().length;
                                    if (vertTxtLen > 0 && vertTxtLen <= 400) {
                                        celClamp.style.setProperty('height', 'auto', 'important');
                                        celClamp.style.setProperty('max-height', 'none', 'important');
                                        celClamp.style.setProperty('overflow-y', 'visible', 'important');
                                    }
                                }
                            }
                        }
                    }

                    function parseRgbaColor(str) {
                        if (!str || str === 'transparent') return null;
                        var m = str.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)/);
                        if (!m) return null;
                        var a = (m[4] !== undefined) ? parseFloat(m[4]) : 1.0;
                        if (isNaN(a)) a = 1.0;
                        return [+m[1], +m[2], +m[3], a];
                    }
                    function rgbLuma(r, g, b) {
                        return 0.299 * r + 0.587 * g + 0.114 * b;
                    }
                    function parseGradientLuma(bgImgStr) {
                        if (!bgImgStr || bgImgStr === 'none' || bgImgStr.indexOf('url(') !== -1) return null;
                        if (bgImgStr.indexOf('gradient(') === -1) return null;
                        // Gradients with transparent stops (e.g. CSS underlines or fade scrims) are not opaque box fills
                        if (/\btransparent\b/i.test(bgImgStr) || /rgba\(\s*\d+\s*,\s*\d+\s*,\s*\d+\s*,\s*0(?:\.0+)?\s*\)/.test(bgImgStr)) {
                            return null;
                        }
                        var re = /rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)/g;
                        var match;
                        var sum = 0;
                        var alphaSum = 0;
                        var count = 0;
                        while ((match = re.exec(bgImgStr)) !== null) {
                            var a = (match[4] !== undefined) ? parseFloat(match[4]) : 1.0;
                            if (isNaN(a)) a = 1.0;
                            alphaSum += a;
                            var r = +match[1] * a + 255 * (1 - a);
                            var g = +match[2] * a + 255 * (1 - a);
                            var b = +match[3] * a + 255 * (1 - a);
                            sum += rgbLuma(r, g, b);
                            count++;
                        }
                        if (count === 0 || (alphaSum / count) < 0.5) return null;
                        return sum / count;
                    }

                    var allElsPre = document.querySelectorAll('*');
                    if (!document.documentElement.dataset.xtchOrigScanned) {
                        document.documentElement.dataset.xtchOrigScanned = '1';
                        for (var pIdx = 0; pIdx < allElsPre.length; pIdx++) {
                            var pel = allElsPre[pIdx];
                            var pcs = window.getComputedStyle(pel);
                            var ptc = parseRgbaColor(pcs.color);
                            if (ptc && ptc[3] >= 0.35 && rgbLuma(ptc[0], ptc[1], ptc[2]) > 165) {
                                pel.dataset.xtchOrigLightText = '1';
                            }
                            if (pcs.backgroundImage && pcs.backgroundImage !== 'none' && pcs.backgroundImage.indexOf('url(') !== -1) {
                                pel.dataset.xtchHasBgUrl = '1';
                            }
                        }
                    }

                    var lightenDarkEnabled = ${if (lightenDark) "true" else "false"};
                    function countDirectNonSpaceChars(node) {
                        var len = 0;
                        for (var cn = node.firstChild; cn; cn = cn.nextSibling) {
                            if (cn.nodeType === 3 && cn.nodeValue) {
                                len += cn.nodeValue.replace(/\s+/g, '').length;
                            }
                        }
                        return len;
                    }
                    function hasContainerOrigLightText(el, isFullPageWrapper) {
                        if (!el) return false;
                        if (isFullPageWrapper && lightenDarkEnabled) return false;
                        var elHasLight = !!(el.dataset && el.dataset.xtchOrigLightText === '1');
                        var directOnEl = countDirectNonSpaceChars(el);
                        if (elHasLight && directOnEl > 0) return true;
                        var lightChars = elHasLight ? directOnEl : 0;
                        var totalChars = directOnEl;
                        var textEls = el.querySelectorAll('p, h1, h2, h3, h4, h5, h6, span, a, li, code, pre, div, strong, em, label, button, b, i, small, td, th, figcaption, blockquote, dt, dd');
                        for (var ti = 0; ti < textEls.length; ti++) {
                            var tel = textEls[ti];
                            if (tel.style && tel.style.display === 'none') continue;
                            var tcs = window.getComputedStyle(tel);
                            if (tcs.display === 'none' || tcs.visibility === 'hidden') continue;
                            var tbg = parseRgbaColor(tcs.backgroundColor);
                            if (tbg && tbg[3] >= 0.15) continue;
                            var hasOpaqueMidParent = false;
                            var anc = tel.parentElement;
                            while (anc && anc !== el) {
                                var abg = parseRgbaColor(window.getComputedStyle(anc).backgroundColor);
                                if (abg && abg[3] >= 0.15) {
                                    hasOpaqueMidParent = true;
                                    break;
                                }
                                anc = anc.parentElement;
                            }
                            if (hasOpaqueMidParent) continue;
                            var directLen = countDirectNonSpaceChars(tel);
                            if (directLen === 0) continue;
                            totalChars += directLen;
                            if (tel.dataset && tel.dataset.xtchOrigLightText === '1') {
                                lightChars += directLen;
                            }
                        }
                        if (totalChars === 0) return elHasLight;
                        return lightChars > totalChars * 0.5;
                    }

                    ${if (lightenDark) """
                    try {
                        if (!document.documentElement.dataset.xtchInverted) {
                            function parseOpaqueLuma(bgStr) {
                                var c = parseRgbaColor(bgStr);
                                if (!c || c[3] < 0.5) return null;
                                return rgbLuma(c[0], c[1], c[2]);
                            }
                            var bodyLuma = document.body ? parseOpaqueLuma(window.getComputedStyle(document.body).backgroundColor) : null;
                            var rootLuma = parseOpaqueLuma(window.getComputedStyle(document.documentElement).backgroundColor);
                            var effectiveLuma = (bodyLuma !== null) ? bodyLuma : rootLuma;
                            if (effectiveLuma !== null && effectiveLuma < 128) {
                                document.documentElement.dataset.xtchInverted = '1';
                                document.documentElement.style.setProperty('background', '#000000', 'important');
                                document.documentElement.style.setProperty('filter', 'invert(1) hue-rotate(180deg)', 'important');
                            }
                        }
                        if (document.documentElement.dataset.xtchInverted === '1') {
                            var mediaReinv = document.querySelectorAll('img, video, canvas');
                            for (var j = 0; j < mediaReinv.length; j++) {
                                var melInv = mediaReinv[j];
                                if (melInv.parentElement && melInv.parentElement.closest && melInv.parentElement.closest('[data-xtch-media-reinv="1"]')) {
                                    continue;
                                }
                                melInv.dataset.xtchMediaReinv = '1';
                                melInv.style.setProperty('filter', 'invert(1) hue-rotate(180deg)', 'important');
                            }
                        }
                    } catch (e2) {}
                    """ else """
                    try {
                        var bodyCNoInv = document.body ? parseRgbaColor(window.getComputedStyle(document.body).backgroundColor) : null;
                        var rootCNoInv = parseRgbaColor(window.getComputedStyle(document.documentElement).backgroundColor);
                        var effPageBgNoInv = (bodyCNoInv && bodyCNoInv[3] >= 0.5) ? bodyCNoInv : rootCNoInv;
                        if (effPageBgNoInv && effPageBgNoInv[3] >= 0.5 && rgbLuma(effPageBgNoInv[0], effPageBgNoInv[1], effPageBgNoInv[2]) < 128) {
                            if (document.body && hasContainerOrigLightText(document.body, true)) {
                                if (rgbLuma(effPageBgNoInv[0], effPageBgNoInv[1], effPageBgNoInv[2]) < 110) {
                                    document.body.style.setProperty('background-color', '#000000', 'important');
                                }
                                document.body.classList.add('__xtch_dark_bg');
                            }
                        }
                    } catch (e2) {}
                    """}

                    var isInverted = document.documentElement.dataset.xtchInverted === '1';
                    var textHex = isInverted ? '#ffffff' : '#000000';
                    var darkBgTextHex = isInverted ? '#000000' : '#ffffff';
                    var lightBgTextHex = isInverted ? '#ffffff' : '#000000';
                    var textStyle = document.getElementById('__xtch_text_style');
                    if (!textStyle) {
                        textStyle = document.createElement('style');
                        textStyle.id = '__xtch_text_style';
                        (document.head || document.documentElement).appendChild(textStyle);
                    }
                    textStyle.textContent =
                        'body, p, li, dt, dd, blockquote, figcaption, td, th, article, section, main, ' +
                        'h1, h2, h3, h4, h5, h6, span, a, strong, em, b, i, small, time, label, ' +
                        'code, pre, cite, q, mark, summary { ' +
                        'color: ' + textHex + ' !important; -webkit-text-fill-color: ' + textHex + ' !important; ' +
                        'text-shadow: none !important; opacity: 1 !important; ' +
                        'mask-image: none !important; -webkit-mask-image: none !important; } ' +
                        'div, button, address, caption, legend, del, ins, sub, sup, abbr, dfn, kbd, samp, var { ' +
                        'color: ' + textHex + ' !important; -webkit-text-fill-color: ' + textHex + ' !important; ' +
                        'text-shadow: none !important; } ' +
                        '.__xtch_dark_bg, .__xtch_dark_bg * { ' +
                        'color: ' + darkBgTextHex + ' !important; -webkit-text-fill-color: ' + darkBgTextHex + ' !important; text-shadow: none !important; } ' +
                        '.__xtch_light_bg, .__xtch_light_bg *, .__xtch_dark_bg .__xtch_light_bg, .__xtch_dark_bg .__xtch_light_bg * { ' +
                        'color: ' + lightBgTextHex + ' !important; -webkit-text-fill-color: ' + lightBgTextHex + ' !important; text-shadow: none !important; } ' +
                        '.__xtch_light_bg .__xtch_dark_bg, .__xtch_light_bg .__xtch_dark_bg *, ' +
                        '.__xtch_dark_bg .__xtch_light_bg .__xtch_dark_bg, .__xtch_dark_bg .__xtch_light_bg .__xtch_dark_bg * { ' +
                        'color: ' + darkBgTextHex + ' !important; -webkit-text-fill-color: ' + darkBgTextHex + ' !important; text-shadow: none !important; }';

                    if (document.body) { void document.body.offsetHeight; }

                    var defaultPageRgb = isInverted ? [0, 0, 0] : [255, 255, 255];
                    function findNearestOpaqueRgb(node) {
                        var cur = node ? node.parentElement : null;
                        while (cur && cur !== document.documentElement) {
                            var c = parseRgbaColor(window.getComputedStyle(cur).backgroundColor);
                            if (c && c[3] >= 0.85) return [c[0], c[1], c[2]];
                            cur = cur.parentElement;
                        }
                        return defaultPageRgb;
                    }

                    var bannerRe = /(cookie|consent|onetrust|didomi|gdpr|cmp-|CybotCookiebot|privacy-banner|modal-backdrop|paywall-overlay|interstitial|sticky-nav|floating-nav|smartbanner|app-banner|install-banner|donate-banner|frb-nag|mw-dismissable-notice|site-notice|app-promo)/i;
                    var adSlotRe = /(^|[-_\s])(ad-slot|adslot|advert|leaderboard|dfp-ad|taboola|outbrain|ad-banner)([-_\s]|$)/i;
                    var appPromptBtnRe = /(installieren|install app|open in app|get the app|download app)/i;
                    var appTextRe = /(has an app|mobile app|our free app|wikipedia-app|app gibt)/i;
                    var allEls = document.querySelectorAll('*');
                    for (var i = 0; i < allEls.length; i++) {
                        var el = allEls[i];
                        var tag = (el.tagName || '').toLowerCase();
                        if (tag === 'html' || tag === 'body') continue;
                        var cs = window.getComputedStyle(el);
                        var idCls = (el.id || '') + ' ' + (typeof el.className === 'string' ? el.className : '');
                        var textLen = (el.innerText || '').trim().length;
                        if (cs.position === 'fixed') {
                            var fRect = el.getBoundingClientRect();
                            if (fRect.height < pageH * 0.85) {
                                // Hide floating fixed overlays/navbars so they never jump into Page 1
                                el.style.setProperty('display', 'none', 'important');
                            } else {
                                el.style.setProperty('position', 'relative', 'important');
                            }
                        } else if (cs.position === 'sticky') {
                            var sRect = el.getBoundingClientRect();
                            if (bannerRe.test(idCls) && sRect.height < pageH * 0.85 && textLen < 600) {
                                el.style.setProperty('display', 'none', 'important');
                            } else {
                                // Un-stick without deleting tall sticky content sections or wrappers
                                el.style.setProperty('position', 'relative', 'important');
                                el.style.setProperty('top', 'auto', 'important');
                                el.style.setProperty('bottom', 'auto', 'important');
                            }
                        } else if (bannerRe.test(idCls) && textLen < 600 && !el.querySelector('main, article, h1')) {
                            var bRect = el.getBoundingClientRect();
                            if (bRect.height < pageH * 0.85 && (cs.position === 'absolute' || (cs.zIndex && parseInt(cs.zIndex, 10) > 100) || (!el.closest('article') && bRect.top < pageH * 0.7))) {
                                el.style.setProperty('display', 'none', 'important');
                            }
                        } else if (adSlotRe.test(idCls) && textLen < 40 && !el.querySelector('main, article')) {
                            el.style.setProperty('display', 'none', 'important');
                        } else if ((tag === 'div' || tag === 'section' || tag === 'aside') && textLen > 0 && textLen < 520 && !el.closest('main, article') && !el.querySelector('main, article, h1')) {
                            var pRect = el.getBoundingClientRect();
                            var pTxt = el.innerText || '';
                            if (pRect.height > 24 && pRect.height < pageH * 0.85 && pRect.top >= -10 && pRect.top < 320 && el.querySelector('button, [role="button"], a') && (appPromptBtnRe.test(pTxt) || (appTextRe.test(pTxt) && /\b(install|laden)\b/i.test(pTxt)))) {
                                el.style.setProperty('display', 'none', 'important');
                            }
                        }
                        if ((tag === 'header' || tag === 'nav') && (cs.position === 'static' || cs.position === 'relative') && el.style.display !== 'none' && cs.display !== 'none') {
                            el.style.setProperty('position', cs.position, 'important');
                        }

                        if (el.style.display !== 'none' && cs.display !== 'none' && cs.visibility !== 'hidden' &&
                            tag !== 'img' && tag !== 'svg' && tag !== 'picture' && tag !== 'canvas' && tag !== 'video') {
                            if (el.dataset.xtchHasBgUrl === '1') {
                                if (!isInverted) {
                                    var bgUrlBoxR = el.getBoundingClientRect();
                                    var bgUrlFullPage = bgUrlBoxR.width >= pageW * 0.95 && bgUrlBoxR.height >= pageH * 1.5;
                                    if (hasContainerOrigLightText(el, bgUrlFullPage)) {
                                        el.classList.add('__xtch_dark_bg');
                                        el.classList.remove('__xtch_light_bg');
                                    }
                                }
                                continue;
                            }

                            var gradLuma = parseGradientLuma(cs.backgroundImage);
                            var bgParsed = parseRgbaColor(cs.backgroundColor);
                            var effBgLuma = null;
                            var effIsGradient = false;
                            if (gradLuma !== null) {
                                effBgLuma = gradLuma;
                                effIsGradient = true;
                            } else if (bgParsed && bgParsed[3] >= 0.15) {
                                if (bgParsed[3] >= 0.92) {
                                    effBgLuma = rgbLuma(bgParsed[0], bgParsed[1], bgParsed[2]);
                                } else {
                                    var parentRgb = findNearestOpaqueRgb(el);
                                    var a = bgParsed[3];
                                    effBgLuma = rgbLuma(
                                        bgParsed[0] * a + parentRgb[0] * (1 - a),
                                        bgParsed[1] * a + parentRgb[1] * (1 - a),
                                        bgParsed[2] * a + parentRgb[2] * (1 - a)
                                    );
                                }
                            }

                            if (effBgLuma !== null) {
                                var boxR = el.getBoundingClientRect();
                                if (textLen === 0 && (boxR.width <= 4 || boxR.height <= 4)) continue;
                                var isBoxTag = (tag === 'pre' || tag === 'code' || tag === 'kbd' || tag === 'blockquote' ||
                                    tag === 'th' || tag === 'mark' || tag === 'aside' || tag === 'details' || tag === 'fieldset');
                                var isFullPageWrapper = boxR.width >= pageW * 0.95 && boxR.height >= pageH * 1.5;
                                var inShadedAncestor = !!(el.parentElement && el.parentElement.closest && el.parentElement.closest('.__xtch_shaded_box'));
                                var hasDarkBorder = (function() {
                                    var bwTop = parseFloat(cs.borderTopWidth) || 0;
                                    var bwBot = parseFloat(cs.borderBottomWidth) || 0;
                                    var bwLeft = parseFloat(cs.borderLeftWidth) || 0;
                                    var bwRight = parseFloat(cs.borderRightWidth) || 0;
                                    if (bwTop < 1 || bwBot < 1 || bwLeft < 1 || bwRight < 1) return false;
                                    var bc = parseRgbaColor(cs.borderTopColor);
                                    return bc && bc[3] >= 0.4 && rgbLuma(bc[0], bc[1], bc[2]) < 200;
                                })();
                                var isCompactBadgeOrButton = (
                                    tag === 'button' || tag === 'a' || tag === 'span' || tag === 'label' || tag === 'mark' ||
                                    (el.getAttribute && el.getAttribute('role') === 'button')
                                ) && boxR.width >= 12 && boxR.height >= 12 && boxR.height <= 64 && boxR.width <= pageW * 0.80 &&
                                    textLen > 0 && textLen <= 80 &&
                                    !el.querySelector('p, h1, h2, h3, h4, h5, h6, article, section, ul, ol, table, blockquote, pre');

                                var inDarkAncestor = !!(el.parentElement && el.parentElement.closest && el.parentElement.closest('.__xtch_dark_bg'));
                                var origLightText = hasContainerOrigLightText(el, isFullPageWrapper);

                                if (!isInverted) {
                                    var hasLightText = origLightText || el.classList.contains('__xtch_dark_bg');
                                    var wantsBlackBg =
                                        ((!isFullPageWrapper || !lightenDarkEnabled) && effBgLuma < 110 && hasLightText) ||
                                        (isCompactBadgeOrButton && ((effBgLuma < 85) || (effBgLuma < 135 && hasLightText)));
                                    if (wantsBlackBg) {
                                        // Black/very-dark containers with original light text and compact dark buttons/badges render as pure black with white text
                                        el.style.setProperty('background-color', '#000000', 'important');
                                        if (effIsGradient) el.style.setProperty('background-image', 'none', 'important');
                                        el.classList.add('__xtch_dark_bg');
                                        el.classList.remove('__xtch_light_bg');
                                        el.classList.remove('__xtch_shaded_box');
                                    } else if (inShadedAncestor && effBgLuma > 236 && !hasDarkBorder) {
                                        // Strip accidental white/near-white inner boxes behind text inside a shaded container
                                        el.classList.remove('__xtch_dark_bg');
                                        el.classList.remove('__xtch_shaded_box');
                                        if (inDarkAncestor) el.classList.add('__xtch_light_bg');
                                        el.style.setProperty('background-color', 'transparent', 'important');
                                        if (effIsGradient) el.style.setProperty('background-image', 'none', 'important');
                                    } else if (effBgLuma < 180) {
                                        // Dark/medium containers -> promote to uniform 3rd E-Ink gray (#aaaaaa) with black text
                                        el.classList.remove('__xtch_dark_bg');
                                        if (inDarkAncestor) el.classList.add('__xtch_light_bg');
                                        var safeBg = isFullPageWrapper ? '#ffffff' : '#aaaaaa';
                                        el.style.setProperty('background-color', safeBg, 'important');
                                        if (effIsGradient) el.style.setProperty('background-image', 'none', 'important');
                                        if (!isFullPageWrapper) el.classList.add('__xtch_shaded_box');
                                    } else if (effBgLuma <= 243) {
                                        el.classList.remove('__xtch_dark_bg');
                                        if (inDarkAncestor) el.classList.add('__xtch_light_bg');
                                        if ((isBoxTag || !isFullPageWrapper) && boxR.width >= 16 && boxR.height >= 12 && (isBoxTag || effBgLuma <= 236)) {
                                            el.style.setProperty('background-color', '#aaaaaa', 'important');
                                            if (effIsGradient) el.style.setProperty('background-image', 'none', 'important');
                                            el.classList.add('__xtch_shaded_box');
                                        } else if (inShadedAncestor && !hasDarkBorder) {
                                            el.style.setProperty('background-color', 'transparent', 'important');
                                            if (effIsGradient) el.style.setProperty('background-image', 'none', 'important');
                                        } else if (effIsGradient) {
                                            el.style.setProperty('background-image', 'none', 'important');
                                        }
                                    } else {
                                        el.classList.remove('__xtch_dark_bg');
                                        if (inDarkAncestor) el.classList.add('__xtch_light_bg');
                                        if (effIsGradient) {
                                            el.style.setProperty('background-image', 'none', 'important');
                                        }
                                    }
                                } else {
                                    // Page is inverted via filter: invert(1); final rendered luma is (255 - effBgLuma)
                                    var renderedLuma = 255 - effBgLuma;
                                    var wantsInvertedBlackBg =
                                        (!isFullPageWrapper && renderedLuma < 110 && !origLightText) ||
                                        (isCompactBadgeOrButton && renderedLuma < 85 && !origLightText);
                                    if (wantsInvertedBlackBg) {
                                        // Originally bright container or compact light button with dark text -> pre-invert white (#ffffff) renders as #000000 with #ffffff text
                                        el.style.setProperty('background-color', '#ffffff', 'important');
                                        if (effIsGradient) el.style.setProperty('background-image', 'none', 'important');
                                        el.classList.add('__xtch_dark_bg');
                                        el.classList.remove('__xtch_light_bg');
                                        el.classList.remove('__xtch_shaded_box');
                                    } else if (inShadedAncestor && renderedLuma > 236 && !hasDarkBorder) {
                                        // Strip white-rendering inner boxes behind text inside a shaded dark-mode container
                                        el.classList.remove('__xtch_dark_bg');
                                        el.classList.remove('__xtch_shaded_box');
                                        el.classList.add('__xtch_light_bg');
                                        el.style.setProperty('background-color', 'transparent', 'important');
                                        if (effIsGradient) el.style.setProperty('background-image', 'none', 'important');
                                    } else if (renderedLuma < 180 && !isFullPageWrapper) {
                                        // Too dark for black text after invert -> #555555 pre-invert renders as uniform #aaaaaa (3rd gray)
                                        el.style.setProperty('background-color', '#555555', 'important');
                                        if (effIsGradient) el.style.setProperty('background-image', 'none', 'important');
                                        el.classList.remove('__xtch_dark_bg');
                                        el.classList.add('__xtch_light_bg');
                                        el.classList.add('__xtch_shaded_box');
                                    } else {
                                        el.classList.remove('__xtch_dark_bg');
                                        if (inDarkAncestor) el.classList.add('__xtch_light_bg');
                                        if (effIsGradient) {
                                            el.style.setProperty('background-image', 'none', 'important');
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (err) {}
            })();
        """.trimIndent()
    }

    internal fun buildPrepareAndMeasureJs(fallbackCssW: Int, fallbackCssH: Int): String {
        return """
            (function() {
                try {
                    var rawViewportW = window.innerWidth || 0;
                    var rawViewportH = window.innerHeight || 0;
                    var pageW = Math.floor(document.documentElement.clientWidth || rawViewportW || $fallbackCssW);
                    if (pageW < 100) pageW = $fallbackCssW;
                    var pageH = (rawViewportH >= 150 && rawViewportH <= ($fallbackCssH * 1.5)) ? Math.floor(rawViewportH) : $fallbackCssH;
                    var dpr = Number(window.devicePixelRatio || 1);
                    var vvScale = Number((window.visualViewport && window.visualViewport.scale) || 1);
                    if (document.body) { void document.body.offsetHeight; }

                    var leadImg = '';
                    try {
                        var resolveAbs = function(rawUrl) {
                            if (!rawUrl) return '';
                            try {
                                var resolved = new URL(rawUrl.trim(), window.location.href).href;
                                return (resolved.indexOf('https://') === 0 || resolved.indexOf('http://') === 0) ? resolved : '';
                            } catch (eUrl) {
                                return '';
                            }
                        };
                        var ogMeta = document.querySelector('meta[property="og:image:secure_url"], meta[property="og:image"], meta[name="twitter:image"], meta[property="twitter:image"]');
                        if (ogMeta && ogMeta.content) {
                            leadImg = resolveAbs(ogMeta.content);
                        }
                        if (!leadImg) {
                            var bestArea = 0;
                            var docImgs = document.querySelectorAll('article figure img, main figure img, article img, main img, figure img, img');
                            for (var im = 0; im < docImgs.length; im++) {
                                var ir = docImgs[im].getBoundingClientRect();
                                var src = resolveAbs(docImgs[im].currentSrc || docImgs[im].getAttribute('src') || docImgs[im].src || '');
                                if (src && ir.width >= 200 && ir.height >= 120) {
                                    var area = ir.width * ir.height;
                                    if (area > bestArea) {
                                        bestArea = area;
                                        leadImg = src;
                                    }
                                }
                            }
                        }
                    } catch (eImg) {}

                    var rawPubDate = '';
                    try {
                        var dateSelectors = [
                            'meta[property="article:published_time"]',
                            'meta[name="article:published_time"]',
                            'meta[property="og:published_time"]',
                            'meta[name="publication_date"]',
                            'meta[name="date"]',
                            'meta[name="pubdate"]',
                            'meta[name="sailthru.date"]',
                            'meta[name="parsely-pub-date"]',
                            'meta[name="DC.date.issued"]'
                        ];
                        for (var ds = 0; ds < dateSelectors.length && !rawPubDate; ds++) {
                            var dm = document.querySelector(dateSelectors[ds]);
                            if (dm && dm.content && dm.content.trim()) {
                                rawPubDate = dm.content.trim();
                            }
                        }
                        if (!rawPubDate) {
                            var tEl = document.querySelector('time[datetime]');
                            if (tEl && tEl.getAttribute('datetime')) {
                                rawPubDate = tEl.getAttribute('datetime').trim();
                            }
                        }
                        if (!rawPubDate) {
                            var tPlain = document.querySelector('time');
                            if (tPlain && tPlain.textContent && tPlain.textContent.trim()) {
                                rawPubDate = tPlain.textContent.trim();
                            }
                        }
                        if (!rawPubDate) {
                            var ldScripts = document.querySelectorAll('script[type="application/ld+json"]');
                            for (var li = 0; li < ldScripts.length && !rawPubDate; li++) {
                                var ldText = ldScripts[li].textContent || '';
                                var mPub = ldText.match(/"datePublished"\s*:\s*"([^"]+)"/) || ldText.match(/"dateCreated"\s*:\s*"([^"]+)"/);
                                if (mPub && mPub[1]) {
                                    rawPubDate = mPub[1].trim();
                                }
                            }
                        }
                    } catch (eDate) {}

                    var existingSpacer = document.getElementById('__xtch_bottom_spacer');
                    if (existingSpacer && existingSpacer.parentNode) existingSpacer.parentNode.removeChild(existingSpacer);

                    var rawDocH = Math.max(
                        document.documentElement.scrollHeight || 0,
                        document.body ? document.body.scrollHeight : 0,
                        pageH
                    );
                    var sy = window.scrollY || window.pageYOffset || 0;
                    var boxes = [];
                    var firstContentTop = 999999;
                    var maxAtomicH = Math.floor(pageH * 0.90);
                    var maxMediaAtomicH = Math.max(40, pageH - 8);

                    function parseRgba(str) {
                        if (!str || str === 'transparent') return null;
                        var m = str.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)/);
                        if (!m) return null;
                        var a = (m[4] !== undefined) ? parseFloat(m[4]) : 1.0;
                        if (isNaN(a)) a = 1.0;
                        return [parseInt(m[1], 10), parseInt(m[2], 10), parseInt(m[3], 10), a];
                    }

                    function getVisibleClipRect(el, rect, elCs) {
                        if (!el || !rect) return null;
                        var top = rect.top;
                        var bottom = rect.bottom;
                        var left = rect.left;
                        var right = rect.right;
                        if (right <= 0 || left >= pageW || (bottom - top) < 2 || (right - left) < 2) return null;
                        var cur = el;
                        while (cur && cur !== document.documentElement) {
                            var ccs = (cur === el && elCs) ? elCs : window.getComputedStyle(cur);
                            if (ccs.display === 'none' || ccs.visibility === 'hidden' || ccs.contentVisibility === 'hidden') return null;
                            var op = parseFloat(ccs.opacity || '1');
                            if (!isNaN(op) && op <= 0.05) return null;
                            if (cur.hasAttribute && (cur.hasAttribute('inert') || cur.hasAttribute('hidden'))) {
                                return null;
                            }
                            if (cur !== el && cur !== document.body && ccs.display !== 'contents') {
                                var ov = ccs.overflow || '';
                                var ovY = ccs.overflowY || '';
                                var ovX = ccs.overflowX || '';
                                var clipY = (ovY === 'hidden' || ovY === 'clip' || ov === 'hidden' || ov === 'clip');
                                var clipX = (ovX === 'hidden' || ovX === 'clip' || ov === 'hidden' || ov === 'clip');
                                if (clipY || clipX) {
                                    var ar = cur.getBoundingClientRect();
                                    if (clipY) {
                                        if (ar.height < 2 || bottom <= ar.top + 1 || top >= ar.bottom - 1) return null;
                                        if (ar.top > top) top = ar.top;
                                        if (ar.bottom < bottom) bottom = ar.bottom;
                                    }
                                    if (clipX) {
                                        if (ar.width < 2 || right <= ar.left + 1 || left >= ar.right - 1) return null;
                                        if (ar.left > left) left = ar.left;
                                        if (ar.right < right) right = ar.right;
                                    }
                                    if (bottom - top < 2 || right - left < 2) return null;
                                }
                            }
                            cur = cur.parentElement;
                        }
                        return { top: top, bottom: bottom, left: left, right: right, width: right - left, height: bottom - top };
                    }

                    function isTopSiteChrome(el) {
                        var cur = el;
                        while (cur && cur !== document.body && cur !== document.documentElement) {
                            var tag = (cur.tagName || '').toLowerCase();
                            var role = (cur.getAttribute && cur.getAttribute('role')) || '';
                            if (tag === 'nav' || role === 'navigation' || role === 'banner') {
                                var rNav = cur.getBoundingClientRect();
                                if (rNav.height <= pageH * 0.45 && (rNav.top + sy) < 260) return true;
                            }
                            if (tag === 'header') {
                                var rHdr = cur.getBoundingClientRect();
                                var hdrH1 = cur.querySelector('h1');
                                var hasArticleH1 = hdrH1 && (hdrH1.innerText || '').trim().length >= 24 && !cur.querySelector('nav');
                                if (!hasArticleH1 && rHdr.height <= pageH * 0.45 && (rHdr.top + sy) < 260) return true;
                            }
                            cur = cur.parentElement;
                        }
                        return false;
                    }

                    var baseBg = (document.body ? parseRgba(window.getComputedStyle(document.body).backgroundColor) : null)
                        || parseRgba(window.getComputedStyle(document.documentElement).backgroundColor)
                        || [255, 255, 255, 1];
                    if (baseBg[3] < 0.1) baseBg = [255, 255, 255, 1];

                    // 1) Collect individual visible text line rects (kind = 0) so no line of text is ever sliced
                    function getTextClipBounds(el, elCs) {
                        if (!el) return null;
                        var top = -1e9;
                        var bottom = 1e9;
                        var left = -1e9;
                        var right = 1e9;
                        var cur = el;
                        while (cur && cur !== document.documentElement) {
                            var ccs = (cur === el && elCs) ? elCs : window.getComputedStyle(cur);
                            if (ccs.display === 'none' || ccs.visibility === 'hidden' || ccs.contentVisibility === 'hidden') return null;
                            var op = parseFloat(ccs.opacity || '1');
                            if (!isNaN(op) && op <= 0.05) return null;
                            if (cur.hasAttribute && (cur.hasAttribute('inert') || cur.hasAttribute('hidden'))) {
                                return null;
                            }
                            if (cur !== document.body && ccs.display !== 'contents') {
                                var ov = ccs.overflow || '';
                                var ovY = ccs.overflowY || '';
                                var ovX = ccs.overflowX || '';
                                var clipY = (ovY === 'hidden' || ovY === 'clip' || ovY === 'scroll' || ovY === 'auto' || ov === 'hidden' || ov === 'clip' || ov === 'scroll' || ov === 'auto');
                                var clipX = (ovX === 'hidden' || ovX === 'clip' || ovX === 'scroll' || ovX === 'auto' || ov === 'hidden' || ov === 'clip' || ov === 'scroll' || ov === 'auto');
                                if (clipY || clipX) {
                                    var ar = cur.getBoundingClientRect();
                                    if (clipY) {
                                        if (ar.height < 2) return null;
                                        if (ar.top > top) top = ar.top;
                                        if (ar.bottom < bottom) bottom = ar.bottom;
                                        if (bottom - top < 2) return null;
                                    }
                                    if (clipX) {
                                        if (ar.width < 2) return null;
                                        if (ar.left > left) left = ar.left;
                                        if (ar.right < right) right = ar.right;
                                        if (right - left < 2) return null;
                                    }
                                }
                            }
                            cur = cur.parentElement || (cur.parentNode && cur.parentNode.nodeType === 11 ? cur.parentNode.host : null);
                        }
                        return { top: top, bottom: bottom, left: left, right: right };
                    }
                    function collectTextFromRoot(root, depth) {
                        if (!root || depth > 4) return;
                        var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
                        var node;
                        var range = document.createRange();
                        while ((node = walker.nextNode())) {
                            if (!node.nodeValue || !node.nodeValue.trim()) continue;
                            var parent = node.parentElement;
                            if (!parent) continue;
                            var pcs = window.getComputedStyle(parent);
                            var clipBounds = getTextClipBounds(parent, pcs);
                            if (!clipBounds) continue;
                            var inTopChrome = isTopSiteChrome(parent);
                            var fontPx = parseFloat(pcs.fontSize) || 16;
                            var padTop = Math.min(3, Math.max(1, Math.ceil(fontPx * 0.06)));
                            var hasUnderline = !!(pcs.textDecorationLine && pcs.textDecorationLine.indexOf('underline') !== -1);
                            var borderBot = parseFloat(pcs.borderBottomWidth) || 0;
                            var padBot = Math.min(4, Math.max(1, Math.ceil(fontPx * 0.08)) + (hasUnderline ? 1 : 0) + (borderBot > 0 && borderBot <= 3 ? Math.ceil(borderBot) : 0));
                            range.selectNodeContents(node);
                            var rects = range.getClientRects();
                            for (var r = 0; r < rects.length; r++) {
                                var rect = rects[r];
                                if (rect.height < 2 || rect.width < 1) continue;
                                var clipLeft = Math.max(0, clipBounds.left);
                                var clipRight = Math.min(pageW, clipBounds.right);
                                if (Math.min(rect.right, clipRight) - Math.max(rect.left, clipLeft) < 1) continue;
                                var ctTop = Math.max(rect.top - padTop, clipBounds.top);
                                var ctBot = Math.min(rect.bottom + padBot, clipBounds.bottom);
                                if (ctBot - ctTop < 2) continue;
                                var t = Math.max(0, Math.floor(ctTop + sy));
                                var b = Math.min(rawDocH, Math.ceil(ctBot + sy));
                                if (t >= rawDocH) continue;
                                var rawTop = Math.max(0, Math.floor(Math.max(rect.top, clipBounds.top) + sy));
                                if (!inTopChrome && rawTop >= 0 && rawTop < firstContentTop) firstContentTop = rawTop;
                                if (b > t && (b - t) <= Math.floor(pageH * 0.45)) {
                                    boxes.push([t, b, 0]);
                                }
                            }
                        }
                        var shadowHosts = root.querySelectorAll ? root.querySelectorAll('*') : [];
                        for (var si = 0; si < shadowHosts.length; si++) {
                            if (shadowHosts[si].shadowRoot) {
                                collectTextFromRoot(shadowHosts[si].shadowRoot, depth + 1);
                            }
                        }
                    }
                    if (document.body) {
                        collectTextFromRoot(document.body, 0);
                    }

                    // 2) Collect media, tables, code/quotes & styled visual boxes ("Kästen") (kind = 1)
                    var mediaSel = 'img, picture, svg, canvas, video, figure, pre, blockquote, table, tr, hr, aside, details, fieldset, form';
                    var media = document.querySelectorAll(mediaSel);
                    for (var k = 0; k < media.length; k++) {
                        var mel = media[k];
                        var mcs = window.getComputedStyle(mel);
                        var mr = getVisibleClipRect(mel, mel.getBoundingClientRect(), mcs);
                        if (!mr) continue;
                        var mt = Math.max(0, Math.floor(mr.top + sy));
                        var mb = Math.min(rawDocH, Math.ceil(mr.bottom + sy));
                        if (mt >= rawDocH || mb <= mt) continue;
                        if (!isTopSiteChrome(mel) && mr.width >= 24 && mr.height >= 24 && mt >= 0 && mt < firstContentTop) {
                            firstContentTop = mt;
                        }
                        var mLimitH = /^(img|picture|svg|canvas|video|figure)$/i.test(mel.tagName || '') ? maxMediaAtomicH : maxAtomicH;
                        if ((mb - mt) <= mLimitH) {
                            boxes.push([mt, mb, 1]);
                            // If a standalone image sits inside a tight wrapper with caption/credit, keep both intact
                            if (mel.tagName && mel.tagName.toLowerCase() === 'img' && mel.parentElement) {
                                var wrap = mel.parentElement;
                                var wTag = (wrap.tagName || '').toLowerCase();
                                if (wTag !== 'body' && wTag !== 'html' && wTag !== 'main' && wTag !== 'article') {
                                    var wcs = window.getComputedStyle(wrap);
                                    var wr = getVisibleClipRect(wrap, wrap.getBoundingClientRect(), wcs);
                                    if (wr && (wrap.innerText || '').trim().length <= 240) {
                                        var wt = Math.max(0, Math.floor(wr.top + sy));
                                        var wb = Math.min(rawDocH, Math.ceil(wr.bottom + sy));
                                        if (wb > wt && (wb - wt) <= maxMediaAtomicH) {
                                            boxes.push([wt, wb, 1]);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2b) Keep CSS background-image photo containers intact across page breaks
                    var bgPhotoEls = document.querySelectorAll('[data-xtch-has-bg-url="1"]');
                    for (var bpi = 0; bpi < bgPhotoEls.length; bpi++) {
                        var bpel = bgPhotoEls[bpi];
                        var bpcs = window.getComputedStyle(bpel);
                        var bpr = getVisibleClipRect(bpel, bpel.getBoundingClientRect(), bpcs);
                        if (!bpr || bpr.width < 24 || bpr.height < 24) continue;
                        var bpt = Math.max(0, Math.floor(bpr.top + sy));
                        var bpb = Math.min(rawDocH, Math.ceil(bpr.bottom + sy));
                        if (bpb > bpt && (bpb - bpt) <= maxMediaAtomicH) {
                            boxes.push([bpt, bpb, 1]);
                        }
                    }

                    // 3) Detect CSS-framed boxes, cards, callouts & shaded containers universally across websites
                    var maxBoxKeepH = Math.floor(pageH * 0.65);
                    var boxCandidates = document.querySelectorAll('div, section, article, ul, ol, dl, li, a, p');
                    for (var cIdx = 0; cIdx < boxCandidates.length; cIdx++) {
                        var cel = boxCandidates[cIdx];
                        var rawCr = cel.getBoundingClientRect();
                        if (rawCr.width < Math.min(120, pageW * 0.35) || rawCr.height < 28 || rawCr.height > maxBoxKeepH) continue;
                        var ccs = window.getComputedStyle(cel);
                        if (ccs.display === 'inline') continue;
                        var cr = getVisibleClipRect(cel, rawCr, ccs);
                        if (!cr || cr.width < Math.min(120, pageW * 0.35) || cr.height < 28 || cr.height > maxBoxKeepH) continue;

                        var bg = parseRgba(ccs.backgroundColor);
                        var hasDistinctBg = bg && bg[3] >= 0.15 &&
                            (Math.abs(bg[0] - baseBg[0]) + Math.abs(bg[1] - baseBg[1]) + Math.abs(bg[2] - baseBg[2])) >= 18;

                        var bt = parseRgba(ccs.borderTopColor);
                        var bb = parseRgba(ccs.borderBottomColor);
                        var bl = parseRgba(ccs.borderLeftColor);
                        var br = parseRgba(ccs.borderRightColor);
                        var hasTopBorder = (parseFloat(ccs.borderTopWidth) || 0) >= 0.8 && bt && bt[3] >= 0.12;
                        var hasBottomBorder = (parseFloat(ccs.borderBottomWidth) || 0) >= 0.8 && bb && bb[3] >= 0.12;
                        var hasLeftBorder = (parseFloat(ccs.borderLeftWidth) || 0) >= 1.5 && bl && bl[3] >= 0.12;
                        var hasRightBorder = (parseFloat(ccs.borderRightWidth) || 0) >= 0.8 && br && br[3] >= 0.12;
                        var hasBoxBorder = (hasTopBorder && hasBottomBorder) || (hasLeftBorder && cr.height <= maxBoxKeepH) ||
                            (hasTopBorder && hasLeftBorder && hasRightBorder);

                        var hasShadow = ccs.boxShadow && ccs.boxShadow !== 'none' && ((parseFloat(ccs.paddingTop) || 0) + (parseFloat(ccs.paddingBottom) || 0) >= 8);

                        if (hasDistinctBg || hasBoxBorder || hasShadow) {
                            var ct = Math.max(0, Math.floor(cr.top + sy));
                            var cb = Math.min(rawDocH, Math.ceil(cr.bottom + sy));
                            if (cb > ct) {
                                boxes.push([ct, cb, 1]);
                            }
                        }
                    }

                    // 4) Keep standalone headings cohesive with their immediate following visible content
                    var bodyFs = document.body ? (parseFloat(window.getComputedStyle(document.body).fontSize) || 16) : 16;
                    function isVisualHeading(el, cs, r) {
                        var tag = (el.tagName || '').toLowerCase();
                        if (/^h[1-6]$/.test(tag) || el.getAttribute('role') === 'heading') return true;
                        if (cs.display === 'inline') return false;
                        if (r.height < 12 || r.height > 84 || r.width < 40) return false;
                        if (el.closest('h1, h2, h3, h4, h5, h6, [role="heading"], a, li, figcaption, td, th, button')) return false;
                        var txt = (el.innerText || '').replace(/\s+/g, ' ').trim();
                        if (txt.length < 2 || txt.length > 68) return false;
                        if (/[.,;!?]$/.test(txt)) return false;
                        var fw = parseInt(cs.fontWeight, 10) || 400;
                        var fs = parseFloat(cs.fontSize) || bodyFs;
                        var isBold = fw >= 600 || cs.fontWeight === 'bold' || cs.fontWeight === 'bolder';
                        var isLarger = fs >= bodyFs * 1.12 || fs >= 17;
                        if (tag === 'dt' || tag === 'summary') return true;
                        if (!isBold && !isLarger) return false;
                        if (el.children && el.children.length > 2) return false;
                        return true;
                    }
                    function findNextVisibleBlock(hel, hr, htText) {
                        var probe = hel;
                        for (var up = 0; up < 3 && probe && probe !== document.body; up++) {
                            var nxt = probe.nextElementSibling;
                            for (var sk = 0; sk < 5 && nxt; sk++) {
                                var ncs = window.getComputedStyle(nxt);
                                var nr = getVisibleClipRect(nxt, nxt.getBoundingClientRect(), ncs);
                                if (nr && nr.top >= hr.bottom - 4) {
                                    return { el: nxt, rect: nr };
                                }
                                nxt = nxt.nextElementSibling;
                            }
                            var par = probe.parentElement;
                            if (!par || par === document.body) break;
                            var pr = par.getBoundingClientRect();
                            var pTxt = (par.innerText || '').replace(/\s+/g, ' ').trim();
                            if (pTxt === htText || Math.abs(pr.bottom - hr.bottom) <= 16) {
                                probe = par;
                            } else {
                                break;
                            }
                        }
                        return null;
                    }

                    var headingCands = document.querySelectorAll('h1, h2, h3, h4, h5, h6, [role="heading"], p, div, span, dt, summary');
                    for (var hIdx = 0; hIdx < headingCands.length; hIdx++) {
                        var hel = headingCands[hIdx];
                        var hcs = window.getComputedStyle(hel);
                        var rawHr = hel.getBoundingClientRect();
                        if (rawHr.height < 12 || rawHr.height > Math.floor(pageH * 0.40)) continue;
                        var hr = getVisibleClipRect(hel, rawHr, hcs);
                        if (!hr) continue;
                        if (!isVisualHeading(hel, hcs, hr)) continue;
                        var ht = Math.max(0, Math.floor(hr.top + sy));
                        var hb = Math.min(rawDocH, Math.ceil(hr.bottom + sy));
                        if (hb > ht && (hb - ht) <= Math.floor(pageH * 0.40)) {
                            boxes.push([ht, hb, 1]);
                            var cardParent = hel.closest('a, li, figure, figcaption');
                            if (!cardParent) {
                                var hText = (hel.innerText || '').replace(/\s+/g, ' ').trim();
                                var nextVis = findNextVisibleBlock(hel, hr, hText);
                                if (nextVis) {
                                    var nr = nextVis.rect;
                                    var gap = nr.top - hr.bottom;
                                    if (gap >= -4 && gap <= 56) {
                                        var nt = Math.max(0, Math.floor(nr.top + sy));
                                        var nb = Math.min(rawDocH, Math.ceil(nr.bottom + sy));
                                        var leadEnd = Math.min(nb, nt + 44);
                                        if (leadEnd > ht && (leadEnd - ht) <= Math.floor(pageH * 0.65)) {
                                            boxes.push([ht, leadEnd, 1]);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4b) Widow & orphan protection for multi-line paragraphs (keep first 2 and last 2 lines together)
                    var maxPairKeepH = Math.floor(pageH * 0.18);
                    var paraEls = document.querySelectorAll('p, li, dd');
                    var paraRange = document.createRange();
                    for (var piPara = 0; piPara < paraEls.length; piPara++) {
                        var pelPara = paraEls[piPara];
                        if (pelPara.querySelector('p, ul, ol, div, table, figure, img, pre, blockquote')) continue;
                        var pcsPara = window.getComputedStyle(pelPara);
                        var prPara = getVisibleClipRect(pelPara, pelPara.getBoundingClientRect(), pcsPara);
                        if (!prPara || prPara.height < 28 || prPara.height > pageH * 3) continue;
                        paraRange.selectNodeContents(pelPara);
                        var pRects = paraRange.getClientRects();
                        var rawParaLines = [];
                        for (var riPara = 0; riPara < pRects.length; riPara++) {
                            var lrPara = pRects[riPara];
                            if (lrPara.height < 8 || lrPara.width < 12) continue;
                            if (lrPara.right <= 0 || lrPara.left >= pageW) continue;
                            var ltPara = Math.max(0, Math.floor(lrPara.top + sy));
                            var lbPara = Math.min(rawDocH, Math.ceil(lrPara.bottom + sy));
                            if (lbPara > ltPara && lbPara - ltPara <= Math.floor(pageH * 0.20)) {
                                rawParaLines.push([ltPara, lbPara]);
                            }
                        }
                        if (rawParaLines.length < 2) continue;
                        rawParaLines.sort(function(a, b) { return a[0] - b[0] || a[1] - b[1]; });
                        var mergedLines = [rawParaLines[0]];
                        for (var mlIdx = 1; mlIdx < rawParaLines.length; mlIdx++) {
                            var prevL = mergedLines[mergedLines.length - 1];
                            var curL = rawParaLines[mlIdx];
                            var prevH = Math.max(1, prevL[1] - prevL[0]);
                            if (curL[0] < prevL[1] && curL[0] <= prevL[0] + Math.floor(prevH * 0.45)) {
                                prevL[0] = Math.min(prevL[0], curL[0]);
                                prevL[1] = Math.max(prevL[1], curL[1]);
                            } else {
                                mergedLines.push(curL);
                            }
                        }
                        if (mergedLines.length >= 2) {
                            var orphanTop = mergedLines[0][0];
                            var orphanBot = mergedLines[1][1];
                            if (orphanBot > orphanTop && (orphanBot - orphanTop) <= maxPairKeepH) {
                                boxes.push([orphanTop, orphanBot, 1]);
                            }
                        }
                        if (mergedLines.length >= 3) {
                            var widowTop = mergedLines[mergedLines.length - 2][0];
                            var widowBot = mergedLines[mergedLines.length - 1][1];
                            if (widowBot > widowTop && (widowBot - widowTop) <= maxPairKeepH) {
                                boxes.push([widowTop, widowBot, 1]);
                            }
                        }
                    }

                    var totalH = Math.max(rawDocH, pageH);

                    // 5) Collect visible background bands/containers so continuing multi-page/page-wide backgrounds bleed cleanly to margins
                    var bgLayers = [];
                    var rootBgEls = [];
                    if (document.documentElement) rootBgEls.push(document.documentElement);
                    if (document.body) rootBgEls.push(document.body);
                    for (var ri = 0; ri < rootBgEls.length; ri++) {
                        var rel = rootBgEls[ri];
                        var rcs = window.getComputedStyle(rel);
                        var rbg = parseRgba(rcs.backgroundColor);
                        var hasRootBgImg = rcs.backgroundImage && rcs.backgroundImage !== 'none' && rcs.backgroundImage.indexOf('url(') === -1;
                        if ((rbg && rbg[3] >= 0.05) || hasRootBgImg) {
                            bgLayers.push([0, totalH, 0, pageW, 1]);
                        }
                    }
                    var bgCands = document.querySelectorAll('main, article, section, footer, header, nav, aside, blockquote, pre, div');
                    for (var bi = 0; bi < bgCands.length && bgLayers.length < 180; bi++) {
                        var bel = bgCands[bi];
                        var bcs = window.getComputedStyle(bel);
                        if (bcs.display === 'none' || bcs.visibility === 'hidden') continue;
                        var br = getVisibleClipRect(bel, bel.getBoundingClientRect(), bcs);
                        if (!br || br.width < pageW * 0.25 || br.height < 40) continue;
                        var bbg = parseRgba(bcs.backgroundColor);
                        var hasBgImg = bcs.backgroundImage && bcs.backgroundImage !== 'none' && bcs.backgroundImage.indexOf('url(') === -1;
                        if ((!bbg || bbg[3] < 0.05) && !hasBgImg) continue;
                        var bl = Math.max(0, Math.floor(br.left));
                        var bri = Math.min(pageW, Math.ceil(br.right));
                        var btTop = Math.max(0, Math.floor(br.top + sy));
                        var btBot = Math.min(rawDocH, Math.ceil(br.bottom + sy));
                        if (bri > bl && btBot > btTop) {
                            var isDocRoot = (btBot - btTop) >= Math.floor(rawDocH * 0.85) && bl <= 4 && bri >= pageW - 4;
                            bgLayers.push([btTop, btBot, bl, bri, isDocRoot ? 1 : 0]);
                        }
                    }

                    var mediaRects = [];
                    var photoEls = document.querySelectorAll('img, picture, video, canvas');
                    for (var pi = 0; pi < photoEls.length && mediaRects.length < 200; pi++) {
                        var pel = photoEls[pi];
                        var pcs = window.getComputedStyle(pel);
                        var pr = getVisibleClipRect(pel, pel.getBoundingClientRect(), pcs);
                        if (!pr || pr.width < 12 || pr.height < 12) continue;
                        var pt = Math.max(0, Math.floor(pr.top + sy));
                        var pb = Math.min(rawDocH, Math.ceil(pr.bottom + sy));
                        var pl = Math.max(0, Math.floor(pr.left));
                        var pRight = Math.min(pageW, Math.ceil(pr.right));
                        if (pb > pt && pRight > pl) {
                            mediaRects.push([pt, pb, pl, pRight]);
                        }
                    }

                    if (document.body) {
                        var spacer = document.createElement('div');
                        spacer.id = '__xtch_bottom_spacer';
                        spacer.style.cssText = 'height:' + Math.ceil(pageH * 1.25) + 'px;width:1px;pointer-events:none;';
                        document.body.appendChild(spacer);
                    }

                    var rawText = (document.body && document.body.innerText) ? document.body.innerText.replace(/\s+/g, ' ').trim() : '';
                    return JSON.stringify({
                        title: document.title || '',
                        excerpt: rawText.slice(0, 300),
                        leadImage: leadImg || '',
                        publishedDate: rawPubDate || '',
                        pageCssW: pageW,
                        pageCssH: pageH,
                        dpr: dpr,
                        vvScale: vvScale,
                        viewportCssW: rawViewportW || pageW,
                        viewportCssH: rawViewportH || pageH,
                        firstContentTop: firstContentTop < 999999 ? firstContentTop : 0,
                        totalHeight: totalH,
                        boxes: boxes,
                        bgLayers: bgLayers,
                        mediaRects: mediaRects
                    });
                } catch (err) {
                    return JSON.stringify({ title: '', excerpt: '', leadImage: '', publishedDate: '', boxes: [], bgLayers: [], mediaRects: [] });
                }
            })();
        """.trimIndent()
    }

    internal data class PageLayoutPlan(
        val effectiveTotalH: Int,
        val initialStartY: Int,
        val validBoxes: List<LayoutBox>,
        val atomicKeepBlocks: List<LayoutBox>,
        val mergedTextBoxes: List<LayoutBox>,
        val atomicBoxes: List<LayoutBox>
    )

    internal fun preparePageLayoutPlan(
        totalHeight: Int,
        pageHeight: Int,
        firstContentTop: Int,
        rawBoxes: List<LayoutBox>
    ): PageLayoutPlan {
        val maxAtomicH = (pageHeight - 8).coerceAtLeast(40)
        val validBoxes = rawBoxes
            .filter { it.bottom > it.top && it.top < totalHeight }
            .sortedWith(compareBy<LayoutBox> { it.top }.thenBy { it.bottom })

        val contentBottom = validBoxes.maxOfOrNull { it.bottom } ?: 0
        val effectiveTotalH = if (contentBottom > 0) {
            minOf(totalHeight, contentBottom + 20).coerceAtLeast(minOf(pageHeight, totalHeight))
        } else {
            totalHeight
        }

        val trimLimit = (pageHeight * 0.85f).toInt()
        val initialStartY = if (firstContentTop in 17 until trimLimit && firstContentTop < effectiveTotalH - 24) {
            (firstContentTop - 2).coerceAtLeast(0)
        } else {
            0
        }

        val atomicKeepBlocks = validBoxes.filter { it.isKeepBlock && (it.bottom - it.top) <= maxAtomicH }
        val mergedTextBoxes = mergeSameLineTextBoxes(
            validBoxes.filter { !it.isKeepBlock && (it.bottom - it.top) <= maxAtomicH }
        )
        val atomicBoxes = (atomicKeepBlocks + mergedTextBoxes)
            .sortedWith(compareBy<LayoutBox> { it.top }.thenBy { it.bottom })

        return PageLayoutPlan(
            effectiveTotalH = effectiveTotalH,
            initialStartY = initialStartY,
            validBoxes = validBoxes,
            atomicKeepBlocks = atomicKeepBlocks,
            mergedTextBoxes = mergedTextBoxes,
            atomicBoxes = atomicBoxes
        )
    }

    internal fun computeNextPageCut(
        y: Int,
        pageHeight: Int,
        atomicKeepBlocks: List<LayoutBox>,
        mergedTextBoxes: List<LayoutBox>,
        atomicBoxes: List<LayoutBox>
    ): Int {
        val bottomSafetyMargin = 6
        val cutPadding = 4
        val minKeepAdvance = (pageHeight * 0.05f).toInt().coerceAtLeast(20)

        val idealEnd = y + pageHeight - bottomSafetyMargin
        var safeCut = idealEnd

        fun dodgeKeepBlocks(): Boolean {
            var movedAny = false
            for (pass in 0 until 16) {
                var moved = false
                for (box in atomicKeepBlocks) {
                    if (box.top <= safeCut - 1 && box.bottom >= safeCut + 1) {
                        val prevTouchBottom = atomicBoxes
                            .filter { it.bottom <= box.top }
                            .maxOfOrNull { it.bottom }
                            ?.coerceAtLeast(y) ?: y
                        val gapMid = (prevTouchBottom + box.top) / 2
                        val candidate = maxOf(
                            prevTouchBottom,
                            box.top - minOf(cutPadding, (box.top - gapMid).coerceAtLeast(1))
                        ).coerceAtMost(box.top)
                        if ((candidate >= y + minKeepAdvance || (candidate > y + 4 && box.bottom - box.top <= pageHeight - bottomSafetyMargin - 4)) &&
                            candidate < safeCut
                        ) {
                            safeCut = candidate
                            moved = true
                            movedAny = true
                        }
                    }
                }
                if (!moved) break
            }
            return movedAny
        }

        dodgeKeepBlocks()
        for (pass in 0 until 16) {
            var stopNow = false
            val earliest = mergedTextBoxes
                .filter { it.top <= safeCut - 1 && it.bottom >= safeCut }
                .minByOrNull { it.top } ?: break

            val prevStrict = atomicBoxes
                .filter { it.bottom <= earliest.top && it.top < earliest.top }
                .maxOfOrNull { it.bottom } ?: y
            val prevOverlap = mergedTextBoxes
                .filter { it.top < earliest.top && it.bottom > earliest.top && it.bottom <= earliest.top + 6 }
                .maxOfOrNull { it.bottom }
            val candidate = when {
                prevOverlap != null -> ((prevOverlap + earliest.top) / 2).coerceIn(y, earliest.top)
                prevStrict < earliest.top -> {
                    val gapMid = (prevStrict + earliest.top) / 2
                    maxOf(prevStrict, earliest.top - minOf(cutPadding, (earliest.top - gapMid).coerceAtLeast(1)))
                }
                else -> earliest.top
            }
            val fitsTextSafety = candidate >= y + minKeepAdvance ||
                (candidate > y + 4 && (earliest.bottom - earliest.top) <= pageHeight - bottomSafetyMargin - 4)
            if (fitsTextSafety && candidate < safeCut) {
                safeCut = candidate
                val dodgedFurther = dodgeKeepBlocks()
                if (prevOverlap != null && !dodgedFurther) {
                    val deeplyStraddled = mergedTextBoxes.any {
                        it !== earliest && it.top <= safeCut - 4 && it.bottom >= safeCut + 4
                    }
                    if (!deeplyStraddled) {
                        stopNow = true
                    }
                }
            } else {
                break
            }
            if (stopNow) break
        }

        val nextY = if (safeCut > y) safeCut else (y + pageHeight)
        return if (nextY <= y) (y + pageHeight) else nextY
    }

    /**
     * Computes universal, non-cutting page break intervals `(startCssY, endCssY)` from DOM bounding boxes.
     * Ensures styled cards/boxes, images, tables, figures, headings, and text lines fit cleanly on a page
     * instead of falling back to an arbitrary slice through an element.
     */
    internal fun computeSmartPageCuts(
        totalHeight: Int,
        pageHeight: Int,
        firstContentTop: Int,
        rawBoxes: List<LayoutBox>,
        maxPages: Int = MAX_PAGES
    ): List<Pair<Int, Int>> {
        if (pageHeight <= 0 || totalHeight <= 0) return listOf(0 to pageHeight.coerceAtLeast(100))

        val plan = preparePageLayoutPlan(
            totalHeight = totalHeight,
            pageHeight = pageHeight,
            firstContentTop = firstContentTop,
            rawBoxes = rawBoxes
        )
        var y = plan.initialStartY
        val pages = mutableListOf<Pair<Int, Int>>()

        while (y < plan.effectiveTotalH && pages.size < maxPages) {
            val remaining = plan.effectiveTotalH - y
            if (remaining <= pageHeight) {
                val hasVisibleTail = plan.validBoxes.any { it.bottom > y + 2 && it.top < plan.effectiveTotalH }
                if (remaining >= 24 || pages.isEmpty() || (hasVisibleTail && remaining >= 6)) {
                    pages.add(y to minOf(plan.effectiveTotalH, y + pageHeight))
                }
                break
            }

            val nextY = computeNextPageCut(
                y = y,
                pageHeight = pageHeight,
                atomicKeepBlocks = plan.atomicKeepBlocks,
                mergedTextBoxes = plan.mergedTextBoxes,
                atomicBoxes = plan.atomicBoxes
            )
            pages.add(y to nextY)
            y = nextY
        }

        if (pages.isEmpty()) {
            pages.add(0 to minOf(totalHeight, pageHeight))
        }
        return pages
    }

    /**
     * Verifies a planned bottom cut row against the actual rendered pixels in a narrow strip
     * around [plannedRelY]. If the boundary between `y - 1` and `y` cuts through active glyph
     * strokes, shifts slightly downward (within [maxSearchDown] to keep trailing descenders)
     * or upward (within [maxSearchUp]) to the center of the nearest clean inter-line gap.
     */
    internal fun findCleanCutRowInStrip(
        stripPixels: IntArray,
        width: Int,
        stripHeight: Int,
        plannedRelY: Int,
        maxSearchUp: Int,
        maxSearchDown: Int
    ): Int {
        if (width < 4 || stripHeight < 4 || stripPixels.size < width * stripHeight) {
            return plannedRelY.coerceIn(1, (stripHeight - 1).coerceAtLeast(1))
        }
        val targetY = plannedRelY.coerceIn(1, stripHeight - 1)
        val luma = IntArray(width * stripHeight)
        for (r in 0 until stripHeight) {
            val rowOff = r * width
            for (x in 0 until width) {
                val c = stripPixels[rowOff + x]
                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb = c and 0xFF
                luma[rowOff + x] = (77 * cr + 150 * cg + 29 * cb) shr 8
            }
        }

        val isStatic = BooleanArray(width)
        for (x in 0 until width) {
            var minL = 255
            var maxL = 0
            for (r in 0 until stripHeight) {
                val v = luma[r * width + x]
                if (v < minL) minL = v
                if (v > maxL) maxL = v
            }
            isStatic[x] = (maxL - minL) <= 18
        }

        val lineInk = IntArray(stripHeight)
        for (r in 0 until stripHeight) {
            val rowOff = r * width
            var ink = 0
            var x = 1
            while (x < width) {
                if (!isStatic[x - 1] && !isStatic[x]) {
                    if (kotlin.math.abs(luma[rowOff + x] - luma[rowOff + x - 1]) >= 28) {
                        ink++
                    }
                    x++
                } else if (isStatic[x - 1] && !isStatic[x]) {
                    var runEnd = x
                    while (runEnd + 1 < width && !isStatic[runEnd + 1]) {
                        runEnd++
                    }
                    for (rx in (x + 1)..runEnd) {
                        if (kotlin.math.abs(luma[rowOff + rx] - luma[rowOff + rx - 1]) >= 28) {
                            ink++
                        }
                    }
                    if (runEnd + 1 < width && isStatic[runEnd + 1]) {
                        val bgLeft = luma[rowOff + x - 1]
                        val bgRight = luma[rowOff + runEnd + 1]
                        if (kotlin.math.abs(bgLeft - bgRight) <= 18 &&
                            kotlin.math.abs(luma[rowOff + x] - bgLeft) >= 28
                        ) {
                            ink++
                        }
                    }
                    x = runEnd + 1
                } else {
                    x++
                }
            }
            lineInk[r] = ink
        }

        fun boundaryScore(y: Int): Int = lineInk[y - 1] + lineInk[y]
        val plannedScore = boundaryScore(targetY)
        if (plannedScore == 0) return targetY

        val minY = maxOf(1, targetY - maxSearchUp.coerceAtLeast(0))
        val maxY = minOf(stripHeight - 1, targetY + maxSearchDown.coerceAtLeast(0))

        // 1) Small downward search to preserve trailing descenders / underlines without kicking the whole line
        for (y in (targetY + 1)..maxY) {
            if (boundaryScore(y) == 0) {
                var bandEnd = y
                while (bandEnd + 1 <= maxY && boundaryScore(bandEnd + 1) == 0) {
                    bandEnd++
                }
                return (y + bandEnd) / 2
            }
        }

        // 2) Upward search to the center of the nearest clean inter-line gap
        for (y in (targetY - 1) downTo minY) {
            if (boundaryScore(y) == 0) {
                var bandStart = y
                while (bandStart - 1 >= minY && boundaryScore(bandStart - 1) == 0) {
                    bandStart--
                }
                return (bandStart + y) / 2
            }
        }

        // 3) Fallback low-ink search (e.g. inside a card that started mid-strip with 2 vertical border edges)
        var bestScore = Int.MAX_VALUE
        for (y in minY..maxY) {
            val s = boundaryScore(y)
            if (s < bestScore) bestScore = s
        }
        if (bestScore < plannedScore && bestScore <= 4 && bestScore * 3 <= plannedScore) {
            val maxDist = maxOf(targetY - minY, maxY - targetY)
            for (d in 1..maxDist) {
                val upY = targetY - d
                if (upY >= minY && boundaryScore(upY) <= bestScore) {
                    var bandStart = upY
                    while (bandStart - 1 >= minY && boundaryScore(bandStart - 1) <= bestScore) {
                        bandStart--
                    }
                    return (bandStart + upY) / 2
                }
                val downY = targetY + d
                if (downY <= maxY && boundaryScore(downY) <= bestScore) {
                    var bandEnd = downY
                    while (bandEnd + 1 <= maxY && boundaryScore(bandEnd + 1) <= bestScore) {
                        bandEnd++
                    }
                    return (downY + bandEnd) / 2
                }
            }
        }

        return targetY
    }

    private fun refineCutRowOnBitmap(
        bitmap: Bitmap,
        srcTopPx: Int,
        plannedBotPx: Int,
        cssToViewScale: Float
    ): Int {
        val maxSearchUp = minOf((44f * cssToViewScale).roundToInt(), ((plannedBotPx - srcTopPx) * 0.35f).toInt())
            .coerceAtLeast(0)
        val maxSearchDown = minOf((6f * cssToViewScale).roundToInt(), bitmap.height - plannedBotPx)
            .coerceAtLeast(0)
        if (maxSearchUp == 0 && maxSearchDown == 0) return plannedBotPx
        val stripTop = (plannedBotPx - maxSearchUp - 2).coerceAtLeast(srcTopPx)
        val stripBottom = (plannedBotPx + maxSearchDown + 2).coerceAtMost(bitmap.height)
        val stripH = stripBottom - stripTop
        if (stripH < 4) return plannedBotPx
        val stripPixels = IntArray(bitmap.width * stripH)
        bitmap.getPixels(stripPixels, 0, bitmap.width, 0, stripTop, bitmap.width, stripH)
        val refinedRelY = findCleanCutRowInStrip(
            stripPixels = stripPixels,
            width = bitmap.width,
            stripHeight = stripH,
            plannedRelY = plannedBotPx - stripTop,
            maxSearchUp = maxSearchUp,
            maxSearchDown = maxSearchDown
        )
        val minBot = (srcTopPx + 16).coerceAtMost(bitmap.height)
        return (stripTop + refinedRelY).coerceIn(minBot, bitmap.height)
    }

    /**
     * Merges inline text client-rects belonging to the same visual line (e.g. `<a>`, `<strong>`,
     * `<sup>`, `<sub>`, or mixed font sizes) so pagination never splits through inline elements
     * on the same line.
     */
    internal fun mergeSameLineTextBoxes(sortedTextBoxes: List<LayoutBox>): List<LayoutBox> {
        if (sortedTextBoxes.size <= 1) return sortedTextBoxes
        val merged = mutableListOf<LayoutBox>()
        var current = sortedTextBoxes.first()
        for (i in 1 until sortedTextBoxes.size) {
            val next = sortedTextBoxes[i]
            val curHeight = (current.bottom - current.top).coerceAtLeast(1)
            val nextHeight = (next.bottom - next.top).coerceAtLeast(1)
            val overlap = minOf(current.bottom, next.bottom) - maxOf(current.top, next.top)
            val minH = minOf(curHeight, nextHeight)
            val isSameVisualLine = next.top < current.bottom &&
                (next.top <= current.top + (curHeight * 0.45f).toInt() || overlap > (minH * 0.50f).toInt())
            if (isSameVisualLine) {
                current = LayoutBox(
                    top = minOf(current.top, next.top),
                    bottom = maxOf(current.bottom, next.bottom),
                    isKeepBlock = false
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    /**
     * Returns DOM background bands (in outermost-to-innermost paint order) whose background
     * continues across [cutY] (or spans the page root/multi-page container cleanly).
     */
    internal fun matchingBgBandsForEdge(
        cutY: Int,
        isTopEdge: Boolean,
        pageHeight: Int,
        bgLayers: List<BgLayer>
    ): List<BgLayer> {
        if (bgLayers.isEmpty()) return emptyList()
        val multiPageMinH = (pageHeight * 1.15f).toInt().coerceAtLeast(100)
        val matched = bgLayers.filter { layer ->
            if (layer.right <= layer.left || layer.bottom <= layer.top) return@filter false
            if (layer.isRoot) {
                layer.top <= cutY + 4 && layer.bottom >= cutY - 2
            } else if (isTopEdge) {
                (layer.top < cutY - 1 && layer.bottom > cutY + 1) ||
                    ((layer.bottom - layer.top) >= multiPageMinH && layer.top <= cutY + 4 && layer.bottom > cutY + 40)
            } else {
                layer.top < cutY - 1 && layer.bottom > cutY + 1
            }
        }
        if (matched.isEmpty()) return emptyList()
        // Collapse consecutive layers with identical horizontal span so only outermost->innermost distinct bands remain
        val result = mutableListOf<BgLayer>()
        for (layer in matched) {
            val prev = result.lastOrNull()
            if (prev != null && prev.left == layer.left && prev.right == layer.right) {
                result[result.lastIndex] = layer
            } else {
                result.add(layer)
            }
        }
        return result
    }

    private fun paintContinuingMarginBackgrounds(
        pageCanvas: Canvas,
        viewBitmap: Bitmap,
        bands: List<BgLayer>,
        srcRowY: Int,
        marginTopPx: Int,
        marginBottomPx: Int,
        actualCssW: Int,
        viewW: Int,
        targetW: Int
    ) {
        if (bands.isEmpty() || marginBottomPx <= marginTopPx || actualCssW <= 0) return
        val yRow = srcRowY.coerceIn(0, viewBitmap.height - 1)
        val fillPaint = Paint().apply { style = Paint.Style.FILL }

        for (idx in bands.indices) {
            val layer = bands[idx]
            val lPx = ((layer.left.toFloat() / actualCssW) * targetW).roundToInt().coerceIn(0, targetW)
            val rPx = ((layer.right.toFloat() / actualCssW) * targetW).roundToInt().coerceIn(0, targetW)
            if (rPx - lPx < 8) continue

            val lView = ((layer.left.toFloat() / actualCssW) * viewW).roundToInt().coerceIn(0, viewW)
            val rView = ((layer.right.toFloat() / actualCssW) * viewW).roundToInt().coerceIn(0, viewW)

            // If narrower child bands follow later in paint order, prefer sampling uncovered outer gutters
            val narrowChildren = bands.subList(idx + 1, bands.size)
                .filter { it.left > layer.left + 4 || it.right < layer.right - 4 }
            var sampledColor: Int? = null
            if (narrowChildren.isNotEmpty()) {
                val innerMinLeft = narrowChildren.minOf { ((it.left.toFloat() / actualCssW) * viewW).roundToInt() }.coerceIn(lView, rView)
                val innerMaxRight = narrowChildren.maxOf { ((it.right.toFloat() / actualCssW) * viewW).roundToInt() }.coerceIn(lView, rView)
                if (innerMinLeft - lView >= 8) {
                    sampledColor = sampleUniformEdgeColor(viewBitmap, yRow, lView, innerMinLeft)
                }
                if (sampledColor == null && rView - innerMaxRight >= 8) {
                    sampledColor = sampleUniformEdgeColor(viewBitmap, yRow, innerMaxRight, rView)
                }
            }
            if (sampledColor == null) {
                sampledColor = sampleUniformEdgeColor(viewBitmap, yRow, lView, rView)
            }
            if (sampledColor != null) {
                fillPaint.color = sampledColor
                pageCanvas.drawRect(
                    lPx.toFloat(),
                    marginTopPx.toFloat(),
                    rPx.toFloat(),
                    marginBottomPx.toFloat(),
                    fillPaint
                )
            }
        }
    }

    /**
     * Samples up to 15 horizontally spaced pixels at [rowY] inside [leftViewPx, rightViewPx).
     * Returns the average RGB color of the dominant cluster if >= 66% of samples agree
     * (rejecting photos/gradients/illustrations so only true solid/shaded backgrounds bleed).
     */
    private fun sampleUniformEdgeColor(
        bitmap: Bitmap,
        rowY: Int,
        leftViewPx: Int,
        rightViewPx: Int
    ): Int? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val y = rowY.coerceIn(0, bitmap.height - 1)
        val xStart = (leftViewPx + 2).coerceIn(0, bitmap.width - 1)
        val xEnd = (rightViewPx - 2).coerceIn(0, bitmap.width - 1)
        val span = xEnd - xStart
        if (span < 6) return null

        val spanWidth = span + 1
        val rowBuf = IntArray(spanWidth)
        bitmap.getPixels(rowBuf, 0, spanWidth, xStart, y, spanWidth, 1)

        val sampleCount = 15
        val buckets = HashMap<Int, MutableList<Int>>()
        for (i in 0 until sampleCount) {
            val relX = (span * i) / (sampleCount - 1)
            val px = rowBuf[relX.coerceIn(0, span)]
            if (Color.alpha(px) < 200) continue
            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)
            val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            buckets.getOrPut(key) { mutableListOf() }.add(px)
        }
        val best = buckets.values.maxByOrNull { it.size } ?: return null
        if (best.size < 10) return null
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (px in best) {
            sumR += Color.red(px)
            sumG += Color.green(px)
            sumB += Color.blue(px)
        }
        return Color.rgb(
            (sumR / best.size).toInt().coerceIn(0, 255),
            (sumG / best.size).toInt().coerceIn(0, 255),
            (sumB / best.size).toInt().coerceIn(0, 255)
        )
    }

    /**
     * Exact separable area/box downsampling for supersampled WebView captures,
     * preserving thin font strokes and crisp edges much better than single-pass bilinear scaling.
     */
    internal fun downsampleAreaArgb(
        srcPixels: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        scratchHoriz: IntArray? = null,
        scratchOut: IntArray? = null
    ): IntArray {
        require(srcWidth > 0 && srcHeight > 0 && dstWidth > 0 && dstHeight > 0) {
            "Invalid dimensions for area downsampling: ${srcWidth}x$srcHeight -> ${dstWidth}x$dstHeight"
        }
        val dstCount = dstWidth * dstHeight
        val out = if (scratchOut != null && scratchOut.size >= dstCount) {
            scratchOut
        } else {
            IntArray(dstCount)
        }
        if (srcWidth == dstWidth && srcHeight == dstHeight) {
            System.arraycopy(srcPixels, 0, out, 0, dstCount)
            return out
        }

        val horizCount = dstWidth * srcHeight
        val horiz = if (scratchHoriz != null && scratchHoriz.size >= horizCount) {
            scratchHoriz
        } else {
            IntArray(horizCount)
        }

        val xStart = IntArray(dstWidth)
        val xEnd = IntArray(dstWidth)
        val xLeftW = DoubleArray(dstWidth)
        val xRightW = DoubleArray(dstWidth)
        val xInvSpan = DoubleArray(dstWidth)
        for (dx in 0 until dstWidth) {
            val fx0 = dx.toDouble() * srcWidth / dstWidth
            val fx1 = (dx + 1).toDouble() * srcWidth / dstWidth
            val sx0 = kotlin.math.floor(fx0).toInt().coerceIn(0, srcWidth - 1)
            val sx1 = (kotlin.math.ceil(fx1).toInt() - 1).coerceIn(sx0, srcWidth - 1)
            xStart[dx] = sx0
            xEnd[dx] = sx1
            if (sx0 == sx1) {
                xLeftW[dx] = fx1 - fx0
                xRightW[dx] = 0.0
            } else {
                xLeftW[dx] = (sx0 + 1) - fx0
                xRightW[dx] = fx1 - sx1
            }
            xInvSpan[dx] = 1.0 / (fx1 - fx0)
        }

        for (sy in 0 until srcHeight) {
            val srcRow = sy * srcWidth
            val dstRow = sy * dstWidth
            for (dx in 0 until dstWidth) {
                val sx0 = xStart[dx]
                val sx1 = xEnd[dx]
                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0
                for (sx in sx0..sx1) {
                    val w = when {
                        sx == sx0 -> xLeftW[dx]
                        sx == sx1 -> xRightW[dx]
                        else -> 1.0
                    }
                    val px = srcPixels[srcRow + sx]
                    sumR += ((px shr 16) and 0xFF) * w
                    sumG += ((px shr 8) and 0xFF) * w
                    sumB += (px and 0xFF) * w
                }
                val inv = xInvSpan[dx]
                val r = (sumR * inv + 0.5).toInt().coerceIn(0, 255)
                val g = (sumG * inv + 0.5).toInt().coerceIn(0, 255)
                val b = (sumB * inv + 0.5).toInt().coerceIn(0, 255)
                horiz[dstRow + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val yStart = IntArray(dstHeight)
        val yEnd = IntArray(dstHeight)
        val yTopW = DoubleArray(dstHeight)
        val yBotW = DoubleArray(dstHeight)
        val yInvSpan = DoubleArray(dstHeight)
        for (dy in 0 until dstHeight) {
            val fy0 = dy.toDouble() * srcHeight / dstHeight
            val fy1 = (dy + 1).toDouble() * srcHeight / dstHeight
            val sy0 = kotlin.math.floor(fy0).toInt().coerceIn(0, srcHeight - 1)
            val sy1 = (kotlin.math.ceil(fy1).toInt() - 1).coerceIn(sy0, srcHeight - 1)
            yStart[dy] = sy0
            yEnd[dy] = sy1
            if (sy0 == sy1) {
                yTopW[dy] = fy1 - fy0
                yBotW[dy] = 0.0
            } else {
                yTopW[dy] = (sy0 + 1) - fy0
                yBotW[dy] = fy1 - sy1
            }
            yInvSpan[dy] = 1.0 / (fy1 - fy0)
        }

        for (dy in 0 until dstHeight) {
            val sy0 = yStart[dy]
            val sy1 = yEnd[dy]
            val topW = yTopW[dy]
            val botW = yBotW[dy]
            val inv = yInvSpan[dy]
            val outRow = dy * dstWidth
            for (dx in 0 until dstWidth) {
                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0
                for (sy in sy0..sy1) {
                    val w = when {
                        sy == sy0 -> topW
                        sy == sy1 -> botW
                        else -> 1.0
                    }
                    val px = horiz[sy * dstWidth + dx]
                    sumR += ((px shr 16) and 0xFF) * w
                    sumG += ((px shr 8) and 0xFF) * w
                    sumB += (px and 0xFF) * w
                }
                val r = (sumR * inv + 0.5).toInt().coerceIn(0, 255)
                val g = (sumG * inv + 0.5).toInt().coerceIn(0, 255)
                val b = (sumB * inv + 0.5).toInt().coerceIn(0, 255)
                out[outRow + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    private fun stampPageNumbersOnXthStreamFile(
        pageStreamFile: File,
        pageOffsets: List<Long>,
        pageSizes: List<Int>,
        pageFooterBgColors: List<Int>,
        targetW: Int,
        targetH: Int,
        bottomMarginTargetPx: Int
    ) {
        if (pageSizes.size <= 1 || bottomMarginTargetPx < 16 || targetW <= 0 || targetH <= bottomMarginTargetPx) return
        val footerStripH = (bottomMarginTargetPx - 2).coerceIn(14, targetH)
        val stripTopY = targetH - footerStripH
        val totalPages = pageSizes.size
        val footerBitmap = createBitmap(targetW, footerStripH)
        val stripPixels = IntArray(targetW * footerStripH)
        try {
            val canvas = Canvas(footerBitmap)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = (footerStripH * 0.52f).coerceIn(11f, 15f)
                isFakeBoldText = false
            }
            val fm = textPaint.fontMetrics
            val baselineY = footerStripH / 2f - (fm.ascent + fm.descent) / 2f
            RandomAccessFile(pageStreamFile, "rw").use { raf ->
                for (i in pageSizes.indices) {
                    val pageSize = pageSizes[i]
                    val pageOffset = pageOffsets[i]
                    val blob = ByteArray(pageSize)
                    raf.seek(pageOffset)
                    raf.readFully(blob)

                    val bgColor = pageFooterBgColors.getOrElse(i) { Color.WHITE }
                    canvas.drawColor(bgColor)
                    textPaint.color = when (bgColor) {
                        Color.BLACK -> Color.WHITE
                        Color.WHITE -> Color.rgb(85, 85, 85)
                        else -> Color.BLACK
                    }
                    canvas.drawText("${i + 1} / $totalPages", targetW / 2f, baselineY, textPaint)
                    footerBitmap.getPixels(stripPixels, 0, targetW, 0, 0, targetW, footerStripH)
                    val stripLevels = XtcBuilder.pixelsToLevels(stripPixels, targetW, footerStripH)
                    XtcBuilder.overlayStripLevelsOnXthPage(
                        xthBlob = blob,
                        width = targetW,
                        height = targetH,
                        stripTopY = stripTopY,
                        stripHeight = footerStripH,
                        stripLevels = stripLevels
                    )
                    raf.seek(pageOffset)
                    raf.write(blob)
                }
            }
        } finally {
            footerBitmap.recycle()
        }
    }
}

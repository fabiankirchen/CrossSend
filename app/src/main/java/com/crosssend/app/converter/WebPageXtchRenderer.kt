package com.crosssend.app.converter

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
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
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.crosssend.app.CrossSendApp
import com.crosssend.app.util.DateUtils
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
import java.util.Date
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

/**
 * Renders live web pages in a hardware-backed offscreen VirtualDisplay Chromium WebView
 * sized to the target CrossPoint reader's aspect ratio, computes DOM-aware page breaks,
 * and encodes every page into 2-bit grayscale XTH blobs inside an .xtch file.
 */
object WebPageXtchRenderer {
    private const val TAG = "WebPageXtchRenderer"
    private const val MAX_PAGES = 120
    private const val CSS_MOBILE_WIDTH = 360f
    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    private const val PREF_WEB_ZOOM = "xtch_web_zoom_percent"
    private const val PREF_LIGHTEN_DARK = "xtch_lighten_dark_pages"

    fun getWebZoomPercent(context: Context): Int {
        return context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .getInt(PREF_WEB_ZOOM, 100)
            .coerceIn(75, 140)
    }

    fun setWebZoomPercent(context: Context, percent: Int) {
        context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_WEB_ZOOM, percent.coerceIn(75, 140))
            .apply()
    }

    fun isLightenDarkPagesEnabled(context: Context): Boolean {
        return context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_LIGHTEN_DARK, true)
    }

    fun setLightenDarkPagesEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_LIGHTEN_DARK, enabled)
            .apply()
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun renderUrlToXtch(
        context: Context,
        url: String,
        outputFile: File,
        title: String,
        author: String?
    ): XtchRenderResult? {
        require(EInkImageOptimizer.isSafeExternalImageUrl(url)) { "Blocked unsafe URL for XTCH render: $url" }
        val (targetW, targetH) = EInkImageOptimizer.getTargetScreenDimensions(context)
        val zoomPercent = getWebZoomPercent(context)
        val lightenDark = isLightenDarkPagesEnabled(context)

        val parsedHost = Uri.parse(url).host.orEmpty()
        withContext(Dispatchers.IO) {
            if (parsedHost.isNotBlank()) {
                EInkImageOptimizer.ssrfSafeDns.lookup(parsedHost)
            }
        }

        val xthPages = mutableListOf<ByteArray>()
        var pageTitle: String? = null
        var excerpt: String? = null
        var leadImageUrl: String? = null
        var publishedDate: Date? = null

        val rendered = withContext(Dispatchers.Main) {
            val activeActivity = CrossSendApp.instance.currentActivityRef?.get()?.takeIf {
                !it.isFinishing && !it.isDestroyed
            }
            val decorView = activeActivity?.window?.decorView as? ViewGroup
            val hostContext = activeActivity ?: context.applicationContext

            val metrics = hostContext.resources.displayMetrics
            val density = metrics.density.coerceAtLeast(1f)
            val densityDpi = metrics.densityDpi.coerceAtLeast(160)
            val topMarginTargetPx = (targetH * 0.0225f).roundToInt().coerceIn(8, 24)
            val bottomMarginTargetPx = (targetH * 0.0175f).roundToInt().coerceIn(6, 20)
            val usableTargetH = (targetH - topMarginTargetPx - bottomMarginTargetPx).coerceAtLeast(100)

            val cssW = CSS_MOBILE_WIDTH.roundToInt()
            val cssH = (CSS_MOBILE_WIDTH * usableTargetH.toFloat() / targetW.toFloat()).roundToInt().coerceAtLeast(200)
            val viewW = (cssW * density).roundToInt().coerceAtLeast(targetW)
            val viewH = (cssH * density).roundToInt().coerceAtLeast(usableTargetH)

            var imageReader: ImageReader? = null
            var virtualDisplay: VirtualDisplay? = null
            var presentation: Presentation? = null
            var fallbackContainer: FrameLayout? = null
            val frameSignal = AtomicReference<CompletableDeferred<Unit>?>(null)

            val webView = WebView(hostContext)
            var renderContextMode = "detached"
            try {
                webView.setBackgroundColor(Color.WHITE)
                webView.isVerticalScrollBarEnabled = false
                webView.isHorizontalScrollBarEnabled = false

                // Prefer an un-occluded offscreen VirtualDisplay + Presentation so Chromium's
                // GPU compositor actively rasterizes all scroll offsets (Pages 3..N) without
                // ever flashing UI on the device screen.
                val presentationAttached = try {
                    val dm = hostContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                    if (dm != null) {
                        val reader = ImageReader.newInstance(viewW, viewH, PixelFormat.RGBA_8888, 3)
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
                            viewW,
                            viewH,
                            densityDpi,
                            reader.surface,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                        )
                        val pres = Presentation(hostContext, vd.display)
                        val root = FrameLayout(pres.context).apply {
                            setBackgroundColor(Color.WHITE)
                            addView(webView, FrameLayout.LayoutParams(viewW, viewH))
                        }
                        pres.setContentView(root)
                        pres.show()
                        imageReader = reader
                        virtualDisplay = vd
                        presentation = pres
                        renderContextMode = "virtualDisplay"
                        true
                    } else false
                } catch (t: Throwable) {
                    Log.w(TAG, "VirtualDisplay setup failed, falling back to decorView: ${t.message}")
                    try { presentation?.dismiss() } catch (_: Exception) {}
                    try { virtualDisplay?.release() } catch (_: Exception) {}
                    try { imageReader?.close() } catch (_: Exception) {}
                    presentation = null
                    virtualDisplay = null
                    imageReader = null
                    false
                }

                if (!presentationAttached && decorView != null) {
                    val container = FrameLayout(hostContext).apply {
                        layoutParams = ViewGroup.LayoutParams(viewW, viewH)
                        translationZ = -1f
                        addView(webView, FrameLayout.LayoutParams(viewW, viewH))
                    }
                    decorView.addView(container, 0)
                    fallbackContainer = container
                    renderContextMode = "decorView"
                }

                try {
                    val allowThirdPartyCookies = hostContext
                        .getSharedPreferences("crosssend_prefs", Context.MODE_PRIVATE)
                        .getBoolean("allow_third_party_cookies", false)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, allowThirdPartyCookies)
                } catch (_: Exception) {}

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setSupportMultipleWindows(false)
                    useWideViewPort = false
                    loadWithOverviewMode = false
                    textZoom = zoomPercent
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = MOBILE_USER_AGENT
                }
                webView.onResume()

                val widthSpec = View.MeasureSpec.makeMeasureSpec(viewW, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(viewH, View.MeasureSpec.EXACTLY)
                webView.measure(widthSpec, heightSpec)
                webView.layout(0, 0, viewW, viewH)

                val pageLoaded = CompletableDeferred<Boolean>()
                val currentProgress = AtomicInteger(0)
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        currentProgress.set(newProgress)
                        val curUrl = view?.url
                        if (newProgress >= 96 && !curUrl.isNullOrBlank() && curUrl != "about:blank" && !pageLoaded.isCompleted) {
                            pageLoaded.complete(true)
                        }
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val reqUrl = request?.url?.toString() ?: return true
                        return !EInkImageOptimizer.isSafeExternalImageUrl(reqUrl)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUri = request?.url ?: return forbiddenResponse()
                        val scheme = reqUri.scheme?.lowercase() ?: return forbiddenResponse()
                        if (scheme == "data" || scheme == "blob") return null
                        val reqUrlStr = reqUri.toString()
                        if (!EInkImageOptimizer.isSafeExternalImageUrl(reqUrlStr)) {
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

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        if (loadedUrl != null && loadedUrl != "about:blank" && currentProgress.get() >= 80 && !pageLoaded.isCompleted) {
                            pageLoaded.complete(true)
                        }
                    }
                }

                Log.i(TAG, "Loading URL ($renderContextMode, ${viewW}x$viewH px, target ${targetW}x$targetH): $url")
                webView.loadUrl(url)
                withTimeoutOrNull(18_000L) { pageLoaded.await() }
                evaluateJsString(webView, buildPreparePageJs(cssW, cssH, lightenDark))
                awaitAnimationFrames(webView)
                waitForWebAssets(webView)
                evaluateJsString(webView, buildPreparePageJs(cssW, cssH, lightenDark))
                awaitAnimationFrames(webView)
                awaitVisualState(webView)
                delay(300L)

                val layoutJsonStr = evaluateJsString(
                    webView,
                    buildPrepareAndMeasureJs(cssW, cssH)
                )

                val cuts = mutableListOf<Pair<Int, Int>>()
                val parsedBoxes = mutableListOf<LayoutBox>()
                val parsedBgLayers = mutableListOf<BgLayer>()
                var measuredCssH = cssH
                var actualCssW = cssW
                if (!layoutJsonStr.isNullOrBlank()) {
                    try {
                        val json = JSONObject(layoutJsonStr)
                        pageTitle = json.optString("title").takeIf { it.isNotBlank() }
                        excerpt = json.optString("excerpt").takeIf { it.isNotBlank() }
                        leadImageUrl = json.optString("leadImage")
                            .takeIf { it.isNotBlank() && EInkImageOptimizer.isSafeExternalImageUrl(it) }
                        publishedDate = json.optString("publishedDate")
                            .takeIf { it.isNotBlank() }
                            ?.let { DateUtils.parseDate(it) }
                        measuredCssH = json.optInt("pageCssH", cssH).coerceAtLeast(100)
                        actualCssW = json.optInt("pageCssW", cssW).coerceAtLeast(100)
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

                        if (parsedBoxes.isNotEmpty()) {
                            cuts.addAll(
                                computeSmartPageCuts(
                                    totalHeight = totalCssH,
                                    pageHeight = measuredCssH,
                                    firstContentTop = firstContentTop,
                                    rawBoxes = parsedBoxes,
                                    maxPages = MAX_PAGES
                                )
                            )
                        } else {
                            val ranges = json.optJSONArray("pages") ?: JSONArray()
                            for (i in 0 until minOf(ranges.length(), MAX_PAGES)) {
                                val pair = ranges.optJSONArray(i) ?: continue
                                val startCssY = pair.optInt(0, 0)
                                val endCssY = pair.optInt(1, startCssY + measuredCssH)
                                if (endCssY > startCssY) {
                                    cuts.add(startCssY to endCssY)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse layout cuts JSON", e)
                    }
                }

                if (cuts.isEmpty()) {
                    cuts.add(0 to measuredCssH)
                }

                webView.measure(widthSpec, heightSpec)
                webView.layout(0, 0, viewW, viewH)
                delay(160L)

                val cssToViewScale = viewW.toFloat() / actualCssW.toFloat()
                val viewBitmap = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888)
                val viewCanvas = Canvas(viewBitmap)
                val pageBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val pageCanvas = Canvas(pageBitmap)
                val downsamplePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

                try {
                    for ((index, range) in cuts.withIndex()) {
                        val (startCssY, endCssY) = range
                        val scrollYPx = (startCssY * cssToViewScale).roundToInt().coerceAtLeast(0)
                        val visibleViewH = ((endCssY - startCssY) * cssToViewScale).roundToInt().coerceIn(1, viewH)
                        val visibleTargetH = ((visibleViewH.toFloat() / viewH.toFloat()) * usableTargetH).roundToInt().coerceIn(1, usableTargetH)
                        val sliceExpectsContent = parsedBoxes.any { it.bottom > startCssY + 4 && it.top < endCssY - 4 }

                        val reader = imageReader
                        var frameSignalForPage: CompletableDeferred<Unit>? = null
                        if (reader != null) {
                            drainImageReader(reader)
                            frameSignalForPage = CompletableDeferred()
                            frameSignal.set(frameSignalForPage)
                        }

                        webView.scrollTo(0, scrollYPx)
                        webView.invalidate()
                        presentation?.window?.decorView?.postInvalidate()
                        awaitAnimationFrames(webView)
                        awaitVisualState(webView)
                        if (scrollYPx > 0) {
                            evaluateJsString(webView, buildHideScrollOverlaysJs())
                            awaitAnimationFrames(webView)
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
                                    capturedFromSurface = copyImageToBitmap(image, viewBitmap, viewW, viewH)
                                } finally {
                                    image.close()
                                }
                            }
                            if (capturedFromSurface && sliceExpectsContent && countNonWhitePixelsSampled(viewBitmap) == 0) {
                                // Compositor tile may still have been blank on first frame; wait briefly and retry
                                webView.invalidate()
                                presentation?.window?.decorView?.postInvalidate()
                                awaitAnimationFrames(webView)
                                delay(160L)
                                val retryImage = try {
                                    reader.acquireLatestImage()
                                } catch (_: Exception) {
                                    null
                                }
                                if (retryImage != null) {
                                    try {
                                        copyImageToBitmap(retryImage, viewBitmap, viewW, viewH)
                                    } finally {
                                        retryImage.close()
                                    }
                                }
                            }
                        } else {
                            delay(if (index == 0) 180L else 100L)
                        }

                        if (!capturedFromSurface) {
                            val scrollClampDelta = scrollYPx - webView.scrollY
                            viewCanvas.drawColor(Color.WHITE)
                            viewCanvas.save()
                            viewCanvas.clipRect(0, 0, viewW, visibleViewH)
                            if (scrollClampDelta > 0) {
                                viewCanvas.translate(0f, -scrollClampDelta.toFloat())
                            }
                            webView.draw(viewCanvas)
                            viewCanvas.restore()
                        }

                        pageCanvas.drawColor(Color.WHITE)
                        val topBands = matchingBgBandsForEdge(
                            cutY = startCssY,
                            isTopEdge = true,
                            pageHeight = measuredCssH,
                            bgLayers = parsedBgLayers
                        )
                        val botBands = matchingBgBandsForEdge(
                            cutY = endCssY,
                            isTopEdge = false,
                            pageHeight = measuredCssH,
                            bgLayers = parsedBgLayers
                        )
                        paintContinuingMarginBackgrounds(
                            pageCanvas = pageCanvas,
                            viewBitmap = viewBitmap,
                            bands = topBands,
                            srcRowY = 1.coerceAtMost(visibleViewH - 1),
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
                            srcRowY = (visibleViewH - 2).coerceAtLeast(0),
                            marginTopPx = topMarginTargetPx + visibleTargetH,
                            marginBottomPx = targetH,
                            actualCssW = actualCssW,
                            viewW = viewW,
                            targetW = targetW
                        )
                        pageCanvas.drawBitmap(
                            viewBitmap,
                            Rect(0, 0, viewW, visibleViewH),
                            Rect(0, topMarginTargetPx, targetW, topMarginTargetPx + visibleTargetH),
                            downsamplePaint
                        )

                        if (index == 0 || index == cuts.lastIndex) {
                            val nonWhite = countNonWhitePixelsSampled(pageBitmap)
                            Log.i(TAG, "Page ${index + 1}/${cuts.size} drawn (surface=$capturedFromSurface, nonWhite=$nonWhite)")
                        }

                        val xthBlob = withContext(Dispatchers.Default) {
                            XtcBuilder.bitmapToXthPage(pageBitmap)
                        }
                        xthPages.add(xthBlob)
                    }
                } finally {
                    frameSignal.set(null)
                    viewBitmap.recycle()
                    pageBitmap.recycle()
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "WebView XTCH render failed for $url", e)
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
                    webView.stopLoading()
                    webView.destroy()
                } catch (_: Exception) {}
            }
        }

        if (!rendered || xthPages.isEmpty()) {
            return null
        }

        val effectiveTitle = title.ifBlank { pageTitle ?: "Article" }
        val built = withContext(Dispatchers.IO) {
            XtcBuilder.buildXtch(
                outputFile = outputFile,
                title = effectiveTitle,
                author = author,
                pages = xthPages,
                width = targetW,
                height = targetH
            )
        }
        if (!built) {
            return null
        }

        Log.i(TAG, "Successfully built XTCH ${outputFile.name} (${outputFile.length()} bytes, ${xthPages.size} pages)")
        return XtchRenderResult(
            pageCount = xthPages.size,
            title = effectiveTitle,
            excerpt = excerpt,
            colorCoverFile = null,
            leadImageUrl = leadImageUrl,
            publishedDate = publishedDate
        )
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
            val paddedBitmap = Bitmap.createBitmap(maxOf(width, rowPixels), height, Bitmap.Config.ARGB_8888)
            try {
                paddedBitmap.copyPixelsFromBuffer(buffer)
                val c = Canvas(dest)
                c.drawBitmap(paddedBitmap, Rect(0, 0, width, height), Rect(0, 0, width, height), null)
            } finally {
                paddedBitmap.recycle()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "copyImageToBitmap failed", e)
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
                "(function(){ var h = Math.max(document.documentElement.scrollHeight||0, document.body?document.body.scrollHeight:0); window.scrollTo(0, Math.floor(h * $frac)); })();"
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

    private fun countNonWhitePixelsSampled(bitmap: Bitmap): Int {
        var count = 0
        val w = bitmap.width
        val h = bitmap.height
        val stepX = maxOf(1, w / 48)
        val stepY = maxOf(1, h / 80)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (r < 245 || g < 245 || b < 245) count++
                x += stepX
            }
            y += stepY
        }
        return count
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
                    for (var i = 0; i < els.length; i++) {
                        var el = els[i];
                        var tag = (el.tagName || '').toLowerCase();
                        if (tag === 'html' || tag === 'body') continue;
                        var cs = window.getComputedStyle(el);
                        if (cs.position === 'fixed') {
                            var fr = el.getBoundingClientRect();
                            if (fr.height < vh * 0.70) {
                                el.style.setProperty('visibility', 'hidden', 'important');
                                el.style.setProperty('opacity', '0', 'important');
                                el.style.setProperty('pointer-events', 'none', 'important');
                            }
                        } else if (cs.position === 'sticky') {
                            var sr = el.getBoundingClientRect();
                            if (sr.height < vh * 0.40 && (sr.top <= 16 || sr.bottom >= vh - 16)) {
                                // Un-stick without collapsing flow height so measured Y-coordinates never shift
                                el.style.setProperty('position', 'relative', 'important');
                                el.style.setProperty('top', 'auto', 'important');
                                el.style.setProperty('bottom', 'auto', 'important');
                            }
                        }
                    }
                } catch (e) {}
            })();
        """.trimIndent()
    }

    private fun buildPreparePageJs(fallbackCssW: Int, fallbackCssH: Int, lightenDark: Boolean): String {
        return """
            (function() {
                try {
                    var pageW = $fallbackCssW;
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

                    if (!document.getElementById('__xtch_cs_meta')) {
                        var metaCs = document.createElement('meta');
                        metaCs.id = '__xtch_cs_meta';
                        metaCs.setAttribute('name', 'color-scheme');
                        metaCs.setAttribute('content', 'light only');
                        if (document.head) document.head.appendChild(metaCs);
                    }

                    if (!document.getElementById('__xtch_style')) {
                        var style = document.createElement('style');
                        style.id = '__xtch_style';
                        style.textContent =
                            ':root { color-scheme: light only !important; } ' +
                            'html { width: ' + pageW + 'px !important; max-width: ' + pageW + 'px !important; ' +
                            'height: auto !important; min-height: 0 !important; max-height: none !important; ' +
                            'overflow-x: clip !important; overflow-y: visible !important; overflow-anchor: none !important; ' +
                            'margin: 0 !important; padding: 0 !important; box-sizing: border-box !important; } ' +
                            'body { width: ' + pageW + 'px !important; max-width: ' + pageW + 'px !important; ' +
                            'height: auto !important; min-height: 0 !important; max-height: none !important; ' +
                            'overflow-x: clip !important; overflow-y: visible !important; overflow-anchor: none !important; ' +
                            'margin: 0 !important; box-sizing: border-box !important; -webkit-font-smoothing: antialiased !important; } ' +
                            'main, article, header, nav { max-width: ' + pageW + 'px !important; overflow-x: clip !important; } ' +
                            'figure, blockquote, aside { max-width: 100% !important; box-sizing: border-box !important; } ' +
                            'img, svg, video, iframe, table, pre { max-width: 100% !important; height: auto !important; } ' +
                            '*::-webkit-scrollbar { display: none !important; }';
                        (document.head || document.documentElement).appendChild(style);
                    }
                    if (document.body) { void document.body.offsetHeight; }

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
                    }

                    ${if (lightenDark) """
                    try {
                        if (!document.documentElement.dataset.xtchInverted) {
                            function parseOpaqueLuma(bgStr) {
                                if (!bgStr) return null;
                                var m = bgStr.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)/);
                                if (!m) return null;
                                var alpha = (m[4] !== undefined) ? parseFloat(m[4]) : 1.0;
                                if (isNaN(alpha) || alpha < 0.5) return null;
                                return 0.299 * (+m[1]) + 0.587 * (+m[2]) + 0.114 * (+m[3]);
                            }
                            var bodyLuma = document.body ? parseOpaqueLuma(window.getComputedStyle(document.body).backgroundColor) : null;
                            var rootLuma = parseOpaqueLuma(window.getComputedStyle(document.documentElement).backgroundColor);
                            var effectiveLuma = (bodyLuma !== null) ? bodyLuma : rootLuma;
                            if (effectiveLuma !== null && effectiveLuma < 85) {
                                document.documentElement.dataset.xtchInverted = '1';
                                document.documentElement.style.setProperty('background', '#000000', 'important');
                                document.documentElement.style.setProperty('filter', 'invert(1) hue-rotate(180deg)', 'important');
                                var imgs = document.querySelectorAll('img, picture, video');
                                for (var j = 0; j < imgs.length; j++) {
                                    imgs[j].style.setProperty('filter', 'invert(1) hue-rotate(180deg)', 'important');
                                }
                            }
                        }
                    } catch (e2) {}
                    """ else ""}
                } catch (err) {}
            })();
        """.trimIndent()
    }

    private fun buildPrepareAndMeasureJs(fallbackCssW: Int, fallbackCssH: Int): String {
        return """
            (function() {
                try {
                    var pageW = Math.round(document.documentElement.clientWidth || window.innerWidth || $fallbackCssW);
                    var pageH = $fallbackCssH;
                    if (pageW < 100) pageW = $fallbackCssW;
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
                    if (document.body) {
                        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                        var node;
                        var range = document.createRange();
                        while ((node = walker.nextNode())) {
                            if (!node.nodeValue || !node.nodeValue.trim()) continue;
                            var parent = node.parentElement;
                            if (!parent) continue;
                            var pcs = window.getComputedStyle(parent);
                            var prect = getVisibleClipRect(parent, parent.getBoundingClientRect(), pcs);
                            if (!prect) continue;
                            var inTopChrome = isTopSiteChrome(parent);
                            range.selectNodeContents(node);
                            var rects = range.getClientRects();
                            for (var r = 0; r < rects.length; r++) {
                                var rect = rects[r];
                                if (rect.height < 2 || rect.width < 1) continue;
                                if (rect.right <= 0 || rect.left >= pageW) continue;
                                var ctTop = Math.max(rect.top, prect.top);
                                var ctBot = Math.min(rect.bottom, prect.bottom);
                                if (ctBot - ctTop < 2) continue;
                                var t = Math.max(0, Math.floor(ctTop + sy));
                                var b = Math.ceil(ctBot + sy);
                                if (t >= rawDocH) continue;
                                if (!inTopChrome && t >= 0 && t < firstContentTop) firstContentTop = t;
                                if (b > t && (b - t) <= Math.floor(pageH * 0.45)) {
                                    boxes.push([t, b, 0]);
                                }
                            }
                        }
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
                        if ((mb - mt) <= maxAtomicH) {
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
                                        if (wb > wt && (wb - wt) <= maxAtomicH) {
                                            boxes.push([wt, wb, 1]);
                                        }
                                    }
                                }
                            }
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

                    if (document.body) {
                        var spacer = document.createElement('div');
                        spacer.id = '__xtch_bottom_spacer';
                        spacer.style.cssText = 'height:' + pageH + 'px;width:1px;pointer-events:none;';
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
                        firstContentTop: firstContentTop < 999999 ? firstContentTop : 0,
                        totalHeight: totalH,
                        boxes: boxes,
                        bgLayers: bgLayers
                    });
                } catch (err) {
                    return JSON.stringify({ title: '', excerpt: '', leadImage: '', publishedDate: '', boxes: [], bgLayers: [] });
                }
            })();
        """.trimIndent()
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

        val maxAtomicH = (pageHeight * 0.90f).toInt().coerceAtLeast(40)
        val bottomSafetyMargin = 4
        val cutPadding = 4
        val minKeepAdvance = (pageHeight * 0.14f).toInt().coerceAtLeast(28)

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
        var y = if (firstContentTop in 17 until trimLimit && firstContentTop < effectiveTotalH - 24) {
            (firstContentTop - 2).coerceAtLeast(0)
        } else {
            0
        }

        val atomicBoxes = validBoxes.filter { (it.bottom - it.top) <= maxAtomicH }
        val pages = mutableListOf<Pair<Int, Int>>()

        while (y < effectiveTotalH && pages.size < maxPages) {
            val remaining = effectiveTotalH - y
            if (remaining <= pageHeight) {
                if (remaining >= 24 || pages.isEmpty()) {
                    pages.add(y to minOf(effectiveTotalH, y + pageHeight))
                }
                break
            }

            val idealEnd = y + pageHeight - bottomSafetyMargin
            var safeCut = idealEnd

            // Fixed-point convergence: dodge keepBlocks first, then clear any straddling text line without backward cascade
            val keepBlocks = atomicBoxes.filter { it.isKeepBlock }
            val textBoxes = atomicBoxes.filter { !it.isKeepBlock }

            fun dodgeKeepBlocks() {
                for (pass in 0 until 16) {
                    var moved = false
                    for (box in keepBlocks) {
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
                            if (candidate >= y + minKeepAdvance && candidate < safeCut) {
                                safeCut = candidate
                                moved = true
                            }
                        }
                    }
                    if (!moved) break
                }
            }

            dodgeKeepBlocks()
            for (pass in 0 until 3) {
                var stopNow = false
                var adjusted = false
                for (box in textBoxes) {
                    if (box.top <= safeCut - 1 && box.bottom >= safeCut) {
                        val prevStrict = atomicBoxes
                            .filter { it.bottom <= box.top && it.top < box.top }
                            .maxOfOrNull { it.bottom } ?: y
                        val prevOverlap = textBoxes
                            .filter { it.top < box.top && it.bottom > box.top && it.bottom <= box.top + 6 }
                            .maxOfOrNull { it.bottom }
                        val candidate = when {
                            prevOverlap != null -> ((prevOverlap + box.top) / 2).coerceIn(y, box.top)
                            prevStrict < box.top -> {
                                val gapMid = (prevStrict + box.top) / 2
                                maxOf(prevStrict, box.top - minOf(cutPadding, (box.top - gapMid).coerceAtLeast(1)))
                            }
                            else -> box.top
                        }
                        if (candidate >= y + minKeepAdvance && candidate < safeCut) {
                            safeCut = candidate
                            adjusted = true
                            dodgeKeepBlocks()
                            if (prevOverlap != null) stopNow = true
                            break
                        }
                    }
                }
                if (stopNow || !adjusted) break
            }

            var nextY = if (safeCut > y + minKeepAdvance) safeCut else (y + pageHeight)
            if (nextY <= y) nextY = y + pageHeight

            pages.add(y to nextY)
            y = nextY
        }

        if (pages.isEmpty()) {
            pages.add(0 to minOf(totalHeight, pageHeight))
        }
        return pages
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
        val xStart = (leftViewPx + 2).coerceIn(0, bitmap.width - 1)
        val xEnd = (rightViewPx - 2).coerceIn(0, bitmap.width - 1)
        val span = xEnd - xStart
        if (span < 6) return null

        val sampleCount = 15
        val buckets = HashMap<Int, MutableList<Int>>()
        for (i in 0 until sampleCount) {
            val x = if (sampleCount <= 1) xStart else xStart + (span * i) / (sampleCount - 1)
            val px = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), rowY)
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
}

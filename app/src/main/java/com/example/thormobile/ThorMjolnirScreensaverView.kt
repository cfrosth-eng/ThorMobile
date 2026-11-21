package com.example.thormobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayInputStream

/**
 * WO-225 V5 Living Mjolnir Storm Universal Screensaver View (Note 20 Ultra Portrait + Landscape).
 *
 * 100% parity with Dell XPS `thor_mjolnir_screensaver.py` (WO-224):
 *  - Hosts a hardware-accelerated offline WebView rendering the 2K matte-obsidian 3D Nordic Raven
 *    (`assets/screensaver_3d/nordic_raven_3d.glb` + local Three.js ES modules) with zero external network access.
 *  - Auto-adapts on Portrait <-> Landscape rotation with calibrated stone pedestal perch coordinates.
 *  - Preserves exact 1:1 calm molten-gold border (18s CW rim / 26s CCW outer stardust) & double-tap exit.
 *  - Serves existing storm/bolt/gold PNGs directly from `res/drawable-nodpi` with zero APK duplication.
 */
class ThorMjolnirScreensaverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "ThorScreensaver3D"
        private const val VIRTUAL_HOST = "thor-local.assets"
        private const val ENTRY_URL = "https://$VIRTUAL_HOST/index.html"
    }

    private var webView: WebView? = null
    private var released = false

    private val drawableByName: Map<String, Int> = mapOf(
        "mjolnir_wide_base_storm.png" to R.drawable.mjolnir_wide_base_storm,
        "mjolnir_tall_base_storm.png" to R.drawable.mjolnir_tall_base_storm,
        "mjolnir_wide_bolt_0.png" to R.drawable.mjolnir_wide_bolt_0,
        "mjolnir_wide_bolt_1.png" to R.drawable.mjolnir_wide_bolt_1,
        "mjolnir_wide_bolt_2.png" to R.drawable.mjolnir_wide_bolt_2,
        "mjolnir_tall_bolt_0.png" to R.drawable.mjolnir_tall_bolt_0,
        "mjolnir_tall_bolt_1.png" to R.drawable.mjolnir_tall_bolt_1,
        "mjolnir_tall_bolt_2.png" to R.drawable.mjolnir_tall_bolt_2,
        "raven_gold_rim_cw.png" to R.drawable.raven_gold_rim_cw,
        "raven_gold_dust_ccw.png" to R.drawable.raven_gold_dust_ccw,
    )

    init {
        setBackgroundColor(Color.parseColor("#020305"))
        isClickable = true
        isFocusable = true
        mountWebView(context)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun mountWebView(ctx: Context) {
        val wv = WebView(ctx).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#020305"))
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isClickable = false
            isFocusable = false
            isLongClickable = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                allowFileAccess = false
                allowContentAccess = false
                // Note: keep blockNetworkLoads=false so Chromium invokes shouldInterceptRequest
                // for https://thor-local.assets/; all external hosts are hard-blocked inside shouldInterceptRequest.
                blockNetworkLoads = false
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                    val msg = "${cm.message()} (${cm.sourceId()}:${cm.lineNumber()})"
                    when (cm.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, msg)
                        ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, msg)
                        else -> Log.i(TAG, msg)
                    }
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val uri = request?.url ?: return empty404()
                    if (uri.host != VIRTUAL_HOST) {
                        return empty404()
                    }
                    val path = uri.path?.trimStart('/') ?: ""
                    return serveLocalAsset(ctx, path)
                }
            }
        }

        webView = wv
        addView(wv)
        wv.loadUrl(ENTRY_URL)
    }

    private fun corsHeaders(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "no-store",
    )

    private fun empty404(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        404,
        "Not Found",
        corsHeaders(),
        ByteArrayInputStream(ByteArray(0)),
    )

    private fun serveLocalAsset(ctx: Context, relPath: String): WebResourceResponse {
        return try {
            when {
                relPath.isEmpty() || relPath == "index.html" -> {
                    val stream = ctx.assets.open("screensaver_3d/index.html")
                    WebResourceResponse("text/html", "utf-8", 200, "OK", corsHeaders(), stream)
                }
                relPath.startsWith("vendor/") -> {
                    val fname = relPath.substringAfterLast('/')
                    val stream = ctx.assets.open("screensaver_3d/vendor/$fname")
                    WebResourceResponse("application/javascript", "utf-8", 200, "OK", corsHeaders(), stream)
                }
                relPath.startsWith("raven/") -> {
                    val fname = relPath.substringAfterLast('/')
                    val mime = if (fname.endsWith(".png", ignoreCase = true)) "image/png" else "model/gltf-binary"
                    val stream = ctx.assets.open("screensaver_3d/$fname")
                    WebResourceResponse(mime, null, 200, "OK", corsHeaders(), stream)
                }
                relPath.startsWith("assets/") -> {
                    val fname = relPath.substringAfterLast('/')
                    val resId = drawableByName[fname]
                    if (resId != null) {
                        val stream = ctx.resources.openRawResource(resId)
                        WebResourceResponse("image/png", null, 200, "OK", corsHeaders(), stream)
                    } else {
                        Log.w(TAG, "Missing screensaver drawable: $fname")
                        empty404()
                    }
                }
                else -> empty404()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed serving local asset '$relPath': ${e.message}")
            empty404()
        }
    }

    // Intercept all touch events so ThorScreensaverActivity's double-tap-to-exit fires reliably
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (released) return
        val wv = webView ?: return
        if (visibility == View.VISIBLE) {
            wv.onResume()
            wv.resumeTimers()
        } else {
            wv.onPause()
        }
    }

    override fun onDetachedFromWindow() {
        releaseBitmaps()
        super.onDetachedFromWindow()
    }

    fun releaseBitmaps() {
        if (released) return
        released = true
        val wv = webView
        webView = null
        if (wv != null) {
            try {
                wv.onPause()
                wv.stopLoading()
                removeView(wv)
                wv.loadUrl("about:blank")
                wv.destroy()
            } catch (_: Exception) {
            }
        }
    }
}

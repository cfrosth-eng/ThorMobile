package com.example.thormobile

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class DisplayShareConsentActivity : Activity() {
    companion object {
        private const val REQ_DISPLAY_SHARE = 9182

        fun launchConsent(context: Context) {
            val intent = Intent(context, DisplayShareConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#040812")) })
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_DISPLAY_SHARE)
        } catch (e: Exception) {
            Toast.makeText(this, "Display share error: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DISPLAY_SHARE && resultCode == RESULT_OK && data != null) {
            if (ThorFlashlightAndLensService.isRearCameraActive) {
                ThorFlashlightAndLensService.stopRearCamera(this)
            }
            val svcIntent = Intent(this, ThorDisplayShareService::class.java).apply {
                action = ThorDisplayShareService.ACTION_START_SHARE
                putExtra(ThorDisplayShareService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ThorDisplayShareService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, svcIntent)
            Toast.makeText(this, "Display Share ON (2-Gear Auto HD)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Display Share cancelled", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}

class ThorDisplayShareService : Service() {

    companion object {
        private const val TAG = "ThorDisplayShare"
        private const val SHARE_CHANNEL_ID = "ThorDisplayShareChannel"
        private const val SHARE_NOTIF_ID = 42
        private const val REFRESH_INTERVAL_MS = 2200L
        private const val LEDGER_WS_URL = "wss://thor-api-443941171993.us-east4.run.app/ws-ledger"

        const val ACTION_START_SHARE = "com.example.thormobile.action.START_DISPLAY_SHARE"
        const val ACTION_STOP_SHARE = "com.example.thormobile.action.STOP_DISPLAY_SHARE"
        const val EXTRA_RESULT_CODE = "extra_share_result_code"
        const val EXTRA_RESULT_DATA = "extra_share_result_data"

        @Volatile var isDisplayShareActive: Boolean = false
        @Volatile var latestFrameJson: String = ""
        @Volatile private var activeService: ThorDisplayShareService? = null

        private val quickWsClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        }

        fun stopSharing(context: Context) {
            val svc = activeService
            val wasActive = isDisplayShareActive || svc != null
            isDisplayShareActive = false
            latestFrameJson = ""
            // WO-196 Safety Lock: synchronously release MediaProjection/VirtualDisplay & only notify cloud if active
            if (svc != null) {
                try {
                    svc.forceImmediateTeardown(notifyCloudOff = wasActive)
                } catch (_: Exception) {}
            } else if (wasActive) {
                dispatchPacket(
                    JSONObject()
                        .put("action", "screen_watcher_state")
                        .put("type", "screen_watcher_state")
                        .put("active", false)
                        .toString()
                )
            }
            try {
                context.stopService(Intent(context, ThorDisplayShareService::class.java))
            } catch (_: Exception) {}
            try {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.cancel(SHARE_NOTIF_ID)
            } catch (_: Exception) {}
        }

        fun sendImmediateNativeFrame() {
            val svc = activeService ?: return
            svc.workerHandler?.post {
                try {
                    svc.captureAndBufferFrame(forceNativeFullRes = true, forceNetworkSend = true)
                } catch (e: Exception) {
                    Log.w(TAG, "Native frame upgrade note: ${e.message}")
                }
            }
        }

        fun dispatchPacket(jsonPayload: String) {
            if (FloatingThorService.sendJsonToActiveBridge(jsonPayload)) {
                return
            }
            try {
                val req = Request.Builder().url(LEDGER_WS_URL).build()
                quickWsClient.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(jsonPayload)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.close(1000, "ack")
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        try { webSocket.cancel() } catch (_: Exception) {}
                    }
                })
            } catch (_: Exception) {}
        }
    }

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastCapturedBitmap: Bitmap? = null
    private var nativeWidth: Int = 1080
    private var nativeHeight: Int = 2316
    private var nativeDpi: Int = 420
    private var dpDensity: Float = 2.625f
    private var sentFirstFrame: Boolean = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            teardownShare(notifyCloudOff = true)
            stopSelf()
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!isDisplayShareActive) return
            try {
                val streamLive = !sentFirstFrame || FloatingThorService.isVoiceSessionOpen()
                captureAndBufferFrame(forceNativeFullRes = false, forceNetworkSend = streamLive)
            } catch (e: Exception) {
                Log.w(TAG, "Refresh note: ${e.message}")
            }
            if (isDisplayShareActive) {
                workerHandler?.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeService = this
        ensureChannel()
        val ht = HandlerThread("ThorDisplayShareWorker").apply { start() }
        workerThread = ht
        workerHandler = Handler(ht.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SHARE) {
            teardownShare(notifyCloudOff = true)
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (intent != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
        } else null

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    SHARE_NOTIF_ID,
                    buildShareNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(SHARE_NOTIF_ID, buildShareNotification())
            }

            teardownShare(notifyCloudOff = false)
            val dm = resources.displayMetrics
            nativeWidth = ((dm.widthPixels / 2) * 2).coerceAtLeast(720)
            nativeHeight = ((dm.heightPixels / 2) * 2).coerceAtLeast(1280)
            nativeDpi = dm.densityDpi
            dpDensity = dm.density
            sentFirstFrame = false

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, resultData)
            if (proj == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            proj.registerCallback(projectionCallback, workerHandler ?: mainHandler)

            val reader = ImageReader.newInstance(nativeWidth, nativeHeight, PixelFormat.RGBA_8888, 2)
            val vDisp = proj.createVirtualDisplay(
                "ThorUiCanvasShare",
                nativeWidth,
                nativeHeight,
                nativeDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                workerHandler
            )

            mediaProjection = proj
            imageReader = reader
            virtualDisplay = vDisp
            isDisplayShareActive = true

            dispatchPacket(
                JSONObject()
                    .put("action", "screen_watcher_state")
                    .put("type", "screen_watcher_state")
                    .put("active", true)
                    .put("source", "display_share")
                    .toString()
            )
            workerHandler?.removeCallbacks(refreshRunnable)
            workerHandler?.postDelayed(refreshRunnable, 280L)
            Log.i(TAG, "2-Gear Display Share ready (${nativeWidth}x${nativeHeight}, dpi=$nativeDpi)")
        } catch (e: Exception) {
            Log.e(TAG, "Display Share start error", e)
            teardownShare(notifyCloudOff = true)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun captureAndBufferFrame(forceNativeFullRes: Boolean, forceNetworkSend: Boolean) {
        val reader = imageReader ?: return
        val img = reader.acquireLatestImage()

        if (img != null) {
            try {
                val plane = img.planes[0]
                val buf = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * nativeWidth
                val strideWidth = nativeWidth + (if (pixelStride > 0) rowPadding / pixelStride else 0)
                val rawBmp = Bitmap.createBitmap(strideWidth, nativeHeight, Bitmap.Config.ARGB_8888)
                buf.rewind()
                rawBmp.copyPixelsFromBuffer(buf)
                val fullBmp = if (strideWidth != nativeWidth) {
                    Bitmap.createBitmap(rawBmp, 0, 0, nativeWidth, nativeHeight).also { rawBmp.recycle() }
                } else {
                    rawBmp
                }
                val prev = lastCapturedBitmap
                lastCapturedBitmap = fullBmp
                if (prev != null && prev !== fullBmp && !prev.isRecycled) {
                    prev.recycle()
                }
            } finally {
                img.close()
            }
        }

        // WO-189 Static-Screen Guarantee: Even if zero pixels changed on screen (`img == null`),
        // encode from `lastCapturedBitmap` in RAM so Thor NEVER goes blind on a motionless phone screen!
        val srcBmp = lastCapturedBitmap
        if (srcBmp == null || srcBmp.isRecycled) {
            if (forceNetworkSend && latestFrameJson.isNotEmpty()) {
                dispatchPacket(latestFrameJson)
            }
            return
        }
        if (img == null && !forceNativeFullRes && latestFrameJson.isNotEmpty()) {
            if (forceNetworkSend) {
                sentFirstFrame = true
                dispatchPacket(latestFrameJson)
            }
            return
        }

        val outWidth: Int
        val outHeight: Int
        val scaleFactor: Double
        val gearName: String
        val baos = ByteArrayOutputStream()

        if (forceNativeFullRes || nativeWidth <= 720) {
            outWidth = nativeWidth
            outHeight = nativeHeight
            scaleFactor = 1.0
            gearName = "native_full_res"
            srcBmp.compress(Bitmap.CompressFormat.JPEG, 84, baos)
        } else {
            scaleFactor = 720.0 / nativeWidth.toDouble()
            outWidth = 720
            outHeight = (((nativeHeight * scaleFactor).toInt() / 2) * 2).coerceAtLeast(640)
            gearName = "standard_720p"
            val scaled = Bitmap.createScaledBitmap(srcBmp, outWidth, outHeight, true)
            scaled.compress(Bitmap.CompressFormat.JPEG, 72, baos)
            if (scaled !== srcBmp) {
                scaled.recycle()
            }
        }

        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        if (b64.isEmpty() || !isDisplayShareActive) return

        val pkt = JSONObject()
            .put("action", "screen_watcher_frame")
            .put("type", "screen_watcher_frame")
            .put("source", "display_share")
            .put("torch_on", ThorFlashlightAndLensService.isTorchOn)
            .put("jpeg_b64", b64)
            .put("mime_type", "image/jpeg")
            .put("width", outWidth)
            .put("height", outHeight)
            .put("native_width_px", nativeWidth)
            .put("native_height_px", nativeHeight)
            .put("density_dpi", nativeDpi)
            .put("dp_density", dpDensity.toDouble())
            .put("scale_factor", scaleFactor)
            .put("gear", gearName)
            .toString()

        latestFrameJson = pkt
        if (forceNetworkSend) {
            sentFirstFrame = true
            dispatchPacket(pkt)
        }
    }

    private fun teardownShare(notifyCloudOff: Boolean) {
        isDisplayShareActive = false
        latestFrameJson = ""
        sentFirstFrame = false
        workerHandler?.removeCallbacks(refreshRunnable)
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        val bmp = lastCapturedBitmap
        lastCapturedBitmap = null
        if (bmp != null && !bmp.isRecycled) {
            try { bmp.recycle() } catch (_: Exception) {}
        }
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
        if (notifyCloudOff) {
            dispatchPacket(
                JSONObject()
                    .put("action", "screen_watcher_state")
                    .put("type", "screen_watcher_state")
                    .put("active", false)
                    .toString()
            )
        }
    }

    fun forceImmediateTeardown(notifyCloudOff: Boolean) {
        teardownShare(notifyCloudOff = notifyCloudOff)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        try {
            getSystemService(NotificationManager::class.java)?.cancel(SHARE_NOTIF_ID)
        } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        val hadActiveProjection = isDisplayShareActive || mediaProjection != null || virtualDisplay != null
        teardownShare(notifyCloudOff = hadActiveProjection)
        if (activeService === this) activeService = null
        try { workerThread?.quitSafely() } catch (_: Exception) {}
        workerThread = null
        workerHandler = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                SHARE_CHANNEL_ID,
                "Thor Display Share",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while sharing display with Thor (in-RAM only)"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildShareNotification(): Notification {
        val stopIntent = Intent(this, ThorDisplayShareService::class.java).apply {
            action = ACTION_STOP_SHARE
        }
        val stopPi = PendingIntent.getService(
            this,
            9183,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, SHARE_CHANNEL_ID)
            .setSmallIcon(R.drawable.image_5)
            .setContentTitle("Thor Display Share: ON (2-Gear Auto HD)")
            .setContentText("In-RAM only (zero files saved). Tap Stop anytime.")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP SHARE", stopPi)
            .build()
    }
}

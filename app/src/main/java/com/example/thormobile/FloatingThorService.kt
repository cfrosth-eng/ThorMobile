package com.example.thormobile

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

class FloatingThorService : Service() {

    companion object {
        private const val TAG = "ThorService"
        private const val TAG_AUDIO = "ThorAudio"
        private const val TAG_WS = "ThorWebSocket"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ThorOverlayNoBadgeV2"
        const val ACTION_STOP = "com.example.thormobile.action.STOP"
        const val ACTION_KEEP_ALIVE = "com.example.thormobile.action.KEEP_ALIVE"
        @Volatile var isServiceRunning = false
        @Volatile var userRequestedStop = false
        @Volatile var serviceCreatedElapsedMs = 0L
        private const val BRIDGE_URL = "wss://thor-api-443941171993.us-east4.run.app"
        // Single-Cloud Architecture (V25.2): Direct connection to primary thor-api only
        private const val END_SESSION_TEXT = "END_SESSION"
        private const val END_DRAIN_MAX_MS = 16_000L
        private const val END_DRAIN_TAIL_MS = 420L
        private const val MIC_SETTLE_DELAY_MS = 25L
        private const val ASSET_DIR = "wakeword"
        private const val WAKE_THRESHOLD = 0.8f
        private const val CAPTURE_SAMPLE_RATE = 16000
        private const val CAPTURE_FRAME_BYTES = 1600
        private const val PRE_ROLL_IDLE_MAX_BYTES = 16000 * 2
        private const val PRE_ROLL_CONNECT_MAX_BYTES = 480000 // 15.0s of 16kHz PCM16 speech across valley dips * 2 * 8
        private const val PLAYBACK_SAMPLE_RATE = 24000
        private const val PLAYBACK_SLICE_BYTES = 1920
        private const val CAPTURE_RING_BYTES = CAPTURE_SAMPLE_RATE * 2
        private const val MAX_SEND_QUEUE_BYTES = 1024L * 1024L

        private const val PRE_BUFFER_DELAY_MS = 110L
        private const val OVERLAY_PREFS = "thor_overlay_v2"
        private const val KEY_POS_X = "overlay_pos_x"
        private const val KEY_POS_Y = "overlay_pos_y"
        private const val KEY_BOSS_PHONE = "boss_phone_fallback"

        const val ACTION_THOR_RESUME_MIC = "com.example.thormobile.ACTION_THOR_RESUME_MIC"
        @Volatile var canAutoResumeMic: Boolean = false

        private val FLUSH_MARKER: ByteString =
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()).toByteString()
    }

    private class PcmChunk(
        val generation: Int,
        val data: ByteArray,
        val enqueueTime: Long = SystemClock.elapsedRealtime()
    )

    /**
     * Animates ONLY the actual painted lightning bolts extracted from image_5.png
     * (core + electric bloom halo) cascading downward in 8 stepped-leader jumps
     * over the 100% undimmed original Mjolnir artwork. Zero fake drawn lines.
     */
    private class ActualArtworkBoltDrawable(
        private val coreBitmap: Bitmap,
        private val haloBitmap: Bitmap
    ) : Drawable() {
        private val haloPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val corePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dstRect = Rect()
        private val steps = 8

        // progress: 0.0f..0.70f (stepped downward cascade along real bolts),
        //           0.70f..0.92f (return-stroke flash across all real forks),
        //           0.92f..1.30f (top-to-bottom tail sweep & fade)
        var strikeProgress: Float = -1f
            set(value) {
                field = value
                invalidateSelf()
            }

        override fun draw(canvas: Canvas) {
            val p = strikeProgress
            if (p < 0f || p > 1.30f) return

            val b = bounds
            if (b.isEmpty) return
            dstRect.set(b)
            val h = b.height()

            if (p <= 0.70f) {
                // Phase 1: Stepped-leader downward cascade along the artwork's actual lightning forks
                val rawNorm = (p / 0.70f).coerceIn(0f, 1f)
                val steppedNorm = ((rawNorm * steps).toInt() + 1).toFloat() / steps.toFloat()
                val frontY = b.top + (h * steppedNorm.coerceIn(0f, 1f)).toInt()
                val leaderBand = (h * 0.24f).toInt().coerceAtLeast(10)
                val leaderTop = (frontY - leaderBand).coerceAtLeast(b.top)

                // Upper trail already ignited
                haloPaint.alpha = 185
                corePaint.alpha = 210
                canvas.save()
                canvas.clipRect(b.left, b.top, b.right, frontY)
                canvas.drawBitmap(haloBitmap, null, dstRect, haloPaint)
                canvas.drawBitmap(coreBitmap, null, dstRect, corePaint)
                canvas.restore()

                // Blazing downward-moving leading wave tip on the real forks
                haloPaint.alpha = 255
                corePaint.alpha = 255
                canvas.save()
                canvas.clipRect(b.left, leaderTop, b.right, frontY)
                canvas.drawBitmap(haloBitmap, null, dstRect, haloPaint)
                canvas.drawBitmap(coreBitmap, null, dstRect, corePaint)
                canvas.drawBitmap(coreBitmap, null, dstRect, corePaint)
                canvas.restore()
            } else if (p <= 0.92f) {
                // Phase 2: Full return-stroke flash along all real lightning bolts in image_5
                val crackle = if (p in 0.78f..0.82f) 0.55f else 1.0f
                haloPaint.alpha = (255 * crackle).toInt()
                corePaint.alpha = (255 * crackle).toInt()
                canvas.drawBitmap(haloBitmap, null, dstRect, haloPaint)
                canvas.drawBitmap(coreBitmap, null, dstRect, corePaint)
                if (crackle == 1.0f) {
                    canvas.drawBitmap(coreBitmap, null, dstRect, corePaint)
                }
            } else {
                // Phase 3: Top-to-bottom tail sweep as the surge dissipates into the hammer
                val fadeNorm = ((p - 0.92f) / 0.38f).coerceIn(0f, 1f)
                val tailTopY = b.top + (h * fadeNorm * 0.60f).toInt()
                val alpha = ((1f - fadeNorm) * 255f).toInt().coerceIn(0, 255)
                haloPaint.alpha = alpha
                corePaint.alpha = alpha
                canvas.save()
                canvas.clipRect(b.left, tailTopY, b.right, b.bottom)
                canvas.drawBitmap(haloBitmap, null, dstRect, haloPaint)
                canvas.drawBitmap(coreBitmap, null, dstRect, corePaint)
                canvas.restore()
            }
        }

        override fun setAlpha(alpha: Int) {
            corePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            corePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    @Volatile private var lastKnownLat: Double? = null
    @Volatile private var lastKnownLon: Double? = null
    @Volatile private var lastKnownSpeedMph: Double = 0.0
    @Volatile private var lastKnownHeading: Double = 0.0

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private var glowRingView: ImageView? = null
    private var hammerImageView: ImageView? = null
    private var lightningUpperView: ImageView? = null
    private var lightningLowerView: ImageView? = null
    private var badgeCardView: View? = null

    private var artworkBoltDrawable: ActualArtworkBoltDrawable? = null
    private var boltAnimator: ValueAnimator? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var ringRotateAnimator: ObjectAnimator? = null
    private var nextIdleStrikeAt = 0L

    private lateinit var audioManager: AudioManager
    private lateinit var powerGuard: PowerGuard
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    @Volatile private var isStandby = false

    private lateinit var db: ThorDatabase
    private lateinit var networkSentinel: NetworkSentinel
    private lateinit var localBrain: ThorLocalBrain
    private val outboxExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var offlineModeActive = false
    @Volatile private var valleyReconnectAttempts = 0
    private val offlineTurnBuffer = java.io.ByteArrayOutputStream()
    private val currentTurnPcm = java.io.ByteArrayOutputStream()
    @Volatile private var turnSpeechDetected = false
    @Volatile private var turnLastVoiceMs = 0L
    @Volatile private var lastHardwareSpeechActiveMs = 0L
    @Volatile private var socketDroppedDuringTurn = false
    @Volatile private var waitingForCloudBurst = false
    @Volatile private var offlineSpeechDetected = false
    @Volatile private var offlineLastVoiceMs = 0L
    @Volatile private var offlineStartedMs = 0L

    private val lock = Any()
    @Volatile private var activeSession = 0
    @Volatile private var socketReady = false
    private val sessionSeq = AtomicInteger(0)
    private var webSocket: WebSocket? = null
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private val preRoll = PreRollBuffer()
    @Volatile private var detector: WakeWordDetector? = null
    private var detectorModels: WakeWordModels? = null
    @Volatile private var detectorResetRequested = false

    private val captureToken = AtomicInteger(0)
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    private var aec: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile private var sharedAudioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE
    @Volatile private var localNoiseFloorRms: Double = 95.0
    @Volatile private var localBargeInStreak: Int = 0

    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private val playbackQueue = LinkedBlockingQueue<PcmChunk>()
    private val flushGeneration = AtomicInteger(0)
    @Volatile private var lastFailoverTurnId: String = ""
    @Volatile private var lastFailoverSmsAtMs: Long = 0L
    @Volatile private var userSpokeInActiveSession: Boolean = false
    @Volatile private var awaitingThorReply: Boolean = false
    @Volatile private var awaitingReplySinceMs: Long = 0L
    @Volatile private var lastPcmChunkArrivalMs: Long = 0L
    @Volatile private var cellularJitterEwmaMs: Double = 25.0

    @Volatile private var isPlayingAudio = false
    @Volatile private var playbackBusy = false
    @Volatile private var currentPlaybackRms: Double = 0.0
    @Volatile private var lastPlaybackTime = 0L
    @Volatile private var totalFramesWritten: Long = 0L
    @Volatile private var speechEndFrame: Long = 0L
    @Volatile private var playbackStartedAtMs: Long = 0L
    @Volatile private var echoBaselineRms: Double = 260.0
    @Volatile private var lastKnownMusicVol: Int = -1
    @Volatile private var volChangedCooldownUntilMs: Long = 0L
    @Volatile private var lastOutputDevCheckMs: Long = 0L
    @Volatile private var cachedExternalAudioOut: Boolean = false
    @Volatile private var bargeRecordingActive: Boolean = false
    @Volatile private var bargeStartedMs: Long = 0L
    @Volatile private var bargeLastVoiceMs: Long = 0L
    @Volatile private var lastBargeCheckSentMs: Long = 0L
    @Volatile private var ambientCabinNoiseRms: Double = 95.0
    private val bargePcmBuffer = java.io.ByteArrayOutputStream()

    @Volatile private var endingSession = false
    @Volatile private var micSettling = false

    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile private var hasAudioFocus = false
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.d(TAG_AUDIO, "Audio focus changed: $change")
    }

    private val stormTicker = object : Runnable {
        override fun run() {
            if (destroyed) return
            val now = SystemClock.elapsedRealtime()

            when {
                isStandby -> {
                    boltAnimator?.cancel()
                    artworkBoltDrawable?.strikeProgress = -1f
                    mainHandler.postDelayed(this, 500L)
                }
                playbackBusy || remainingHardwareSpeechMs() > 0L || (now - lastHardwareSpeechActiveMs < 350L) -> {
                    if (boltAnimator?.isRunning != true) {
                        fireArtworkLightningCascade(durationMs = 380L)
                    }
                    mainHandler.postDelayed(this, 110L)
                }
                activeSession != 0 || endingSession || playbackBusy || remainingHardwareSpeechMs() > 0L -> {
                    if (now >= nextIdleStrikeAt && boltAnimator?.isRunning != true) {
                        fireArtworkLightningCascade(durationMs = 480L)
                        nextIdleStrikeAt = now + Random.nextLong(1400L, 2500L)
                    }
                    mainHandler.postDelayed(this, 180L)
                }
                else -> {
                    boltAnimator?.cancel()
                    artworkBoltDrawable?.strikeProgress = -1f
                    mainHandler.postDelayed(this, 600L)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ObdLinkAutoWatchdog.startAutonomousWatchdog("https://" + "thor-api-443941171993.us-east4.run.app")
        VikingPopupOverlayManager.start(this, "https://" + "thor-api-443941171993.us-east4.run.app")
        RavenHeraldService.ensureRunning(this)
        isServiceRunning = true
        canAutoResumeMic = !isStandby

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        powerGuard = PowerGuard(this)

        db = ThorDatabase.getDatabase(this)
        networkSentinel = NetworkSentinel(this)
        localBrain = ThorLocalBrain(this)
        networkSentinel.onConnectionRestored = {
            synchronized(lock) {
                if (activeSession != 0) {
                    if (!socketReady && webSocket == null) {
                        openBridgeSocket(activeSession)
                    } else if (socketReady) {
                        flushOutbox()
                    }
                }
            }
        }
        networkSentinel.startMonitoring()

        createNotificationChannel()
        try {
            val notification = buildNotification(sessionActive = false, wakeReady = false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot enter foreground", e)
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_thor, null)
        glowRingView = floatingView.findViewById(R.id.thor_glow_ring)
        hammerImageView = floatingView.findViewById(R.id.thor_icon_image)
        lightningUpperView = floatingView.findViewById(R.id.thor_lightning_upper)
        lightningLowerView = floatingView.findViewById(R.id.thor_lightning_lower)
        badgeCardView = floatingView.findViewById(R.id.thor_badge_card)

        buildActualArtworkLightningLayer()

        val layoutFlag: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val metrics = resources.displayMetrics
        val iconPx = (120 * metrics.density).toInt()
        val touchSlopPx = (10 * metrics.density).toInt().coerceAtLeast(10)
        val defaultX = (metrics.widthPixels - iconPx - (10 * metrics.density).toInt()).coerceAtLeast(0)
        val defaultY = (metrics.heightPixels * 0.36f).toInt()
        val posPrefs = getSharedPreferences(OVERLAY_PREFS, MODE_PRIVATE)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = posPrefs.getInt(KEY_POS_X, defaultX)
        params.y = posPrefs.getInt(KEY_POS_Y, defaultY)
        windowManager.addView(floatingView, params)

        applyVisualState()
        loadWakeWordAsync()
        nextIdleStrikeAt = SystemClock.elapsedRealtime() + 1800L
        mainHandler.postDelayed({ fireArtworkLightningCascade(durationMs = 500L) }, 350L)
        mainHandler.post(stormTicker)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var longPressFired = false

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isStandby) {
                    fireArtworkLightningCascade(durationMs = 450L)
                    handleOverlayTap()
                } else {
                    toast("Thor is resting. Double-tap to wake.")
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isStandby && activeSession != 0) {
                    stopStreamingAudio()
                } else {
                    toggleStandby()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                longPressFired = true
                shutdownService()
            }
        })

        floatingView.setOnTouchListener { _: View, event: MotionEvent ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    longPressFired = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    if (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx) {
                        isDragging = true
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(floatingView, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        posPrefs.edit()
                            .putInt(KEY_POS_X, params.x)
                            .putInt(KEY_POS_Y, params.y)
                            .apply()
                    }
                }
            }
            true
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    val lat = location.latitude
                    val lon = location.longitude
                    val spdMph = if (location.hasSpeed()) (location.speed * 2.2369363) else 0.0
                    val hdg = if (location.hasBearing()) location.bearing.toDouble() else 0.0
                    lastKnownLat = lat
                    lastKnownLon = lon
                    lastKnownSpeedMph = spdMph
                    lastKnownHeading = hdg
                    val payload = org.json.JSONObject()
                        .put("type", "gps_update")
                        .put("lat", lat)
                        .put("lon", lon)
                        .put("speed_mph", Math.round(spdMph * 10.0) / 10.0)
                        .put("heading", Math.round(hdg))
                        .toString()
                    synchronized(lock) {
                        if (socketReady && webSocket != null) {
                            try {
                                webSocket?.send(payload)
                            } catch (e: Exception) {
                                Log.e(TAG_WS, "Failed to send gps_update", e)
                            }
                        }
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
                .setMinUpdateDistanceMeters(10f)
                .build()
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    lastKnownLat = loc.latitude
                    lastKnownLon = loc.longitude
                }
            }
        }

        loadWakeWordAsync()
    }

    /**
     * Keeps image_5.png 100% untouched on hammerImageView, and extracts ONLY the real
     * cyan-white lightning forks from image_5.png into a supercharged core + bloom halo
     * so the artwork's own lightning bolts visibly cascade downward from top to bottom.
     */
    private fun buildActualArtworkLightningLayer() {
        try {
            hammerImageView?.setImageResource(R.drawable.image_5)
            hammerImageView?.alpha = 1.0f

            val src = BitmapFactory.decodeResource(resources, R.drawable.image_5) ?: return
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)

            val corePixels = IntArray(w * h)
            val haloSeedPixels = IntArray(w * h)

            for (y in 0 until h) {
                val rowOffset = y * w
                for (x in 0 until w) {
                    val idx = rowOffset + x
                    val c = pixels[idx]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF

                    val cyanExcess = b - (r * 0.58f).toInt()
                    val luma = (r * 0.25f + g * 0.40f + b * 0.35f).toInt()

                    // Strictly match only the real cyan-white lightning forks in image_5.png
                    if (b > 140 && g > 120 && cyanExcess > 42 && luma > 135) {
                        val alpha = ((luma - 125) * 2.5f).toInt().coerceIn(0, 255)
                        // Pure white-hot core on the exact bolt filaments
                        corePixels[idx] = (alpha shl 24) or (255 shl 16) or (255 shl 8) or 255
                        // Electric cyan-blue halo seed around the forks
                        haloSeedPixels[idx] = (alpha shl 24) or (110 shl 16) or (230 shl 8) or 255
                    }
                }
            }

            val coreBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            coreBmp.setPixels(corePixels, 0, w, 0, 0, w, h)

            val haloSeedBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            haloSeedBmp.setPixels(haloSeedPixels, 0, w, 0, 0, w, h)

            // Fast downscale-upscale bloom so the real lightning forks expand & glow when struck
            val smallW = (w / 5).coerceAtLeast(16)
            val smallH = (h / 5).coerceAtLeast(16)
            val smallBmp = Bitmap.createScaledBitmap(haloSeedBmp, smallW, smallH, true)
            val haloBmp = Bitmap.createScaledBitmap(smallBmp, w, h, true)

            val drawable = ActualArtworkBoltDrawable(coreBmp, haloBmp)
            artworkBoltDrawable = drawable
            lightningUpperView?.colorFilter = null
            lightningUpperView?.alpha = 1.0f
            lightningUpperView?.setImageDrawable(drawable)
            lightningLowerView?.alpha = 0.0f
        } catch (e: Exception) {
            Log.e(TAG, "Failed building artwork lightning layer", e)
        }
    }

    private fun fireArtworkLightningCascade(durationMs: Long) {
        if (destroyed || isStandby) return
        val drawable = artworkBoltDrawable ?: return

        boltAnimator?.cancel()
        boltAnimator = ValueAnimator.ofFloat(0.0f, 1.30f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                drawable.strikeProgress = anim.animatedValue as Float
            }
            start()
        }
    }

    private fun applyVisualState() {
        mainHandler.post {
            if (destroyed) return@post
            pulseAnimator?.cancel()
            pulseAnimator = null
            ringRotateAnimator?.cancel()
            ringRotateAnimator = null

            val ring = glowRingView ?: return@post

            when {
                isStandby -> {
                    ring.setColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)
                    ring.rotation = 0f
                    hammerImageView?.alpha = 0.70f
                    boltAnimator?.cancel()
                    artworkBoltDrawable?.strikeProgress = -1f
                    pulseAnimator = ObjectAnimator.ofFloat(ring, View.ALPHA, 0.25f, 0.95f).apply {
                        duration = 1000L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        start()
                    }
                }
                activeSession != 0 -> {
                    ring.colorFilter = null
                    hammerImageView?.alpha = 1.0f
                    pulseAnimator = ObjectAnimator.ofFloat(ring, View.ALPHA, 0.45f, 1.0f).apply {
                        duration = 550L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        start()
                    }
                    ringRotateAnimator = ObjectAnimator.ofFloat(ring, View.ROTATION, ring.rotation, ring.rotation + 360f).apply {
                        duration = 7000L
                        interpolator = LinearInterpolator()
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.RESTART
                        start()
                    }
                }
                else -> {
                    // WO-129: Conversation closed (activeSession == 0) -> calm, still golden ring awaiting 'Thor' wake word
                    ring.colorFilter = null
                    ring.rotation = 0f
                    ring.alpha = 0.85f
                    hammerImageView?.alpha = 0.92f
                    boltAnimator?.cancel()
                    artworkBoltDrawable?.strikeProgress = -1f
                }
            }
        }
    }

    private fun toggleStandby() {
        if (isStandby) {
            isStandby = false
            canAutoResumeMic = true
            mainHandler.post {
                applyVisualState()
                fireArtworkLightningCascade(durationMs = 450L)
                toast("Microphone Locked. Thor is listening.")
            }
            synchronized(lock) { ensureCapture() }
            mainHandler.post { refreshNotification() }
        } else {
            isStandby = true
            canAutoResumeMic = false
            if (awaitingThorReply) {
                VikingPopupOverlayManager.enableFastReplyWatchPoll(this, "https://" + "thor-api-443941171993.us-east4.run.app", 45_000L)
            }
            awaitingThorReply = false
            stopStreamingAudio()
            stopCaptureAndReleaseMic()
            mainHandler.post {
                applyVisualState()
                toast("Standby Mode: Microphone Free for Gemini/Other Apps.")
                refreshNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_THOR_RESUME_MIC) {
            if (!destroyed && !userRequestedStop && !isStandby && canAutoResumeMic && hasMicPermission()) {
                Log.i(TAG_AUDIO, "WO-139: Autonomous Thor Mic Re-Open triggered to deliver pending reply.")
                if (activeSession == 0) {
                    startSession(keepPreRoll = false)
                } else {
                    synchronized(lock) { endingSession = false }
                    applyVisualState()
                }
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            userRequestedStop = true
            canAutoResumeMic = false
            getSharedPreferences("thor_overlay_prefs", MODE_PRIVATE).edit().putBoolean("user_stopped", true).apply()
            shutdownService()
            return START_NOT_STICKY
        }
        userRequestedStop = false
        canAutoResumeMic = !isStandby
        getSharedPreferences("thor_overlay_prefs", MODE_PRIVATE).edit().putBoolean("user_stopped", false).apply()
        RavenHeraldService.ensureRunning(this)
        VikingPopupOverlayManager.start(this, "https://" + "thor-api-443941171993.us-east4.run.app")
        return START_STICKY
    }

    private fun scheduleKeepAliveIfAllowed() {
        val stoppedByUser = userRequestedStop ||
            getSharedPreferences("thor_overlay_prefs", MODE_PRIVATE).getBoolean("user_stopped", false)
        val ranLongEnough = (android.os.SystemClock.elapsedRealtime() - serviceCreatedElapsedMs) > 1500L
        if (!stoppedByUser && ranLongEnough && android.provider.Settings.canDrawOverlays(this) && hasMicPermission()) {
            try {
                val keepIntent = Intent(applicationContext, FloatingThorService::class.java).apply {
                    action = ACTION_KEEP_ALIVE
                }
                val pi = PendingIntent.getForegroundService(
                    applicationContext,
                    777,
                    keepIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val am = getSystemService(android.app.AlarmManager::class.java)
                am?.set(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 450L,
                    pi
                )
            } catch (_: Exception) {}
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleKeepAliveIfAllowed()
        super.onTaskRemoved(rootIntent)
    }

    private fun cancelOverlayNotificationCompletely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        try {
            val nm = getSystemService(NotificationManager::class.java)
                nm?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    private fun shutdownService() {
        val wasAwaiting = awaitingThorReply
        userRequestedStop = true
        canAutoResumeMic = false
        awaitingThorReply = false
        isServiceRunning = false
        getSharedPreferences("thor_overlay_prefs", MODE_PRIVATE).edit().putBoolean("user_stopped", true).apply()
        if (destroyed) {
            cancelOverlayNotificationCompletely()
            RavenHeraldService.ensureRunning(this)
            return
        }
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        stopStreamingAudio()
        mainHandler.removeCallbacksAndMessages(null)
        cancelOverlayNotificationCompletely()
        // Keep zero-mic Silent Raven Herald alive even after long-press shutdown so Ravens always reach Boss!
        RavenHeraldService.ensureRunning(this)
        if (wasAwaiting) {
            VikingPopupOverlayManager.enableFastReplyWatchPoll(this, "https://" + "thor-api-443941171993.us-east4.run.app", 45_000L)
        }
        stopSelf()
    }

    private fun resolveContactNameOrNumber(rawAddress: String): String {
        if (rawAddress.isBlank()) return "Unknown"
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return rawAddress
        }
        try {
            val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(rawAddress))
            contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return "$name ($rawAddress)"
                }
            }
        } catch (_: Exception) {}
        return rawAddress
    }

    private fun normalizeDialableSmsNumber(raw: String): String {
        val trimmed = raw.trim()
        val sb = StringBuilder()
        for (ch in trimmed) {
            if (ch.isDigit() || (ch == '+' && sb.isEmpty())) sb.append(ch)
        }
        return sb.toString()
    }

    private fun resolvePhoneNumberFromContactName(nameQuery: String): String {
        val clean = nameQuery.trim()
        if (clean.isEmpty()) return ""
        val hasLetters = clean.any { it.isLetter() }
        if (!hasLetters) {
            val norm = normalizeDialableSmsNumber(clean)
            return if (norm.count { it.isDigit() } >= 7) norm else ""
        }
        if (clean.lowercase() in setOf("boss", "me", "myself", "my phone", "my number")) {
            val savedBoss = getSharedPreferences(OVERLAY_PREFS, MODE_PRIVATE).getString(KEY_BOSS_PHONE, "") ?: ""
            val normSaved = normalizeDialableSmsNumber(savedBoss)
            if (normSaved.count { it.isDigit() } >= 7) return normSaved
        }
        val embedded = Regex("[+0-9][0-9()\\-\\s.]{6,}").find(clean)?.value
        if (embedded != null) {
            val normEmb = normalizeDialableSmsNumber(embedded)
            if (normEmb.count { it.isDigit() } >= 7) return normEmb
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ""
        }
        var bestNumber = ""
        try {
            val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val proj = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                android.provider.ContactsContract.CommonDataKinds.Phone.TYPE
            )
            val sel = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            contentResolver.query(uri, proj, sel, arrayOf("%$clean%"), null)?.use { c ->
                while (c.moveToNext()) {
                    val candidate = normalizeDialableSmsNumber(c.getString(0) ?: "")
                    val pType = c.getInt(1)
                    if (candidate.count { it.isDigit() } >= 7) {
                        if (bestNumber.isEmpty() || pType == android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                            bestNumber = candidate
                            if (pType == android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) break
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return bestNumber
    }

    private fun readRecentGoogleMessagesJson(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return arr
        }
        try {
            val uri = android.net.Uri.parse("content://sms")
            contentResolver.query(uri, arrayOf("address", "body", "date", "type", "read"), null, null, "date DESC LIMIT 10")?.use { c ->
                val sdf = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US)
                while (c.moveToNext()) {
                    val addr = c.getString(0) ?: ""
                    val body = c.getString(1) ?: ""
                    val dateMs = c.getLong(2)
                    val msgType = c.getInt(3)
                    val isRead = c.getInt(4) == 1
                    val item = org.json.JSONObject()
                        .put("sender", resolveContactNameOrNumber(addr))
                        .put("address", addr)
                        .put("direction", if (msgType == 2) "SENT_BY_BOSS" else "RECEIVED")
                        .put("unread", !isRead && msgType == 1)
                        .put("time", sdf.format(java.util.Date(dateMs)))
                        .put("body", body.take(400))
                    arr.put(item)
                }
            }
        } catch (e: Exception) {
            Log.w("ThorSMS", "Failed reading SMS inbox: ${e.message}")
        }
        return arr
    }

    private fun sendSmsAckToServer(reqId: String, recipientRaw: String, number: String, ok: Boolean, status: String) {
        try {
            val ack = org.json.JSONObject()
                .put("type", "sms_delivery_ack")
                .put("req_id", reqId)
                .put("recipient", recipientRaw)
                .put("number", number)
                .put("ok", ok)
                .put("status", status)
            webSocket?.send(ack.toString())
        } catch (_: Exception) {}
    }

    private fun triggerLocalVoiceOutageFailover(
        ws: WebSocket,
        turnId: String,
        replyText: String,
        bossPhone: String,
        reasonLabel: String,
        allowSmsBackup: Boolean
    ) {
        if (replyText.isBlank() || turnId == lastFailoverTurnId) return
        lastFailoverTurnId = turnId
        Log.w(TAG_AUDIO, "WO-125 Voice Failover triggered ($reasonLabel) for $turnId")

        // 1. Instant Visual Heads-Up Card on screen (Zero extra notification-badge clutter!)
        VikingPopupOverlayManager.showDirectVoiceFailoverCard(
            this@FloatingThorService,
            "https://thor-api-443941171993.us-east4.run.app",
            reasonLabel,
            replyText
        )

        // 2. Carrier SMS Backup when screen is off, overlays blocked, or audio pipeline failed (45s cooldown)
        val pm = try { getSystemService(android.os.PowerManager::class.java) } catch (_: Exception) { null }
        val screenOff = pm != null && !pm.isInteractive
        val overlayBlocked = !android.provider.Settings.canDrawOverlays(this)
        val savedPhone = bossPhone.ifBlank {
            getSharedPreferences(OVERLAY_PREFS, MODE_PRIVATE).getString(KEY_BOSS_PHONE, "") ?: ""
        }
        val nowEl = SystemClock.elapsedRealtime()
        if ((allowSmsBackup || screenOff || overlayBlocked) &&
            savedPhone.count { it.isDigit() } >= 7 &&
            (nowEl - lastFailoverSmsAtMs) >= 45_000L
        ) {
            lastFailoverSmsAtMs = nowEl
            dispatchGoogleMessageSms(savedPhone, "[Thor Voice Backup - $reasonLabel] ${replyText.take(300)}", "failover_$turnId")
        }

        // 3. Closed-Loop Telemetry ACK back to thor-api
        try {
            val curVol = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { -1 }
            ws.send(
                org.json.JSONObject()
                    .put("type", "voice_playback_ack")
                    .put("turn_id", turnId)
                    .put("heard_ok", false)
                    .put("volume", curVol)
                    .put("reason", reasonLabel)
                    .toString()
            )
        } catch (_: Exception) {}
    }

    private fun scheduleVoiceTurnClosedLoopCheck(
        ws: WebSocket,
        sessionAtStart: Int,
        turnId: String,
        replyText: String,
        bossPhone: String
    ) {
        if (bossPhone.count { it.isDigit() } >= 7) {
            getSharedPreferences(OVERLAY_PREFS, MODE_PRIVATE).edit().putString(KEY_BOSS_PHONE, bossPhone).apply()
        }
        val curVol = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 8 }
        val isMuted = curVol <= 0 || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager.isStreamMute(AudioManager.STREAM_MUSIC))
        if (isMuted) {
            triggerLocalVoiceOutageFailover(ws, turnId, replyText, bossPhone, "MEDIA VOLUME MUTED", allowSmsBackup = false)
            return
        }
        val genAtStart = flushGeneration.get()
        val framesBase = totalFramesWritten
        val headBase = try { (audioTrack?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL } catch (_: Exception) { 0L }

        mainHandler.postDelayed({
            if (sessionAtStart != activeSession || destroyed || isStandby) return@postDelayed
            if (genAtStart != flushGeneration.get()) return@postDelayed
            val framesNow = totalFramesWritten
            val headNow = try { (audioTrack?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL } catch (_: Exception) { 0L }
            val volNow = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 8 }
            if (volNow <= 0) {
                triggerLocalVoiceOutageFailover(ws, turnId, replyText, bossPhone, "MEDIA VOLUME MUTED", allowSmsBackup = false)
            } else if (framesNow <= framesBase) {
                triggerLocalVoiceOutageFailover(ws, turnId, replyText, bossPhone, "NO AUDIO FRAMES RECEIVED", allowSmsBackup = true)
            } else if (headNow <= headBase && remainingHardwareSpeechMs() > 0L) {
                triggerLocalVoiceOutageFailover(ws, turnId, replyText, bossPhone, "AUDIOTRACK PLAYHEAD STALLED", allowSmsBackup = true)
            } else {
                try {
                    ws.send(
                        org.json.JSONObject()
                            .put("type", "voice_playback_ack")
                            .put("turn_id", turnId)
                            .put("heard_ok", true)
                            .put("volume", volNow)
                            .put("reason", "verified_speaker_advance")
                            .toString()
                    )
                } catch (_: Exception) {}
            }
        }, 3200L)
    }

    private fun dispatchGoogleMessageSms(recipientRaw: String, messageBody: String, reqId: String = "latest") {
        val targetNumber = resolvePhoneNumberFromContactName(recipientRaw)
        if (targetNumber.count { it.isDigit() } < 7 || messageBody.isBlank()) {
            Log.w("ThorSMS", "Cannot send SMS: unresolved contact/number for '$recipientRaw'")
            sendSmsAckToServer(reqId, recipientRaw, targetNumber, false, "contact_not_found")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val smsMgr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    getSystemService(android.telephony.SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    android.telephony.SmsManager.getDefault()
                }
                if (smsMgr != null) {
                    val ackAction = "com.example.thormobile.SMS_ACK_${System.nanoTime()}"
                    val ackDone = java.util.concurrent.atomic.AtomicBoolean(false)
                    val ackReceiver = object : android.content.BroadcastReceiver() {
                        override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
                            if (ackDone.compareAndSet(false, true)) {
                                try { unregisterReceiver(this) } catch (_: Exception) {}
                                val sentOk = (resultCode == android.app.Activity.RESULT_OK)
                                val st = if (sentOk) "carrier_radio_sent" else "carrier_radio_error_$resultCode"
                                Log.i("ThorSMS", "Carrier SMS ACK ($targetNumber): $st")
                                sendSmsAckToServer(reqId, recipientRaw, targetNumber, sentOk, st)
                            }
                        }
                    }
                    ContextCompat.registerReceiver(
                        this,
                        ackReceiver,
                        android.content.IntentFilter(ackAction),
                        ContextCompat.RECEIVER_EXPORTED
                    )
                    mainHandler.postDelayed({
                        if (ackDone.compareAndSet(false, true)) {
                            try { unregisterReceiver(ackReceiver) } catch (_: Exception) {}
                        }
                    }, 15_000L)

                    val piFlags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    val piIntent = Intent(ackAction).setPackage(packageName)
                    val sentPi = android.app.PendingIntent.getBroadcast(this, (System.nanoTime() and 0x7FFFFFFF).toInt(), piIntent, piFlags)
                    val parts = smsMgr.divideMessage(messageBody)
                    if (parts != null && parts.size > 1) {
                        val sentIntents = java.util.ArrayList<android.app.PendingIntent>(parts.size)
                        for (idx in 0 until parts.size) {
                            if (idx == parts.size - 1 && sentPi != null) {
                                sentIntents.add(sentPi)
                            } else {
                                val noopIntent = Intent("com.example.thormobile.SMS_PART_NOOP_$idx").setPackage(packageName)
                                val noopPi = android.app.PendingIntent.getBroadcast(this, idx + 1000, noopIntent, piFlags)
                                if (noopPi != null) sentIntents.add(noopPi)
                            }
                        }
                        smsMgr.sendMultipartTextMessage(targetNumber, null, parts, sentIntents, null)
                    } else {
                        smsMgr.sendTextMessage(targetNumber, null, messageBody, sentPi, null)
                    }
                    Log.i("ThorSMS", "Dispatched background carrier SMS (${parts?.size ?: 1} part(s)) to $targetNumber")
                    return
                }
            } catch (e: Exception) {
                Log.w("ThorSMS", "Direct SmsManager threw, falling back to composer: ${e.message}")
            }
        }
        try {
            val smsUri = android.net.Uri.parse("smsto:" + android.net.Uri.encode(targetNumber))
            val i = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                putExtra("sms_body", messageBody)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
            sendSmsAckToServer(reqId, recipientRaw, targetNumber, false, "opened_composer_permission_needed")
        } catch (e: Exception) {
            Log.e("ThorSMS", "SMS composer intent failed", e)
            sendSmsAckToServer(reqId, recipientRaw, targetNumber, false, "sms_failed_${e.javaClass.simpleName}")
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun handleOverlayTap() {
        if (activeSession != 0) {
            if (playbackBusy || playbackQueue.isNotEmpty() || SystemClock.elapsedRealtime() - lastPlaybackTime < 500L) {
                flushPlayback()
                try { webSocket?.send("INTERRUPT") } catch (_: Exception) {}
            } else {
                toast("Listening... (Double-tap to hang up)")
            }
        } else if (hasMicPermission()) {
            startSession(keepPreRoll = false)
        } else {
            toast("Microphone permission required")
        }
    }

    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun loadWakeWordAsync() {
        Thread({
            try {
                fun asset(name: String): ByteArray = assets.open("$ASSET_DIR/$name").use { it.readBytes() }
                val models = WakeWordModels(
                    asset("melspectrogram.onnx"), asset("embedding_model.onnx"), asset("thor.onnx")
                )
                if (destroyed) {
                    models.close()
                    return@Thread
                }
                detectorModels = models
                detector = WakeWordDetector(models, threshold = WAKE_THRESHOLD)
                if (hasMicPermission() && !isStandby) {
                    synchronized(lock) { ensureCapture() }
                    mainHandler.post { refreshNotification() }
                }
            } catch (e: Exception) {
                if (hasMicPermission() && !isStandby) {
                    synchronized(lock) { ensureCapture() }
                }
            }
        }, "ThorWakeLoad").start()
    }

    private fun startSession(keepPreRoll: Boolean) {
        synchronized(lock) {
            if (activeSession != 0 || destroyed || isStandby || !hasMicPermission()) return
            ensureCapture()
            if (captureThread == null) return
            if (!keepPreRoll) preRoll.clear()
            requestMicAudioFocus()
            startPlayback()
            endingSession = false
            socketReady = false
                offlineModeActive = false
                valleyReconnectAttempts = 0
            turnSpeechDetected = false
            userSpokeInActiveSession = false
            awaitingThorReply = false
            awaitingReplySinceMs = 0L
            lastPcmChunkArrivalMs = 0L
            socketDroppedDuringTurn = false
            waitingForCloudBurst = false
            currentTurnPcm.reset()
            val id = sessionSeq.incrementAndGet()
            activeSession = id
            micSettling = true
            mainHandler.postDelayed({ openBridgeSocket(id) }, MIC_SETTLE_DELAY_MS)
        }
        applyVisualState()
        mainHandler.post {
            fireArtworkLightningCascade(durationMs = 450L)
            refreshNotification()
        }
    }

    private fun enterOfflineLocalSession(id: Int) {
        synchronized(lock) {
            if (id != activeSession || destroyed) return
            micSettling = false
            socketReady = false
            offlineModeActive = true
            offlineSpeechDetected = false
            val now = SystemClock.elapsedRealtime()
            offlineStartedMs = now
            offlineLastVoiceMs = now
            offlineTurnBuffer.reset()
            preRoll.drain { chunk ->
                offlineTurnBuffer.write(chunk, 0, chunk.size)
                val ts = System.currentTimeMillis()
                outboxExecutor.execute {
                    try { db.outboxDao().insertAudio(OutboxAudio(timestamp = ts, audioData = chunk)) } catch (_: Exception) {}
                }
            }
        }
        Log.i(TAG_AUDIO, "Entered Offline Local Brain session (session=$id)")
        mainHandler.post { toast("Offline Mode: Gemma 4 Local Brain listening...") }
    }

    private fun openBridgeSocket(id: Int) {
        if (!networkSentinel.isConnected && valleyReconnectAttempts >= 6) {
            valleyReconnectAttempts = 0
            enterOfflineLocalSession(id)
            return
        }
        synchronized(lock) {
            if (id != activeSession || destroyed) return
            micSettling = false
            val targetUrl = BRIDGE_URL
            val request = Request.Builder()
                .url(targetUrl)
                .addHeader("ngrok-skip-browser-warning", "true")
                .build()
            webSocket = okHttpClient.newWebSocket(request, BridgeListener(id))
        }
    }

    private fun stopCaptureAndReleaseMic() {
        var threadToJoin: Thread? = null
        var recordToRelease: AudioRecord? = null
        synchronized(lock) {
            captureToken.incrementAndGet()
            threadToJoin = captureThread
            captureThread = null
            recordToRelease = audioRecord
            audioRecord = null
            preRoll.clear()
            currentTurnPcm.reset()
            turnSpeechDetected = false
        }
        try { threadToJoin?.interrupt() } catch (_: Exception) {}
        releaseCapture(recordToRelease)
        try { powerGuard.releaseWakeLock() } catch (_: Exception) {}
    }

    private fun stopStreamingAudio() {
        var socketToClose: WebSocket? = null
        synchronized(lock) {
            if (activeSession == 0) {
                abandonMicAudioFocus()
                return
            }
            activeSession = 0
            socketReady = false
            endingSession = false
            micSettling = false
            socketToClose = webSocket
            webSocket = null
            preRoll.clear()
            currentTurnPcm.reset()
            turnSpeechDetected = false
            detectorResetRequested = true
            stopPlayback()
            // Immediately release AudioFocus so YouTube / Music resumes playing automatically!
            abandonMicAudioFocus()
        }
        if (isStandby) {
            stopCaptureAndReleaseMic()
        }
        socketToClose?.close(1000, "Client disconnect")
        applyVisualState()
        mainHandler.post { refreshNotification() }
    }

    private fun beginEndDrain(sessionId: Int) {
        synchronized(lock) {
            if (sessionId != activeSession || endingSession) return
            endingSession = true
        }
        val deadline = SystemClock.elapsedRealtime() + END_DRAIN_MAX_MS
        var quietChecks = 0
        mainHandler.post(object : Runnable {
            override fun run() {
                if (sessionId != activeSession) return
                val quiet = playbackQueue.isEmpty() && !playbackBusy && remainingHardwareSpeechMs() <= 0L
                quietChecks = if (quiet) quietChecks + 1 else 0
                when {
                    quietChecks >= 3 -> mainHandler.postDelayed({
                        if (sessionId == activeSession) stopStreamingAudio()
                    }, END_DRAIN_TAIL_MS)
                    SystemClock.elapsedRealtime() >= deadline -> stopStreamingAudio()
                    else -> mainHandler.postDelayed(this, 100)
                }
            }
        })
    }

    private fun launchUrlOrYouTubeOnPhone(rawUrl: String, ytVideoId: String = "") {
        var cleanUrl = rawUrl.trim()
        if (cleanUrl.isEmpty() && ytVideoId.isNotBlank()) {
            cleanUrl = "https://www.youtube.com/watch?v=${ytVideoId.trim()}"
        }
        if (cleanUrl.isEmpty()) return
        if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
            cleanUrl = "https://$cleanUrl"
        }
        mainHandler.post {
            val isYt = ytVideoId.isNotBlank() ||
                cleanUrl.contains("youtube.com", ignoreCase = true) ||
                cleanUrl.contains("youtu.be", ignoreCase = true)
            if (isYt) {
                try {
                    val ytIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(cleanUrl)).apply {
                        setPackage("com.google.android.youtube")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(ytIntent)
                    Log.i("ThorBridge", "Launched YouTube app: $cleanUrl")
                    return@post
                } catch (_: Exception) {}
            }
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(cleanUrl)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(webIntent)
                Log.i("ThorBridge", "Launched browser URL: $cleanUrl")
            } catch (e: Exception) {
                Log.e("ThorBridge", "Failed to open URL: $cleanUrl", e)
            }
        }
    }

    private inner class BridgeListener(private val sessionId: Int) : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            synchronized(lock) {
                if (sessionId != activeSession) {
                    ws.close(1000, "Stale session")
                    return
                }
                preRoll.drain { ws.send(it.toByteString(0, it.size)) }
                socketReady = true
                val lat = lastKnownLat
                val lon = lastKnownLon
                if (lat != null && lon != null) {
                    try {
                        ws.send(
                                org.json.JSONObject()
                                    .put("type", "gps_update")
                                    .put("lat", lat)
                                    .put("lon", lon)
                                    .put("speed_mph", Math.round(lastKnownSpeedMph * 10.0) / 10.0)
                                    .put("heading", Math.round(lastKnownHeading))
                                    .put("sms_recent", readRecentGoogleMessagesJson())
                                    .toString()
                            )
                    } catch (e: Exception) {
                        Log.e(TAG_WS, "Failed to send initial gps_update on connect", e)
                    }
                }
            }
            flushOutbox()
        }

        override fun onMessage(ws: WebSocket, text: String) {
            if (sessionId != activeSession) return

            if (text == END_SESSION_TEXT) {
                beginEndDrain(sessionId)
                return
            }

            try {
                val json = org.json.JSONObject(text)
                if (json.optString("action") == "thinking_heartbeat") {
                    valleyReconnectAttempts = 0
                    lastPlaybackTime = SystemClock.elapsedRealtime()
                    mainHandler.post {
                        if (!destroyed && !isStandby && activeSession != 0 && boltAnimator?.isRunning != true) {
                            fireArtworkLightningCascade(durationMs = 360L)
                        }
                    }
                    return
                }
                if (json.optString("action") == "rearm_mic") {
                    synchronized(lock) {
                        if (sessionId == activeSession) {
                            endingSession = false
                            waitingForCloudBurst = false
                        }
                    }
                    if (activeSession == 0 && !destroyed && !isStandby && hasMicPermission()) {
                        startSession(keepPreRoll = true)
                    } else {
                        applyVisualState()
                    }
                    return
                }
                if (json.optString("action") == "request_sms_sync") {
                        try {
                            ws.send(org.json.JSONObject().put("type", "sms_sync").put("messages", readRecentGoogleMessagesJson()).toString())
                        } catch (_: Exception) {}
                    }
                if (json.optString("action") == "thinking_complete") {
                    lastPlaybackTime = SystemClock.elapsedRealtime()
                    return
                }
                if (json.optString("action") == "voice_turn_envelope") {
                    awaitingThorReply = false
                    scheduleVoiceTurnClosedLoopCheck(
                            ws = ws,
                            sessionAtStart = sessionId,
                            turnId = json.optString("turn_id", "vt_${System.currentTimeMillis()}"),
                            replyText = json.optString("reply_text"),
                            bossPhone = json.optString("boss_phone")
                        )
                    }
                    if (json.optString("action") == "voice_turn_server_failed") {
                        triggerLocalVoiceOutageFailover(
                            ws = ws,
                            turnId = json.optString("turn_id", "vt_${System.currentTimeMillis()}"),
                            replyText = json.optString("reply_text"),
                            bossPhone = json.optString("boss_phone"),
                            reasonLabel = "CLOUD TTS OFFLINE",
                            allowSmsBackup = true
                        )
                    }
                    if (json.optString("action") == "raven_card") {
                        VikingPopupOverlayManager.showRavenCard(
                            context = this@FloatingThorService,
                            serverBaseUrl = "https://" + "thor-api-443941171993.us-east4.run.app",
                            title = json.optString("title", "Raven Dispatch"),
                            message = json.optString("message", ""),
                            linksJson = json.optJSONArray("links")
                        )
                    }
                    if (json.optString("action") == "open_url") {
                        launchUrlOrYouTubeOnPhone(
                            rawUrl = json.optString("url", ""),
                            ytVideoId = json.optString("youtube_video_id", "")
                        )
                    }
                    if (json.optString("action") == "upgrade_progress_event") {
                        val directPop = json.optJSONObject("reply_popup")
                        if (directPop != null) {
                            VikingPopupOverlayManager.showRavenCard(
                                context = this@FloatingThorService,
                                serverBaseUrl = "https://" + "thor-api-443941171993.us-east4.run.app",
                                title = directPop.optString("title", "Raven Dispatch"),
                                message = directPop.optString("body", ""),
                                linksJson = directPop.optJSONArray("links")
                            )
                        } else {
                            VikingPopupOverlayManager.pollNow(this@FloatingThorService, "https://" + "thor-api-443941171993.us-east4.run.app")
                        }
                    }
                    if (json.optString("action") == "phone_intent") {
                    val intentType = json.optString("intent_type")
                    val label = json.optString("label", "Thor")
                    when (intentType) {
                            "send_sms" -> {
                                val recip = json.optString("recipient")
                                val body = json.optString("message_body")
                                val reqId = json.optString("req_id", "latest")
                                dispatchGoogleMessageSms(recip, body, reqId)
                            }
                            "timer" -> {
                            val secs = json.optInt("seconds", 60)
                            val i = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                                putExtra(android.provider.AlarmClock.EXTRA_LENGTH, secs)
                                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try { startActivity(i) } catch (e: Exception) { Log.e("ThorBridge", "Timer intent failed", e) }
                        }
                        "alarm" -> {
                            val hr = json.optInt("hour", 7)
                            val min = json.optInt("minute", 0)
                            val i = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hr)
                                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, min)
                                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try { startActivity(i) } catch (e: Exception) { Log.e("ThorBridge", "Alarm intent failed", e) }
                        }
                        "dial" -> {
                            val num = json.optString("phone_number")
                            if (num.isNotEmpty()) {
                                val i = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + android.net.Uri.encode(num))).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                try { startActivity(i) } catch (e: Exception) { Log.e("ThorBridge", "Dial intent failed", e) }
                            }
                        }
                    }
                }
                if (json.optString("action") == "launch_maps") {
                    val dest = json.optString("destination")
                    val waypointsRaw = json.optString("waypoints", "").trim()
                    if (dest.isNotEmpty()) {
                        val gmmIntentUri = if (waypointsRaw.isNotEmpty()) {
                            android.net.Uri.parse(
                                "https://www.google.com/maps/dir/?api=1" +
                                "&destination=" + android.net.Uri.encode(dest) +
                                "&waypoints=" + android.net.Uri.encode(waypointsRaw) +
                                "&travelmode=driving&dir_action=navigate"
                            )
                        } else {
                            android.net.Uri.parse("google.navigation:q=" + android.net.Uri.encode(dest))
                        }

                        val mapIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, gmmIntentUri)
                        mapIntent.setPackage("com.google.android.apps.maps")
                        mapIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

                        try {
                            startActivity(mapIntent)
                            android.util.Log.i("ThorBridge", "Launching Google Maps to: $dest (waypoints=$waypointsRaw)")
                        } catch (e: Exception) {
                            android.util.Log.e("ThorBridge", "Google Maps app not found.", e)
                            val fallbackUri = android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(dest))
                            val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, fallbackUri)
                            fallbackIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(fallbackIntent)
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            if (sessionId != activeSession) return
            if (bytes == FLUSH_MARKER) {
                lastPcmChunkArrivalMs = 0L
                flushPlayback()
            } else {
                awaitingThorReply = false
                val nowArr = SystemClock.elapsedRealtime()
                val prevArr = lastPcmChunkArrivalMs
                lastPcmChunkArrivalMs = nowArr
                if (prevArr > 0L) {
                    val gapMs = nowArr - prevArr
                    if (gapMs in 15L..950L) {
                        val dev = kotlin.math.abs(gapMs.toDouble() - 260.0)
                        cellularJitterEwmaMs = (0.78 * cellularJitterEwmaMs + 0.22 * dev).coerceIn(15.0, 260.0)
                    }
                }
                playbackQueue.add(PcmChunk(flushGeneration.get(), bytes.toByteArray()))
            }
        }
        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            try { ws.close(1000, null) } catch (_: Exception) {}
            if (sessionId == activeSession) {
                synchronized(lock) {
                    socketReady = false
                    if (webSocket === ws) webSocket = null
                }
                if (endingSession || playbackQueue.isNotEmpty() || playbackBusy || remainingHardwareSpeechMs() > 0L) {
                    beginEndDrain(sessionId)
                } else {
                    stopStreamingAudio()
                }
            }
        }
        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            if (sessionId == activeSession) {
                val stillWaitingOnThor = awaitingThorReply && !endingSession && !userRequestedStop && !isStandby && canAutoResumeMic
                synchronized(lock) {
                    socketReady = false
                    if (webSocket === ws) webSocket = null
                }
                if (stillWaitingOnThor) {
                    Log.w(TAG_WS, "WO-139: Socket closed while Thor was still thinking -> keeping mic open & reconnecting!")
                    VikingPopupOverlayManager.enableFastReplyWatchPoll(
                        this@FloatingThorService,
                        "https://" + "thor-api-443941171993.us-east4.run.app",
                        45_000L
                    )
                    mainHandler.postDelayed({
                        if (sessionId == activeSession && !socketReady && !destroyed && !isStandby && !userRequestedStop) {
                            openBridgeSocket(sessionId)
                        }
                    }, 650L)
                } else if (endingSession || playbackQueue.isNotEmpty() || playbackBusy || remainingHardwareSpeechMs() > 0L) {
                    beginEndDrain(sessionId)
                } else {
                    stopStreamingAudio()
                }
            }
        }
        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    val wasReady = socketReady
                    Log.w(TAG_WS, "Hill-Hopper WebSocket dip (wasReady=$wasReady, attempts=$valleyReconnectAttempts): ${t.message}")
                    if (sessionId == activeSession && !destroyed && !isStandby) {
                        synchronized(lock) {
                            socketReady = false
                            webSocket = null
                            socketDroppedDuringTurn = true
                            valleyReconnectAttempts += 1
                        }
                        try { ws.cancel() } catch (_: Exception) {}

                        // HILL-HOPPER VALLEY RECOVERY:
                        // Keep activeSession & microphone capture running into preRoll for up to 12 reconnect attempts (~14 seconds)
                        // so when Boss crests the next hill, preRoll bursts the entire uninterrupted sentence to thor-api!
                        if (valleyReconnectAttempts <= 12) {
                            val retryDelayMs = if (wasReady) 800L else 1100L
                            mainHandler.postDelayed({
                                if (sessionId == activeSession && !socketReady && !offlineModeActive && !destroyed && !isStandby) {
                                    openBridgeSocket(sessionId)
                                }
                            }, retryDelayMs)
                    } else {
                        // Extended connection loss mid-session -> notify Boss via Raven Card & fast-poll for pending resume when signal returns
                        val wasWaitingReply = awaitingThorReply
                        val notifyDrop = wasWaitingReply || userSpokeInActiveSession || socketDroppedDuringTurn
                        valleyReconnectAttempts = 0
                        if (wasWaitingReply) {
                            VikingPopupOverlayManager.enableFastReplyWatchPoll(
                                this@FloatingThorService,
                                "https://" + "thor-api-443941171993.us-east4.run.app",
                                120_000L
                            )
                        }
                        if (notifyDrop) {
                            val dropTitle = if (wasWaitingReply) "Thor Still Thinking — Signal Lost" else "Voice Line Interrupted"
                            val dropBody = if (wasWaitingReply) {
                                "Cell signal dropped before Thor finished answering. Thor saved your reply and will automatically re-open the mic & send a Raven as soon as signal returns."
                            } else {
                                "Cloud voice link dropped in weak coverage — switched to local standby. Tap Thor whenever you want to continue."
                            }
                            mainHandler.post {
                                VikingPopupOverlayManager.showRavenCard(
                                    context = this@FloatingThorService,
                                    serverBaseUrl = "https://" + "thor-api-443941171993.us-east4.run.app",
                                    title = dropTitle,
                                    message = dropBody,
                                    linksJson = null
                                )
                            }
                        }
                        enterOfflineLocalSession(sessionId)
                    }
                    }
            }
    }

    private fun flushOutbox() {
        outboxExecutor.execute {
            try {
                val pending = db.outboxDao().getPendingAudio()
                if (pending.isNotEmpty()) {
                    // STORAGE AUTO-PURGE GUARD: Immediately delete any item older than 24h or beyond the newest 20 items
                    val cutoffMs = System.currentTimeMillis() - (24L * 3600L * 1000L)
                    if (pending.size > 20) {
                        for (oldItem in pending.dropLast(20)) {
                            try { db.outboxDao().deleteAudio(oldItem) } catch (_: Exception) {}
                        }
                    }
                    for (item in pending) {
                        if (item.timestamp < cutoffMs) {
                            try { db.outboxDao().deleteAudio(item) } catch (_: Exception) {}
                        }
                    }
                    Log.d(TAG_WS, "Flushing ${pending.size} offline chunks to Thor...")
                    var targetWs: WebSocket? = null
                    synchronized(lock) { targetWs = webSocket }

                    if (targetWs != null && socketReady) {
                        for (audio in pending) {
                            targetWs!!.send(audio.audioData.toByteString(0, audio.audioData.size))
                            db.outboxDao().deleteAudio(audio)
                        }
                        Log.d(TAG_WS, "Offline memory sync complete.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG_WS, "Error flushing Outbox", e)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestMicAudioFocus() {
        if (hasAudioFocus) return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(audioFocusListener, mainHandler)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @Suppress("DEPRECATION")
    private fun abandonMicAudioFocus() {
        if (!hasAudioFocus && audioFocusRequest == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            audioManager.abandonAudioFocus(audioFocusListener)
        }
        audioFocusRequest = null
        hasAudioFocus = false
    }

    private fun ensureCapture() {
        if (captureThread != null || destroyed || !hasMicPermission() || isStandby) return
        val record = createAudioRecord() ?: return

        audioRecord = record
        powerGuard.acquireWakeLock()
        val token = captureToken.incrementAndGet()
        val thread = Thread({ captureLoop(token, record) }, "ThorCapture")
        captureThread = thread
        thread.start()
    }

    @Suppress("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minBuffer > 0) maxOf(minBuffer, CAPTURE_RING_BYTES) else CAPTURE_RING_BYTES
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                CAPTURE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
        } catch (e: Exception) { return null }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }

        sharedAudioSessionId = record.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(record.audioSessionId)
            aec?.enabled = true
            Log.i(TAG_AUDIO, "Acoustic Echo Canceler natively bound to session ${record.audioSessionId}")
        } else {
            Log.w(TAG_AUDIO, "Acoustic Echo Canceler not available on this device.")
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)
            noiseSuppressor?.enabled = true
            Log.i(TAG_AUDIO, "Hardware NoiseSuppressor natively bound to session ${record.audioSessionId}")
        } else {
            Log.w(TAG_AUDIO, "Hardware NoiseSuppressor not available on this device.")
        }

        return record
    }

    private fun captureLoop(token: Int, record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var failed = false
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                failed = true
                return
            }
            val frame = ByteArray(CAPTURE_FRAME_BYTES)
            val shorts = ShortArray(CAPTURE_FRAME_BYTES / 2)
            var droppedFrames = 0

            while (token == captureToken.get()) {
                val read = record.read(frame, 0, frame.size)
                if (read < 0) {
                    failed = true
                    break
                }
                if (read == 0) continue

                // V26 FULL-DUPLEX HARDWARE AEC +VOCAL-BAND FILTER + SUB-25MS LOCAL BARGE-IN
                var vocalSumSq = 0.0
                val scCount = read / 2
                var prevSample = 0.0
                for (i in 0 until scCount) {
                    val curSample = ((frame[2 * i + 1].toInt() shl 8) or (frame[2 * i].toInt() and 0xFF)).toShort().toDouble()
                    // First-order 300Hz+ vocal high-pass difference filter strips sub-250Hz road/heater hum
                    val hpDiff = curSample - (0.86 * prevSample)
                    prevSample = curSample
                    vocalSumSq += hpDiff * hpDiff
                }
                val vocalRms = if (scCount > 0) kotlin.math.sqrt(vocalSumSq / scCount) else 0.0
                val nowPlaybackMs = SystemClock.elapsedRealtime()
                val curVol = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 8 }
                val maxVol = try { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) } catch (_: Exception) { 15 }
                val volRatio = curVol.toDouble() / maxVol.toDouble()
                if (lastKnownMusicVol != -1 && curVol != lastKnownMusicVol) {
                    volChangedCooldownUntilMs = nowPlaybackMs + 1000L
                    bargeRecordingActive = false
                    bargePcmBuffer.reset()
                    localBargeInStreak = 0
                }
                lastKnownMusicVol = curVol

                if (nowPlaybackMs - lastOutputDevCheckMs >= 2000L) {
                    lastOutputDevCheckMs = nowPlaybackMs
                    cachedExternalAudioOut = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                    it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                    it.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                                    it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                            }
                        } else false
                    } catch (_: Exception) { false }
                }

                val remHwMs = remainingHardwareSpeechMs()
                if (playbackBusy || playbackQueue.isNotEmpty() || remHwMs > 0L) {
                    lastHardwareSpeechActiveMs = nowPlaybackMs
                }
                val inPlaybackWindow = playbackBusy || playbackQueue.isNotEmpty() || remHwMs > 0L || (nowPlaybackMs - lastHardwareSpeechActiveMs < 420L)
                val canBargeNow = playbackQueue.isNotEmpty() || remHwMs > 240L

                if (inPlaybackWindow) {
                    // Calibrate live speaker echo on first 350ms & volume-key presses; never blindly flush AudioTrack on speaker bleed!
                    val inCalibOrVolGrace = (nowPlaybackMs < volChangedCooldownUntilMs) || (nowPlaybackMs - playbackStartedAtMs < 350L)
                    val bargeTrig = if (cachedExternalAudioOut) {
                        kotlin.math.max(420.0, echoBaselineRms * 1.65 + 220.0)
                    } else {
                        kotlin.math.max(960.0 + volRatio * 600.0, echoBaselineRms * 1.90 + 400.0)
                    }

                    if (inCalibOrVolGrace || !canBargeNow) {
                        if (inCalibOrVolGrace) {
                            echoBaselineRms = 0.80 * echoBaselineRms + 0.20 * kotlin.math.min(vocalRms, 2600.0)
                        }
                        localBargeInStreak = 0
                    } else if (vocalRms >= bargeTrig) {
                        localBargeInStreak += 1
                        if (localBargeInStreak >= 6 && !bargeRecordingActive && (nowPlaybackMs - lastBargeCheckSentMs) >= 1800L) {
                            bargeRecordingActive = true
                            bargeStartedMs = nowPlaybackMs
                            bargeLastVoiceMs = nowPlaybackMs
                            bargePcmBuffer.reset()
                        }
                        if (bargeRecordingActive) {
                            bargeLastVoiceMs = nowPlaybackMs
                        }
                    } else {
                        localBargeInStreak = 0
                        if (!bargeRecordingActive) {
                            echoBaselineRms = 0.92 * echoBaselineRms + 0.08 * kotlin.math.min(vocalRms, 2400.0)
                        }
                    }

                    if (bargeRecordingActive) {
                        if (bargePcmBuffer.size() < 48000) {
                            bargePcmBuffer.write(frame, 0, read)
                        }
                        val quietSinceBargeVoice = nowPlaybackMs - bargeLastVoiceMs
                        val totalBargeMs = nowPlaybackMs - bargeStartedMs
                        if ((bargePcmBuffer.size() >= 12800 && quietSinceBargeVoice >= 420L) || totalBargeMs >= 1350L) {
                            val clipBytes = bargePcmBuffer.toByteArray()
                            bargeRecordingActive = false
                            bargePcmBuffer.reset()
                            lastBargeCheckSentMs = nowPlaybackMs
                            val wsNow = webSocket
                            if (wsNow != null && socketReady && clipBytes.size >= 12800) {
                                try {
                                    val b64 = android.util.Base64.encodeToString(clipBytes, android.util.Base64.NO_WRAP)
                                    val msgJson = org.json.JSONObject()
                                        .put("type", "barge_in_check")
                                        .put("pcm_b64", b64)
                                        .toString()
                                    wsNow.send(msgJson)
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    synchronized(lock) {
                        preRoll.clear()
                        currentTurnPcm.reset()
                        turnSpeechDetected = false
                    }
                    continue
                } else if (bargeRecordingActive) {
                    bargeRecordingActive = false
                    bargePcmBuffer.reset()
                }

                var target: WebSocket? = null
                var listening = false
                var shouldSaveToOutbox = false

                synchronized(lock) {
                    when {
                        activeSession == 0 -> {
                            preRoll.add(frame.copyOf(read))
                            preRoll.trimTo(PRE_ROLL_IDLE_MAX_BYTES)
                            listening = true
                        }
                        endingSession -> Unit
                        micSettling -> Unit
                        socketReady && networkSentinel.isConnected -> target = webSocket
                        !socketReady && networkSentinel.isConnected -> {
                            preRoll.add(frame.copyOf(read))
                            preRoll.trimTo(PRE_ROLL_CONNECT_MAX_BYTES)
                        }
                        else -> {
                            shouldSaveToOutbox = true
                        }
                    }
                }

                if (activeSession != 0 && !offlineModeActive && !endingSession) {
                    val pcmCopy = frame.copyOf(read)
                    val nowMs = SystemClock.elapsedRealtime()
                    val dynOnsetThresh = kotlin.math.max(210.0, kotlin.math.min(430.0, localNoiseFloorRms + 130.0))
                    val dynSustainThresh = kotlin.math.max(135.0, kotlin.math.min(290.0, localNoiseFloorRms + 65.0))
                    val dynSpeechThresh = if (turnSpeechDetected) dynSustainThresh else dynOnsetThresh
                    if (!inPlaybackWindow && vocalRms >= dynSpeechThresh) {
                        if (!turnSpeechDetected) {
                            turnSpeechDetected = true
                            userSpokeInActiveSession = true
                            currentTurnPcm.reset()
                        }
                        turnLastVoiceMs = nowMs
                    } else if (!turnSpeechDetected && !inPlaybackWindow) {
                        localNoiseFloorRms = (0.92 * localNoiseFloorRms) + (0.08 * kotlin.math.min(vocalRms, 320.0))
                        ambientCabinNoiseRms = (0.90 * ambientCabinNoiseRms) + (0.10 * kotlin.math.min(vocalRms, 1200.0))
                    }
                    // Hard 480KB (15s) cap on currentTurnPcm in RAM; auto-wiped the moment turn finishes
                    if (turnSpeechDetected && currentTurnPcm.size() < 480000) {
                        currentTurnPcm.write(pcmCopy, 0, pcmCopy.size)
                    }
                    val turnBytes = currentTurnPcm.size()
                    val requiredSilenceMs = when {
                        turnBytes < 22400 -> 1350L
                        turnBytes <= 96000 -> 1700L
                        else -> 1700L
                    }
                    if (turnSpeechDetected && (nowMs - turnLastVoiceMs) >= requiredSilenceMs && turnBytes >= 9600) {
                        if (target != null && !socketDroppedDuringTurn) {
                            try {
                                val noiseMsg = org.json.JSONObject()
                                    .put("type", "ambient_noise")
                                    .put("noise_rms", Math.round(ambientCabinNoiseRms * 10.0) / 10.0)
                                    .toString()
                                target!!.send(noiseMsg)
                            } catch (_: Exception) {}
                            awaitingThorReply = true
                            awaitingReplySinceMs = nowMs
                            VikingPopupOverlayManager.enableFastReplyWatchPoll(
                                this@FloatingThorService,
                                "https://" + "thor-api-443941171993.us-east4.run.app",
                                40_000L
                            )
                            try { target!!.send("END_OF_TURN") } catch (_: Exception) {}
                            turnSpeechDetected = false
                            currentTurnPcm.reset()
                        } else if (!waitingForCloudBurst) {
                            waitingForCloudBurst = true
                            val turnSnapshot = currentTurnPcm.toByteArray()
                            val snapSession = activeSession
                            mainHandler.postDelayed({
                                if (waitingForCloudBurst && snapSession == activeSession && !socketReady && !offlineModeActive) {
                                    waitingForCloudBurst = false
                                    turnSpeechDetected = false
                                    currentTurnPcm.reset()
                                    val ts = System.currentTimeMillis()
                                    outboxExecutor.execute {
                                        try { db.outboxDao().insertAudio(OutboxAudio(timestamp = ts, audioData = turnSnapshot)) } catch (_: Exception) {}
                                    }
                                    offlineModeActive = true
                                    localBrain.processOfflineVoiceTurn(
                                        pcm16kMono = turnSnapshot,
                                        onStartSpeaking = { playbackBusy = true },
                                        onFinishedSpeaking = {
                                            playbackBusy = false
                                            offlineModeActive = false
                                            lastPlaybackTime = SystemClock.elapsedRealtime()
                                            mainHandler.post { stopStreamingAudio() }
                                        }
                                    )
                                }
                            }, 3800L)
                        }
                    }
                }

                if (target != null) {
                    if (target!!.queueSize() <= MAX_SEND_QUEUE_BYTES) {
                        target!!.send(frame.toByteString(0, read))
                    } else {
                        socketDroppedDuringTurn = true
                        droppedFrames++
                        if (droppedFrames % 100 == 1) {
                            Log.w(TAG_AUDIO, "Uplink stalled: send queue over cap, marked turn for burst recovery.")
                        }
                    }
                } else if (shouldSaveToOutbox) {
                    val chunk = frame.copyOf(read)
                    val ts = System.currentTimeMillis()
                    // Per-20ms frame DB spam disabled; complete turn is saved once when utterance completes
                        if (offlineModeActive) {
                            offlineTurnBuffer.write(chunk, 0, chunk.size)
                            var sumSq = 0.0
                            val sampleCount = read / 2
                            for (i in 0 until sampleCount) {
                                val s = ((chunk[2 * i + 1].toInt() shl 8) or (chunk[2 * i].toInt() and 0xFF)).toShort().toDouble()
                                sumSq += s * s
                            }
                            val rms = if (sampleCount > 0) kotlin.math.sqrt(sumSq / sampleCount) else 0.0
                            val now = SystemClock.elapsedRealtime()
                            if (rms > 320.0) {
                                offlineSpeechDetected = true
                                offlineLastVoiceMs = now
                            }
                            val silenceMs = now - offlineLastVoiceMs
                            val totalMs = now - offlineStartedMs
                            if ((offlineSpeechDetected && offlineTurnBuffer.size() >= 16000 && silenceMs >= 1300L) || offlineTurnBuffer.size() >= 384000) {
                                offlineModeActive = false
                                val recordedPcm = offlineTurnBuffer.toByteArray()
                                offlineTurnBuffer.reset()
                                Log.i(TAG_AUDIO, "Dispatching ${recordedPcm.size} PCM bytes to Gemma 4 Local Brain.")
                                    outboxExecutor.execute {
                                        try { db.outboxDao().insertAudio(OutboxAudio(timestamp = System.currentTimeMillis(), audioData = recordedPcm)) } catch (_: Exception) {}
                                    }
                                localBrain.processOfflineVoiceTurn(
                                    pcm16kMono = recordedPcm,
                                    onStartSpeaking = {
                                        playbackBusy = true
                                    },
                                    onFinishedSpeaking = {
                                        playbackBusy = false
                                        lastPlaybackTime = SystemClock.elapsedRealtime()
                                        mainHandler.post { stopStreamingAudio() }
                                    }
                                )
                            } else if (!offlineSpeechDetected && totalMs >= 6500L) {
                                offlineModeActive = false
                                offlineTurnBuffer.reset()
                                mainHandler.post { stopStreamingAudio() }
                            }
                        }
                } else if (listening) {
                    runWakeWord(shorts, frame, read)
                }
            }
        } catch (e: Exception) {
            failed = true
        } finally {
            var current = false
            synchronized(lock) {
                if (token == captureToken.get()) {
                    current = true
                    captureThread = null
                    audioRecord = null
                    powerGuard.releaseWakeLock()
                }
            }
            releaseCapture(record)

            if (current && failed && !destroyed && !isStandby) {
                Log.w(TAG_AUDIO, "Microphone preempted. Entering Auto-Standby.")
                mainHandler.post {
                    if (!isStandby) toggleStandby()
                    VikingPopupOverlayManager.showRavenCard(
                        context = this@FloatingThorService,
                        serverBaseUrl = "https://" + "thor-api-443941171993.us-east4.run.app",
                        title = "Microphone Paused",
                        message = "Android temporarily claimed the microphone. Double-tap the red Thor ring to wake the mic back up.",
                        linksJson = null
                    )
                }
            }
        }
    }

    private fun runWakeWord(shorts: ShortArray, frame: ByteArray, read: Int) {
        val det = detector ?: return
        if (detectorResetRequested) {
            det.reset()
            detectorResetRequested = false
        }
        val n = read / 2
        for (i in 0 until n) {
            shorts[i] = ((frame[2 * i + 1].toInt() shl 8) or (frame[2 * i].toInt() and 0xFF)).toShort()
        }
        val triggered = try {
            det.process(shorts, n)
        } catch (e: Exception) {
            detector = null
            mainHandler.post { refreshNotification() }
            false
        }
        if (triggered) startSession(keepPreRoll = true)
    }

    private fun releaseCapture(record: AudioRecord?) {
        try { aec?.release()
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        noiseSuppressor = null; aec = null } catch (e: Exception) {}
        try { if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() } catch (e: Exception) {}
        try { record?.release() } catch (e: Exception) {}
    }

    private fun startPlayback() {
        playbackQueue.clear()
        val minBuffer = AudioTrack.getMinBufferSize(
            PLAYBACK_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val oneSecBytes = PLAYBACK_SAMPLE_RATE * 2
        val bufferSize = if (minBuffer > 0) maxOf(minBuffer * 4, oneSecBytes) else oneSecBytes

        val trackBuilder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(PLAYBACK_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
        try {
            if (sharedAudioSessionId > 0) {
                trackBuilder.setSessionId(sharedAudioSessionId)
            }
        } catch (_: Exception) {}
        val track = trackBuilder.build()
        track.play()
        audioTrack = track

        totalFramesWritten = 0L
        speechEndFrame = 0L
        isPlayingAudio = true
        val silenceKeepAlive = ByteArray(960) // 20ms of 24kHz PCM16 post-sentence flush (never injected mid-sentence!)
        var lastRealSpeechWriteMs = 0L
        var awaitingUtterancePrebuffer = true
        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                while (isPlayingAudio) {
                    val chunk = playbackQueue.poll(20, TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        val remSpeechMs = remainingHardwareSpeechMs()
                        val sinceWriteMs = SystemClock.elapsedRealtime() - lastRealSpeechWriteMs
                        if (lastRealSpeechWriteMs > 0L && sinceWriteMs < 320L) {
                            if (remSpeechMs <= 0L && playbackQueue.isEmpty()) {
                                currentPlaybackRms = 0.0
                            }
                            continue
                        }
                        if (playbackQueue.isEmpty() && remSpeechMs <= 0L) {
                            playbackBusy = false
                            currentPlaybackRms = 0.0
                            awaitingUtterancePrebuffer = true
                        }
                        val sw = try { track.write(silenceKeepAlive, 0, silenceKeepAlive.size) } catch (_: Exception) { 0 }
                        if (sw > 0) totalFramesWritten += (sw / 2).toLong()
                        continue
                    }
                    if (chunk.generation != flushGeneration.get()) {
                        awaitingUtterancePrebuffer = true
                        continue
                    }

                    if (awaitingUtterancePrebuffer && remainingHardwareSpeechMs() <= 0L) {
                        val adaptiveWaitMs = (125L + (cellularJitterEwmaMs * 0.65).toLong()).coerceIn(125L, 245L)
                        val waitStart = SystemClock.elapsedRealtime()
                        while ((SystemClock.elapsedRealtime() - waitStart) < adaptiveWaitMs &&
                            chunk.generation == flushGeneration.get()
                        ) {
                            var bufferedBytes = chunk.data.size
                            for (qChunk in playbackQueue) {
                                bufferedBytes += qChunk.data.size
                                if (bufferedBytes >= 23040) break
                            }
                            if (bufferedBytes >= 23040) break
                            Thread.sleep(12L)
                        }
                        awaitingUtterancePrebuffer = false
                    }

                    if (!playbackBusy && remainingHardwareSpeechMs() <= 0L) {
                        playbackStartedAtMs = SystemClock.elapsedRealtime()
                    }
                    playbackBusy = true
                    try {
                        var sq = 0.0
                        val nSamp = chunk.data.size / 2
                        for (i in 0 until nSamp step 4) {
                            val s = ((chunk.data[2 * i + 1].toInt() shl 8) or (chunk.data[2 * i].toInt() and 0xFF)).toShort().toDouble()
                            sq += s * s
                        }
                        currentPlaybackRms = if (nSamp > 0) kotlin.math.sqrt(sq / ((nSamp + 3) / 4)) else 0.0
                        var offset = 0
                        while (offset < chunk.data.size && chunk.generation == flushGeneration.get()) {
                            val length = min(PLAYBACK_SLICE_BYTES * 4, chunk.data.size - offset)
                            val written = track.write(chunk.data, offset, length)
                            if (written < 0) break
                            offset += written
                            totalFramesWritten += (written / 2).toLong()
                            speechEndFrame = totalFramesWritten
                            val nowEl = SystemClock.elapsedRealtime()
                            lastPlaybackTime = nowEl
                            lastRealSpeechWriteMs = nowEl
                        }
                    } finally {
                        lastPlaybackTime = SystemClock.elapsedRealtime()
                        if (playbackQueue.isEmpty() && remainingHardwareSpeechMs() <= 0L) {
                            playbackBusy = false
                            currentPlaybackRms = 0.0
                        }
                    }
                }
            } catch (e: InterruptedException) {
            } catch (e: Exception) {}
        }, "ThorPlayback")
        playbackThread = thread
        thread.start()
    }

    private fun remainingHardwareSpeechMs(): Long {
        val track = audioTrack ?: return 0L
        val endF = speechEndFrame
        if (endF <= 0L) return 0L
        return try {
            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (endF > head) ((endF - head) * 1000L) / PLAYBACK_SAMPLE_RATE else 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun flushPlayback() {
        flushGeneration.incrementAndGet()
        playbackQueue.clear()
        playbackBusy = false
        currentPlaybackRms = 0.0
        bargeRecordingActive = false
        bargePcmBuffer.reset()
        val track = audioTrack ?: return
        try {
            track.pause()
            track.flush()
            val headNow = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            totalFramesWritten = headNow
            speechEndFrame = headNow
            track.play()
        } catch (e: Exception) {}
    }

    private fun stopPlayback() {
        isPlayingAudio = false
        playbackBusy = false
        currentPlaybackRms = 0.0
        totalFramesWritten = 0L
        speechEndFrame = 0L
        bargeRecordingActive = false
        bargePcmBuffer.reset()
        playbackThread?.interrupt()
        playbackThread = null
        flushGeneration.incrementAndGet()
        playbackQueue.clear()
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            try { track.pause(); track.flush() } catch (e: Exception) {}
            try { track.release() } catch (e: Exception) {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            try { nm?.deleteNotificationChannel("ThorChannelId") } catch (_: Exception) {}
            val channel = NotificationChannel(CHANNEL_ID, "Thor Service Channel", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(sessionActive: Boolean, wakeReady: Boolean): Notification {
        val text = when {
            sessionActive -> "In conversation. Double-tap icon or pause 8s to hang up."
            isStandby -> "Standby Mode (Mic Free). Double-tap icon to wake."
            wakeReady -> "Listening for 'Thor' (or Single-Tap icon to talk)."
            else -> "Single-Tap icon to talk, Double-Tap to free mic."
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingThorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Thor Voice Overlay")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setNumber(0)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .addAction(0, "Stop Thor", stopIntent)
            .build()
    }

    private fun refreshNotification() {
        if (destroyed || !isServiceRunning || userRequestedStop) return
        val wakeReady = detector != null && captureThread != null
        val notification = buildNotification(sessionActive = activeSession != 0, wakeReady = wakeReady)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val shouldReviveAfterTaskSwipe = !userRequestedStop &&
            !getSharedPreferences("thor_overlay_prefs", MODE_PRIVATE).getBoolean("user_stopped", false)
        if (shouldReviveAfterTaskSwipe) {
            scheduleKeepAliveIfAllowed()
        }
        destroyed = true
        canAutoResumeMic = false
        awaitingThorReply = false
        isServiceRunning = false
        cancelOverlayNotificationCompletely()
        mainHandler.removeCallbacksAndMessages(null)
        stopStreamingAudio()
        mainHandler.removeCallbacksAndMessages(null)
        cancelOverlayNotificationCompletely()
        if (::audioManager.isInitialized) abandonMicAudioFocus()
        boltAnimator?.cancel()
        pulseAnimator?.cancel()
        ringRotateAnimator?.cancel()

        networkSentinel.stopMonitoring()
        outboxExecutor.shutdown()
        if (::localBrain.isInitialized) localBrain.shutdown()

        synchronized(lock) {
            captureToken.incrementAndGet()
            preRoll.clear()
        }
        captureThread?.let {
            try { it.join(1000) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        powerGuard.releaseWakeLock()
        detector = null
        try { detectorModels?.close() } catch (e: Exception) {}
        detectorModels = null
        if (::floatingView.isInitialized) {
            try { windowManager.removeView(floatingView) } catch (e: Exception) {}
        }
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        super.onDestroy()
    }
}

package com.example.thormobile

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
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
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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

class FloatingThorService : Service() {

    companion object {
        private const val TAG = "ThorService"
        private const val TAG_AUDIO = "ThorAudio"
        private const val TAG_WS = "ThorWebSocket"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ThorChannelId"
        const val ACTION_STOP = "com.example.thormobile.action.STOP"
        private const val BRIDGE_URL = "wss://thor-api-443941171993.us-east4.run.app"
        private const val END_SESSION_TEXT = "END_SESSION"
        private const val END_DRAIN_MAX_MS = 15_000L
        private const val END_DRAIN_TAIL_MS = 500L
        private const val MIC_SETTLE_DELAY_MS = 600L
        private const val ASSET_DIR = "wakeword"
        private const val WAKE_THRESHOLD = 0.8f
        private const val CAPTURE_SAMPLE_RATE = 16000
        private const val CAPTURE_FRAME_BYTES = 1600
        private const val PRE_ROLL_IDLE_MAX_BYTES = 16000 * 2
        private const val PRE_ROLL_CONNECT_MAX_BYTES = 16000 * 2 * 8
        private const val PLAYBACK_SAMPLE_RATE = 24000
        private const val PLAYBACK_SLICE_BYTES = 1920
        private const val CAPTURE_RING_BYTES = CAPTURE_SAMPLE_RATE * 2
        private const val MAX_SEND_QUEUE_BYTES = 1024L * 1024L
        private val FLUSH_MARKER: ByteString =
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()).toByteString()
    }

    private class PcmChunk(val generation: Int, val data: ByteArray)

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private var glowRingView: ImageView? = null
    private var pulseAnimator: ObjectAnimator? = null
    private lateinit var audioManager: AudioManager
    private lateinit var powerGuard: PowerGuard
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    @Volatile private var isStandby = false

    // THOR MICRO-AGENT COMPONENTS
    private lateinit var db: ThorDatabase
    private lateinit var networkSentinel: NetworkSentinel
    private val outboxExecutor = Executors.newSingleThreadExecutor()

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
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private val playbackQueue = LinkedBlockingQueue<PcmChunk>()
    private val flushGeneration = AtomicInteger(0)
    @Volatile private var isPlayingAudio = false
    @Volatile private var playbackBusy = false
    @Volatile private var endingSession = false
    @Volatile private var micSettling = false

    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile private var hasAudioFocus = false
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.d(TAG_AUDIO, "Audio focus changed: $change")
    }

    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        powerGuard = PowerGuard(this)

        // Initialize Thor Local Memory & Sentinel
        db = ThorDatabase.getDatabase(this)
        networkSentinel = NetworkSentinel(this)
        networkSentinel.onConnectionRestored = {
            synchronized(lock) {
                if (activeSession != 0) {
                    if (!socketReady && webSocket == null) {
                        Log.d(TAG_WS, "Network restored. Reconnecting socket for active session...")
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
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
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

        val layoutFlag: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100
        windowManager.addView(floatingView, params)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var longPressFired = false

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isStandby) {
                    handleOverlayTap()
                } else {
                    toast("Thor is resting. Double-tap to wake.")
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleStandby()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                longPressFired = true
                shutdownService()
            }
        })

        floatingView.setOnTouchListener { _, event ->
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

                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(floatingView, params)
                    }
                }
            }
            true
        }

        loadWakeWordAsync()
    }

    private fun toggleStandby() {
        if (isStandby) {
            isStandby = false
            mainHandler.post {
                glowRingView?.colorFilter = null
                stopListeningAnimation()
                toast("Microphone Locked. Thor is listening.")
            }
            synchronized(lock) { ensureCapture() }
            mainHandler.post { refreshNotification() }
        } else {
            isStandby = true
            stopStreamingAudio()
            synchronized(lock) {
                captureToken.incrementAndGet()
                preRoll.clear()
            }
            mainHandler.post {
                glowRingView?.setColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)
                startListeningAnimation()
                toast("Standby Mode: Microphone Free.")
                refreshNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdownService()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun shutdownService() {
        if (destroyed) return
        mainHandler.removeCallbacksAndMessages(null)
        stopStreamingAudio()
        stopForeground(true)
        stopSelf()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun handleOverlayTap() {
        if (activeSession != 0) {
            stopStreamingAudio()
        } else if (hasMicPermission()) {
            startSession(keepPreRoll = false)
        } else {
            toast("Microphone permission required")
        }
    }

    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun startListeningAnimation() {
        mainHandler.post {
            glowRingView?.let { view ->
                if (pulseAnimator == null) {
                    pulseAnimator = ObjectAnimator.ofFloat(view, View.ALPHA, 0.3f, 1.0f).apply {
                        duration = 1000L
                        repeatCount = ObjectAnimator.INFINITE
                        repeatMode = ObjectAnimator.REVERSE
                    }
                }
                if (pulseAnimator?.isStarted != true) {
                    view.alpha = 0.3f
                    pulseAnimator?.start()
                }
            }
        }
    }

    private fun stopListeningAnimation() {
        mainHandler.post {
            pulseAnimator?.cancel()
            pulseAnimator = null
            glowRingView?.alpha = 0.0f
        }
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
                toast("Wake word unavailable; tap the icon to talk.")
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
            enterCommunicationMode()
            startPlayback()
            endingSession = false
            socketReady = false
            val id = sessionSeq.incrementAndGet()
            activeSession = id
            micSettling = true
            mainHandler.postDelayed({ openBridgeSocket(id) }, MIC_SETTLE_DELAY_MS)
        }
        startListeningAnimation()
        mainHandler.post { refreshNotification() }
    }

    private fun openBridgeSocket(id: Int) {
        synchronized(lock) {
            if (id != activeSession || destroyed) return
            micSettling = false
            val request = Request.Builder()
                .url(BRIDGE_URL)
                .addHeader("ngrok-skip-browser-warning", "true")
                .build()
            webSocket = okHttpClient.newWebSocket(request, BridgeListener(id))
        }
    }

    private fun stopStreamingAudio() {
        var socketToClose: WebSocket? = null
        synchronized(lock) {
            if (activeSession == 0) return
            activeSession = 0
            socketReady = false
            endingSession = false
            micSettling = false
            socketToClose = webSocket
            webSocket = null
            preRoll.clear()
            detectorResetRequested = true
            stopPlayback()
            exitCommunicationMode()
            abandonMicAudioFocus()
        }
        socketToClose?.close(1000, "Client disconnect")
        if (!isStandby) stopListeningAnimation()
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
                val quiet = playbackQueue.isEmpty() && !playbackBusy
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

    private inner class BridgeListener(private val sessionId: Int) : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            synchronized(lock) {
                if (sessionId != activeSession) {
                    ws.close(1000, "Stale session")
                    return
                }
                preRoll.drain { ws.send(it.toByteString(0, it.size)) }
                socketReady = true
            }
            // Trigger flush for any offline audio saved in the Outbox
            flushOutbox()
        }
        override fun onMessage(ws: WebSocket, text: String) {
            if (sessionId != activeSession) return
            if (text == END_SESSION_TEXT) beginEndDrain(sessionId)
        }
        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            if (sessionId != activeSession) return
            if (bytes == FLUSH_MARKER) flushPlayback()
            else playbackQueue.add(PcmChunk(flushGeneration.get(), bytes.toByteArray()))
        }
        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            if (sessionId == activeSession) stopStreamingAudio()
        }
        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            if (sessionId == activeSession) stopStreamingAudio()
        }
        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            // Do NOT stop the session. Safely drop the socket and let the Interceptor queue to the Outbox.
            synchronized(lock) {
                if (sessionId == activeSession) {
                    socketReady = false
                    webSocket = null
                    mainHandler.post { toast("Connection lost. Routing to offline Outbox.") }
                }
            }
        }
    }

    private fun flushOutbox() {
        outboxExecutor.execute {
            try {
                val pending = db.outboxDao().getPendingAudio()
                if (pending.isNotEmpty()) {
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
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(audioFocusListener, mainHandler)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasAudioFocus) Log.w(TAG_AUDIO, "Audio focus request not granted (result=$result)")
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

    @Suppress("DEPRECATION")
    private fun enterCommunicationMode() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
    }

    @Suppress("DEPRECATION")
    private fun exitCommunicationMode() {
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    private fun ensureCapture() {
        if (captureThread != null || destroyed || !hasMicPermission() || isStandby) return
        val record = createAudioRecord() ?: return
        val aec = attachEchoCanceler(record)
        val ns = attachNoiseSuppressor(record)
        audioRecord = record
        echoCanceler = aec
        noiseSuppressor = ns
        powerGuard.acquireWakeLock()
        val token = captureToken.incrementAndGet()
        val thread = Thread({ captureLoop(token, record, aec, ns) }, "ThorCapture")
        captureThread = thread
        thread.start()
    }

    private fun createAudioRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minBuffer > 0) maxOf(minBuffer, CAPTURE_RING_BYTES) else CAPTURE_RING_BYTES
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                CAPTURE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
        } catch (e: Exception) { return null }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return record
    }

    private fun attachEchoCanceler(record: AudioRecord): AcousticEchoCanceler? {
        if (!AcousticEchoCanceler.isAvailable()) return null
        val aec = AcousticEchoCanceler.create(record.audioSessionId)
        if (aec != null) aec.enabled = true
        return aec
    }

    private fun attachNoiseSuppressor(record: AudioRecord): NoiseSuppressor? {
        if (!NoiseSuppressor.isAvailable()) return null
        val ns = NoiseSuppressor.create(record.audioSessionId)
        if (ns != null) {
            ns.enabled = true
            Log.d(TAG_AUDIO, "Hardware Noise Suppressor engaged.")
        }
        return ns
    }

    private fun captureLoop(token: Int, record: AudioRecord, aec: AcousticEchoCanceler?, ns: NoiseSuppressor?) {
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
                        else -> {
                            // ACTIVE SESSION INTERCEPTOR: No Socket or No Internet
                            shouldSaveToOutbox = true
                        }
                    }
                }

                if (target != null) {
                    if (target!!.queueSize() <= MAX_SEND_QUEUE_BYTES) {
                        target!!.send(frame.toByteString(0, read))
                    } else {
                        droppedFrames++
                        if (droppedFrames % 100 == 1) {
                            Log.w(TAG_AUDIO, "Uplink stalled: send queue over cap, dropped $droppedFrames frame(s).")
                        }
                    }
                } else if (shouldSaveToOutbox) {
                    // STORE-AND-FORWARD: Write raw PCM to the local Android SQLite Database
                    val chunk = frame.copyOf(read)
                    val ts = System.currentTimeMillis()
                    outboxExecutor.execute {
                        try {
                            db.outboxDao().insertAudio(OutboxAudio(timestamp = ts, audioData = chunk))
                        } catch (e: Exception) {
                            Log.e(TAG_AUDIO, "Failed to write to offline Outbox", e)
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
                    echoCanceler = null
                    noiseSuppressor = null
                    powerGuard.releaseWakeLock()
                }
            }
            releaseCapture(record, aec, ns)

            if (current && failed && !destroyed && !isStandby) {
                Log.w(TAG_AUDIO, "Microphone preempted. Entering Auto-Standby.")
                mainHandler.post {
                    if (!isStandby) toggleStandby()
                    toast("Thor yielded microphone to Android.")
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

    private fun releaseCapture(record: AudioRecord?, aec: AcousticEchoCanceler?, ns: NoiseSuppressor?) {
        try { ns?.release() } catch (e: Exception) {}
        try { aec?.release() } catch (e: Exception) {}
        try { if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() } catch (e: Exception) {}
        try { record?.release() } catch (e: Exception) {}
    }

    private fun startPlayback() {
        playbackQueue.clear()
        val minBuffer = AudioTrack.getMinBufferSize(
            PLAYBACK_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minBuffer > 0) minBuffer * 2 else PLAYBACK_SLICE_BYTES * 8

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
            .build()
        track.play()
        audioTrack = track

        isPlayingAudio = true
        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                while (isPlayingAudio) {
                    val chunk = playbackQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    playbackBusy = true
                    try {
                        var offset = 0
                        while (offset < chunk.data.size && chunk.generation == flushGeneration.get()) {
                            val length = min(PLAYBACK_SLICE_BYTES, chunk.data.size - offset)
                            val written = track.write(chunk.data, offset, length)
                            if (written < 0) break
                            offset += written
                        }
                    } finally {
                        playbackBusy = false
                    }
                }
            } catch (e: InterruptedException) {
            } catch (e: Exception) {}
        }, "ThorPlayback")
        playbackThread = thread
        thread.start()
    }

    private fun flushPlayback() {
        flushGeneration.incrementAndGet()
        playbackQueue.clear()
        val track = audioTrack ?: return
        try {
            track.pause()
            track.flush()
            track.play()
        } catch (e: Exception) {}
    }

    private fun stopPlayback() {
        isPlayingAudio = false
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
            val channel = NotificationChannel(CHANNEL_ID, "Thor Service Channel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(sessionActive: Boolean, wakeReady: Boolean): Notification {
        val text = when {
            sessionActive -> "In conversation. Tap to hang up."
            isStandby -> "Standby Mode. Double-tap to wake."
            wakeReady -> "Listening for 'Thor'."
            else -> "Tap the icon to talk."
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingThorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vanguard Online")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .addAction(0, "Stop Thor", stopIntent)
            .build()
    }

    private fun refreshNotification() {
        if (destroyed) return
        val wakeReady = detector != null && captureThread != null
        val notification = buildNotification(sessionActive = activeSession != 0, wakeReady = wakeReady)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        stopStreamingAudio()
        if (::audioManager.isInitialized) abandonMicAudioFocus()
        stopListeningAnimation()

        // Shut down Micro-Agent components
        networkSentinel.stopMonitoring()
        outboxExecutor.shutdown()

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
        super.onDestroy()
    }
}
package com.example.thormobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraConsentActivity : Activity() {
    companion object {
        private const val REQ_CAM = 9186
        private const val EXTRA_TORCH = "extra_torch"

        fun launch(context: Context, wantTorch: Boolean = false) {
            context.startActivity(Intent(context, CameraConsentActivity::class.java).apply {
                putExtra(EXTRA_TORCH, wantTorch)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#040812")) })
        val wantTorch = intent?.getBooleanExtra(EXTRA_TORCH, false) == true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            ThorFlashlightAndLensService.startRearCamera(this, wantTorch)
            finish()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAM)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAM && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            ThorFlashlightAndLensService.startRearCamera(this, intent?.getBooleanExtra(EXTRA_TORCH, false) == true)
        } else {
            Toast.makeText(this, "Camera permission required for Rear-Camera Vision", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}

class ThorFlashlightAndLensService : Service() {

    companion object {
        private const val TAG = "ThorFlashLens"
        private const val CH_ID = "ThorRearLensChannel"
        private const val NOTIF_ID = 43
        private const val INTERVAL_MS = 2000L

        const val ACTION_START_LENS = "com.example.thormobile.action.START_REAR_LENS"
        const val ACTION_STOP_LENS = "com.example.thormobile.action.STOP_REAR_LENS"
        const val ACTION_SNAP_PHOTO = "com.example.thormobile.action.SNAP_REAR_PHOTO"
        const val EXTRA_INIT_TORCH = "extra_init_torch"
        const val EXTRA_SNAP_ID = "extra_snap_photo_id"
        const val EXTRA_SNAP_NOTE = "extra_snap_note"
        const val EXTRA_AUTO_CLOSE = "extra_auto_close_after_snap"

        @Volatile var isTorchOn: Boolean = false
        @Volatile var isRearCameraActive: Boolean = false
        @Volatile var latestCamFrameJson: String = ""
        @Volatile private var cbRegistered: Boolean = false
        @Volatile private var activeSvc: ThorFlashlightAndLensService? = null

        private fun backCamInfo(cm: CameraManager): Pair<String?, Boolean> {
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    return Pair(id, ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true)
                }
            }
            return Pair(cm.cameraIdList.firstOrNull(), false)
        }

        fun ensureTorchListener(context: Context) {
            if (cbRegistered) return
            try {
                val app = context.applicationContext
                val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val (backId, _) = backCamInfo(cm)
                cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId == backId && !isRearCameraActive) {
                            isTorchOn = enabled
                        }
                    }
                }, Handler(Looper.getMainLooper()))
                cbRegistered = true
            } catch (_: Exception) {}
        }

        fun setTorch(context: Context, enable: Boolean): Boolean {
            val app = context.applicationContext
            ensureTorchListener(app)
            val svc = activeSvc
            if (isRearCameraActive && svc != null) {
                isTorchOn = enable
                svc.applySessionTorch(enable)
                return true
            }
            return try {
                val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val (backId, hasFlash) = backCamInfo(cm)
                if (backId != null && hasFlash) {
                    cm.setTorchMode(backId, enable)
                    isTorchOn = enable
                    true
                } else false
            } catch (e: Exception) {
                Log.e(TAG, "setTorchMode($enable) failed", e)
                false
            }
        }

        fun toggleTorch(context: Context): Boolean {
            setTorch(context, !isTorchOn)
            return isTorchOn
        }

        fun startRearCamera(context: Context, withTorch: Boolean = false) {
            val app = context.applicationContext
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                CameraConsentActivity.launch(app, withTorch)
                return
            }
            if (ThorDisplayShareService.isDisplayShareActive) {
                ThorDisplayShareService.stopSharing(app)
            }
            val keepTorch = withTorch || isTorchOn
            ContextCompat.startForegroundService(app, Intent(app, ThorFlashlightAndLensService::class.java).apply {
                action = ACTION_START_LENS
                putExtra(EXTRA_INIT_TORCH, keepTorch)
            })
        }

        fun capturePhotoSnapshot(context: Context, photoId: String, bossNote: String = "", withTorch: Boolean = false) {
            val app = context.applicationContext
            val cleanId = photoId.ifBlank { "PHOTO-${System.currentTimeMillis()}" }
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                CameraConsentActivity.launch(app, withTorch)
                return
            }
            val svc = activeSvc
            if (isRearCameraActive && svc != null) {
                if (withTorch && !isTorchOn) {
                    isTorchOn = true
                    svc.applySessionTorch(true)
                }
                svc.armImmediateSnapshot(cleanId, bossNote, autoCloseAfter = false, warmupDelayMs = 150L)
                return
            }
            if (ThorDisplayShareService.isDisplayShareActive) {
                ThorDisplayShareService.stopSharing(app)
            }
            ContextCompat.startForegroundService(app, Intent(app, ThorFlashlightAndLensService::class.java).apply {
                action = ACTION_SNAP_PHOTO
                putExtra(EXTRA_INIT_TORCH, withTorch || isTorchOn)
                putExtra(EXTRA_SNAP_ID, cleanId)
                putExtra(EXTRA_SNAP_NOTE, bossNote)
                putExtra(EXTRA_AUTO_CLOSE, true)
            })
        }

        fun saveJpegToThorVisionGalleryAndCache(context: Context, photoId: String, jpegBytes: ByteArray): String {
            if (jpegBytes.isEmpty()) return ""
            val cleanId = photoId.replace(Regex("[^A-Za-z0-9_-]"), "").ifBlank { "PHOTO_${System.currentTimeMillis()}" }
            try {
                val vaultDir = File(context.filesDir, "thor_visual_vault").apply { mkdirs() }
                File(vaultDir, "$cleanId.jpg").writeBytes(jpegBytes)
            } catch (_: Exception) {}

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dispName = "THOR_${stamp}_${cleanId.takeLast(8)}.jpg"
            val relFolder = Environment.DIRECTORY_PICTURES + "/Thor_Vision"
            return try {
                val resolver = context.contentResolver
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, dispName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, relFolder)
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { os -> os.write(jpegBytes) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val doneCv = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                        resolver.update(uri, doneCv, null, null)
                    }
                    "$relFolder/$dispName"
                } else ""
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore Thor_Vision save note: ${e.message}")
                ""
            }
        }

        fun stopRearCamera(context: Context) {
            isRearCameraActive = false
            latestCamFrameJson = ""
            ThorDisplayShareService.dispatchPacket(
                JSONObject().put("action", "screen_watcher_state").put("type", "screen_watcher_state").put("active", false).toString()
            )
            try { context.applicationContext.stopService(Intent(context.applicationContext, ThorFlashlightAndLensService::class.java)) } catch (_: Exception) {}
            isTorchOn = false
        }

        fun sendImmediateHighResFrame() {
            activeSvc?.camHandler?.post { activeSvc?.forceNextHighRes = true }
        }
    }

    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var sensorDeg: Int = 90
    private var hasFlashUnit: Boolean = true

    private var wm: WindowManager? = null
    private var previewCard: LinearLayout? = null
    private var torchBtnTv: TextView? = null
    private var previewSurface: Surface? = null

    @Volatile private var lastEncodeMs: Long = 0L
    @Volatile private var sentFirst: Boolean = false
    @Volatile private var forceNextHighRes: Boolean = false
    @Volatile private var pendingSnapId: String = ""
    @Volatile private var pendingSnapNote: String = ""
    @Volatile private var snapReadyAfterElapsedMs: Long = 0L
    @Volatile private var autoCloseAfterSnap: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeSvc = this
        ensureChannel()
        val ht = HandlerThread("ThorRearLensBg").apply { start() }
        camThread = ht
        camHandler = Handler(ht.looper)
    }

    fun armImmediateSnapshot(photoId: String, bossNote: String, autoCloseAfter: Boolean, warmupDelayMs: Long = 200L) {
        pendingSnapId = photoId
        pendingSnapNote = bossNote
        autoCloseAfterSnap = autoCloseAfter
        snapReadyAfterElapsedMs = System.currentTimeMillis() + warmupDelayMs
        forceNextHighRes = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_LENS) {
            teardownAll(notifyOff = true)
            stopSelf()
            return START_NOT_STICKY
        }
        isTorchOn = intent?.getBooleanExtra(EXTRA_INIT_TORCH, isTorchOn) ?: isTorchOn
        if (intent?.action == ACTION_SNAP_PHOTO) {
            val sid = intent.getStringExtra(EXTRA_SNAP_ID) ?: "PHOTO-${System.currentTimeMillis()}"
            val snote = intent.getStringExtra(EXTRA_SNAP_NOTE) ?: ""
            val aClose = intent.getBooleanExtra(EXTRA_AUTO_CLOSE, false)
            armImmediateSnapshot(sid, snote, autoCloseAfter = aClose, warmupDelayMs = 1150L)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIF_ID, buildNotif())
            }
            isRearCameraActive = true
            sentFirst = false
            mainHandler.post { buildOverlayAndOpenCamera() }
        } catch (e: Exception) {
            Log.e(TAG, "Start rear lens failed", e)
            teardownAll(notifyOff = true)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun bgBox(fill: String, border: String, radDp: Int, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(fill))
            cornerRadius = dp(radDp).toFloat()
            setStroke(dp(strokeDp), Color.parseColor(border))
        }

    private fun buildOverlayAndOpenCamera() {
        if (!Settings.canDrawOverlays(this)) {
            openCameraHardware(null)
            return
        }
        if (previewCard != null) {
            refreshTorchPill()
            return
        }
        val winMgr = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = winMgr
        val lType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            dp(196), dp(264), lType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12); y = dp(86)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bgBox("#09111F", "#F5B942", 16, 2)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val hdr = TextView(this).apply {
            text = "THOR LENS - DRAG"
            setTextColor(Color.parseColor("#FDE047"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(1), 0, dp(4))
        }
        root.addView(hdr)

        val tex = TextureView(this)
        root.addView(tex, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        val tPill = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(5), dp(4), dp(5))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
            setOnClickListener {
                isTorchOn = !isTorchOn
                applySessionTorch(isTorchOn)
                refreshTorchPill()
            }
        }
        torchBtnTv = tPill

        val snapPill = TextView(this).apply {
            text = "Snap"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#DCFCE7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = bgBox("#052E16", "#22C55E", 99)
            setPadding(dp(4), dp(5), dp(4), dp(5))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.95f).apply { marginEnd = dp(4) }
            setOnClickListener {
                armImmediateSnapshot("PHOTO-${System.currentTimeMillis()}", "Manual Thor Lens Snapshot", autoCloseAfter = false, warmupDelayMs = 80L)
            }
        }

        val xPill = TextView(this).apply {
            text = "Off"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FEE2E2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = bgBox("#3B0D14", "#EF4444", 99)
            setPadding(dp(4), dp(5), dp(4), dp(5))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
            setOnClickListener {
                isTorchOn = false
                teardownAll(notifyOff = true)
                stopSelf()
            }
        }
        row.addView(tPill); row.addView(snapPill); row.addView(xPill)
        root.addView(row)

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        hdr.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = ev.rawX; ty = ev.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (sx - (ev.rawX - tx).toInt()).coerceAtLeast(0)
                    lp.y = (sy + (ev.rawY - ty).toInt()).coerceAtLeast(dp(24))
                    try { winMgr.updateViewLayout(root, lp) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
        tex.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                st.setDefaultBufferSize(1280, 720)
                val s = Surface(st)
                previewSurface = s
                openCameraHardware(s)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        previewCard = root
        refreshTorchPill()
        try { winMgr.addView(root, lp) } catch (_: Exception) { openCameraHardware(null) }
    }

    private fun refreshTorchPill() {
        mainHandler.post {
            val tv = torchBtnTv ?: return@post
            if (isTorchOn) {
                tv.text = "Light ON"
                tv.setTextColor(Color.parseColor("#FEF08A"))
                tv.background = bgBox("#422006", "#FACC15", 99)
            } else {
                tv.text = "Light"
                tv.setTextColor(Color.parseColor("#E0F2FE"))
                tv.background = bgBox("#172554", "#38BDF8", 99)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraHardware(viewSurf: Surface?) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val (backId, flashOk) = backCamInfo(cm)
        if (backId == null) { stopSelf(); return }
        hasFlashUnit = flashOk
        sensorDeg = cm.getCameraCharacteristics(backId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val reader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2)
        reader.setOnImageAvailableListener({ ir ->
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                val snapDue = pendingSnapId.isNotEmpty() && now >= snapReadyAfterElapsedMs
                val hiRes = forceNextHighRes || snapDue
                if (!hiRes && sentFirst && (now - lastEncodeMs) < INTERVAL_MS) return@setOnImageAvailableListener
                lastEncodeMs = now
                forceNextHighRes = false
                val buf = img.planes[0].buffer
                val raw = ByteArray(buf.remaining())
                buf.get(raw)
                encodeAndSendFrame(raw, hiRes, snapDue)
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }, camHandler)
        imageReader = reader

        cm.openCamera(backId, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                cameraDevice = cam
                val surfaces = mutableListOf<Surface>(reader.surface)
                if (viewSurf != null && viewSurf.isValid) surfaces.add(viewSurf)
                @Suppress("DEPRECATION")
                cam.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(sess: CameraCaptureSession) {
                        captureSession = sess
                        applySessionTorch(isTorchOn)
                        ThorDisplayShareService.dispatchPacket(
                            JSONObject().put("action", "screen_watcher_state").put("type", "screen_watcher_state")
                                .put("active", true).put("source", "rear_camera").toString()
                        )
                    }
                    override fun onConfigureFailed(sess: CameraCaptureSession) {}
                }, camHandler)
            }
            override fun onDisconnected(cam: CameraDevice) { cam.close(); cameraDevice = null }
            override fun onError(cam: CameraDevice, err: Int) { cam.close(); cameraDevice = null; stopSelf() }
        }, camHandler)
    }

    fun applySessionTorch(enable: Boolean) {
        val cam = cameraDevice ?: return
        val sess = captureSession ?: return
        val reader = imageReader ?: return
        camHandler?.post {
            try {
                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(reader.surface)
                    previewSurface?.let { if (it.isValid) addTarget(it) }
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    if (hasFlashUnit) {
                        set(CaptureRequest.FLASH_MODE, if (enable) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                    }
                }
                sess.setRepeatingRequest(req.build(), null, camHandler)
                refreshTorchPill()
            } catch (_: Exception) {}
        }
    }

    private fun encodeAndSendFrame(rawJpeg: ByteArray, hiRes: Boolean, isSnapshotCapture: Boolean) {
        if (!isRearCameraActive || rawJpeg.isEmpty()) return
        val bmp = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size) ?: return
        val mat = Matrix()
        if (sensorDeg != 0) mat.postRotate(sensorDeg.toFloat())
        val scale = ((if (hiRes || isSnapshotCapture) 1920f else 1280f) / maxOf(bmp.width, bmp.height).coerceAtLeast(1)).coerceAtMost(1f)
        mat.postScale(scale, scale)
        val outBmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mat, true)
        if (outBmp !== bmp) bmp.recycle()
        val w = outBmp.width; val h = outBmp.height
        val baos = ByteArrayOutputStream()
        outBmp.compress(Bitmap.CompressFormat.JPEG, if (isSnapshotCapture) 92 else (if (hiRes) 84 else 74), baos)
        outBmp.recycle()

        val jpegBytes = baos.toByteArray()
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        if (isSnapshotCapture) {
            val capturedId = pendingSnapId
            val capturedNote = pendingSnapNote
            val shouldClose = autoCloseAfterSnap
            pendingSnapId = ""
            pendingSnapNote = ""
            autoCloseAfterSnap = false

            val galPath = saveJpegToThorVisionGalleryAndCache(applicationContext, capturedId, jpegBytes)
            val snapPkt = JSONObject()
                .put("action", "camera_snapshot_captured")
                .put("type", "camera_snapshot_captured")
                .put("photo_id", capturedId)
                .put("boss_note", capturedNote)
                .put("gallery_path", galPath)
                .put("jpeg_b64", b64)
                .put("width", w)
                .put("height", h)
                .toString()
            ThorDisplayShareService.dispatchPacket(snapPkt)
            mainHandler.post {
                Toast.makeText(applicationContext, "Saved to Pictures/Thor_Vision & Thor Memory", Toast.LENGTH_SHORT).show()
                if (shouldClose) {
                    isTorchOn = false
                    teardownAll(notifyOff = true)
                    stopSelf()
                }
            }
            if (shouldClose) return
        }

        val pkt = JSONObject()
            .put("action", "screen_watcher_frame").put("type", "screen_watcher_frame")
            .put("source", "rear_camera").put("torch_on", isTorchOn)
            .put("jpeg_b64", b64)
            .put("mime_type", "image/jpeg").put("width", w).put("height", h)
            .put("native_width_px", w).put("native_height_px", h)
            .put("scale_factor", 1.0).put("gear", if (hiRes) "native_full_res" else "standard_720p")
            .toString()
        latestCamFrameJson = pkt
        if (!sentFirst || hiRes || FloatingThorService.isVoiceSessionOpen()) {
            sentFirst = true
            ThorDisplayShareService.dispatchPacket(pkt)
        }
    }

    private fun teardownAll(notifyOff: Boolean) {
        isRearCameraActive = false
        latestCamFrameJson = ""
        sentFirst = false
        try { captureSession?.close() } catch (_: Exception) {}; captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}; cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) {}; imageReader = null
        try { previewSurface?.release() } catch (_: Exception) {}; previewSurface = null
        val card = previewCard; previewCard = null; torchBtnTv = null
        if (card != null) mainHandler.post { try { wm?.removeView(card) } catch (_: Exception) {} }
        if (notifyOff) {
            ThorDisplayShareService.dispatchPacket(
                JSONObject().put("action", "screen_watcher_state").put("type", "screen_watcher_state").put("active", false).toString()
            )
        }
    }

    override fun onDestroy() {
        teardownAll(notifyOff = true)
        isTorchOn = false
        if (activeSvc === this) activeSvc = null
        try { camThread?.quitSafely() } catch (_: Exception) {}
        camThread = null; camHandler = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "Thor Rear-Camera Lens", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotif(): Notification {
        val pi = PendingIntent.getService(
            this, 9187, Intent(this, ThorFlashlightAndLensService::class.java).apply { action = ACTION_STOP_LENS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.image_5)
            .setContentTitle("Thor Rear-Camera Lens: ON (RAM + Thor_Vision)")
            .setContentText("Say 'take a picture' or tap Snap to save to Thor_Vision.")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP CAMERA", pi)
            .build()
    }
}

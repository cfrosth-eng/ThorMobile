package com.example.thormobile

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

object LivingPedestalDirector {
    private const val TAG = "LivingPedestalV5"
    private val mainHandler = Handler(Looper.getMainLooper())

    private const val GOLD_SHADOW = 0xFF3A2203.toInt()
    private const val GOLD_BRONZE = 0xFF5A3907.toInt()
    private const val GOLD_RICH   = 0xFF966B12.toInt()
    private const val GOLD_WARM   = 0xFFD4A234.toInt()
    private const val GOLD_GLINT  = 0xFFFEF08A.toInt()

    enum class PedestalOccupant { MJOLNIR, EMPTY_STONE, RAVEN }

    @Volatile var hasUnreadRaven: Boolean = false
        private set
    @Volatile var currentOccupant: PedestalOccupant = PedestalOccupant.MJOLNIR
        private set
    @Volatile var ravenCardUnfoldedNow: Boolean = false
        private set
    @Volatile var flightInProgress: Boolean = false
        private set

    @Volatile private var attachedCrestView: LivingPedestalCrestView? = null
    private var flightWindowManager: WindowManager? = null
    private var activeFlightView: RavenFlightStageView? = null
    private var moltenStreamOverlay: MoltenGoldScreenStreamView? = null
    private var flightAnimator: ValueAnimator? = null
    private var patrolRunnable: Runnable? = null

    fun isPedestalAttached(): Boolean = attachedCrestView != null

    private var mjolnirBaseBmp: Bitmap? = null
    private var emptyPedestalBmp: Bitmap? = null
    private var mjolnirSwapCoreBmp: Bitmap? = null
    private var mjolnirSwapHaloBmp: Bitmap? = null
    private var ravenBaseBmp: Bitmap? = null
    private var ravenEyeBlinkPatchBmp: Bitmap? = null
    private var ravenFgOccluderBmp: Bitmap? = null
    private var mjolnirFgOccluderBmp: Bitmap? = null
    private var ringStripBmp: Bitmap? = null
    private var runesGoldBmp: Bitmap? = null
    private val boltCores = arrayOfNulls<Bitmap>(4)
    private val boltHalos = arrayOfNulls<Bitmap>(4)
    private val flyFrames = arrayOfNulls<Bitmap>(3)

    fun dp(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    fun dpInt(ctx: Context, v: Int): Int = dp(ctx, v.toFloat()).toInt()

    fun getPhysicalGlassSize(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun buildFullGlassOverlayParams(ctx: Context): WindowManager.LayoutParams {
        val (realW, realH) = getPhysicalGlassSize(ctx)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(
            realW, realH, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START; x = 0; y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun decodeRaw(ctx: Context, resId: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        BitmapFactory.decodeResource(ctx.resources, resId, opts)?.apply { density = Bitmap.DENSITY_NONE }
    } catch (e: Exception) { Log.w(TAG, "Decode($resId): ${e.message}"); null }

    @Synchronized
    fun ensureAssetsLoaded(ctx: Context) {
        val app = ctx.applicationContext
        if (mjolnirBaseBmp != null && emptyPedestalBmp != null && ravenBaseBmp != null && flyFrames[0] != null) return
        mjolnirBaseBmp = decodeRaw(app, R.drawable.mjolnir_pedestal_base_hd)
        emptyPedestalBmp = decodeRaw(app, R.drawable.empty_pedestal_base_hd) ?: mjolnirBaseBmp
        mjolnirSwapCoreBmp = decodeRaw(app, R.drawable.mjolnir_swap_bolt_core)
        mjolnirSwapHaloBmp = decodeRaw(app, R.drawable.mjolnir_swap_bolt_halo)
        ravenBaseBmp = decodeRaw(app, R.drawable.raven_storm_base_hd)
        ravenEyeBlinkPatchBmp = decodeRaw(app, R.drawable.raven_eye_blink_patch)
        ravenFgOccluderBmp = decodeRaw(app, R.drawable.raven_fg_occluder)
        mjolnirFgOccluderBmp = decodeRaw(app, R.drawable.mjolnir_fg_occluder)
        ringStripBmp = decodeRaw(app, R.drawable.raven_gold_ring_strip)
        runesGoldBmp = decodeRaw(app, R.drawable.raven_runes_gold_hd)

        val cIds = intArrayOf(R.drawable.raven_bolt_0_core, R.drawable.raven_bolt_1_core, R.drawable.raven_bolt_2_core, R.drawable.raven_bolt_3_core)
        val hIds = intArrayOf(R.drawable.raven_bolt_0_halo, R.drawable.raven_bolt_1_halo, R.drawable.raven_bolt_2_halo, R.drawable.raven_bolt_3_halo)
        for (i in 0 until 4) { boltCores[i] = decodeRaw(app, cIds[i]); boltHalos[i] = decodeRaw(app, hIds[i]) }
        flyFrames[0] = decodeRaw(app, R.drawable.raven_fly_up)
        flyFrames[1] = decodeRaw(app, R.drawable.raven_fly_glide)
        flyFrames[2] = decodeRaw(app, R.drawable.raven_fly_down)
    }

    fun createAndAttachPedestalView(ctx: Context): LivingPedestalCrestView {
        ensureAssetsLoaded(ctx)
        val view = LivingPedestalCrestView(ctx)
        attachedCrestView = view
        if (hasUnreadRaven && !ravenCardUnfoldedNow) {
            setPedestalOccupant(PedestalOccupant.RAVEN)
            scheduleNextPatrolFlight(ctx.applicationContext)
        } else {
            setPedestalOccupant(PedestalOccupant.MJOLNIR)
        }
        return view
    }

    private fun setPedestalOccupant(occ: PedestalOccupant) {
        currentOccupant = occ
        attachedCrestView?.setOccupantImmediate(occ)
    }

    fun detachFromService() {
        cancelPatrolSchedule()
        stopActiveFlightOverlay()
        detachMoltenStreamOverlay()
        attachedCrestView?.stopLoop()
        attachedCrestView = null
    }

    private fun ensureMoltenStreamOverlay(ctx: Context): MoltenGoldScreenStreamView? {
        val app = ctx.applicationContext
        if (!Settings.canDrawOverlays(app)) return null
        moltenStreamOverlay?.let { if (it.parent != null) return it }
        val wm = flightWindowManager ?: (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { flightWindowManager = it }
        val view = MoltenGoldScreenStreamView(app)
        return try { wm.addView(view, buildFullGlassOverlayParams(app)); moltenStreamOverlay = view; view } catch (_: Exception) { null }
    }

    private fun detachMoltenStreamOverlay() {
        val v = moltenStreamOverlay; moltenStreamOverlay = null
        if (v != null && v.parent != null) { try { flightWindowManager?.removeView(v) } catch (_: Exception) {} }
    }

    fun updateServiceAudioVisualState(isStandby: Boolean, sessionActive: Boolean, speakingActive: Boolean) {
        mainHandler.post { attachedCrestView?.updateOperationalState(isStandby, sessionActive, speakingActive, hasUnreadRaven) }
    }

    fun pulseMutedRunesFeedback() {
        mainHandler.post { attachedCrestView?.pulseRunesResonance() }
    }

    fun triggerLightningPulse() {
        mainHandler.post { attachedCrestView?.triggerElectricStrikeBurst(460L, 1.0f) }
    }

    fun onRavenDispatched(ctx: Context, eventKey: String, title: String, cleanBase: String) {
        val app = ctx.applicationContext
        ensureAssetsLoaded(app)
        mainHandler.post {
            hasUnreadRaven = true
            ravenCardUnfoldedNow = false
            val crest = attachedCrestView ?: return@post
            crest.refreshDripActivity(true)
            cancelPatrolSchedule()

            if (currentOccupant == PedestalOccupant.RAVEN || flightInProgress) {
                crest.pulseRunesResonance()
                if (!flightInProgress) scheduleNextPatrolFlight(app)
                return@post
            }

            setPedestalOccupant(PedestalOccupant.MJOLNIR)
            launchLivePursuitFlight(
                app = app,
                mode = FlightMode.ARRIVAL,
                onComplete = { if (hasUnreadRaven && !ravenCardUnfoldedNow) scheduleNextPatrolFlight(app) }
            )
        }
    }

    fun onRavenCardUnfolded() {
        mainHandler.post {
            ravenCardUnfoldedNow = true
            cancelPatrolSchedule()
            if (flightInProgress) {
                stopActiveFlightOverlay()
                setPedestalOccupant(PedestalOccupant.RAVEN)
            }
        }
    }

    fun onRavenCardFoldedBackToPedestal(ctx: Context) {
        val app = ctx.applicationContext
        mainHandler.post {
            if (!hasUnreadRaven) return@post
            ravenCardUnfoldedNow = false
            setPedestalOccupant(PedestalOccupant.RAVEN)
            attachedCrestView?.pulseRunesResonance()
            attachedCrestView?.refreshDripActivity(true)
            scheduleNextPatrolFlight(app)
        }
    }

    fun dismissUnreadRavenWithTakeoff(ctx: Context) {
        val app = ctx.applicationContext
        ensureAssetsLoaded(app)
        mainHandler.post {
            val wasRaven = hasUnreadRaven || currentOccupant == PedestalOccupant.RAVEN
            hasUnreadRaven = false
            ravenCardUnfoldedNow = false
            cancelPatrolSchedule()
            stopActiveFlightOverlay()

            val crest = attachedCrestView
            if (crest == null) { currentOccupant = PedestalOccupant.MJOLNIR; return@post }
            crest.refreshDripActivity(false)
            if (!wasRaven) { setPedestalOccupant(PedestalOccupant.MJOLNIR); return@post }

            launchLivePursuitFlight(
                app = app,
                mode = FlightMode.DEPARTURE,
                onComplete = { crest.refreshDripActivity(false) }
            )
        }
    }

    private fun cancelPatrolSchedule() {
        patrolRunnable?.let { mainHandler.removeCallbacks(it) }; patrolRunnable = null
    }

    private fun scheduleNextPatrolFlight(app: Context) {
        cancelPatrolSchedule()
        if (!hasUnreadRaven || ravenCardUnfoldedNow) return
        val r = Runnable {
            if (!hasUnreadRaven || ravenCardUnfoldedNow || flightInProgress || attachedCrestView == null) return@Runnable
            launchLivePursuitFlight(
                app = app,
                mode = FlightMode.PATROL,
                onComplete = { if (hasUnreadRaven && !ravenCardUnfoldedNow) scheduleNextPatrolFlight(app) }
            )
        }
        patrolRunnable = r
        mainHandler.postDelayed(r, Random.nextLong(14_000L, 22_000L))
    }

    private enum class FlightMode { ARRIVAL, PATROL, DEPARTURE }

    private fun getPerchInOverlayCoords(app: Context, targetView: View?): PointF {
        val crest = attachedCrestView
        if (crest != null && crest.isAttachedToWindow) {
            val cLoc = IntArray(2); crest.getLocationOnScreen(cLoc)
            val oLoc = IntArray(2); if (targetView != null && targetView.isAttachedToWindow) targetView.getLocationOnScreen(oLoc)
            val px = (cLoc[0] - oLoc[0]) + crest.padSide + crest.cardW * 0.49f
            val py = (cLoc[1] - oLoc[1]) + crest.padTop + crest.cardH * 0.34f
            return PointF(px, py)
        }
        return PointF(dp(app, 73f), dp(app, 82f))
    }

    /**
     * WO-164 Real-Time 60fps Autonomous Pursuit Flight Engine:
     * - 100% solid opaque Raven (alpha = 255 from frame 0 to touchdown, 0 fade-out!).
     * - Clean Empty-Stone transfer: Raven leaps off EMPTY_STONE at t=0ms -> 260ms later Mjolnir strikes in with image_5 lightning;
     *   on approach, Mjolnir dissolves into EMPTY_STONE via lightning ~260ms before talons plant onto the stone!
     * - Live Mid-Flight Pedestal Chasing: reads live pedestal coordinates every frame; if Boss drags the pedestal
     *   anywhere mid-flight, Huginn banks and chases it in real time until physically landing on the stone!
     */
    private fun launchLivePursuitFlight(app: Context, mode: FlightMode, onComplete: (() -> Unit)?) {
        if (!Settings.canDrawOverlays(app)) {
            setPedestalOccupant(if (mode == FlightMode.DEPARTURE) PedestalOccupant.MJOLNIR else PedestalOccupant.RAVEN)
            onComplete?.invoke()
            return
        }
        stopActiveFlightOverlay()
        val wm = flightWindowManager ?: (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { flightWindowManager = it }
        val stageView = RavenFlightStageView(app, flyFrames)
        try {
            wm.addView(stageView, buildFullGlassOverlayParams(app))
            activeFlightView = stageView
            flightInProgress = true
        } catch (_: Exception) {
            setPedestalOccupant(if (mode == FlightMode.DEPARTURE) PedestalOccupant.MJOLNIR else PedestalOccupant.RAVEN)
            onComplete?.invoke()
            return
        }

        val (swInt, shInt) = getPhysicalGlassSize(app)
        val sw = swInt.toFloat(); val sh = shInt.toFloat()
        val startPerch = getPerchInOverlayCoords(app, stageView)

        var bx: Float; var by: Float; var vx: Float; var vy: Float
        if (mode == FlightMode.ARRIVAL) {
            bx = sw + dp(app, 55f); by = sh * 0.16f
            vx = -dp(app, 340f); vy = dp(app, 95f)
        } else {
            // Liftoff from exact perched Raven position -> leave EMPTY_STONE underneath!
            bx = startPerch.x; by = startPerch.y
            vx = dp(app, 265f); vy = -dp(app, 145f)
            setPedestalOccupant(PedestalOccupant.EMPTY_STONE)
        }

        // Scenic on-screen cruise waypoints before entering live homing pursuit
        val cruiseWps = ArrayList<PointF>()
        when (mode) {
            FlightMode.ARRIVAL -> cruiseWps.add(PointF(sw * 0.24f, max(dp(app, 110f), sh * 0.28f)))
            FlightMode.DEPARTURE -> cruiseWps.add(PointF(sw + dp(app, 160f), sh * 0.12f))
            FlightMode.PATROL -> {
                when (Random.nextInt(3)) {
                    0 -> { cruiseWps.add(PointF(sw * 0.80f, sh * 0.24f)); cruiseWps.add(PointF(sw * 0.22f, sh * 0.44f)) }
                    1 -> { cruiseWps.add(PointF(sw * 0.82f, sh * 0.48f)); cruiseWps.add(PointF(sw * 0.18f, sh * 0.30f)) }
                    else -> { cruiseWps.add(PointF(sw * 0.76f, sh * 0.34f)); cruiseWps.add(PointF(sw * 0.32f, sh * 0.18f)) }
                }
            }
        }

        var wpIdx = 0
        var liftoffHammerMaterialized = (mode == FlightMode.ARRIVAL)
        var approachHammerDissolved = false
        val startMs = SystemClock.elapsedRealtime()
        var lastMs = startMs
        var prevPerchX = startPerch.x; var prevPerchY = startPerch.y
        var wingPhase = 0f

        flightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 60_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = SystemClock.elapsedRealtime()
                val dt = ((now - lastMs).coerceIn(1L, 45L)).toFloat() / 1000f
                lastMs = now
                val elapsedMs = now - startMs

                // 260ms after Raven leaps clear of stone -> materialize Mjolnir in intense image_5 lightning!
                if (!liftoffHammerMaterialized && elapsedMs >= 260L) {
                    liftoffHammerMaterialized = true
                    attachedCrestView?.triggerElectricStrikeBurst(460L, 1.0f)
                    mainHandler.postDelayed({
                        if (flightInProgress && currentOccupant == PedestalOccupant.EMPTY_STONE) {
                            setPedestalOccupant(PedestalOccupant.MJOLNIR)
                        }
                    }, 95L)
                }

                val livePerch = getPerchInOverlayCoords(app, stageView)
                val pedSpeed = hypot((livePerch.x - prevPerchX).toDouble(), (livePerch.y - prevPerchY).toDouble()).toFloat() / max(dt, 0.008f)
                prevPerchX = livePerch.x; prevPerchY = livePerch.y

                val homingPhase = (wpIdx >= cruiseWps.size) && (mode != FlightMode.DEPARTURE)
                val targetX: Float; val targetY: Float

                if (!homingPhase) {
                    val wp = cruiseWps[min(wpIdx, cruiseWps.size - 1)]
                    targetX = wp.x; targetY = wp.y
                    if (mode != FlightMode.DEPARTURE && hypot((wp.x - bx).toDouble(), (wp.y - by).toDouble()) < dp(app, 78f)) {
                        wpIdx++
                    }
                    if (mode == FlightMode.DEPARTURE && bx > sw + dp(app, 95f)) {
                        stopActiveFlightOverlay()
                        onComplete?.invoke()
                        return@addUpdateListener
                    }
                } else {
                    // Live Homing Pursuit: curve into Left-to-Right approach offset when far, converging to exact stone!
                    val distRaw = hypot((livePerch.x - bx).toDouble(), (livePerch.y - by).toDouble()).toFloat()
                    val flareRatio = ((distRaw - dp(app, 24f)) / dp(app, 120f)).coerceIn(0f, 1f)
                    val leadLeft = if (livePerch.x > dp(app, 105f)) dp(app, 56f) * flareRatio else -dp(app, 28f) * flareRatio
                    targetX = (livePerch.x - leadLeft).coerceIn(dp(app, 24f), sw - dp(app, 24f))
                    targetY = (livePerch.y - dp(app, 22f) * flareRatio).coerceIn(dp(app, 32f), sh - dp(app, 80f))

                    // Dissolve Mjolnir into EMPTY_STONE via lightning ~260ms before touchdown!
                    if (!approachHammerDissolved && distRaw < dp(app, 96f) && pedSpeed < dp(app, 120f)) {
                        approachHammerDissolved = true
                        attachedCrestView?.triggerElectricStrikeBurst(320L, 1.0f)
                        mainHandler.postDelayed({
                            if (flightInProgress && hasUnreadRaven) setPedestalOccupant(PedestalOccupant.EMPTY_STONE)
                        }, 85L)
                    }

                    // Touchdown ONLY when physically on the stone AND Boss isn't actively dragging it away!
                    if (distRaw <= dp(app, 10f) && pedSpeed < dp(app, 85f)) {
                        setPedestalOccupant(if (hasUnreadRaven) PedestalOccupant.RAVEN else PedestalOccupant.MJOLNIR)
                        attachedCrestView?.pulseRunesResonance()
                        stopActiveFlightOverlay()
                        onComplete?.invoke()
                        return@addUpdateListener
                    }
                }

                // Smooth aerodynamic steering force toward live target
                val dxT = targetX - bx; val dyT = targetY - by
                val distT = hypot(dxT.toDouble(), dyT.toDouble()).toFloat().coerceAtLeast(1f)
                val desiredSpeed = when {
                    mode == FlightMode.DEPARTURE -> dp(app, 520f)
                    !homingPhase -> dp(app, 420f)
                    else -> {
                        val distPerch = hypot((livePerch.x - bx).toDouble(), (livePerch.y - by).toDouble()).toFloat()
                        // Boost speed if Boss drags pedestal far away; brake smoothly into final 85dp flare!
                        val baseSpd = dp(app, 85f) + (distPerch / dp(app, 160f)).coerceIn(0f, 1.25f) * dp(app, 390f)
                        max(baseSpd, min(dp(app, 640f), pedSpeed * 1.15f))
                    }
                }
                val desVx = (dxT / distT) * desiredSpeed
                val desVy = (dyT / distT) * desiredSpeed
                val steerRate = if (homingPhase) 6.2f else 3.8f
                vx += (desVx - vx) * min(1f, dt * steerRate)
                vy += (desVy - vy) * min(1f, dt * steerRate)
                bx += vx * dt
                by += vy * dt

                val curSpd = hypot(vx.toDouble(), vy.toDouble()).toFloat().coerceAtLeast(1f)
                val isSoaringGlide = !homingPhase && (vy / curSpd > 0.26f) && elapsedMs > 380L
                val flapHz = if (homingPhase && distT < dp(app, 90f)) 3.5f else if (elapsedMs < 420L) 3.4f else 2.5f
                wingPhase = (wingPhase + dt * flapHz) % 1f

                val distToStone = hypot((livePerch.x - bx).toDouble(), (livePerch.y - by).toDouble()).toFloat()
                val nearStone = (homingPhase && distToStone < dp(app, 85f)) || (mode != FlightMode.ARRIVAL && elapsedMs < 240L)
                val scale = if (homingPhase) {
                    0.92f + 0.16f * (distToStone / dp(app, 140f)).coerceIn(0f, 1f)
                } else if (mode != FlightMode.ARRIVAL && elapsedMs < 260L) {
                    0.92f + 0.16f * (elapsedMs / 260f)
                } else 1.08f

                val lockRight = (homingPhase && distToStone < dp(app, 48f)) || (mode != FlightMode.ARRIVAL && elapsedMs < 180L)
                val pitchDamp = if (homingPhase && distToStone < dp(app, 55f)) (distToStone / dp(app, 55f)).coerceIn(0f, 1f) else 1f

                stageView.updateFluidPose(bx, by, vx, vy * pitchDamp, dt, wingPhase, isSoaringGlide, scale, lockRight, nearStone)
            }
            start()
        }
    }

    private fun stopActiveFlightOverlay() {
        flightAnimator?.cancel(); flightAnimator = null; flightInProgress = false
        val v = activeFlightView; activeFlightView = null
        if (v != null && v.parent != null) { try { flightWindowManager?.removeView(v) } catch (_: Exception) {} }
    }

    /**
     * 100% Opaque Shoulder-Locked Raven Renderer (0 transparency, 0 turn squish, continuous 60fps wing arc).
     */
    private class RavenFlightStageView(ctx: Context, private val frames: Array<Bitmap?>) : View(ctx) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { alpha = 255 }
        private val baseBoxPx = dp(ctx, 134f)
        private val dstRect = RectF()

        private var curX = -500f; private var curY = -500f
        private var facingSign = 1f
        private var bankTiltDeg = 0f
        private var smoothPitchDeg = 0f
        private var frameIdx = 1
        private var wingStrokeScaleY = 1f
        private var curScale = 1f

        fun updateFluidPose(
            x: Float, y: Float, vx: Float, vy: Float, dt: Float,
            wingPhase: Float, gliding: Boolean, scale: Float, forceRight: Boolean, dampenBob: Boolean
        ) {
            val bob = if (gliding || dampenBob) 0f else sin(wingPhase * Math.PI * 2.0).toFloat() * dp(context, 2.0f)
            curX = x; curY = y + bob

            val wantRight = if (forceRight) true else if (abs(vx) > dp(context, 12f)) (vx >= 0f) else (facingSign > 0f)
            val newSign = if (wantRight) 1f else -1f
            if (newSign != facingSign) {
                facingSign = newSign
                bankTiltDeg = -14f // Crisp banking roll on direction turn (NEVER squishes width to 0!)
            }
            bankTiltDeg += (0f - bankTiltDeg) * min(1f, dt * 8.5f)

            val absVx = abs(vx).coerceAtLeast(1f)
            val targetPitch = Math.toDegrees(atan2(vy.toDouble(), absVx.toDouble())).toFloat().coerceIn(-22f, 22f)
            smoothPitchDeg += (targetPitch - smoothPitchDeg) * min(1f, dt * 10.0f)

            if (gliding) {
                frameIdx = 1
                wingStrokeScaleY = 1.0f
            } else {
                // 4-part continuous wing cycle around locked shoulder center (448, 448)
                val p4 = wingPhase * 4f
                frameIdx = when (p4.toInt().coerceIn(0, 3)) {
                    0 -> 0 // Upstroke peak
                    1 -> 1 // Mid-down sweep
                    2 -> 2 // Downstroke power
                    else -> 1 // Recovery sweep
                }
                wingStrokeScaleY = 0.94f + 0.09f * abs(cos(wingPhase * Math.PI * 2.0)).toFloat()
            }
            curScale = scale
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val bmp = frames.getOrNull(frameIdx) ?: frames.firstOrNull { it != null } ?: return
            val half = (baseBoxPx * curScale) * 0.5f
            canvas.save()
            canvas.translate(curX, curY)
            canvas.scale(facingSign, wingStrokeScaleY)
            canvas.rotate(smoothPitchDeg + bankTiltDeg)
            dstRect.set(-half, -half, half, half)
            paint.alpha = 255
            canvas.drawBitmap(bmp, null, dstRect, paint)
            canvas.restore()
        }
    }

    /**
     * Authentic High-Voltage `image_5.png` Mjolnir Lightning Drawable (Stepped Cascade + Blazing Double Return Stroke).
     */
    private class ElectricHammerLightningDrawable : Drawable() {
        private val haloPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val corePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dstRect = RectF()
        var progress: Float = -1f
            set(value) { field = value; invalidateSelf() }
        var intensityScale: Float = 1.0f

        override fun draw(canvas: Canvas) {
            val p = progress
            if (currentOccupant == PedestalOccupant.RAVEN || p < 0f || p > 1f) return
            val b = bounds; if (b.isEmpty) return
            val core = mjolnirSwapCoreBmp ?: boltCores[0] ?: return
            val halo = mjolnirSwapHaloBmp ?: boltHalos[0] ?: core
            dstRect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            val h = dstRect.height()

            if (p <= 0.42f) {
                val step = (((p / 0.42f) * 8f).toInt() + 1) / 8f
                val frontY = dstRect.top + h * step.coerceIn(0.15f, 1f)
                haloPaint.alpha = (245 * intensityScale).toInt().coerceIn(0, 255)
                corePaint.alpha = (255 * intensityScale).toInt().coerceIn(0, 255)
                canvas.save()
                canvas.clipRect(dstRect.left, dstRect.top, dstRect.right, frontY)
                canvas.drawBitmap(halo, null, dstRect, haloPaint)
                canvas.drawBitmap(core, null, dstRect, corePaint)
                canvas.drawBitmap(core, null, dstRect, corePaint)
                canvas.restore()
            } else if (p <= 0.76f) {
                val crackle = if (p in 0.56f..0.61f) 0.55f else 1.0f
                haloPaint.alpha = (255 * crackle * intensityScale).toInt().coerceIn(0, 255)
                corePaint.alpha = (255 * crackle * intensityScale).toInt().coerceIn(0, 255)
                canvas.drawBitmap(halo, null, dstRect, haloPaint)
                canvas.drawBitmap(core, null, dstRect, corePaint)
                if (crackle > 0.9f) canvas.drawBitmap(core, null, dstRect, corePaint)
            } else {
                val fade = ((1f - p) / 0.24f).coerceIn(0f, 1f)
                val tailTop = dstRect.top + h * (1f - fade) * 0.45f
                haloPaint.alpha = (245 * fade * intensityScale).toInt().coerceIn(0, 255)
                corePaint.alpha = (255 * fade * intensityScale).toInt().coerceIn(0, 255)
                canvas.save()
                canvas.clipRect(dstRect.left, tailTop, dstRect.right, dstRect.bottom)
                canvas.drawBitmap(halo, null, dstRect, haloPaint)
                canvas.drawBitmap(core, null, dstRect, corePaint)
                canvas.restore()
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * WO-164 Bottom Molten Gold Pool with Impact Splatter Crowns & Phone-Tilt Accelerometer Fluid Sloshing!
     */
    private class MoltenGoldScreenStreamView(ctx: Context) : View(ctx), SensorEventListener {
        private data class GoldDrop(
            var x: Float = 0f, var y: Float = 0f, var vx: Float = 0f, var vy: Float = 0f,
            var r: Float = 0f, var weight: Float = 1f, var isSplatter: Boolean = false, var active: Boolean = false
        )

        private val drops = Array(64) { GoldDrop() }
        private val cols = 72
        private val pileH = FloatArray(cols + 1)
        private val pileVel = FloatArray(cols + 1)
        private val flux = FloatArray(cols + 2)

        private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pileFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stripPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dropPath = Path()
        private val pilePath = Path()
        private val surfaceEdgePath = Path()
        private val stripDst = RectF()

        private var sensorMgr: SensorManager? = null
        private var rawTiltRight = 0f
        private var smoothTiltRight = 0f
        private var texShift = 0f

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            try {
                val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                sensorMgr = sm
                val s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
            } catch (_: Exception) {}
        }

        override fun onDetachedFromWindow() {
            try { sensorMgr?.unregisterListener(this) } catch (_: Exception) {}
            sensorMgr = null
            super.onDetachedFromWindow()
        }

        override fun onSensorChanged(event: SensorEvent?) {
            val v = event?.values ?: return
            // Android Portrait: +values[0] = tilted left, -values[0] = tilted right -> invert so +1 = tilted right!
            rawTiltRight = (-v[0] / 9.81f).coerceIn(-1f, 1f)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        fun spawnDetachedDropFromPedestal(screenX: Float, screenY: Float) {
            val wLoc = IntArray(2); getLocationOnScreen(wLoc)
            val lx = screenX - wLoc[0]
            val ly = screenY - wLoc[1]
            drops.firstOrNull { !it.active }?.apply {
                active = true; isSplatter = false
                x = lx + (Random.nextFloat() - 0.5f) * dp(context, 1.2f)
                y = ly; vx = 0f; vy = dp(context, 48f)
                r = dp(context, Random.nextFloat() * 1.1f + 4.4f)
                weight = 1.0f
            }
            // Trailing satellite micro-bead
            drops.firstOrNull { !it.active }?.apply {
                active = true; isSplatter = false
                x = lx; y = ly - dp(context, 5.5f); vx = 0f; vy = dp(context, 20f)
                r = dp(context, 1.8f); weight = 0.22f
            }
        }

        fun stepPhysics(dt: Float, flowActive: Boolean): Boolean {
            val (physW, physH) = getPhysicalGlassSize(context)
            val sw = max(width.toFloat(), physW.toFloat())
            val wLoc = IntArray(2); getLocationOnScreen(wLoc)
            val sh = (physH - wLoc[1]).toFloat().coerceAtLeast(200f)
            val gravity = dp(context, 580f)

            smoothTiltRight += (rawTiltRight - smoothTiltRight) * min(1f, dt * 7.5f)
            texShift = (texShift + dt * (85f + smoothTiltRight * 140f) + 2048f) % 2048f

            var anyDrops = false
            for (d in drops) {
                if (!d.active) continue
                anyDrops = true
                // Falling drops also feel subtle lateral phone tilt!
                d.vx += smoothTiltRight * dp(context, 95f) * dt
                d.vy += gravity * dt
                d.x = (d.x + d.vx * dt).coerceIn(dp(context, 4f), sw - dp(context, 4f))
                d.y += d.vy * dt

                val colIdx = ((d.x / sw) * cols).toInt().coerceIn(0, cols)
                val surfY = sh - pileH[colIdx]
                if (d.y >= surfY) {
                    d.active = false
                    val addPeak = dp(context, if (d.isSplatter) 0.55f else 4.8f) * d.weight
                    val maxDepth = dp(context, 84f)
                    for (k in max(0, colIdx - 5)..min(cols, colIdx + 5)) {
                        val dist = (k - colIdx).toFloat()
                        val w = exp((-dist * dist / 6.5f).toDouble()).toFloat()
                        pileH[k] = (pileH[k] + addPeak * w).coerceAtMost(maxDepth)
                        pileVel[k] += (if (abs(k - colIdx) <= 1) -dp(context, 34f) else dp(context, 18f) * w) * d.weight
                    }

                    // Erupt Molten 24k Gold Splatter Crown when primary drop impacts the pool!
                    if (!d.isSplatter && d.weight >= 0.75f) {
                        val poolDepthHere = pileH[colIdx]
                        val count = if (poolDepthHere > dp(context, 6f)) 8 else 5
                        for (s in 0 until count) {
                            drops.firstOrNull { !it.active }?.apply {
                                active = true; isSplatter = true
                                val side = if (s % 2 == 0) 1f else -1f
                                val spread = (0.35f + Random.nextFloat() * 0.85f) * side
                                x = d.x + side * dp(context, 2.5f)
                                y = surfY - dp(context, 2.0f)
                                vx = spread * dp(context, 135f) + smoothTiltRight * dp(context, 35f)
                                vy = -dp(context, Random.nextFloat() * 125f + 78f)
                                r = dp(context, Random.nextFloat() * 1.3f + 1.5f)
                                weight = 0.28f
                            }
                        }
                    }
                }
            }

            // Volume-conserving Shallow-Water + Phone-Tilt Gravity Sloshing along bottom bezel!
            flux[0] = 0f; flux[cols + 1] = 0f
            val slumpK = 15.5f
            val tiltDrive = smoothTiltRight * dp(context, 42f)
            for (k in 0 until cols) {
                val avgH = 0.5f * (pileH[k] + pileH[k + 1])
                if (avgH <= 0.05f) {
                    flux[k + 1] = 0f
                } else {
                    // Hydrostatic leveling + lateral phone-tilt gravity push!
                    val dh = pileH[k] - pileH[k + 1]
                    val q = (dh * slumpK + tiltDrive * (avgH / dp(context, 22f)).coerceAtMost(1.8f)) * dt
                    val maxMove = if (q > 0f) pileH[k] * 0.42f else -pileH[k + 1] * 0.42f
                    flux[k + 1] = if (q > 0f) min(q, maxMove) else max(q, maxMove)
                }
            }

            var maxPile = 0f
            val maxCap = dp(context, 86f)
            for (k in 0..cols) {
                val lap = pileH[max(0, k - 1)] + pileH[min(cols, k + 1)] - 2f * pileH[k]
                pileVel[k] = (pileVel[k] + lap * 42f * dt) * (1f - min(0.9f, 4.8f * dt))
                var hNew = pileH[k] + (flux[k] - flux[k + 1]) + pileVel[k] * dt * 0.35f
                if (!flowActive) hNew -= dp(context, 4.8f) * dt
                pileH[k] = hNew.coerceIn(0f, maxCap)
                if (pileH[k] > maxPile) maxPile = pileH[k]
            }

            invalidate()
            return flowActive || anyDrops || maxPile > 0.4f
        }

        override fun onDraw(canvas: Canvas) {
            val (physW, physH) = getPhysicalGlassSize(context)
            val sw = max(width.toFloat(), physW.toFloat())
            val wLoc = IntArray(2); getLocationOnScreen(wLoc)
            val sh = (physH - wLoc[1]).toFloat().coerceAtLeast(200f)

            for (d in drops) {
                if (!d.active) continue
                val spd = hypot(d.vx.toDouble(), d.vy.toDouble()).toFloat().coerceAtLeast(1f)
                val stretch = (1.35f + spd / dp(context, 520f)).coerceAtMost(2.5f)
                val angDeg = Math.toDegrees(atan2(d.vx.toDouble(), d.vy.toDouble())).toFloat()

                canvas.save()
                canvas.translate(d.x, d.y)
                canvas.rotate(-angDeg)
                dropPath.reset()
                dropPath.moveTo(0f, -d.r * stretch)
                dropPath.quadTo(d.r * 1.12f, -d.r * 0.1f, 0f, d.r)
                dropPath.quadTo(-d.r * 1.12f, -d.r * 0.1f, 0f, -d.r * stretch)
                dropPath.close()

                dropPaint.shader = LinearGradient(
                    -d.r, 0f, d.r, 0f,
                    intArrayOf(GOLD_BRONZE, GOLD_WARM, GOLD_RICH, GOLD_BRONZE),
                    floatArrayOf(0f, 0.38f, 0.78f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawPath(dropPath, dropPaint)
                glintPaint.color = GOLD_GLINT
                canvas.drawCircle(-d.r * 0.25f, d.r * 0.12f, d.r * 0.30f, glintPaint)
                canvas.restore()
            }

            var peakH = 0f
            for (hv in pileH) if (hv > peakH) peakH = hv
            if (peakH > 0.4f) {
                pilePath.reset(); surfaceEdgePath.reset()
                var started = false
                pilePath.moveTo(0f, sh + dp(context, 4f))
                for (k in 0..cols) {
                    val px = (k.toFloat() / cols.toFloat()) * sw
                    val hc = pileH[k]
                    val py = sh - hc
                    pilePath.lineTo(px, py)
                    if (hc > dp(context, 0.6f)) {
                        if (!started) { surfaceEdgePath.moveTo(px, py); started = true } else surfaceEdgePath.lineTo(px, py)
                    } else if (started) { surfaceEdgePath.lineTo(px, sh); started = false }
                }
                pilePath.lineTo(sw, sh + dp(context, 4f))
                pilePath.close()

                pileFillPaint.shader = LinearGradient(
                    0f, sh - peakH - dp(context, 2f), 0f, sh,
                    intArrayOf(GOLD_WARM, GOLD_RICH, GOLD_BRONZE, GOLD_SHADOW),
                    floatArrayOf(0f, 0.36f, 0.78f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawPath(pilePath, pileFillPaint)

                ringStripBmp?.let { strip ->
                    canvas.save()
                    canvas.clipPath(pilePath)
                    stripPaint.alpha = 165
                    val tileW = sw * 0.85f
                    var sx = -(texShift % tileW)
                    while (sx < sw) {
                        stripDst.set(sx, sh - peakH - dp(context, 6f), sx + tileW, sh + dp(context, 6f))
                        canvas.drawBitmap(strip, null, stripDst, stripPaint)
                        sx += tileW
                    }
                    canvas.restore()
                }

                rimStrokePaint.strokeWidth = dp(context, 1.6f)
                rimStrokePaint.color = GOLD_GLINT
                canvas.drawPath(surfaceEdgePath, rimStrokePaint)
            }
        }
    }

    /**
     * Unified Living Pedestal Crest View V5:
     * - Welded 6:00 Swelling Drop (moves 1:1 with pedestal drag with 0ms lag!)
     * - Gold vs Crimson-Red Chiseled Pedestal Runes for Mic ON vs Mic OFF
     */
    class LivingPedestalCrestView(ctx: Context) : FrameLayout(ctx) {
        val padSide = dpInt(ctx, 18)
        val padTop = dpInt(ctx, 18)
        val padBottom = dpInt(ctx, 34) // Extra bottom canvas room so attached 6:00 drop moves 1:1 with drag!
        val cardW = dpInt(ctx, 112)
        val cardH = dpInt(ctx, 140)
        private val cornerR = dp(ctx, 20f)

        val totalWindowWidthPx: Int = cardW + padSide * 2
        val totalWindowHeightPx: Int = cardH + padTop + padBottom

        private val artView: PedestalBackdropView
        private val electricHammerDrawable = ElectricHammerLightningDrawable()
        private val runesDrawable: ShimmeringRuneOverlayDrawable?
        private val skyBoltsDrawable: SkyStormBoltsDrawable

        private val flankSegs = 64; private val radSteps = 8
        private val rightBx = FloatArray(flankSegs + 1); private val rightBy = FloatArray(flankSegs + 1)
        private val rightNx = FloatArray(flankSegs + 1); private val rightNy = FloatArray(flankSegs + 1)
        private val leftBx = FloatArray(flankSegs + 1);  private val leftBy = FloatArray(flankSegs + 1)
        private val leftNx = FloatArray(flankSegs + 1);  private val leftNy = FloatArray(flankSegs + 1)
        private val rightMeshVerts = FloatArray((flankSegs + 1) * (radSteps + 1) * 2)
        private val leftMeshVerts = FloatArray((flankSegs + 1) * (radSteps + 1) * 2)
        private var meshReady = false

        private val scrolledStripBmp: Bitmap? = ringStripBmp?.let { Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888) }
        private val scrolledStripCanvas: Canvas? = scrolledStripBmp?.let { Canvas(it) }
        private val stripDrawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val ringPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { alpha = 244 }

        private val pendantPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pendantStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val pendantGlint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pendantPath = Path()
        private var pendantFill = 0f
        private var recoilPhase = 0f

        private var lastFrameMs = SystemClock.elapsedRealtime()
        private var flowPhase = 0f
        private var isStandbyMode = false
        private var isSessionOpen = false
        private var isSpeakingNow = false
        private var hasUnreadRavenFlag = false

        private var nextMjolnirIdleArcAtMs = SystemClock.elapsedRealtime() + 3800L
        private val bootShowcaseUntilMs = SystemClock.elapsedRealtime() + 32_000L
        private var masterLoop: ValueAnimator? = null
        private var strikeAnimator: ValueAnimator? = null

        init {
            setWillNotDraw(false); clipChildren = false; clipToPadding = false
            layoutParams = LayoutParams(totalWindowWidthPx, totalWindowHeightPx)

            val roundedCard = FrameLayout(ctx).apply {
                layoutParams = LayoutParams(cardW, cardH, Gravity.TOP or Gravity.START).apply { leftMargin = padSide; topMargin = padTop }
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, cornerR) }
                }
                elevation = dp(ctx, 7f)
            }

            artView = PedestalBackdropView(ctx)
            roundedCard.addView(artView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            runesDrawable = runesGoldBmp?.let { ShimmeringRuneOverlayDrawable(it) }
            if (runesDrawable != null) {
                roundedCard.addView(ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_XY; setImageDrawable(runesDrawable)
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                })
            }

            skyBoltsDrawable = SkyStormBoltsDrawable(boltCores, boltHalos)
            roundedCard.addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_XY; setImageDrawable(skyBoltsDrawable)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            })

            roundedCard.addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_XY; setImageDrawable(electricHammerDrawable)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            })

            addView(roundedCard)
            refreshScrolledStripBitmap()
            startMasterLoop()
        }

        private fun refreshScrolledStripBitmap() {
            val src = ringStripBmp ?: return; val c = scrolledStripCanvas ?: return
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val w = src.width.toFloat()
            val shiftPx = ((flowPhase % 1f + 1f) % 1f) * w
            c.drawBitmap(src, shiftPx, 0f, stripDrawPaint)
            c.drawBitmap(src, shiftPx - w, 0f, stripDrawPaint)
        }

        fun setOccupantImmediate(occ: PedestalOccupant) {
            artView.setOccupant(occ)
            if (occ == PedestalOccupant.MJOLNIR) {
                nextMjolnirIdleArcAtMs = SystemClock.elapsedRealtime() + Random.nextLong(3600L, 6800L)
            }
            invalidate()
        }

        fun pulseRunesResonance() {
            runesDrawable?.triggerLightningResonance()
            skyBoltsDrawable.forceStrikeNow()
            invalidate()
        }

        fun triggerElectricStrikeBurst(durationMs: Long = 460L, intensity: Float = 1.0f) {
            strikeAnimator?.cancel()
            skyBoltsDrawable.forceStrikeNow()
            runesDrawable?.triggerLightningResonance()
            electricHammerDrawable.intensityScale = intensity
            strikeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = LinearInterpolator()
                addUpdateListener { a -> electricHammerDrawable.progress = a.animatedValue as Float }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { electricHammerDrawable.progress = -1f }
                })
                start()
            }
        }

        fun updateOperationalState(standby: Boolean, sessionOpen: Boolean, speaking: Boolean, unreadRaven: Boolean) {
            val toggled = (isStandbyMode != standby)
            isStandbyMode = standby
            isSessionOpen = sessionOpen
            isSpeakingNow = speaking
            hasUnreadRavenFlag = unreadRaven
            // Outer ring ALWAYS stays 24k Gold; Pedestal Runes switch Gold (Mic ON) <-> Crimson Red (Mic OFF)!
            ringPaint.colorFilter = null
            runesDrawable?.isMutedRed = standby
            if (toggled) runesDrawable?.triggerLightningResonance()
            invalidate()
        }

        fun refreshDripActivity(unreadRaven: Boolean) {
            hasUnreadRavenFlag = unreadRaven
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 20 && h > 20) { buildDualFlankGeometry(); meshReady = true; updateDualCascadingMeshes(0f) }
        }

        private fun buildDualFlankGeometry() {
            val left = padSide.toFloat(); val top = padTop.toFloat()
            val right = (padSide + cardW).toFloat(); val bottom = (padTop + cardH).toFloat()
            val cx = (left + right) * 0.5f; val r = cornerR.coerceAtMost(min(cardW, cardH) * 0.46f)
            val halfTop = ((right - r) - cx).coerceAtLeast(1f)
            val side = ((bottom - r) - (top + r)).coerceAtLeast(1f)
            val halfBot = ((right - r) - cx).coerceAtLeast(1f)
            val arc = (0.5 * Math.PI * r).toFloat()
            val total = halfTop + arc + side + arc + halfBot

            for (i in 0..flankSegs) {
                var s = (i.toFloat() / flankSegs.toFloat()) * total
                var rx: Float; var ry: Float; var rnx: Float; var rny: Float
                if (s <= halfTop) { rx = cx + s; ry = top; rnx = 0f; rny = -1f }
                else {
                    s -= halfTop
                    if (s <= arc) { val a = (-Math.PI * 0.5).toFloat() + s / r; rnx = cos(a.toDouble()).toFloat(); rny = sin(a.toDouble()).toFloat(); rx = (right - r) + r * rnx; ry = (top + r) + r * rny }
                    else {
                        s -= arc
                        if (s <= side) { rx = right; ry = (top + r) + s; rnx = 1f; rny = 0f }
                        else {
                            s -= side
                            if (s <= arc) { val a = s / r; rnx = cos(a.toDouble()).toFloat(); rny = sin(a.toDouble()).toFloat(); rx = (right - r) + r * rnx; ry = (bottom - r) + r * rny }
                            else { s -= arc; rx = (right - r) - s.coerceAtMost(halfBot); ry = bottom; rnx = 0f; rny = 1f }
                        }
                    }
                }
                rightBx[i] = rx; rightBy[i] = ry; rightNx[i] = rnx; rightNy[i] = rny
                leftBx[i] = 2f * cx - rx; leftBy[i] = ry; leftNx[i] = -rnx; leftNy[i] = rny
            }
        }

        private fun updateDualCascadingMeshes(swell: Float) {
            if (!meshReady) return
            val innerTuck = -dp(context, 0.5f)
            val outerSpan = padSide.toFloat() - innerTuck
            val pull = dp(context, 5.2f) * (0.28f + 0.72f * swell)
            for (i in 0..flankSegs) {
                val u = i.toFloat() / flankSegs.toFloat()
                val bell = if (u > 0.85f) { val t = (u - 0.85f) / 0.15f; t * t * (3f - 2f * t) } else 0f
                for (j in 0..radSteps) {
                    val rf = j.toFloat() / radSteps.toFloat()
                    val dist = innerTuck + outerSpan * rf
                    val fy = pull * bell * rf * rf
                    val idx = (j * (flankSegs + 1) + i) * 2
                    rightMeshVerts[idx] = rightBx[i] + rightNx[i] * dist; rightMeshVerts[idx + 1] = rightBy[i] + rightNy[i] * dist + fy
                    leftMeshVerts[idx] = leftBx[i] + leftNx[i] * dist;   leftMeshVerts[idx + 1] = leftBy[i] + leftNy[i] * dist + fy
                }
            }
        }

        private fun startMasterLoop() {
            masterLoop?.cancel()
            val startAt = SystemClock.elapsedRealtime()
            lastFrameMs = startAt
            masterLoop = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 8000L; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART; interpolator = LinearInterpolator()
                addUpdateListener {
                    val now = SystemClock.elapsedRealtime()
                    val dt = ((now - lastFrameMs).coerceIn(1L, 80L)).toFloat() / 1000f
                    lastFrameMs = now
                    val elapsed = (now - startAt).coerceAtLeast(0L)

                    val flowActive = (!isStandbyMode || hasUnreadRavenFlag) &&
                        (isSessionOpen || isSpeakingNow || hasUnreadRavenFlag || now < bootShowcaseUntilMs)

                    if (recoilPhase > 0f) recoilPhase = (recoilPhase - dt * 6.5f).coerceAtLeast(0f)
                    if (flowActive) {
                        val speedMult = if (isSpeakingNow) 1.24f else 1.0f
                        flowPhase = (flowPhase + (dt / 7.2f) * speedMult) % 1f
                        refreshScrolledStripBitmap()
                        val pulseRad = ((elapsed % 1100L).toDouble() / 1100.0) * Math.PI * 2.0
                        ringPaint.alpha = ((0.82 + 0.18 * sin(pulseRad)) * 255.0).toInt().coerceIn(170, 255)

                        pendantFill += dt * 0.96f
                        if (pendantFill >= 1f) {
                            pendantFill = 0f; recoilPhase = 1f
                            if (isAttachedToWindow) {
                                val cLoc = IntArray(2); getLocationOnScreen(cLoc)
                                val sx = cLoc[0] + padSide + cardW * 0.50f
                                val sy = cLoc[1] + padTop + cardH + padSide * 0.52f + dp(context, 12.5f)
                                ensureMoltenStreamOverlay(context)?.spawnDetachedDropFromPedestal(sx, sy)
                            }
                        }
                    } else {
                        ringPaint.alpha = 224
                        pendantFill = (pendantFill - dt * 2.6f).coerceAtLeast(0f)
                    }

                    val swell = (pendantFill - recoilPhase * 0.35f).coerceIn(0f, 1f)
                    updateDualCascadingMeshes(if (flowActive) swell else 0f)

                    if (isAttachedToWindow && (flowActive || moltenStreamOverlay != null)) {
                        val mv = ensureMoltenStreamOverlay(context)
                        val keep = mv?.stepPhysics(dt, flowActive) ?: false
                        if (!keep && !flowActive) detachMoltenStreamOverlay()
                    }

                    artView.stepBlink(now)
                    runesDrawable?.tick(dt, elapsed)
                    skyBoltsDrawable.tick(now)

                    if (currentOccupant == PedestalOccupant.MJOLNIR && !isStandbyMode && now >= nextMjolnirIdleArcAtMs) {
                        nextMjolnirIdleArcAtMs = now + Random.nextLong(3800L, 7200L)
                        triggerElectricStrikeBurst(durationMs = 360L, intensity = 0.92f)
                    }
                    invalidate()
                }
                start()
            }
        }

        fun stopLoop() { masterLoop?.cancel(); masterLoop = null; strikeAnimator?.cancel(); strikeAnimator = null }

        override fun dispatchDraw(canvas: Canvas) {
            val strip = scrolledStripBmp ?: ringStripBmp
            if (strip != null && meshReady) {
                canvas.drawBitmapMesh(strip, flankSegs, radSteps, rightMeshVerts, 0, null, 0, ringPaint)
                canvas.drawBitmapMesh(strip, flankSegs, radSteps, leftMeshVerts, 0, null, 0, ringPaint)
            }
            // Welded 6:00 Gathering Drop drawn on Pedestal's own canvas -> 0.0ms lag when dragging!
            if (pendantFill > 0.03f || recoilPhase > 0.05f) {
                val cx = padSide + cardW * 0.50f
                val rimY = padTop + cardH + padSide * 0.52f
                val lift = recoilPhase * dp(context, 3.0f)
                val cy = rimY + (pendantFill * pendantFill * dp(context, 12.5f) - lift).coerceAtLeast(dp(context, 2.0f))
                val r = dp(context, 3.0f + 4.6f * pendantFill)
                val funnelW = dp(context, 13.0f) * (1f - 0.28f * (cy - rimY) / dp(context, 16f)).coerceIn(0.45f, 1f)
                val waistW = (r * 0.54f).coerceAtLeast(dp(context, 1.2f))
                val waistY = rimY + (cy - rimY) * 0.48f

                pendantPath.reset()
                pendantPath.moveTo(cx - funnelW, rimY - dp(context, 2.0f))
                pendantPath.quadTo(cx - waistW, waistY, cx - r, cy)
                pendantPath.arcTo(cx - r, cy - r * 0.65f, cx + r, cy + r * 1.05f, 180f, -180f, false)
                pendantPath.quadTo(cx + waistW, waistY, cx + funnelW, rimY - dp(context, 2.0f))
                pendantPath.close()

                pendantPaint.shader = LinearGradient(
                    cx - r * 1.1f, cy, cx + r * 1.1f, cy,
                    intArrayOf(GOLD_BRONZE, GOLD_WARM, GOLD_RICH, GOLD_BRONZE),
                    floatArrayOf(0f, 0.38f, 0.78f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawPath(pendantPath, pendantPaint)
                pendantStroke.strokeWidth = dp(context, 0.65f); pendantStroke.color = 0xAA4C3006.toInt()
                canvas.drawPath(pendantPath, pendantStroke)
                pendantGlint.color = GOLD_GLINT
                canvas.drawCircle(cx - r * 0.28f, cy + r * 0.14f, r * 0.30f, pendantGlint)
            }
            super.dispatchDraw(canvas)
        }
    }

    private class PedestalBackdropView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val blinkPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dst = RectF()
        private var occ = PedestalOccupant.MJOLNIR
        private var blinkAlpha = 0f
        private var blinkStartMs = 0L
        private var nextBlinkAtMs = SystemClock.elapsedRealtime() + 2200L

        fun setOccupant(o: PedestalOccupant) { occ = o; blinkAlpha = 0f; blinkStartMs = 0L; invalidate() }

        fun stepBlink(nowMs: Long) {
            if (occ != PedestalOccupant.RAVEN) return
            if (blinkStartMs > 0L) {
                val el = nowMs - blinkStartMs
                if (el >= 185L) { blinkStartMs = 0L; blinkAlpha = 0f; nextBlinkAtMs = nowMs + Random.nextLong(2400L, 4800L) }
                else blinkAlpha = sin((el.toDouble() / 185.0) * Math.PI).toFloat().coerceIn(0f, 1f)
                invalidate()
            } else if (nowMs >= nextBlinkAtMs) {
                blinkStartMs = nowMs
            }
        }

        override fun onDraw(canvas: Canvas) {
            val bmp = when (occ) {
                PedestalOccupant.RAVEN -> ravenBaseBmp
                PedestalOccupant.EMPTY_STONE -> emptyPedestalBmp ?: mjolnirBaseBmp
                PedestalOccupant.MJOLNIR -> mjolnirBaseBmp
            } ?: return
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(bmp, null, dst, paint)
            if (occ == PedestalOccupant.RAVEN && blinkAlpha > 0.02f) {
                ravenEyeBlinkPatchBmp?.let { eye ->
                    blinkPaint.alpha = (blinkAlpha * 255f).toInt().coerceIn(0, 255)
                    canvas.drawBitmap(eye, null, dst, blinkPaint)
                }
            }
        }
    }

    /**
     * Chiseled Elder Futhark Pedestal Runes:
     * - Mic ON (`isMutedRed = false`): Shimmering 24k Norse Gold
     * - Mic OFF (`isMutedRed = true`): Glowing Crimson Ruby Red (#DC2626 / #EF4444 + #FCA5A5 glint)
     */
    private class ShimmeringRuneOverlayDrawable(private val runesBitmap: Bitmap) : Drawable() {
        private val normRect = RectF(0.32031f, 0.61129f, 0.62813f, 0.89530f)
        private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(0xCC220802.toInt(), PorterDuff.Mode.SRC_IN)
        }
        private val redFilter = PorterDuffColorFilter(0xFFDC2626.toInt(), PorterDuff.Mode.SRC_IN)
        private val basePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
        private val dstRect = RectF(); private val shadowRect = RectF()
        var isMutedRed: Boolean = false
        private var shimmerPhase = 0f; private var emberBreath = 0f; private var strikeFlash = 0f

        fun triggerLightningResonance() { strikeFlash = 1.0f; invalidateSelf() }

        fun tick(dt: Float, elapsedMs: Long) {
            shimmerPhase = (elapsedMs % 3400L).toFloat() / 3400f
            emberBreath = (0.5 + 0.5 * sin((elapsedMs.toDouble() / 2000.0) * Math.PI * 2.0)).toFloat()
            if (strikeFlash > 0f) strikeFlash = (strikeFlash - dt * 2.6f).coerceAtLeast(0f)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val b = bounds; if (b.isEmpty) return
            val bw = b.width().toFloat(); val bh = b.height().toFloat()
            dstRect.set(b.left + normRect.left * bw, b.top + normRect.top * bh, b.left + normRect.right * bw, b.top + normRect.bottom * bh)
            val offPx = max(1f, bw * 0.008f)
            shadowRect.set(dstRect.left + offPx, dstRect.top + offPx, dstRect.right + offPx, dstRect.bottom + offPx)
            canvas.drawBitmap(runesBitmap, null, shadowRect, shadowPaint)

            basePaint.colorFilter = if (isMutedRed) redFilter else null
            basePaint.alpha = (225 + 30 * emberBreath).toInt().coerceIn(0, 255)
            canvas.drawBitmap(runesBitmap, null, dstRect, basePaint)

            val rw = dstRect.width(); val rh = dstRect.height()
            val lid = canvas.saveLayer(dstRect, null)
            canvas.drawBitmap(runesBitmap, null, dstRect, maskPaint)
            val sx = dstRect.left - rw * 0.55f + (rw * 2.10f) * shimmerPhase
            val sy = dstRect.top - rh * 0.20f + (rh * 0.65f) * shimmerPhase
            val cols = if (isMutedRed) {
                intArrayOf(0x00991B1B, 0x99EF4444.toInt(), 0xFFFCA5A5.toInt(), 0xFFFFE4E6.toInt(), 0xFFFCA5A5.toInt(), 0x99EF4444.toInt(), 0x00991B1B)
            } else {
                intArrayOf(0x00966B12, 0x88D4A234.toInt(), 0xFFFFF08A.toInt(), 0xFFFFFBEB.toInt(), 0xFFFFF08A.toInt(), 0x88D4A234.toInt(), 0x00966B12)
            }
            sweepPaint.shader = LinearGradient(sx, sy, sx + rw * 0.42f, sy + rh * 0.28f, cols, floatArrayOf(0f, 0.22f, 0.44f, 0.50f, 0.56f, 0.78f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(dstRect, sweepPaint)

            if (strikeFlash > 0.02f) {
                sweepPaint.shader = null
                sweepPaint.color = if (isMutedRed) Color.argb((strikeFlash * 225f).toInt(), 255, 160, 160) else Color.argb((strikeFlash * 225f).toInt(), 255, 251, 235)
                canvas.drawRect(dstRect, sweepPaint)
            }
            canvas.restoreToCount(lid)
        }

        override fun setAlpha(alpha: Int) { basePaint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { basePaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class SkyStormBoltsDrawable(private val cores: Array<Bitmap?>, private val halos: Array<Bitmap?>) : Drawable() {
        private val normRects = arrayOf(
            RectF(0.00000f, 0.06207f, 0.44609f, 0.56677f), RectF(0.31562f, 0.00000f, 0.77578f, 0.22696f),
            RectF(0.30469f, 0.26583f, 0.77656f, 0.49843f), RectF(0.67812f, 0.19875f, 1.00000f, 0.56489f)
        )
        private val haloPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val corePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val occPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        private val dstRect = RectF(); private val fullRect = RectF()
        private val startMs = LongArray(4); private val durMs = LongArray(4); private val nextAtMs = LongArray(4)
        private var lastAnyMs = 0L

        init {
            val now = SystemClock.elapsedRealtime()
            for (i in 0 until 4) { durMs[i] = Random.nextLong(460L, 780L); nextAtMs[i] = now + Random.nextLong(400L + i * 520L, 2100L + i * 750L) }
        }

        fun forceStrikeNow() {
            val now = SystemClock.elapsedRealtime()
            startMs[0] = now; durMs[0] = 500L; startMs[3] = now + 45L; durMs[3] = 540L; lastAnyMs = now
            invalidateSelf()
        }

        fun tick(nowMs: Long) {
            for (i in 0 until 4) {
                val active = startMs[i] > 0L && (nowMs - startMs[i]) <= durMs[i]
                if (!active && nowMs >= nextAtMs[i]) {
                    if ((nowMs - lastAnyMs) < 290L) nextAtMs[i] = lastAnyMs + Random.nextLong(320L, 820L)
                    else { startMs[i] = nowMs; durMs[i] = Random.nextLong(440L, 780L); lastAnyMs = nowMs; nextAtMs[i] = nowMs + durMs[i] + Random.nextLong(1250L, 4400L) }
                }
            }
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val b = bounds; if (b.isEmpty) return
            val bw = b.width().toFloat(); val bh = b.height().toFloat()
            fullRect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            val lid = canvas.saveLayer(fullRect, null)
            val nowMs = SystemClock.elapsedRealtime()
            for (i in 0 until 4) {
                val st = startMs[i]; if (st <= 0L) continue
                val el = nowMs - st; if (el < 0L || el > durMs[i]) continue
                val core = cores[i] ?: continue; val halo = halos[i] ?: continue
                val nr = normRects[i]
                dstRect.set(b.left + nr.left * bw, b.top + nr.top * bh, b.left + nr.right * bw, b.top + nr.bottom * bh)
                val p = (el.toFloat() / durMs[i].toFloat()) * 1.30f
                val alpha = if (p <= 0.85f) 248 else ((1f - (p - 0.85f) / 0.45f) * 248f).toInt().coerceIn(0, 255)
                val frontBottom = if (p < 0.52f) dstRect.top + dstRect.height() * (p / 0.52f) else dstRect.bottom
                haloPaint.alpha = alpha; corePaint.alpha = alpha
                canvas.save(); canvas.clipRect(dstRect.left, dstRect.top, dstRect.right, frontBottom)
                canvas.drawBitmap(halo, null, dstRect, haloPaint); canvas.drawBitmap(core, null, dstRect, corePaint)
                canvas.restore()
            }
            val occ = when (currentOccupant) {
                PedestalOccupant.RAVEN -> ravenFgOccluderBmp
                PedestalOccupant.MJOLNIR -> mjolnirFgOccluderBmp
                PedestalOccupant.EMPTY_STONE -> null
            }
            if (occ != null) canvas.drawBitmap(occ, null, fullRect, occPaint)
            canvas.restoreToCount(lid)
        }

        override fun setAlpha(alpha: Int) { corePaint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { corePaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

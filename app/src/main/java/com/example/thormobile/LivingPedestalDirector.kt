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
import android.graphics.RadialGradient
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
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

object LivingPedestalDirector {
    private const val TAG = "LivingPedestalV71"
    private val mainHandler = Handler(Looper.getMainLooper())

    // Exact Original 24k Norse Gold Palette (Restored 1:1 from v26.9.21 / c8c50e9!)
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
    @Volatile var activeRavenPoseIdx: Int = 0
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
    private var lastFlightArchetype: Int = -1

    fun isPedestalAttached(): Boolean = attachedCrestView != null

    private var mjolnirBaseBmp: Bitmap? = null
    private var emptyPedestalBmp: Bitmap? = null
    private var mjolnirSwapCoreBmp: Bitmap? = null
    private var mjolnirSwapHaloBmp: Bitmap? = null
    private val ravenPoses = arrayOfNulls<Bitmap>(3)
    private val ravenOccs = arrayOfNulls<Bitmap>(3)
    private var mjolnirFgOccluderBmp: Bitmap? = null
    private var ravenEyeBlinkPatchBmp: Bitmap? = null
    private var wetRimLeftBmp: Bitmap? = null
    private var wetRimRightBmp: Bitmap? = null
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
        if (mjolnirBaseBmp != null && ravenPoses[0] != null && mjolnirSwapCoreBmp != null && boltCores[0] != null) return
        mjolnirBaseBmp = decodeRaw(app, R.drawable.mjolnir_pedestal_base_hd)
        emptyPedestalBmp = decodeRaw(app, R.drawable.empty_pedestal_base_hd) ?: mjolnirBaseBmp
        mjolnirSwapCoreBmp = decodeRaw(app, R.drawable.mjolnir_swap_bolt_core)
        mjolnirSwapHaloBmp = decodeRaw(app, R.drawable.mjolnir_swap_bolt_halo)
        ravenPoses[0] = decodeRaw(app, R.drawable.raven_storm_base_hd)
        ravenPoses[1] = decodeRaw(app, R.drawable.raven_perch_pose_turn) ?: ravenPoses[0]
        ravenPoses[2] = decodeRaw(app, R.drawable.raven_perch_pose_step) ?: ravenPoses[0]
        ravenOccs[0] = decodeRaw(app, R.drawable.raven_fg_occluder)
        ravenOccs[1] = decodeRaw(app, R.drawable.raven_fg_occluder_turn) ?: ravenOccs[0]
        ravenOccs[2] = decodeRaw(app, R.drawable.raven_fg_occluder_step) ?: ravenOccs[0]
        mjolnirFgOccluderBmp = decodeRaw(app, R.drawable.mjolnir_fg_occluder)
        ravenEyeBlinkPatchBmp = decodeRaw(app, R.drawable.raven_eye_blink_patch)
        wetRimLeftBmp = decodeRaw(app, R.drawable.raven_wet_rim_left)
        wetRimRightBmp = decodeRaw(app, R.drawable.raven_wet_rim_right)
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
        cancelPatrolSchedule(); stopActiveFlightOverlay(); detachMoltenStreamOverlay()
        attachedCrestView?.stopLoop(); attachedCrestView = null
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

    fun pulseMutedRunesFeedback() { mainHandler.post { attachedCrestView?.pulseRunesResonance() } }
    fun triggerLightningPulse() { mainHandler.post { attachedCrestView?.triggerElectricStrikeBurst(460L, 1.0f, 0) } }

    fun onRavenDispatched(ctx: Context, eventKey: String, title: String, cleanBase: String) {
        val app = ctx.applicationContext
        ensureAssetsLoaded(app)
        mainHandler.post {
            hasUnreadRaven = true; ravenCardUnfoldedNow = false
            val crest = attachedCrestView ?: return@post
            crest.refreshDripActivity(true)
            cancelPatrolSchedule()
            if (currentOccupant == PedestalOccupant.RAVEN || flightInProgress) {
                crest.pulseRunesResonance()
                if (!flightInProgress) scheduleNextPatrolFlight(app)
                return@post
            }
            setPedestalOccupant(PedestalOccupant.MJOLNIR)
            launchLivePursuitFlight(app, FlightMode.ARRIVAL) {
                if (hasUnreadRaven && !ravenCardUnfoldedNow) scheduleNextPatrolFlight(app)
            }
        }
    }

    fun onRavenCardUnfolded() {
        mainHandler.post {
            ravenCardUnfoldedNow = true; cancelPatrolSchedule()
            if (flightInProgress) { stopActiveFlightOverlay(); setPedestalOccupant(PedestalOccupant.RAVEN) }
        }
    }

    fun onRavenCardFoldedBackToPedestal(ctx: Context) {
        val app = ctx.applicationContext
        mainHandler.post {
            if (!hasUnreadRaven) return@post
            ravenCardUnfoldedNow = false; setPedestalOccupant(PedestalOccupant.RAVEN)
            attachedCrestView?.pulseRunesResonance(); attachedCrestView?.refreshDripActivity(true)
            scheduleNextPatrolFlight(app)
        }
    }

    fun dismissUnreadRavenWithTakeoff(ctx: Context) {
        val app = ctx.applicationContext
        ensureAssetsLoaded(app)
        mainHandler.post {
            val wasRaven = hasUnreadRaven || currentOccupant == PedestalOccupant.RAVEN
            hasUnreadRaven = false; ravenCardUnfoldedNow = false
            cancelPatrolSchedule(); stopActiveFlightOverlay()
            val crest = attachedCrestView
            if (crest == null) { currentOccupant = PedestalOccupant.MJOLNIR; return@post }
            crest.refreshDripActivity(false)
            if (!wasRaven) { setPedestalOccupant(PedestalOccupant.MJOLNIR); return@post }
            launchLivePursuitFlight(app, FlightMode.DEPARTURE) { crest.refreshDripActivity(false) }
        }
    }

    private fun cancelPatrolSchedule() { patrolRunnable?.let { mainHandler.removeCallbacks(it) }; patrolRunnable = null }

    private fun scheduleNextPatrolFlight(app: Context) {
        cancelPatrolSchedule()
        if (!hasUnreadRaven || ravenCardUnfoldedNow) return
        val roll = Random.nextFloat()
        val delayMs = when {
            roll < 0.34f -> Random.nextLong(4_000L, 6_600L)
            roll < 0.80f -> Random.nextLong(6_600L, 10_800L)
            else         -> Random.nextLong(10_800L, 15_600L)
        }
        val r = Runnable {
            if (!hasUnreadRaven || ravenCardUnfoldedNow || flightInProgress || attachedCrestView == null) return@Runnable
            launchLivePursuitFlight(app, FlightMode.PATROL) {
                if (hasUnreadRaven && !ravenCardUnfoldedNow) scheduleNextPatrolFlight(app)
            }
        }
        patrolRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private enum class FlightMode { ARRIVAL, PATROL, DEPARTURE }

    private fun getPerchInOverlayCoords(app: Context, targetView: View?): PointF {
        val crest = attachedCrestView
        if (crest != null && crest.isAttachedToWindow) {
            val cLoc = IntArray(2); crest.getLocationOnScreen(cLoc)
            val oLoc = IntArray(2); if (targetView != null && targetView.isAttachedToWindow) targetView.getLocationOnScreen(oLoc)
            return PointF((cLoc[0] - oLoc[0]) + crest.padSide + crest.cardW * 0.49f, (cLoc[1] - oLoc[1]) + crest.padTop + crest.cardH * 0.34f)
        }
        return PointF(dp(app, 75f), dp(app, 84f))
    }

    private fun launchLivePursuitFlight(app: Context, mode: FlightMode, onComplete: (() -> Unit)?) {
        if (!Settings.canDrawOverlays(app)) {
            setPedestalOccupant(if (mode == FlightMode.DEPARTURE) PedestalOccupant.MJOLNIR else PedestalOccupant.RAVEN)
            onComplete?.invoke(); return
        }
        stopActiveFlightOverlay()
        val wm = flightWindowManager ?: (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { flightWindowManager = it }
        val stageView = RavenFlightStageView(app, flyFrames)
        try {
            wm.addView(stageView, buildFullGlassOverlayParams(app))
            activeFlightView = stageView; flightInProgress = true
        } catch (_: Exception) {
            setPedestalOccupant(if (mode == FlightMode.DEPARTURE) PedestalOccupant.MJOLNIR else PedestalOccupant.RAVEN)
            onComplete?.invoke(); return
        }

        val (swInt, shInt) = getPhysicalGlassSize(app)
        val sw = swInt.toFloat(); val sh = shInt.toFloat()
        val startPerch = getPerchInOverlayCoords(app, stageView)

        var bx: Float; var by: Float; var headingRad: Float; var speedPx: Float
        if (mode == FlightMode.ARRIVAL) {
            bx = sw + dp(app, 55f); by = sh * (0.12f + Random.nextFloat() * 0.14f)
            headingRad = Math.toRadians(165.0).toFloat(); speedPx = dp(app, 420f)
        } else {
            bx = startPerch.x; by = startPerch.y
            headingRad = Math.toRadians((-18.0 - Random.nextDouble() * 14.0)).toFloat()
            speedPx = dp(app, 330f)
            setPedestalOccupant(PedestalOccupant.EMPTY_STONE)
        }

        val cruiseWps = ArrayList<PointF>()
        val pedOnLeft = startPerch.x < sw * 0.52f
        val farX = if (pedOnLeft) sw * (0.76f + Random.nextFloat() * 0.12f) else sw * (0.14f + Random.nextFloat() * 0.12f)
        val nearX = if (pedOnLeft) sw * (0.16f + Random.nextFloat() * 0.10f) else sw * (0.72f + Random.nextFloat() * 0.10f)

        when (mode) {
            FlightMode.ARRIVAL -> cruiseWps.add(PointF(sw * 0.20f, sh * (0.24f + Random.nextFloat() * 0.14f)))
            FlightMode.DEPARTURE -> cruiseWps.add(PointF(sw + dp(app, 170f), max(dp(app, 60f), startPerch.y - dp(app, 140f))))
            FlightMode.PATROL -> {
                var arch = Random.nextInt(5)
                if (arch == lastFlightArchetype) arch = (arch + 1 + Random.nextInt(4)) % 5
                lastFlightArchetype = arch
                fun jit(x: Float, y: Float) = PointF(
                    (x + (Random.nextFloat() - 0.5f) * dp(app, 44f)).coerceIn(dp(app, 36f), sw - dp(app, 36f)),
                    (y + (Random.nextFloat() - 0.5f) * dp(app, 52f)).coerceIn(dp(app, 55f), sh - dp(app, 120f))
                )
                when (arch) {
                    0 -> { cruiseWps.add(jit(sw * 0.58f, max(dp(app, 85f), startPerch.y + dp(app, 70f)))); cruiseWps.add(jit(sw * 0.20f, max(dp(app, 95f), startPerch.y + dp(app, 130f)))) }
                    1 -> { cruiseWps.add(jit(farX, sh * 0.14f)); cruiseWps.add(jit(farX * 0.85f, sh * 0.46f)); cruiseWps.add(jit(sw * 0.16f, sh * 0.28f)) }
                    2 -> { cruiseWps.add(jit(sw * 0.68f, sh * 0.45f)); cruiseWps.add(jit(farX, sh * 0.78f)); cruiseWps.add(jit(sw * 0.22f, sh * 0.62f)) }
                    3 -> { cruiseWps.add(jit(farX, sh * 0.24f)); cruiseWps.add(jit(nearX, sh * 0.54f)); cruiseWps.add(jit(farX, sh * 0.62f)); cruiseWps.add(jit(sw * 0.16f, sh * 0.30f)) }
                    else -> { cruiseWps.add(jit(farX, sh * 0.28f)); cruiseWps.add(jit(sw * 0.54f, sh * 0.52f)); cruiseWps.add(jit(farX * 0.9f, sh * 0.34f)); cruiseWps.add(jit(sw * 0.18f, sh * 0.24f)) }
                }
            }
        }

        var wpIdx = 0; var passedLeftGate = false
        var liftoffHammerMaterialized = (mode == FlightMode.ARRIVAL); var approachHammerDissolved = false
        val startMs = SystemClock.elapsedRealtime(); var lastMs = startMs
        var prevPerchX = startPerch.x; var prevPerchY = startPerch.y; var wingPhase = 0f

        flightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 60_000L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener {
                val now = SystemClock.elapsedRealtime()
                val dt = ((now - lastMs).coerceIn(1L, 45L)).toFloat() / 1000f
                lastMs = now; val elapsedMs = now - startMs

                if (!liftoffHammerMaterialized && elapsedMs >= 250L) {
                    liftoffHammerMaterialized = true
                    attachedCrestView?.triggerElectricStrikeBurst(460L, 1.0f, 0)
                    mainHandler.postDelayed({
                        if (flightInProgress && currentOccupant == PedestalOccupant.EMPTY_STONE) setPedestalOccupant(PedestalOccupant.MJOLNIR)
                    }, 85L)
                }

                val livePerch = getPerchInOverlayCoords(app, stageView)
                val pedSpeed = hypot((livePerch.x - prevPerchX).toDouble(), (livePerch.y - prevPerchY).toDouble()).toFloat() / max(dt, 0.008f)
                prevPerchX = livePerch.x; prevPerchY = livePerch.y
                if (pedSpeed > dp(app, 210f)) passedLeftGate = false

                val homingPhase = (wpIdx >= cruiseWps.size) && (mode != FlightMode.DEPARTURE)
                val targetX: Float; val targetY: Float

                if (!homingPhase) {
                    val wp = cruiseWps[min(wpIdx, cruiseWps.size - 1)]
                    targetX = wp.x; targetY = wp.y
                    if (mode != FlightMode.DEPARTURE && hypot((wp.x - bx).toDouble(), (wp.y - by).toDouble()) < dp(app, 86f)) wpIdx++
                    if (mode == FlightMode.DEPARTURE && bx > sw + dp(app, 95f)) {
                        stopActiveFlightOverlay(); onComplete?.invoke(); return@addUpdateListener
                    }
                } else {
                    val gateX = livePerch.x - dp(app, 92f)
                    val gateY = (livePerch.y - dp(app, 24f)).coerceIn(dp(app, 36f), sh - dp(app, 90f))
                    if (!passedLeftGate) {
                        targetX = gateX; targetY = gateY
                        if (hypot((gateX - bx).toDouble(), (gateY - by).toDouble()) < dp(app, 42f) || (bx < livePerch.x - dp(app, 35f) && cos(headingRad.toDouble()) > 0.45)) passedLeftGate = true
                    } else { targetX = livePerch.x; targetY = livePerch.y }

                    val distStone = hypot((livePerch.x - bx).toDouble(), (livePerch.y - by).toDouble()).toFloat()
                    if (!approachHammerDissolved && passedLeftGate && distStone < dp(app, 88f) && pedSpeed < dp(app, 120f)) {
                        approachHammerDissolved = true
                        attachedCrestView?.triggerElectricStrikeBurst(340L, 1.0f, 0)
                        mainHandler.postDelayed({
                            if (flightInProgress && hasUnreadRaven) setPedestalOccupant(PedestalOccupant.EMPTY_STONE)
                        }, 80L)
                    }
                    if (passedLeftGate && distStone <= dp(app, 11f) && pedSpeed < dp(app, 90f)) {
                        setPedestalOccupant(if (hasUnreadRaven) PedestalOccupant.RAVEN else PedestalOccupant.MJOLNIR)
                        attachedCrestView?.pulseRunesResonance()
                        stopActiveFlightOverlay(); onComplete?.invoke(); return@addUpdateListener
                    }
                }

                val desiredAngle = atan2((targetY - by).toDouble(), (targetX - bx).toDouble()).toFloat()
                var angDiff = desiredAngle - headingRad
                while (angDiff > Math.PI) angDiff -= (Math.PI * 2.0).toFloat()
                while (angDiff < -Math.PI) angDiff += (Math.PI * 2.0).toFloat()

                val distStone = hypot((livePerch.x - bx).toDouble(), (livePerch.y - by).toDouble()).toFloat()
                val maxTurnRate = if (homingPhase && passedLeftGate) 7.5f else 3.9f
                headingRad += angDiff.coerceIn(-maxTurnRate * dt, maxTurnRate * dt)

                val targetSpd = when {
                    mode == FlightMode.DEPARTURE -> dp(app, 520f)
                    !homingPhase -> dp(app, 425f)
                    !passedLeftGate -> max(dp(app, 360f), min(dp(app, 620f), pedSpeed * 1.15f))
                    else -> max(dp(app, 95f) + (distStone / dp(app, 120f)).coerceIn(0f, 1f) * dp(app, 290f), min(dp(app, 580f), pedSpeed * 1.15f))
                }
                speedPx += (targetSpd - speedPx) * min(1f, dt * 5.5f)

                val vx = cos(headingRad.toDouble()).toFloat() * speedPx
                val vy = sin(headingRad.toDouble()).toFloat() * speedPx
                bx += vx * dt; by += vy * dt

                val isGlide = !homingPhase && (vy / max(1f, speedPx) > 0.22f) && elapsedMs > 360L
                val flapHz = if (homingPhase && passedLeftGate && distStone < dp(app, 85f)) 3.4f else 2.5f
                wingPhase = (wingPhase + dt * flapHz) % 1f

                val nearStone = (homingPhase && passedLeftGate && distStone < dp(app, 85f)) || (mode != FlightMode.ARRIVAL && elapsedMs < 240L)
                val scale = if (homingPhase && passedLeftGate) 0.92f + 0.16f * (distStone / dp(app, 110f)).coerceIn(0f, 1f)
                else if (mode != FlightMode.ARRIVAL && elapsedMs < 260L) 0.92f + 0.16f * (elapsedMs / 260f) else 1.08f

                val lockRight = (homingPhase && passedLeftGate) || (mode != FlightMode.ARRIVAL && elapsedMs < 200L)
                val pitchDamp = if (homingPhase && passedLeftGate && distStone < dp(app, 55f)) (distStone / dp(app, 55f)).coerceIn(0f, 1f) else 1f
                stageView.updateFluidPose(bx, by, vx, vy * pitchDamp, dt, wingPhase, isGlide, scale, lockRight, nearStone)
            }
            start()
        }
    }

    private fun stopActiveFlightOverlay() {
        flightAnimator?.cancel(); flightAnimator = null; flightInProgress = false
        val v = activeFlightView; activeFlightView = null
        if (v != null && v.parent != null) { try { flightWindowManager?.removeView(v) } catch (_: Exception) {} }
    }

    private class RavenFlightStageView(ctx: Context, private val frames: Array<Bitmap?>) : View(ctx) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { alpha = 255 }
        private val baseBoxPx = dp(ctx, 134f)
        private val dstRect = RectF()
        private var curX = -500f; private var curY = -500f
        private var facingSign = 1f; private var bankTiltDeg = 0f; private var smoothPitchDeg = 0f
        private var frameIdx = 1; private var wingStrokeScaleY = 1f; private var curScale = 1f

        fun updateFluidPose(x: Float, y: Float, vx: Float, vy: Float, dt: Float, wingPhase: Float, gliding: Boolean, scale: Float, forceRight: Boolean, dampenBob: Boolean) {
            val bob = if (gliding || dampenBob) 0f else sin(wingPhase * Math.PI * 2.0).toFloat() * dp(context, 2.0f)
            curX = x; curY = y + bob
            val wantRight = if (forceRight) true else if (abs(vx) > dp(context, 12f)) (vx >= 0f) else (facingSign > 0f)
            val newSign = if (wantRight) 1f else -1f
            if (newSign != facingSign) { facingSign = newSign; bankTiltDeg = -14f }
            bankTiltDeg += (0f - bankTiltDeg) * min(1f, dt * 8.5f)
            val targetPitch = Math.toDegrees(atan2(vy.toDouble(), abs(vx).coerceAtLeast(1f).toDouble())).toFloat().coerceIn(-22f, 22f)
            smoothPitchDeg += (targetPitch - smoothPitchDeg) * min(1f, dt * 10.0f)
            if (gliding) { frameIdx = 1; wingStrokeScaleY = 1.0f }
            else {
                frameIdx = when ((wingPhase * 4f).toInt().coerceIn(0, 3)) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 1 }
                wingStrokeScaleY = 0.94f + 0.09f * abs(cos(wingPhase * Math.PI * 2.0)).toFloat()
            }
            curScale = scale
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val bmp = frames.getOrNull(frameIdx) ?: frames.firstOrNull { it != null } ?: return
            val half = (baseBoxPx * curScale) * 0.5f
            canvas.save(); canvas.translate(curX, curY); canvas.scale(facingSign, wingStrokeScaleY); canvas.rotate(smoothPitchDeg + bankTiltDeg)
            dstRect.set(-half, -half, half, half); paint.alpha = 255
            canvas.drawBitmap(bmp, null, dstRect, paint)
            canvas.restore()
        }
    }

    /**
     * Exact Restored `image_5.png` Mjolnir Lightning (`mjolnir_swap_bolt_core/halo` from v26.9.21)!
     * Supports sectorMode (0 = full swap cascade, 1 = left/head forks only, 2 = right/pommel forks only)
     * so idle Mjolnir crackles individual `image_5` branches asynchronously instead of firing everything simultaneously!
     */
    private class ElectricHammerLightningDrawable : Drawable() {
        private val haloPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val corePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dstRect = RectF()
        var progress: Float = -1f
            set(value) { field = value; invalidateSelf() }
        var intensityScale: Float = 1.0f
        var sectorMode: Int = 0

        override fun draw(canvas: Canvas) {
            val p = progress
            if (currentOccupant == PedestalOccupant.RAVEN || p < 0f || p > 1f) return
            val b = bounds; if (b.isEmpty) return
            val core = mjolnirSwapCoreBmp ?: boltCores[0] ?: return
            val halo = mjolnirSwapHaloBmp ?: boltHalos[0] ?: core
            dstRect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            val w = dstRect.width(); val h = dstRect.height()

            val cLeft = if (sectorMode == 2) dstRect.left + w * 0.42f else dstRect.left
            val cRight = if (sectorMode == 1) dstRect.left + w * 0.58f else dstRect.right

            if (p <= 0.42f) {
                val step = (((p / 0.42f) * 8f).toInt() + 1) / 8f
                val frontY = dstRect.top + h * step.coerceIn(0.15f, 1f)
                haloPaint.alpha = (245 * intensityScale).toInt().coerceIn(0, 255)
                corePaint.alpha = (255 * intensityScale).toInt().coerceIn(0, 255)
                canvas.save(); canvas.clipRect(cLeft, dstRect.top, cRight, frontY)
                canvas.drawBitmap(halo, null, dstRect, haloPaint)
                canvas.drawBitmap(core, null, dstRect, corePaint)
                canvas.drawBitmap(core, null, dstRect, corePaint)
                canvas.restore()
            } else if (p <= 0.76f) {
                val crackle = if (p in 0.56f..0.61f) 0.50f else 1.0f
                haloPaint.alpha = (255 * crackle * intensityScale).toInt().coerceIn(0, 255)
                corePaint.alpha = (255 * crackle * intensityScale).toInt().coerceIn(0, 255)
                canvas.save(); canvas.clipRect(cLeft, dstRect.top, cRight, dstRect.bottom)
                canvas.drawBitmap(halo, null, dstRect, haloPaint)
                canvas.drawBitmap(core, null, dstRect, corePaint)
                if (crackle > 0.9f) canvas.drawBitmap(core, null, dstRect, corePaint)
                canvas.restore()
            } else {
                val fade = ((1f - p) / 0.24f).coerceIn(0f, 1f)
                val tailTop = dstRect.top + h * (1f - fade) * 0.45f
                haloPaint.alpha = (245 * fade * intensityScale).toInt().coerceIn(0, 255)
                corePaint.alpha = (255 * fade * intensityScale).toInt().coerceIn(0, 255)
                canvas.save(); canvas.clipRect(cLeft, tailTop, cRight, dstRect.bottom)
                canvas.drawBitmap(halo, null, dstRect, haloPaint)
                canvas.drawBitmap(core, null, dstRect, corePaint)
                canvas.restore()
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * Heavy 19.3 g/cm3 Molten Gold Pool in Exact Original v26.9.21 24k Gold Palette!
     */
    private class MoltenGoldScreenStreamView(ctx: Context) : View(ctx), SensorEventListener {
        private data class GoldGlob(var x: Float = 0f, var y: Float = 0f, var vx: Float = 0f, var vy: Float = 0f, var r: Float = 0f, var weight: Float = 1f, var isTaffy: Boolean = false, var active: Boolean = false)

        private val globs = Array(64) { GoldGlob() }
        private val cols = 72
        private val meshRows = 3
        private val h = FloatArray(cols + 1)
        private val uVel = FloatArray(cols + 1)
        private val tmpH = FloatArray(cols + 1)
        private val tmpU = FloatArray(cols + 1)
        private val flux = FloatArray(cols + 1)
        private val poolMeshVerts = FloatArray((cols + 1) * (meshRows + 1) * 2)

        private val globPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD_GLINT }
        private val baseBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { alpha = 155 }
        private val lipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val dropPath = Path()
        private val pilePath = Path()
        private val scrolledPoolBmp: Bitmap? = ringStripBmp?.let { Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888) }
        private val scrolledPoolCanvas: Canvas? = scrolledPoolBmp?.let { Canvas(it) }

        private var sensorMgr: SensorManager? = null
        private var rawSinTilt = 0f
        private var smoothSinTilt = 0f
        private var uvShiftPx = 0f

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
            sensorMgr = null; super.onDetachedFromWindow()
        }

        override fun onSensorChanged(event: SensorEvent?) {
            val v = event?.values ?: return
            rawSinTilt = (-v[0] / 9.81f).coerceIn(-0.92f, 0.92f)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        fun spawnDetachedDropFromPedestal(screenX: Float, screenY: Float) {
            val wLoc = IntArray(2); getLocationOnScreen(wLoc)
            val lx = screenX - wLoc[0]; val ly = screenY - wLoc[1]
            globs.firstOrNull { !it.active }?.apply {
                active = true; isTaffy = false
                x = lx + (Random.nextFloat() - 0.5f) * dp(context, 1.0f); y = ly
                vx = 0f; vy = dp(context, 54f); r = dp(context, Random.nextFloat() * 1.0f + 4.5f); weight = 1.0f
            }
            globs.firstOrNull { !it.active }?.apply {
                active = true; isTaffy = true
                x = lx; y = ly - dp(context, 5.2f); vx = 0f; vy = dp(context, 22f); r = dp(context, 1.85f); weight = 0.22f
            }
        }

        fun stepPhysics(dt: Float, flowActive: Boolean): Boolean {
            val (physW, physH) = getPhysicalGlassSize(context)
            val sw = max(width.toFloat(), physW.toFloat())
            val wLoc = IntArray(2); getLocationOnScreen(wLoc)
            val sh = (physH - wLoc[1]).toFloat().coerceAtLeast(200f) - dp(context, 4f)
            val dx = sw / cols.toFloat()

            smoothSinTilt += (rawSinTilt - smoothSinTilt) * min(1f, dt * 12.0f)

            var anyGlobs = false
            for (g in globs) {
                if (!g.active) continue
                anyGlobs = true
                g.vx += smoothSinTilt * dp(context, 155f) * dt
                g.vy += dp(context, if (g.isTaffy) 780f else 620f) * dt
                g.x = (g.x + g.vx * dt).coerceIn(dp(context, 6f), sw - dp(context, 6f))
                g.y += g.vy * dt

                val ci = ((g.x / sw) * cols).toInt().coerceIn(0, cols)
                val surfY = sh - h[ci]
                if (g.y >= surfY) {
                    g.active = false
                    val addPeak = dp(context, if (g.isTaffy) 1.0f else 7.8f) * g.weight
                    val maxCap = dp(context, 128f)
                    for (k in max(0, ci - 5)..min(cols, ci + 5)) {
                        val d = (k - ci).toFloat()
                        val w = exp((-d * d / 8.5f).toDouble()).toFloat()
                        h[k] = (h[k] + addPeak * w).coerceAtMost(maxCap)
                        uVel[k] += (if (k < ci) -dp(context, 48f) else dp(context, 48f)) * w * g.weight
                    }
                    if (!g.isTaffy && g.weight >= 0.75f) {
                        for (s in 0 until 5) {
                            globs.firstOrNull { !it.active }?.apply {
                                active = true; isTaffy = true
                                val side = if (s % 2 == 0) 1f else -1f
                                x = g.x + side * dp(context, 3.2f); y = surfY - dp(context, 2.5f)
                                vx = side * dp(context, 48f + Random.nextFloat() * 52f) + smoothSinTilt * dp(context, 38f)
                                vy = -dp(context, 88f + Random.nextFloat() * 58f)
                                r = dp(context, 2.2f + Random.nextFloat() * 1.2f); weight = 0.28f
                            }
                        }
                    }
                }
            }

            val gravTilt = smoothSinTilt * dp(context, 1240f)
            val hydroK = dp(context, 540f)
            val viscShear = 240f
            val bedDecay = exp((-5.2f * dt).toDouble()).toFloat()
            val maxU = dp(context, 520f)

            for (i in 1 until cols) {
                val locH = max(dp(context, 0.4f), 0.5f * (h[i] + h[i - 1]))
                val dhdx = (h[i] - h[i - 1]) / dx
                val lapU = uVel[i + 1] + uVel[i - 1] - 2f * uVel[i]
                val mob = min(1.40f, (locH / dp(context, 28f)).toDouble().pow(0.55).toFloat())
                val nu = (uVel[i] + (-hydroK * dhdx + gravTilt * mob + viscShear * lapU * dt) * dt) * bedDecay
                tmpU[i] = nu.coerceIn(-maxU, maxU)
            }
            tmpU[0] = 0f; tmpU[cols] = 0f
            for (i in 0..cols) uVel[i] = tmpU[i]

            for (i in 1 until cols) {
                val vel = uVel[i]
                val donor = if (vel >= 0f) h[i - 1] else h[i]
                val q = vel * donor * dt / dx
                flux[i] = if (vel >= 0f) min(q, h[i - 1] * 0.45f) else max(q, -h[i] * 0.45f)
            }

            var avgVel = 0f
            val maxCap = dp(context, 128f)
            for (i in 0..cols) {
                val fIn = if (i > 0) flux[i] else 0f
                val fOut = if (i < cols) flux[i + 1] else 0f
                var nh = h[i] + fIn - fOut
                if (!flowActive) nh -= dp(context, 6.0f) * dt
                tmpH[i] = nh.coerceIn(0f, maxCap)
                avgVel += uVel[i]
            }

            var maxPile = 0f
            for (i in 0..cols) {
                val l = tmpH[max(0, i - 1)]; val c = tmpH[i]; val r = tmpH[min(cols, i + 1)]
                h[i] = 0.20f * l + 0.60f * c + 0.20f * r
                if (h[i] > maxPile) maxPile = h[i]
            }

            if (maxPile > 0.4f) {
                val src = ringStripBmp
                if (src != null && scrolledPoolCanvas != null) {
                    val wBmp = src.width.toFloat()
                    uvShiftPx = ((uvShiftPx - (avgVel / cols.toFloat()) * dt * 0.55f) % wBmp + wBmp) % wBmp
                    scrolledPoolCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    scrolledPoolCanvas.drawBitmap(src, -uvShiftPx, 0f, null)
                    scrolledPoolCanvas.drawBitmap(src, wBmp - uvShiftPx, 0f, null)
                }
                val glassBot = sh + dp(context, 14f)
                for (r in 0..meshRows) {
                    val rf = r.toFloat() / meshRows.toFloat()
                    for (c in 0..cols) {
                        val px = (c.toFloat() / cols.toFloat()) * sw
                        val topY = sh - h[c]
                        val idx = (r * (cols + 1) + c) * 2
                        poolMeshVerts[idx] = px
                        poolMeshVerts[idx + 1] = topY + (glassBot - topY) * rf
                    }
                }
            }
            invalidate()
            return flowActive || anyGlobs || maxPile > 0.4f
        }

        override fun onDraw(canvas: Canvas) {
            val (physW, physH) = getPhysicalGlassSize(context)
            val sw = max(width.toFloat(), physW.toFloat())
            val wLoc = IntArray(2); getLocationOnScreen(wLoc)
            val sh = (physH - wLoc[1]).toFloat().coerceAtLeast(200f) - dp(context, 4f)
            val glassBot = sh + dp(context, 14f)
            val dx = sw / cols.toFloat()

            for (d in globs) {
                if (!d.active) continue
                val spd = hypot(d.vx.toDouble(), d.vy.toDouble()).toFloat().coerceAtLeast(1f)
                val stretch = (1.28f + spd / dp(context, 560f)).coerceAtMost(2.2f)
                val angDeg = Math.toDegrees(atan2(d.vx.toDouble(), d.vy.toDouble())).toFloat()
                canvas.save(); canvas.translate(d.x, d.y); canvas.rotate(-angDeg)
                dropPath.reset()
                dropPath.moveTo(0f, -d.r * stretch)
                dropPath.quadTo(d.r * 1.12f, -d.r * 0.1f, 0f, d.r)
                dropPath.quadTo(-d.r * 1.12f, -d.r * 0.1f, 0f, -d.r * stretch)
                dropPath.close()
                globPaint.shader = LinearGradient(-d.r, 0f, d.r, 0f, intArrayOf(GOLD_BRONZE, GOLD_WARM, GOLD_RICH, GOLD_BRONZE), floatArrayOf(0f, 0.38f, 0.78f, 1f), Shader.TileMode.CLAMP)
                canvas.drawPath(dropPath, globPaint)
                canvas.drawCircle(-d.r * 0.25f, d.r * 0.12f, d.r * 0.30f, glintPaint)
                canvas.restore()
            }

            var peakH = 0f
            for (hv in h) if (hv > peakH) peakH = hv
            if (peakH > 0.4f) {
                pilePath.reset(); pilePath.moveTo(0f, glassBot)
                for (k in 0..cols) pilePath.lineTo((k.toFloat() / cols.toFloat()) * sw, sh - h[k])
                pilePath.lineTo(sw, glassBot); pilePath.close()

                canvas.save(); canvas.clipPath(pilePath)
                baseBodyPaint.shader = LinearGradient(0f, sh - peakH - dp(context, 2f), 0f, glassBot, intArrayOf(GOLD_WARM, GOLD_RICH, GOLD_BRONZE, GOLD_SHADOW), floatArrayOf(0f, 0.38f, 0.78f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, sh - peakH - dp(context, 4f), sw, glassBot, baseBodyPaint)

                (scrolledPoolBmp ?: ringStripBmp)?.let { tex ->
                    canvas.drawBitmapMesh(tex, cols, meshRows, poolMeshVerts, 0, null, 0, meshPaint)
                }

                val lipUnderPx = dp(context, 1.8f)
                val darkW = dp(context, 3.4f)
                val specW = dp(context, 1.8f)
                for (i in 0 until cols) {
                    if (h[i] <= dp(context, 0.5f) && h[i + 1] <= dp(context, 0.5f)) continue
                    val x0 = (i.toFloat() / cols.toFloat()) * sw; val x1 = ((i + 1).toFloat() / cols.toFloat()) * sw
                    val y0 = sh - h[i]; val y1 = sh - h[i + 1]
                    lipPaint.strokeWidth = darkW; lipPaint.color = 0x883A2203.toInt()
                    canvas.drawLine(x0, y0 + lipUnderPx, x1, y1 + lipUnderPx, lipPaint)

                    val slope = (h[i + 1] - h[i]) / dx
                    val spec = (0.32f + abs(slope) * 1.55f).coerceIn(0f, 1f)
                    val specAlpha = (38 + (182f * spec * spec).toInt()).coerceIn(0, 220)
                    lipPaint.strokeWidth = specW
                    lipPaint.color = (GOLD_GLINT and 0x00FFFFFF) or (specAlpha shl 24)
                    canvas.drawLine(x0, y0 + dp(context, 0.7f), x1, y1 + dp(context, 0.7f), lipPaint)
                }
                canvas.restore()
            }
        }
    }

    /**
     * Living Pedestal Crest View V7.1:
     * - Exact Original 24k Gold Ring Strip + Crisp Individual Sparkling Gold-Dust Specks Outside the Gold Ring!
     */
    class LivingPedestalCrestView(ctx: Context) : FrameLayout(ctx) {
        val padSide = dpInt(ctx, 20); val padTop = dpInt(ctx, 20); val padBottom = dpInt(ctx, 34)
        val cardW = dpInt(ctx, 112); val cardH = dpInt(ctx, 140)
        private val cornerR = dp(ctx, 20f)
        val totalWindowWidthPx: Int = cardW + padSide * 2
        val totalWindowHeightPx: Int = cardH + padTop + padBottom

        private val artView: PedestalBackdropView
        private val electricHammerDrawable = ElectricHammerLightningDrawable()
        private val runesDrawable: ShimmeringRuneOverlayDrawable?
        private val skyBoltsDrawable = SkyStormBoltsDrawable(boltCores, boltHalos)

        private val flankSegs = 64; private val radSteps = 8
        private val rightBx = FloatArray(flankSegs + 1); private val rightBy = FloatArray(flankSegs + 1)
        private val rightNx = FloatArray(flankSegs + 1); private val rightNy = FloatArray(flankSegs + 1)
        private val leftBx = FloatArray(flankSegs + 1);  private val leftBy = FloatArray(flankSegs + 1)
        private val leftNx = FloatArray(flankSegs + 1);  private val leftNy = FloatArray(flankSegs + 1)
        private val rightMeshVerts = FloatArray((flankSegs + 1) * (radSteps + 1) * 2)
        private val leftMeshVerts = FloatArray((flankSegs + 1) * (radSteps + 1) * 2)
        private var meshReady = false

        // 84 Individual Sparkling 24k Gold-Dust Specks Outside the Gold Ring (NEVER smear or flow into a band!)
        private val dustCount = 84
        private val dustFlank = BooleanArray(dustCount) { it % 2 == 0 }
        private val dustU = FloatArray(dustCount) { Random.nextFloat() }
        private val dustOutDp = FloatArray(dustCount) { 4.2f + Random.nextFloat() * 12.2f }
        private val dustRadDp = FloatArray(dustCount) { 0.55f + Random.nextFloat() * 1.15f }
        private val dustTwinklePhase = FloatArray(dustCount) { Random.nextFloat() * 6.283f }
        private val dustTwinkleHz = FloatArray(dustCount) { 1.4f + Random.nextFloat() * 2.6f }
        private val dustDriftRate = FloatArray(dustCount) { 0.012f + Random.nextFloat() * 0.024f }
        private val dustCorePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dustHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val scrolledStripBmp: Bitmap? = ringStripBmp?.let { Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888) }
        private val scrolledStripCanvas: Canvas? = scrolledStripBmp?.let { Canvas(it) }
        private val stripDrawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val ringPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { alpha = 246 }

        private val pendantPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pendantStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val pendantGlint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pendantPath = Path()
        private var pendantFill = 0f; private var recoilPhase = 0f

        private var lastFrameMs = SystemClock.elapsedRealtime()
        private var flowPhase = 0f
        private var isStandbyMode = false; private var isSessionOpen = false; private var isSpeakingNow = false; private var hasUnreadRavenFlag = false
        private var nextMjolnirIdleArcAtMs = SystemClock.elapsedRealtime() + 3400L
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
                roundedCard.addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageDrawable(runesDrawable); layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT) })
            }
            roundedCard.addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageDrawable(skyBoltsDrawable); layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT) })
            roundedCard.addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageDrawable(electricHammerDrawable); layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT) })

            addView(roundedCard)
            refreshScrolledStripBitmap()
            startMasterLoop()
        }

        private fun refreshScrolledStripBitmap() {
            val src = ringStripBmp ?: return; val c = scrolledStripCanvas ?: return
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val w = src.width.toFloat(); val shiftPx = ((flowPhase % 1f + 1f) % 1f) * w
            c.drawBitmap(src, shiftPx, 0f, stripDrawPaint); c.drawBitmap(src, shiftPx - w, 0f, stripDrawPaint)
        }

        fun setOccupantImmediate(occ: PedestalOccupant) {
            artView.setOccupant(occ)
            if (occ == PedestalOccupant.MJOLNIR) nextMjolnirIdleArcAtMs = SystemClock.elapsedRealtime() + Random.nextLong(3200L, 6200L)
            invalidate()
        }

        fun pulseRunesResonance() {
            runesDrawable?.triggerLightningResonance()
            skyBoltsDrawable.forceSingleStrikeNow()
            invalidate()
        }

        fun triggerElectricStrikeBurst(durationMs: Long = 460L, intensity: Float = 1.0f, sector: Int = 0) {
            strikeAnimator?.cancel()
            skyBoltsDrawable.forceSingleStrikeNow()
            runesDrawable?.triggerLightningResonance()
            electricHammerDrawable.intensityScale = intensity
            electricHammerDrawable.sectorMode = sector
            strikeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs; interpolator = LinearInterpolator()
                addUpdateListener { a -> electricHammerDrawable.progress = a.animatedValue as Float }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { electricHammerDrawable.progress = -1f }
                })
                start()
            }
        }

        fun updateOperationalState(standby: Boolean, sessionOpen: Boolean, speaking: Boolean, unreadRaven: Boolean) {
            val standbyChanged = (isStandbyMode != standby)
            val sessionJustOpened = (!isSessionOpen && sessionOpen)
            isStandbyMode = standby; isSessionOpen = sessionOpen; isSpeakingNow = speaking; hasUnreadRavenFlag = unreadRaven
            runesDrawable?.isMutedRed = standby
            runesDrawable?.isSessionHot = sessionOpen && !standby
            if (standbyChanged) runesDrawable?.triggerLightningResonance()
            if (sessionJustOpened && !standby) {
                if (currentOccupant == PedestalOccupant.MJOLNIR) triggerElectricStrikeBurst(460L, 1.0f, 0) else pulseRunesResonance()
            }
            invalidate()
        }

        fun refreshDripActivity(unreadRaven: Boolean) { hasUnreadRavenFlag = unreadRaven; invalidate() }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 20 && h > 20) { buildDualFlankGeometry(); meshReady = true; updateDualCascadingMeshes(0f) }
        }

        private fun buildDualFlankGeometry() {
            val left = padSide.toFloat(); val top = padTop.toFloat()
            val right = (padSide + cardW).toFloat(); val bottom = (padTop + cardH).toFloat()
            val cx = (left + right) * 0.5f; val r = cornerR.coerceAtMost(min(cardW, cardH) * 0.46f)
            val halfTop = ((right - r) - cx).coerceAtLeast(1f); val side = ((bottom - r) - (top + r)).coerceAtLeast(1f)
            val halfBot = ((right - r) - cx).coerceAtLeast(1f); val arc = (0.5 * Math.PI * r).toFloat()
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
            // Keep scrolling molten gold focused in a crisp 11.5dp border channel so outer gold-dust specks stay distinct!
            val outerSpan = dp(context, 11.5f) - innerTuck
            val pull = dp(context, 4.8f) * (0.28f + 0.72f * swell)
            for (i in 0..flankSegs) {
                val u = i.toFloat() / flankSegs.toFloat()
                val bell = if (u > 0.85f) { val t = (u - 0.85f) / 0.15f; t * t * (3f - 2f * t) } else 0f
                for (j in 0..radSteps) {
                    val rf = j.toFloat() / radSteps.toFloat()
                    val dist = innerTuck + outerSpan * rf; val fy = pull * bell * rf * rf
                    val idx = (j * (flankSegs + 1) + i) * 2
                    rightMeshVerts[idx] = rightBx[i] + rightNx[i] * dist; rightMeshVerts[idx + 1] = rightBy[i] + rightNy[i] * dist + fy
                    leftMeshVerts[idx] = leftBx[i] + leftNx[i] * dist;   leftMeshVerts[idx + 1] = leftBy[i] + leftNy[i] * dist + fy
                }
            }
        }

        private fun startMasterLoop() {
            masterLoop?.cancel()
            val startAt = SystemClock.elapsedRealtime(); lastFrameMs = startAt
            masterLoop = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 8000L; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART; interpolator = LinearInterpolator()
                addUpdateListener {
                    val now = SystemClock.elapsedRealtime()
                    val dt = ((now - lastFrameMs).coerceIn(1L, 80L)).toFloat() / 1000f
                    lastFrameMs = now; val elapsed = (now - startAt).coerceAtLeast(0L)

                    val flowActive = (!isStandbyMode || hasUnreadRavenFlag) &&
                        (isSessionOpen || isSpeakingNow || hasUnreadRavenFlag || now < bootShowcaseUntilMs)

                    // Gently drift & twinkle individual 24k Gold-Dust Specks outside the gold ring!
                    for (k in 0 until dustCount) {
                        dustTwinklePhase[k] = (dustTwinklePhase[k] + dt * dustTwinkleHz[k]) % 62.83f
                        if (flowActive) dustU[k] = (dustU[k] + dt * dustDriftRate[k]) % 1f
                    }

                    if (recoilPhase > 0f) recoilPhase = (recoilPhase - dt * 6.5f).coerceAtLeast(0f)
                    if (flowActive) {
                        flowPhase = (flowPhase + (dt / 10.5f) * (if (isSpeakingNow) 1.25f else 1.0f)) % 1f
                        refreshScrolledStripBitmap()
                        ringPaint.alpha = ((0.84 + 0.16 * sin(((elapsed % 1100L).toDouble() / 1100.0) * Math.PI * 2.0)) * 255.0).toInt().coerceIn(175, 255)
                        pendantFill += dt * 0.96f
                        if (pendantFill >= 1f) {
                            pendantFill = 0f; recoilPhase = 1f
                            if (isAttachedToWindow) {
                                val cLoc = IntArray(2); getLocationOnScreen(cLoc)
                                ensureMoltenStreamOverlay(context)?.spawnDetachedDropFromPedestal(
                                    cLoc[0] + padSide + cardW * 0.50f,
                                    cLoc[1] + padTop + cardH + dp(context, 7.5f) + dp(context, 12.0f)
                                )
                            }
                        }
                    } else {
                        ringPaint.alpha = 226
                        pendantFill = (pendantFill - dt * 2.6f).coerceAtLeast(0f)
                    }

                    updateDualCascadingMeshes(if (flowActive) (pendantFill - recoilPhase * 0.35f).coerceIn(0f, 1f) else 0f)
                    if (isAttachedToWindow && (flowActive || moltenStreamOverlay != null)) {
                        val mv = ensureMoltenStreamOverlay(context)
                        if (!(mv?.stepPhysics(dt, flowActive) ?: false) && !flowActive) detachMoltenStreamOverlay()
                    }

                    artView.tickLivingScene(dt, now, skyBoltsDrawable.currentFlashIntensity())
                    runesDrawable?.tick(dt, elapsed)
                    skyBoltsDrawable.tick(now)

                    if (currentOccupant == PedestalOccupant.MJOLNIR && !isStandbyMode && now >= nextMjolnirIdleArcAtMs) {
                        nextMjolnirIdleArcAtMs = now + Random.nextLong(3400L, 6800L)
                        // Alternate individual left-head forks (1), right-pommel forks (2), and full arc (0) so bolts don't always fire together!
                        val sec = Random.nextInt(3)
                        triggerElectricStrikeBurst(durationMs = 360L, intensity = 0.94f, sector = sec)
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

                // Crisp Individual 24k Gold-Dust Specks Outside the Gold Ring!
                for (k in 0 until dustCount) {
                    val segF = dustU[k] * flankSegs.toFloat()
                    val i0 = segF.toInt().coerceIn(0, flankSegs - 1)
                    val frac = segF - i0
                    val bx = if (dustFlank[k]) rightBx[i0] + (rightBx[i0 + 1] - rightBx[i0]) * frac else leftBx[i0] + (leftBx[i0 + 1] - leftBx[i0]) * frac
                    val by = if (dustFlank[k]) rightBy[i0] + (rightBy[i0 + 1] - rightBy[i0]) * frac else leftBy[i0] + (leftBy[i0 + 1] - leftBy[i0]) * frac
                    val nx = if (dustFlank[k]) rightNx[i0] else leftNx[i0]
                    val ny = if (dustFlank[k]) rightNy[i0] else leftNy[i0]

                    val outPx = dp(context, dustOutDp[k])
                    val sx = bx + nx * outPx
                    val sy = by + ny * outPx
                    val tw = (0.5f + 0.5f * sin(dustTwinklePhase[k].toDouble()).toFloat())
                    val alphaCore = (65 + (190f * tw * tw).toInt()).coerceIn(0, 255)
                    val radPx = dp(context, dustRadDp[k]) * (0.75f + 0.35f * tw)

                    dustHaloPaint.color = (GOLD_WARM and 0x00FFFFFF) or ((alphaCore / 3) shl 24)
                    canvas.drawCircle(sx, sy, radPx * 2.1f, dustHaloPaint)
                    dustCorePaint.color = (GOLD_GLINT and 0x00FFFFFF) or (alphaCore shl 24)
                    canvas.drawCircle(sx, sy, radPx, dustCorePaint)
                }
            }

            if (pendantFill > 0.03f || recoilPhase > 0.05f) {
                val cx = padSide + cardW * 0.50f; val rimY = padTop + cardH + dp(context, 7.2f)
                val dropDist = (pendantFill * pendantFill * dp(context, 12.0f) - recoilPhase * dp(context, 2.8f)).coerceAtLeast(dp(context, 1.6f))
                val cy = rimY + dropDist; val r = dp(context, 2.6f + 3.8f * pendantFill)
                val rootW = dp(context, 6.2f); val pinchW = (r * (0.62f - 0.34f * pendantFill)).coerceAtLeast(dp(context, 1.15f))
                val pinchY = rimY + dropDist * 0.44f

                pendantPath.reset()
                pendantPath.moveTo(cx - rootW, rimY - dp(context, 1.8f))
                pendantPath.cubicTo(cx - pinchW * 0.85f, rimY + dropDist * 0.18f, cx - pinchW, pinchY, cx - r, cy)
                pendantPath.arcTo(cx - r, cy - r * 0.60f, cx + r, cy + r * 1.05f, 180f, -180f, false)
                pendantPath.cubicTo(cx + pinchW, pinchY, cx + pinchW * 0.85f, rimY + dropDist * 0.18f, cx + rootW, rimY - dp(context, 1.8f))
                pendantPath.close()

                pendantPaint.shader = LinearGradient(cx - r * 1.1f, cy, cx + r * 1.1f, cy, intArrayOf(GOLD_BRONZE, GOLD_WARM, GOLD_RICH, GOLD_BRONZE), floatArrayOf(0f, 0.38f, 0.78f, 1f), Shader.TileMode.CLAMP)
                canvas.drawPath(pendantPath, pendantPaint)
                pendantStroke.strokeWidth = dp(context, 0.6f); pendantStroke.color = 0xAA4C3006.toInt()
                canvas.drawPath(pendantPath, pendantStroke)
                pendantGlint.color = GOLD_GLINT
                canvas.drawCircle(cx - r * 0.26f, cy + r * 0.14f, r * 0.29f, pendantGlint)
            }
            super.dispatchDraw(canvas)
        }
    }

    private class PedestalBackdropView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val blendPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val blinkPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val dst = RectF()

        private var occ = PedestalOccupant.MJOLNIR
        private var prevPose = 0; private var curPose = 0; private var poseBlend = 1f
        private var nextPoseSwitchMs = SystemClock.elapsedRealtime() + 2400L
        private var blinkAlpha = 0f; private var blinkStartMs = 0L; private var nextBlinkAtMs = SystemClock.elapsedRealtime() + 1900L

        private val rainX = FloatArray(24) { Random.nextFloat() }
        private val rainY = FloatArray(24) { Random.nextFloat() }
        private val rainSpd = FloatArray(24) { 0.85f + Random.nextFloat() * 0.55f }
        private var rainFlash = 0f

        fun setOccupant(o: PedestalOccupant) {
            occ = o; prevPose = 0; curPose = 0; poseBlend = 1f; activeRavenPoseIdx = 0
            blinkAlpha = 0f; blinkStartMs = 0L
            nextPoseSwitchMs = SystemClock.elapsedRealtime() + Random.nextLong(2100L, 4200L)
            invalidate()
        }

        fun tickLivingScene(dt: Float, nowMs: Long, skyBoltFlash: Float) {
            rainFlash = skyBoltFlash
            for (i in rainX.indices) {
                rainY[i] += rainSpd[i] * dt * 1.35f; rainX[i] -= rainSpd[i] * dt * 0.20f
                if (rainY[i] > 1.06f) { rainY[i] = -0.08f; rainX[i] = Random.nextFloat() * 1.18f }
            }
            if (occ != PedestalOccupant.RAVEN) { invalidate(); return }

            if (poseBlend < 1f) {
                poseBlend = (poseBlend + dt * 4.8f).coerceAtMost(1f)
                if (poseBlend >= 0.5f) activeRavenPoseIdx = curPose
            } else if (nowMs >= nextPoseSwitchMs) {
                prevPose = curPose
                curPose = if (curPose == 0) (if (Random.nextBoolean()) 1 else 2) else (if (Random.nextFloat() < 0.65f) 0 else (3 - curPose))
                poseBlend = 0f
                nextPoseSwitchMs = nowMs + Random.nextLong(1900L, 4400L)
            }

            if (curPose == 0 && poseBlend >= 0.95f) {
                if (blinkStartMs > 0L) {
                    val el = nowMs - blinkStartMs
                    if (el >= 175L) { blinkStartMs = 0L; blinkAlpha = 0f; nextBlinkAtMs = nowMs + Random.nextLong(2100L, 4600L) }
                    else blinkAlpha = sin((el.toDouble() / 175.0) * Math.PI).toFloat().coerceIn(0f, 1f)
                } else if (nowMs >= nextBlinkAtMs) blinkStartMs = nowMs
            } else blinkAlpha = 0f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            dst.set(0f, 0f, w, h)
            when (occ) {
                PedestalOccupant.RAVEN -> {
                    val bPrev = ravenPoses[prevPose] ?: ravenPoses[0] ?: return
                    val bCur = ravenPoses[curPose] ?: bPrev
                    paint.alpha = 255; canvas.drawBitmap(bPrev, null, dst, paint)
                    if (poseBlend < 1f && bCur !== bPrev) {
                        blendPaint.alpha = (poseBlend * 255f).toInt().coerceIn(0, 255)
                        canvas.drawBitmap(bCur, null, dst, blendPaint)
                    } else if (bCur !== bPrev) {
                        canvas.drawBitmap(bCur, null, dst, paint)
                    }
                    if (curPose == 0 && blinkAlpha > 0.02f) {
                        ravenEyeBlinkPatchBmp?.let { eye ->
                            blinkPaint.alpha = (blinkAlpha * 255f).toInt().coerceIn(0, 255)
                            canvas.drawBitmap(eye, null, dst, blinkPaint)
                        }
                    }
                }
                PedestalOccupant.EMPTY_STONE -> (emptyPedestalBmp ?: mjolnirBaseBmp)?.let { canvas.drawBitmap(it, null, dst, paint) }
                PedestalOccupant.MJOLNIR -> mjolnirBaseBmp?.let { canvas.drawBitmap(it, null, dst, paint) }
            }

            rainPaint.strokeWidth = max(1f, w * 0.0055f)
            rainPaint.color = Color.argb((28 + 54 * rainFlash).toInt().coerceIn(0, 110), 210, 240, 255)
            for (i in rainX.indices) {
                val x0 = rainX[i] * w; val y0 = rainY[i] * h
                canvas.drawLine(x0, y0, x0 - w * 0.012f, y0 + h * 0.062f, rainPaint)
            }
        }
    }

    private class ShimmeringRuneOverlayDrawable(private val runesBitmap: Bitmap) : Drawable() {
        private val normRect = RectF(0.32031f, 0.61129f, 0.62813f, 0.89530f)
        private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = PorterDuffColorFilter(0xDD1A0502.toInt(), PorterDuff.Mode.SRC_IN) }
        private val redHaloCoverPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = PorterDuffColorFilter(0xCC450A0A.toInt(), PorterDuff.Mode.SRC_IN) }
        private val redFilter = PorterDuffColorFilter(0xFFEF233C.toInt(), PorterDuff.Mode.SRC_IN)
        private val hotGoldFilter = PorterDuffColorFilter(0xFFFFEA70.toInt(), PorterDuff.Mode.SRC_IN)
        private val basePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
        private val dstRect = RectF(); private val shadowRect = RectF()
        var isMutedRed: Boolean = false; var isSessionHot: Boolean = false
        private var shimmerPhase = 0f; private var emberBreath = 0f; private var strikeFlash = 0f

        fun triggerLightningResonance() { strikeFlash = 1.0f; invalidateSelf() }

        fun tick(dt: Float, elapsedMs: Long) {
            shimmerPhase = (elapsedMs % 3200L).toFloat() / 3200f
            emberBreath = (0.5 + 0.5 * sin((elapsedMs.toDouble() / 1800.0) * Math.PI * 2.0)).toFloat()
            if (strikeFlash > 0f) strikeFlash = (strikeFlash - dt * 2.5f).coerceAtLeast(0f)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val b = bounds; if (b.isEmpty) return
            val bw = b.width().toFloat(); val bh = b.height().toFloat()
            dstRect.set(b.left + normRect.left * bw, b.top + normRect.top * bh, b.left + normRect.right * bw, b.top + normRect.bottom * bh)
            if (isMutedRed) {
                val ex = max(1.5f, bw * 0.014f)
                for (dx in intArrayOf(-1, 1)) for (dy in intArrayOf(-1, 1)) {
                    shadowRect.set(dstRect.left + dx * ex, dstRect.top + dy * ex, dstRect.right + dx * ex, dstRect.bottom + dy * ex)
                    canvas.drawBitmap(runesBitmap, null, shadowRect, redHaloCoverPaint)
                }
            } else {
                val off = max(1f, bw * 0.008f)
                shadowRect.set(dstRect.left + off, dstRect.top + off, dstRect.right + off, dstRect.bottom + off)
                canvas.drawBitmap(runesBitmap, null, shadowRect, shadowPaint)
            }

            val lid = canvas.saveLayer(dstRect, null)
            maskPaint.colorFilter = when { isMutedRed -> redFilter; isSessionHot -> hotGoldFilter; else -> null }
            maskPaint.alpha = (232 + 23 * emberBreath).toInt().coerceIn(0, 255)
            canvas.drawBitmap(runesBitmap, null, dstRect, maskPaint)

            val rw = dstRect.width(); val rh = dstRect.height()
            val sx = dstRect.left - rw * 0.55f + (rw * 2.10f) * shimmerPhase
            val sy = dstRect.top - rh * 0.20f + (rh * 0.65f) * shimmerPhase
            val cols = if (isMutedRed) intArrayOf(0x00EF233C, 0xAAFF4D6D.toInt(), 0xFFFFB3C1.toInt(), 0xFFFFF0F3.toInt(), 0xFFFFB3C1.toInt(), 0xAAFF4D6D.toInt(), 0x00EF233C)
            else intArrayOf(0x00966B12, 0x88D4A234.toInt(), 0xFFFFF08A.toInt(), 0xFFFFFBEB.toInt(), 0xFFFFF08A.toInt(), 0x88D4A234.toInt(), 0x00966B12)
            sweepPaint.shader = LinearGradient(sx, sy, sx + rw * 0.44f, sy + rh * 0.30f, cols, floatArrayOf(0f, 0.22f, 0.44f, 0.50f, 0.56f, 0.78f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(dstRect, sweepPaint)

            if (strikeFlash > 0.02f) {
                sweepPaint.shader = null
                sweepPaint.color = if (isMutedRed) Color.argb((strikeFlash * 235f).toInt(), 255, 190, 200) else Color.argb((strikeFlash * 235f).toInt(), 255, 251, 235)
                canvas.drawRect(dstRect, sweepPaint)
            }
            canvas.restoreToCount(lid)
        }

        override fun setAlpha(alpha: Int) { basePaint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { basePaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * Exact Restored v26.9.21 Sky Bolts (`raven_bolt_0..3_core/halo` at exact normRects),
     * firing strictly ONE asynchronous bolt at a time behind the Raven/Mjolnir!
     */
    private class SkyStormBoltsDrawable(private val cores: Array<Bitmap?>, private val halos: Array<Bitmap?>) : Drawable() {
        private val normRects = arrayOf(
            RectF(0.00000f, 0.06207f, 0.44609f, 0.56677f), RectF(0.31562f, 0.00000f, 0.77578f, 0.22696f),
            RectF(0.30469f, 0.26583f, 0.77656f, 0.49843f), RectF(0.67812f, 0.19875f, 1.00000f, 0.56489f)
        )
        private val haloPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val corePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val occPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        private val dstRect = RectF(); private val fullRect = RectF()
        private val startMs = LongArray(4); private val durMs = LongArray(4); private val nextAtMs = LongArray(4)
        private var lastAnyMs = 0L
        private var lastForcedIdx = 3

        init {
            val now = SystemClock.elapsedRealtime()
            for (i in 0 until 4) { durMs[i] = Random.nextLong(460L, 780L); nextAtMs[i] = now + Random.nextLong(500L + i * 650L, 2200L + i * 850L) }
        }

        fun forceSingleStrikeNow() {
            val now = SystemClock.elapsedRealtime()
            val pick = (lastForcedIdx + 1 + Random.nextInt(3)) % 4
            lastForcedIdx = pick
            startMs[pick] = now; durMs[pick] = 540L; lastAnyMs = now
            invalidateSelf()
        }

        fun currentFlashIntensity(): Float {
            val now = SystemClock.elapsedRealtime()
            var peak = 0f
            for (i in 0 until 4) {
                val el = now - startMs[i]
                if (startMs[i] > 0L && el in 0L..durMs[i]) peak = max(peak, 1f - el.toFloat() / durMs[i].toFloat())
            }
            return peak
        }

        fun tick(nowMs: Long) {
            for (i in 0 until 4) {
                val active = startMs[i] > 0L && (nowMs - startMs[i]) <= durMs[i]
                if (!active && nowMs >= nextAtMs[i]) {
                    if ((nowMs - lastAnyMs) < 480L) nextAtMs[i] = lastAnyMs + Random.nextLong(480L, 1100L)
                    else { startMs[i] = nowMs; durMs[i] = Random.nextLong(440L, 780L); lastAnyMs = nowMs; nextAtMs[i] = nowMs + durMs[i] + Random.nextLong(1350L, 4500L) }
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
            var leftRim = 0f; var rightRim = 0f

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
                if (i == 0 || i == 2) leftRim = max(leftRim, alpha / 255f) else rightRim = max(rightRim, alpha / 255f)
            }

            val occ = when (currentOccupant) {
                PedestalOccupant.RAVEN -> ravenOccs[activeRavenPoseIdx.coerceIn(0, 2)] ?: ravenOccs[0]
                PedestalOccupant.MJOLNIR -> mjolnirFgOccluderBmp
                PedestalOccupant.EMPTY_STONE -> null
            }
            if (occ != null) canvas.drawBitmap(occ, null, fullRect, occPaint)
            canvas.restoreToCount(lid)

            if (currentOccupant == PedestalOccupant.RAVEN && activeRavenPoseIdx == 0) {
                if (leftRim > 0.05f) wetRimLeftBmp?.let { rimPaint.alpha = (leftRim * 195f).toInt(); canvas.drawBitmap(it, null, fullRect, rimPaint) }
                if (rightRim > 0.05f) wetRimRightBmp?.let { rimPaint.alpha = (rightRim * 195f).toInt(); canvas.drawBitmap(it, null, fullRect, rimPaint) }
            }
        }

        override fun setAlpha(alpha: Int) { corePaint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { corePaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

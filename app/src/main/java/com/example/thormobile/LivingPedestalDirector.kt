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
    private const val TAG = "LivingPedestalV3"
    private val mainHandler = Handler(Looper.getMainLooper())

    // Exact rich burnished 24k histogram sampled from raven_gold_ring_strip.png (zero pale lemon tint!)
    private const val GOLD_SHADOW = 0xFF3A2203.toInt()
    private const val GOLD_BRONZE = 0xFF5A3907.toInt()
    private const val GOLD_RICH   = 0xFF966B12.toInt()
    private const val GOLD_WARM   = 0xFFD4A234.toInt()
    private const val GOLD_GLINT  = 0xFFFEF08A.toInt()
    private const val GOLD_HOT    = 0xFFFFFBEB.toInt()

    @Volatile var hasUnreadRaven: Boolean = false
        private set
    @Volatile var ravenCurrentlyPerched: Boolean = false
        private set
    @Volatile var ravenCardUnfoldedNow: Boolean = false
        private set
    @Volatile var flightInProgress: Boolean = false
        private set

    // Shared live 6:00 meniscus swell & recoil factor so the ring funnel & falling droplets move as ONE liquid!
    @Volatile var liveConfluenceSwell: Float = 0f

    @Volatile private var attachedCrestView: LivingPedestalCrestView? = null
    private var flightWindowManager: WindowManager? = null
    private var activeFlightView: RavenFlightStageView? = null
    private var moltenStreamOverlay: MoltenGoldScreenStreamView? = null
    private var flightAnimator: ValueAnimator? = null
    private var patrolRunnable: Runnable? = null

    fun isPedestalAttached(): Boolean = attachedCrestView != null

    private var mjolnirBaseBmp: Bitmap? = null
    private var mjolnirSwapCoreBmp: Bitmap? = null
    private var mjolnirSwapHaloBmp: Bitmap? = null
    private var ravenBaseBmp: Bitmap? = null
    private var ravenEyeBlinkPatchBmp: Bitmap? = null
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
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            realW, realH, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun decodeRaw(ctx: Context, resId: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeResource(ctx.resources, resId, opts)?.apply { density = Bitmap.DENSITY_NONE }
    } catch (e: Exception) {
        Log.w(TAG, "Decode($resId): ${e.message}")
        null
    }

    @Synchronized
    fun ensureAssetsLoaded(ctx: Context) {
        val app = ctx.applicationContext
        if (mjolnirBaseBmp != null && ravenBaseBmp != null && ravenEyeBlinkPatchBmp != null && flyFrames[0] != null) return
        mjolnirBaseBmp = decodeRaw(app, R.drawable.mjolnir_pedestal_base_hd)
        mjolnirSwapCoreBmp = decodeRaw(app, R.drawable.mjolnir_swap_bolt_core)
        mjolnirSwapHaloBmp = decodeRaw(app, R.drawable.mjolnir_swap_bolt_halo)
        ravenBaseBmp = decodeRaw(app, R.drawable.raven_storm_base_hd)
        ravenEyeBlinkPatchBmp = decodeRaw(app, R.drawable.raven_eye_blink_patch)
        ringStripBmp = decodeRaw(app, R.drawable.raven_gold_ring_strip)
        runesGoldBmp = decodeRaw(app, R.drawable.raven_runes_gold_hd)

        val cIds = intArrayOf(R.drawable.raven_bolt_0_core, R.drawable.raven_bolt_1_core, R.drawable.raven_bolt_2_core, R.drawable.raven_bolt_3_core)
        val hIds = intArrayOf(R.drawable.raven_bolt_0_halo, R.drawable.raven_bolt_1_halo, R.drawable.raven_bolt_2_halo, R.drawable.raven_bolt_3_halo)
        for (i in 0 until 4) {
            boltCores[i] = decodeRaw(app, cIds[i])
            boltHalos[i] = decodeRaw(app, hIds[i])
        }
        flyFrames[0] = decodeRaw(app, R.drawable.raven_fly_up)
        flyFrames[1] = decodeRaw(app, R.drawable.raven_fly_glide)
        flyFrames[2] = decodeRaw(app, R.drawable.raven_fly_down)
    }

    fun createAndAttachPedestalView(ctx: Context): LivingPedestalCrestView {
        ensureAssetsLoaded(ctx)
        val view = LivingPedestalCrestView(ctx)
        attachedCrestView = view
        if (hasUnreadRaven && !ravenCardUnfoldedNow) {
            ravenCurrentlyPerched = true
            view.setOccupantImmediate(showRaven = true)
            scheduleNextPatrolFlight(ctx.applicationContext)
        } else {
            ravenCurrentlyPerched = false
            view.setOccupantImmediate(showRaven = false)
        }
        return view
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
        return try {
            wm.addView(view, buildFullGlassOverlayParams(app))
            moltenStreamOverlay = view
            view
        } catch (_: Exception) { null }
    }

    private fun detachMoltenStreamOverlay() {
        val v = moltenStreamOverlay
        moltenStreamOverlay = null
        liveConfluenceSwell = 0f
        if (v != null && v.parent != null) {
            try { flightWindowManager?.removeView(v) } catch (_: Exception) {}
        }
    }

    fun updateServiceAudioVisualState(isStandby: Boolean, sessionActive: Boolean, speakingActive: Boolean) {
        mainHandler.post {
            attachedCrestView?.updateOperationalState(isStandby, sessionActive, speakingActive, hasUnreadRaven)
        }
    }

    fun triggerLightningPulse() {
        mainHandler.post { attachedCrestView?.triggerElectricStrikeBurst(420L) }
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

            if (ravenCurrentlyPerched || flightInProgress) {
                crest.triggerElectricStrikeBurst(420L)
                if (!flightInProgress) scheduleNextPatrolFlight(app)
                return@post
            }

            crest.setOccupantImmediate(showRaven = false)
            launchSplineFlight(
                app = app,
                mode = FlightMode.ARRIVAL,
                onLiftoffImmediate = null,
                onPreLandingLightningMask = {
                    // Dissolve Mjolnir in electric lightning BEFORE flying Raven overlaps pedestal (0 hammer-through-chest!)
                    crest.hideMjolnirForIncomingLanding()
                    crest.triggerElectricStrikeBurst(440L)
                },
                onTouchdownExact = {
                    ravenCurrentlyPerched = true
                    crest.setOccupantImmediate(showRaven = true)
                },
                onComplete = {
                    if (hasUnreadRaven && !ravenCardUnfoldedNow) scheduleNextPatrolFlight(app)
                }
            )
        }
    }

    fun onRavenCardUnfolded() {
        mainHandler.post {
            ravenCardUnfoldedNow = true
            cancelPatrolSchedule()
            if (flightInProgress) {
                stopActiveFlightOverlay()
                ravenCurrentlyPerched = true
                attachedCrestView?.setOccupantImmediate(showRaven = true)
            }
        }
    }

    fun onRavenCardFoldedBackToPedestal(ctx: Context) {
        val app = ctx.applicationContext
        mainHandler.post {
            if (!hasUnreadRaven) return@post
            ravenCardUnfoldedNow = false
            ravenCurrentlyPerched = true
            attachedCrestView?.setOccupantImmediate(showRaven = true)
            attachedCrestView?.triggerElectricStrikeBurst(360L)
            attachedCrestView?.refreshDripActivity(true)
            scheduleNextPatrolFlight(app)
        }
    }

    fun dismissUnreadRavenWithTakeoff(ctx: Context) {
        val app = ctx.applicationContext
        ensureAssetsLoaded(app)
        mainHandler.post {
            val wasShowing = hasUnreadRaven || ravenCurrentlyPerched
            hasUnreadRaven = false
            ravenCardUnfoldedNow = false
            cancelPatrolSchedule()
            stopActiveFlightOverlay()

            val crest = attachedCrestView
            if (crest == null) {
                ravenCurrentlyPerched = false
                return@post
            }
            crest.refreshDripActivity(false)
            if (!wasShowing) {
                crest.setOccupantImmediate(showRaven = false)
                return@post
            }

            launchSplineFlight(
                app = app,
                mode = FlightMode.DEPARTURE,
                onLiftoffImmediate = {
                    ravenCurrentlyPerched = false
                    crest.setOccupantImmediate(showRaven = false)
                    crest.triggerElectricStrikeBurst(480L)
                },
                onPreLandingLightningMask = null,
                onTouchdownExact = null,
                onComplete = { crest.refreshDripActivity(false) }
            )
        }
    }

    private fun cancelPatrolSchedule() {
        patrolRunnable?.let { mainHandler.removeCallbacks(it) }
        patrolRunnable = null
    }

    private fun scheduleNextPatrolFlight(app: Context) {
        cancelPatrolSchedule()
        if (!hasUnreadRaven || ravenCardUnfoldedNow) return
        val delayMs = Random.nextLong(14_000L, 23_000L)
        val r = Runnable {
            if (!hasUnreadRaven || ravenCardUnfoldedNow || flightInProgress || attachedCrestView == null) return@Runnable
            launchSplineFlight(
                app = app,
                mode = FlightMode.PATROL,
                onLiftoffImmediate = {
                    ravenCurrentlyPerched = false
                    attachedCrestView?.setOccupantImmediate(showRaven = false)
                    attachedCrestView?.triggerElectricStrikeBurst(420L)
                },
                onPreLandingLightningMask = {
                    if (hasUnreadRaven) {
                        attachedCrestView?.hideMjolnirForIncomingLanding()
                        attachedCrestView?.triggerElectricStrikeBurst(440L)
                    }
                },
                onTouchdownExact = {
                    if (hasUnreadRaven) {
                        ravenCurrentlyPerched = true
                        attachedCrestView?.setOccupantImmediate(showRaven = true)
                    }
                },
                onComplete = {
                    if (hasUnreadRaven && !ravenCardUnfoldedNow) scheduleNextPatrolFlight(app)
                }
            )
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
            val px = (cLoc[0] - oLoc[0]) + crest.padSide + crest.cardW * 0.50f
            val py = (cLoc[1] - oLoc[1]) + crest.padTop + crest.cardH * 0.35f
            return PointF(px, py)
        }
        return PointF(dp(app, 74f), dp(app, 86f))
    }

    /**
     * Evaluates a C1-continuous Catmull-Rom spline across `pts` (with phantom endpoints at index 0 & size-1).
     * Guarantees 100% smooth position AND tangent velocity across the entire flight without mid-air kinks!
     */
    private fun evalCatmullRom(pts: List<PointF>, uNorm: Float, outPos: PointF, outVel: PointF) {
        val segCount = (pts.size - 3).coerceAtLeast(1)
        val scaled = uNorm.coerceIn(0f, 0.9999f) * segCount
        val seg = scaled.toInt().coerceIn(0, segCount - 1)
        val t = scaled - seg

        val p0 = pts[seg]
        val p1 = pts[seg + 1]
        val p2 = pts[seg + 2]
        val p3 = pts[seg + 3]

        val t2 = t * t
        val t3 = t2 * t
        outPos.x = 0.5f * ((2f * p1.x) + (-p0.x + p2.x) * t + (2f * p0.x - 5f * p1.x + 4f * p2.x - p3.x) * t2 + (-p0.x + 3f * p1.x - 3f * p2.x + p3.x) * t3)
        outPos.y = 0.5f * ((2f * p1.y) + (-p0.y + p2.y) * t + (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2 + (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * t3)

        outVel.x = 0.5f * ((-p0.x + p2.x) + (4f * p0.x - 10f * p1.x + 8f * p2.x - 2f * p3.x) * t + (-3f * p0.x + 9f * p1.x - 9f * p2.x + 3f * p3.x) * t2)
        outVel.y = 0.5f * ((-p0.y + p2.y) + (4f * p0.y - 10f * p1.y + 8f * p2.y - 2f * p3.y) * t + (-3f * p0.y + 9f * p1.y - 9f * p2.y + 3f * p3.y) * t2)
    }

    private fun buildSmoothSplineWaypoints(app: Context, mode: FlightMode, perch: PointF): Pair<List<PointF>, Long> {
        val (swInt, shInt) = getPhysicalGlassSize(app)
        val sw = swInt.toFloat()
        val sh = shInt.toFloat()

        return when (mode) {
            FlightMode.ARRIVAL -> {
                // Swoops in from upper-right sky, banks gracefully on the left side of screen, and flares Left-to-Right onto the stone!
                val pStart = PointF(sw + dp(app, 70f), sh * 0.14f)
                val w1 = PointF(sw * 0.62f, sh * 0.24f)
                val w2 = PointF(sw * 0.16f, max(perch.y + dp(app, 95f), sh * 0.22f))
                val w3 = PointF(max(dp(app, 18f), perch.x - dp(app, 55f)), max(dp(app, 42f), perch.y - dp(app, 28f)))
                val phantom0 = PointF(sw + dp(app, 220f), sh * 0.08f)
                val phantomEnd = PointF(perch.x + dp(app, 95f), perch.y)
                Pair(listOf(phantom0, pStart, w1, w2, w3, perch, phantomEnd), 2050L)
            }
            FlightMode.DEPARTURE -> {
                val w1 = PointF(perch.x + dp(app, 125f), max(dp(app, 36f), perch.y - dp(app, 42f)))
                val w2 = PointF(sw * 0.64f, sh * 0.19f)
                val pEnd = PointF(sw + dp(app, 110f), sh * 0.11f)
                val phantom0 = PointF(perch.x - dp(app, 95f), perch.y + dp(app, 18f))
                val phantomEnd = PointF(sw + dp(app, 260f), sh * 0.05f)
                Pair(listOf(phantom0, perch, w1, w2, pEnd, phantomEnd), 1380L)
            }
            FlightMode.PATROL -> {
                // 3 varied, 100% ON-SCREEN figure-8 / soaring recon loops (never vanishes off-screen, always lands Left-to-Right!)
                val phantom0 = PointF(perch.x - dp(app, 110f), perch.y + dp(app, 28f))
                val phantomEnd = PointF(perch.x + dp(app, 110f), perch.y)
                val preFlare = PointF(max(dp(app, 16f), perch.x - dp(app, 52f)), max(dp(app, 40f), perch.y - dp(app, 24f)))
                when (Random.nextInt(3)) {
                    0 -> Pair(listOf(
                        phantom0,
                        perch,
                        PointF(sw * 0.54f, max(dp(app, 55f), perch.y - dp(app, 25f))),
                        PointF(sw * 0.86f, sh * 0.28f),
                        PointF(sw * 0.52f, sh * 0.46f),
                        PointF(sw * 0.14f, sh * 0.30f),
                        preFlare,
                        perch,
                        phantomEnd
                    ), 3650L)
                    1 -> Pair(listOf(
                        phantom0,
                        perch,
                        PointF(sw * 0.62f, sh * 0.18f),
                        PointF(sw * 0.84f, sh * 0.52f),
                        PointF(sw * 0.36f, sh * 0.60f),
                        PointF(sw * 0.12f, sh * 0.34f),
                        preFlare,
                        perch,
                        phantomEnd
                    ), 3950L)
                    else -> Pair(listOf(
                        phantom0,
                        perch,
                        PointF(sw * 0.48f, sh * 0.30f),
                        PointF(sw * 0.86f, sh * 0.38f),
                        PointF(sw * 0.60f, sh * 0.16f),
                        PointF(sw * 0.16f, sh * 0.26f),
                        preFlare,
                        perch,
                        phantomEnd
                    ), 3550L)
                }
            }
        }
    }

    private fun launchSplineFlight(
        app: Context,
        mode: FlightMode,
        onLiftoffImmediate: (() -> Unit)?,
        onPreLandingLightningMask: (() -> Unit)?,
        onTouchdownExact: (() -> Unit)?,
        onComplete: (() -> Unit)?
    ) {
        if (!Settings.canDrawOverlays(app)) {
            onLiftoffImmediate?.invoke()
            onPreLandingLightningMask?.invoke()
            onTouchdownExact?.invoke()
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
            onTouchdownExact?.invoke()
            onComplete?.invoke()
            return
        }

        onLiftoffImmediate?.invoke()

        var waypoints: List<PointF>? = null
        var preMaskFired = false
        var touchdownFired = false
        val posOut = PointF()
        val velOut = PointF()
        val startMs = SystemClock.elapsedRealtime()
        var lastTickMs = startMs
        val initialPerch = getPerchInOverlayCoords(app, stageView)
        val (initPts, totalDur) = buildSmoothSplineWaypoints(app, mode, initialPerch)
        waypoints = initPts

        flightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalDur
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val rawU = (anim.animatedValue as Float).coerceIn(0f, 1f)
                val now = SystemClock.elapsedRealtime()
                val dt = ((now - lastTickMs).coerceIn(1L, 50L)).toFloat() / 1000f
                lastTickMs = now

                // Smooth ease-in-out timing curve so liftoff accelerates naturally and landing brakes smoothly!
                val easedU = when (mode) {
                    FlightMode.ARRIVAL -> (1.0 - (1.0 - rawU.toDouble()).pow(1.65)).toFloat()
                    FlightMode.DEPARTURE -> rawU.toDouble().pow(1.30).toFloat()
                    FlightMode.PATROL -> (0.5 - 0.5 * cos(rawU.toDouble() * Math.PI)).toFloat()
                }.coerceIn(0f, 1f)

                val pts = waypoints ?: initPts
                evalCatmullRom(pts, easedU, posOut, velOut)

                // Continuous 60fps wingbeat cycle (0..4) with soaring glide during fast dives
                val elapsedSec = (now - startMs).toFloat() / 1000f
                val flapRateHz = if (easedU > 0.80f && mode != FlightMode.DEPARTURE) 3.4f else 2.4f
                val phase4 = (elapsedSec * flapRateHz * 4f) % 4f
                val speed = hypot(velOut.x.toDouble(), velOut.y.toDouble()).toFloat().coerceAtLeast(1f)
                val divingGlide = (velOut.y / speed > 0.30f) && easedU in 0.22f..0.72f

                val seq = intArrayOf(0, 1, 2, 1)
                val idxA: Int
                val idxB: Int
                val blend: Float
                if (divingGlide) {
                    idxA = 1; idxB = 1; blend = 0f
                } else {
                    val slot = phase4.toInt().coerceIn(0, 3)
                    idxA = seq[slot]
                    idxB = seq[(slot + 1) % 4]
                    blend = (0.5 - 0.5 * cos((phase4 - slot) * Math.PI)).toFloat()
                }

                // Subtle vertical wing-lift bob synced with downstroke
                val liftBob = if (divingGlide) 0f else sin(elapsedSec * flapRateHz * Math.PI * 2.0).toFloat() * dp(app, 2.2f)

                val scaleDepth = when {
                    mode != FlightMode.DEPARTURE && easedU > 0.78f -> 1.00f - 0.34f * ((easedU - 0.78f) / 0.22f)
                    mode != FlightMode.ARRIVAL && easedU < 0.20f -> 0.66f + 0.34f * (easedU / 0.20f)
                    else -> 1.00f + 0.08f * sin(easedU * Math.PI).toFloat()
                }

                // Fire electric lightning & hide Mjolnir at u >= 0.85 BEFORE Raven overlaps the hammer!
                if (!preMaskFired && mode != FlightMode.DEPARTURE && easedU >= 0.85f) {
                    preMaskFired = true
                    onPreLandingLightningMask?.invoke()
                }
                if (!touchdownFired && mode != FlightMode.DEPARTURE && easedU >= 0.93f) {
                    touchdownFired = true
                    onTouchdownExact?.invoke()
                }

                val alphaFade = when {
                    mode != FlightMode.DEPARTURE && easedU > 0.91f -> ((1f - easedU) / 0.09f).coerceIn(0f, 1f)
                    mode == FlightMode.DEPARTURE && easedU > 0.88f -> ((1f - easedU) / 0.12f).coerceIn(0f, 1f)
                    else -> 1f
                }

                val lockRight = (mode != FlightMode.DEPARTURE && easedU > 0.82f) || (mode != FlightMode.ARRIVAL && easedU < 0.14f)
                val pitchDampen = if (mode != FlightMode.DEPARTURE && easedU > 0.80f) ((1f - easedU) / 0.20f).coerceIn(0f, 1f) else 1f

                stageView.updateSmoothPose(
                    x = posOut.x,
                    y = posOut.y + liftBob * pitchDampen,
                    vx = velOut.x,
                    vy = velOut.y * pitchDampen,
                    dt = dt,
                    frameA = idxA,
                    frameB = idxB,
                    frameBlend = blend,
                    scale = scaleDepth,
                    alpha = alphaFade,
                    forceRight = lockRight
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!preMaskFired && mode != FlightMode.DEPARTURE) onPreLandingLightningMask?.invoke()
                    if (!touchdownFired && mode != FlightMode.DEPARTURE) onTouchdownExact?.invoke()
                    stopActiveFlightOverlay()
                    onComplete?.invoke()
                }
            })
            start()
        }
    }

    private fun stopActiveFlightOverlay() {
        flightAnimator?.cancel()
        flightAnimator = null
        flightInProgress = false
        val v = activeFlightView
        activeFlightView = null
        if (v != null && v.parent != null) {
            try { flightWindowManager?.removeView(v) } catch (_: Exception) {}
        }
    }

    /**
     * 60fps Cross-Blended & Smooth-Banking Flight Renderer (0 centroid jump, 0 instant mirror snap).
     */
    private class RavenFlightStageView(ctx: Context, private val frames: Array<Bitmap?>) : View(ctx) {
        private val paintA = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val paintB = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val baseSizePx = dp(ctx, 114f)
        private val dstRect = RectF()

        private var curX = -500f; private var curY = -500f
        private var bankScaleX = 1f
        private var smoothPitchDeg = 0f
        private var idxA = 1; private var idxB = 1; private var blend = 0f
        private var curScale = 1f; private var curAlpha = 1f

        fun updateSmoothPose(
            x: Float, y: Float, vx: Float, vy: Float, dt: Float,
            frameA: Int, frameB: Int, frameBlend: Float,
            scale: Float, alpha: Float, forceRight: Boolean
        ) {
            curX = x; curY = y
            val targetSign = if (forceRight) 1f else if (vx >= 0f) 1f else -1f
            // Smooth 3D banking turn across 160ms instead of a jarring 1-frame horizontal flip!
            bankScaleX += (targetSign - bankScaleX) * min(1f, dt * 11.5f)
            if (abs(bankScaleX) < 0.14f) bankScaleX = if (targetSign >= 0f) 0.14f else -0.14f

            val absVx = abs(vx).coerceAtLeast(1f)
            val targetPitch = Math.toDegrees(atan2(vy.toDouble(), absVx.toDouble())).toFloat().coerceIn(-24f, 24f)
            smoothPitchDeg += (targetPitch - smoothPitchDeg) * min(1f, dt * 10.0f)

            idxA = frameA.coerceIn(0, 2)
            idxB = frameB.coerceIn(0, 2)
            blend = frameBlend.coerceIn(0f, 1f)
            curScale = scale
            curAlpha = alpha.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (curAlpha <= 0.01f) return
            val bmpA = frames.getOrNull(idxA) ?: return
            val bmpB = frames.getOrNull(idxB) ?: bmpA
            val half = (baseSizePx * curScale) * 0.5f

            canvas.save()
            canvas.translate(curX, curY)
            canvas.scale(bankScaleX, 1f)
            canvas.rotate(smoothPitchDeg)
            dstRect.set(-half, -half, half, half)

            if (idxA == idxB || blend <= 0.02f) {
                paintA.alpha = (curAlpha * 255f).toInt().coerceIn(0, 255)
                canvas.drawBitmap(bmpA, null, dstRect, paintA)
            } else {
                paintA.alpha = (curAlpha * (1f - blend * 0.65f) * 255f).toInt().coerceIn(0, 255)
                paintB.alpha = (curAlpha * blend * 255f).toInt().coerceIn(0, 255)
                canvas.drawBitmap(bmpA, null, dstRect, paintA)
                canvas.drawBitmap(bmpB, null, dstRect, paintB)
            }
            canvas.restore()
        }
    }

    private class ElectricHammerLightningDrawable : Drawable() {
        private val haloPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val corePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dstRect = RectF()
        var progress: Float = -1f
            set(value) { field = value; invalidateSelf() }
        var intensityScale: Float = 1.0f

        override fun draw(canvas: Canvas) {
            val p = progress
            if (p < 0f || p > 1f) return
            val b = bounds; if (b.isEmpty) return
            val core = mjolnirSwapCoreBmp ?: boltCores[0] ?: return
            val halo = mjolnirSwapHaloBmp ?: boltHalos[0] ?: core
            dstRect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            val h = dstRect.height()

            if (p < 0.30f) {
                val step = (((p / 0.30f) * 6f).toInt() + 1) / 6f
                val frontY = dstRect.top + h * step.coerceIn(0.20f, 1f)
                haloPaint.alpha = (205 * intensityScale).toInt().coerceIn(0, 255)
                corePaint.alpha = (255 * intensityScale).toInt().coerceIn(0, 255)
                canvas.save()
                canvas.clipRect(dstRect.left, dstRect.top, dstRect.right, frontY)
                canvas.drawBitmap(halo, null, dstRect, haloPaint)
                canvas.drawBitmap(core, null, dstRect, corePaint)
                canvas.restore()
            } else if (p <= 0.68f) {
                val flicker = if (p in 0.46f..0.52f) 0.45f else 1.0f
                haloPaint.alpha = (215 * flicker * intensityScale).toInt().coerceIn(0, 255)
                corePaint.alpha = (255 * flicker * intensityScale).toInt().coerceIn(0, 255)
                canvas.drawBitmap(halo, null, dstRect, haloPaint)
                canvas.drawBitmap(core, null, dstRect, corePaint)
            } else {
                val fade = ((1f - p) / 0.32f).coerceIn(0f, 1f)
                haloPaint.alpha = (195 * fade * intensityScale).toInt().coerceIn(0, 255)
                corePaint.alpha = (255 * fade * intensityScale).toInt().coerceIn(0, 255)
                canvas.drawBitmap(halo, null, dstRect, haloPaint)
                canvas.drawBitmap(core, null, dstRect, corePaint)
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * WO-162 True Fluid 6:00 Gold Drip & Full-Glass Bottom-Pixel Growing Molten Gold Pile (y = 3088px!).
     */
    private class MoltenGoldScreenStreamView(ctx: Context) : View(ctx) {
        private data class GoldDrop(
            var x: Float = 0f, var y: Float = 0f, var vy: Float = 0f,
            var r: Float = 0f, var volumeWeight: Float = 1f, var active: Boolean = false
        )
        private data class GoldMote(
            var x: Float = 0f, var y: Float = 0f, var vx: Float = 0f, var vy: Float = 0f,
            var r: Float = 0f, var life: Float = 0f, var maxLife: Float = 0.5f, var active: Boolean = false
        )

        private val drops = Array(20) { GoldDrop() }
        private val motes = Array(44) { GoldMote() }

        // 72-column viscous heightfield flush to physical bottom pixel of Note 20 Ultra (grows deeper over time!)
        private val cols = 72
        private val pileH = FloatArray(cols + 1)
        private val pileVel = FloatArray(cols + 1)
        private val tempH = FloatArray(cols + 1)

        private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pileFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stripPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dropPath = Path()
        private val pilePath = Path()
        private val surfaceEdgePath = Path()
        private val stripDst = RectF()

        private var pendantFill = 0f     // 0.0 -> 1.0 as left & right streams feed the 6:00 tip
        private var recoilPhase = 0f     // >0 right after pinch-off so meniscus snaps upward naturally!
        private var texShift = 0f
        private var rimX = dp(ctx, 74f)
        private var rimY = dp(ctx, 165f)
        private var flowing = false

        fun stepPhysics(dt: Float, exactRimX: Float, exactRimY: Float, flowActive: Boolean): Boolean {
            rimX = exactRimX
            rimY = exactRimY
            flowing = flowActive

            val (physW, physH) = getPhysicalGlassSize(context)
            val sw = max(width.toFloat(), physW.toFloat())
            val sh = max(height.toFloat(), physH.toFloat())
            val gravity = dp(context, 540f)

            texShift = (texShift + dt * 95f) % 2048f

            // 1. Continuous 6:00 Liquid-Metal Meniscus Swell, Pinch-Off & Surface-Tension Recoil
            if (recoilPhase > 0f) {
                recoilPhase = (recoilPhase - dt * 6.5f).coerceAtLeast(0f)
            }
            if (flowActive) {
                pendantFill += dt * 1.38f // ~0.72s per rich molten droplet
                if (pendantFill >= 1f) {
                    pendantFill = 0f
                    recoilPhase = 1f
                    // Primary rich 24k droplet snaps free from the stretched tip
                    drops.firstOrNull { !it.active }?.apply {
                        active = true
                        x = rimX + (Random.nextFloat() - 0.5f) * dp(context, 1.4f)
                        y = rimY + dp(context, 10.5f)
                        vy = dp(context, 42f)
                        r = dp(context, Random.nextFloat() * 0.9f + 3.2f)
                        volumeWeight = 1.0f
                    }
                    // Plateau-Rayleigh tiny trailing satellite micro-bead!
                    drops.firstOrNull { !it.active }?.apply {
                        active = true
                        x = rimX
                        y = rimY + dp(context, 5.2f)
                        vy = dp(context, 18f)
                        r = dp(context, 1.45f)
                        volumeWeight = 0.25f
                    }
                }
                liveConfluenceSwell = (pendantFill - recoilPhase * 0.35f).coerceIn(0f, 1f)
            } else {
                pendantFill = (pendantFill - dt * 2.5f).coerceAtLeast(0f)
                liveConfluenceSwell = pendantFill
            }

            // 2. Step Falling Drops & Accumulate Real Volume into Bottom Heightfield!
            var anyDrops = false
            for (d in drops) {
                if (!d.active) continue
                anyDrops = true
                d.vy += gravity * dt
                d.y += d.vy * dt

                val colIdx = ((d.x / sw) * cols).toInt().coerceIn(0, cols)
                val localSurfaceY = sh - pileH[colIdx]
                if (d.y >= localSurfaceY) {
                    d.active = false
                    // Deposit real molten gold volume centered at impact column (grows up to ~38dp mound!)
                    val addPeak = dp(context, 1.65f) * d.volumeWeight
                    val maxMoundPx = dp(context, 38f)
                    for (k in max(0, colIdx - 6)..min(cols, colIdx + 6)) {
                        val dist = (k - colIdx).toFloat()
                        val w = exp((-dist * dist / 7.5f).toDouble()).toFloat()
                        pileH[k] = (pileH[k] + addPeak * w).coerceAtMost(maxMoundPx)
                        // Ripple wave impulse outward from impact crater!
                        pileVel[k] += (if (abs(k - colIdx) <= 1) -dp(context, 28f) else dp(context, 16f) * w) * d.volumeWeight
                    }
                    if (d.volumeWeight >= 0.8f) {
                        repeat(4) {
                            motes.firstOrNull { !it.active }?.apply {
                                active = true
                                x = d.x; y = localSurfaceY - dp(context, 1.5f)
                                vx = (Random.nextFloat() - 0.5f) * dp(context, 82f)
                                vy = -dp(context, Random.nextFloat() * 68f + 28f)
                                r = dp(context, Random.nextFloat() * 1.0f + 1.1f)
                                life = 0f; maxLife = Random.nextFloat() * 0.32f + 0.26f
                            }
                        }
                    }
                }
            }

            // 3. Step 1D Viscous Shallow-Water Slumping & Surface Ripples Along Bottom Bezel!
            var maxPile = 0f
            for (k in 0..cols) {
                val leftH = pileH[max(0, k - 1)]
                val rightH = pileH[min(cols, k + 1)]
                val laplacian = (leftH + rightH - 2f * pileH[k])
                // Wave propagation + damping
                pileVel[k] = (pileVel[k] + laplacian * 48f * dt) * (1f - min(0.9f, 5.2f * dt))
                // Viscous gravity slumping so the mound spreads steadily across the bottom of the screen
                val slump = laplacian * 3.4f * dt
                var nextH = pileH[k] + pileVel[k] * dt + slump
                if (!flowActive) {
                    // Gently drain below bottom bezel once Raven message is cleared!
                    nextH -= dp(context, 4.2f) * dt
                }
                tempH[k] = nextH.coerceAtLeast(0f)
                if (tempH[k] > maxPile) maxPile = tempH[k]
            }
            for (k in 0..cols) pileH[k] = tempH[k]

            // 4. Step Splash Motes
            var anyMotes = false
            for (m in motes) {
                if (!m.active) continue
                anyMotes = true
                m.life += dt
                if (m.life >= m.maxLife) { m.active = false; continue }
                m.x += m.vx * dt
                m.y += m.vy * dt
                m.vy += gravity * 0.28f * dt
            }

            invalidate()
            return flowActive || anyDrops || anyMotes || maxPile > 0.4f
        }

        private fun drawRichGoldTeardrop(canvas: Canvas, cx: Float, cy: Float, r: Float, topPinY: Float?, stretch: Float) {
            dropPath.reset()
            if (topPinY != null) {
                // Continuous liquid-metal funnel bridge merging straight out of the 6:00 gold frame!
                val funnelHalfW = dp(context, 7.5f) * (1f - 0.35f * (cy - topPinY) / dp(context, 14f)).coerceIn(0.4f, 1f)
                val waistHalfW = (r * 0.52f).coerceAtLeast(dp(context, 1.1f))
                val waistY = topPinY + (cy - topPinY) * 0.48f
                dropPath.moveTo(cx - funnelHalfW, topPinY - dp(context, 2.0f))
                dropPath.quadTo(cx - waistHalfW, waistY, cx - r, cy)
                dropPath.arcTo(cx - r, cy - r * 0.65f, cx + r, cy + r * 1.05f, 180f, -180f, false)
                dropPath.quadTo(cx + waistHalfW, waistY, cx + funnelHalfW, topPinY - dp(context, 2.0f))
                dropPath.close()
            } else {
                val tailY = cy - r * stretch
                dropPath.moveTo(cx, tailY)
                dropPath.quadTo(cx + r * 1.12f, cy - r * 0.12f, cx, cy + r)
                dropPath.quadTo(cx - r * 1.12f, cy - r * 0.12f, cx, tailY)
                dropPath.close()
            }

            // Rich 3D burnished ring-gold gradient (bronze rim -> warm 24k core -> antique gold shadow)
            dropPaint.shader = LinearGradient(
                cx - r * 1.1f, cy, cx + r * 1.1f, cy,
                intArrayOf(GOLD_BRONZE, GOLD_WARM, GOLD_RICH, GOLD_BRONZE),
                floatArrayOf(0f, 0.38f, 0.78f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(dropPath, dropPaint)

            // Subtle dark-bronze 0.6dp contour + crisp warm specular glint so drop matches the ring 100%!
            rimStrokePaint.strokeWidth = dp(context, 0.65f)
            rimStrokePaint.color = 0xAA4C3006.toInt()
            canvas.drawPath(dropPath, rimStrokePaint)

            glintPaint.color = GOLD_GLINT
            canvas.drawCircle(cx - r * 0.28f, cy + r * 0.14f, r * 0.30f, glintPaint)
        }

        override fun onDraw(canvas: Canvas) {
            val (physW, physH) = getPhysicalGlassSize(context)
            val sw = max(width.toFloat(), physW.toFloat())
            val sh = max(height.toFloat(), physH.toFloat())

            // 1. Attached Swelling / Recoiling Liquid-Gold Meniscus right on the 6:00 Frame Rim!
            if (flowing || pendantFill > 0.04f) {
                val recoilLift = recoilPhase * dp(context, 3.2f)
                val neckDrop = (pendantFill * pendantFill * dp(context, 10.5f) - recoilLift).coerceAtLeast(dp(context, 1.2f))
                val rad = dp(context, 1.8f + 1.85f * pendantFill)
                drawRichGoldTeardrop(canvas, rimX, rimY + neckDrop, rad, topPinY = rimY, stretch = 1.4f)
            }

            // 2. Falling Burnished 24k Droplets
            for (d in drops) {
                if (!d.active) continue
                val stretch = (1.45f + d.vy / dp(context, 520f)).coerceAtMost(2.55f)
                drawRichGoldTeardrop(canvas, d.x, d.y, d.r, topPinY = null, stretch = stretch)
            }

            // 3. Splash Motes
            for (m in motes) {
                if (!m.active) continue
                val sa = ((1f - m.life / m.maxLife) * 255f).toInt().coerceIn(0, 255)
                glintPaint.color = (GOLD_WARM and 0x00FFFFFF) or (sa shl 24)
                canvas.drawCircle(m.x, m.y, m.r, glintPaint)
            }

            // 4. True Growing Molten Gold Pile Anchored Flush to Physical Bottom Pixel (y = sh = 3088)!
            var peakH = 0f
            for (hVal in pileH) if (hVal > peakH) peakH = hVal
            if (peakH > 0.4f) {
                pilePath.reset()
                surfaceEdgePath.reset()
                var startedEdge = false

                pilePath.moveTo(0f, sh + dp(context, 3f))
                for (k in 0..cols) {
                    val px = (k.toFloat() / cols.toFloat()) * sw
                    val hCol = pileH[k]
                    val py = sh - hCol
                    pilePath.lineTo(px, py)
                    if (hCol > dp(context, 0.6f)) {
                        if (!startedEdge) {
                            surfaceEdgePath.moveTo(px, py)
                            startedEdge = true
                        } else {
                            surfaceEdgePath.lineTo(px, py)
                        }
                    } else if (startedEdge) {
                        surfaceEdgePath.lineTo(px, sh)
                        startedEdge = false
                    }
                }
                pilePath.lineTo(sw, sh + dp(context, 3f))
                pilePath.close()

                // Solid metallic 24k gold mass from undulating top down to the literal bottom bezel pixel!
                pileFillPaint.shader = LinearGradient(
                    0f, sh - peakH - dp(context, 2f), 0f, sh,
                    intArrayOf(GOLD_WARM, GOLD_RICH, GOLD_BRONZE, GOLD_SHADOW),
                    floatArrayOf(0f, 0.36f, 0.78f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(pilePath, pileFillPaint)

                // Overlay scrolling ring stardust texture strictly clipped inside the molten pile!
                ringStripBmp?.let { strip ->
                    canvas.save()
                    canvas.clipPath(pilePath)
                    stripPaint.alpha = 155
                    val tileW = sw * 0.85f
                    var startX = -(texShift % tileW)
                    while (startX < sw) {
                        stripDst.set(startX, sh - peakH - dp(context, 6f), startX + tileW, sh + dp(context, 6f))
                        canvas.drawBitmap(strip, null, stripDst, stripPaint)
                        startX += tileW
                    }
                    canvas.restore()
                }

                // Crisp specular molten gold crest highlight along the undulating top surface!
                if (startedEdge || peakH > dp(context, 1.2f)) {
                    rimStrokePaint.strokeWidth = dp(context, 1.5f)
                    rimStrokePaint.color = GOLD_GLINT
                    canvas.drawPath(surfaceEdgePath, rimStrokePaint)
                }
            }
        }
    }

    /**
     * Unified Living Pedestal Crest View V3:
     * - 60fps GPU `drawBitmapMesh` Living Raven (fluid sine/damped-spring head tilts + chest breathing + isolated eye blink!)
     * - Converging 6:00 Liquid-Gold Funnel synced to `liveConfluenceSwell`
     * - Deep Chiseled Ember + Lightning-Resonant Pedestal Runes
     */
    class LivingPedestalCrestView(ctx: Context) : FrameLayout(ctx) {
        val padSide = dpInt(ctx, 18)
        val padTop = dpInt(ctx, 18)
        val padBottom = dpInt(ctx, 18)
        val cardW = dpInt(ctx, 112)
        val cardH = dpInt(ctx, 140)
        private val cornerR = dp(ctx, 20f)

        val totalWindowWidthPx: Int = cardW + padSide * 2
        val totalWindowHeightPx: Int = cardH + padTop + padBottom

        private val livingArtView: LivingPedestalArtMeshView
        private val electricHammerDrawable = ElectricHammerLightningDrawable()
        private val runesDrawable: ShimmeringRuneOverlayDrawable?
        private val skyBoltsDrawable: SkyStormBoltsDrawable

        private val flankSegs = 64
        private val radSteps = 8
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

        private var lastFrameMs = SystemClock.elapsedRealtime()
        private var flowPhase = 0f
        private var isStandbyMode = false
        private var isSessionOpen = false
        private var isSpeakingNow = false
        private var hasUnreadRavenFlag = false
        private var showingRavenOnStone = false

        private var nextMjolnirIdleArcAtMs = SystemClock.elapsedRealtime() + 4500L
        private val bootShowcaseUntilMs = SystemClock.elapsedRealtime() + 30_000L

        private var masterLoop: ValueAnimator? = null
        private var strikeAnimator: ValueAnimator? = null

        init {
            setWillNotDraw(false)
            clipChildren = false
            clipToPadding = false
            layoutParams = LayoutParams(totalWindowWidthPx, totalWindowHeightPx)

            val roundedCard = FrameLayout(ctx).apply {
                layoutParams = LayoutParams(cardW, cardH, Gravity.TOP or Gravity.START).apply {
                    leftMargin = padSide
                    topMargin = padTop
                }
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, cornerR)
                    }
                }
                elevation = dp(ctx, 7f)
            }

            livingArtView = LivingPedestalArtMeshView(ctx)
            roundedCard.addView(livingArtView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            runesDrawable = runesGoldBmp?.let { ShimmeringRuneOverlayDrawable(it) }
            if (runesDrawable != null) {
                roundedCard.addView(ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    setImageDrawable(runesDrawable)
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                })
            }

            skyBoltsDrawable = SkyStormBoltsDrawable(boltCores, boltHalos)
            roundedCard.addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageDrawable(skyBoltsDrawable)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            })

            roundedCard.addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageDrawable(electricHammerDrawable)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            })

            addView(roundedCard)
            refreshScrolledStripBitmap()
            startMasterLoop()
        }

        private fun refreshScrolledStripBitmap() {
            val src = ringStripBmp ?: return
            val c = scrolledStripCanvas ?: return
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val w = src.width.toFloat()
            val shiftPx = ((flowPhase % 1f + 1f) % 1f) * w
            c.drawBitmap(src, shiftPx, 0f, stripDrawPaint)
            c.drawBitmap(src, shiftPx - w, 0f, stripDrawPaint)
        }

        fun hideMjolnirForIncomingLanding() {
            // Smoothly pre-switches backdrop inside lightning flash before Raven chest crosses hammer!
            showingRavenOnStone = true
            livingArtView.setMode(showRaven = true, preLandingFadeIn = true)
            invalidate()
        }

        fun setOccupantImmediate(showRaven: Boolean) {
            showingRavenOnStone = showRaven
            livingArtView.setMode(showRaven = showRaven, preLandingFadeIn = false)
            if (!showRaven) {
                nextMjolnirIdleArcAtMs = SystemClock.elapsedRealtime() + Random.nextLong(4200L, 7800L)
            }
            invalidate()
        }

        fun triggerElectricStrikeBurst(durationMs: Long = 420L, intensity: Float = 1.0f) {
            strikeAnimator?.cancel()
            skyBoltsDrawable.forceStrikeNow()
            runesDrawable?.triggerLightningResonance()
            electricHammerDrawable.intensityScale = intensity
            strikeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = LinearInterpolator()
                addUpdateListener { a -> electricHammerDrawable.progress = a.animatedValue as Float }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        electricHammerDrawable.progress = -1f
                    }
                })
                start()
            }
        }

        fun updateOperationalState(standby: Boolean, sessionOpen: Boolean, speaking: Boolean, unreadRaven: Boolean) {
            isStandbyMode = standby
            isSessionOpen = sessionOpen
            isSpeakingNow = speaking
            hasUnreadRavenFlag = unreadRaven
            ringPaint.colorFilter = if (standby && !unreadRaven) {
                PorterDuffColorFilter(Color.parseColor("#EF4444"), PorterDuff.Mode.SRC_ATOP)
            } else null
            livingArtView.alpha = if (standby && !unreadRaven) 0.78f else 1.0f
            invalidate()
        }

        fun refreshDripActivity(unreadRaven: Boolean) {
            hasUnreadRavenFlag = unreadRaven
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 20 && h > 20) {
                buildDualFlankGeometry()
                meshReady = true
                updateDualCascadingMeshes(0f)
            }
        }

        private fun buildDualFlankGeometry() {
            val left = padSide.toFloat()
            val top = padTop.toFloat()
            val right = (padSide + cardW).toFloat()
            val bottom = (padTop + cardH).toFloat()
            val cx = (left + right) * 0.5f
            val r = cornerR.coerceAtMost(min(cardW, cardH) * 0.46f)

            val halfTopStraight = ((right - r) - cx).coerceAtLeast(1f)
            val sideStraight = ((bottom - r) - (top + r)).coerceAtLeast(1f)
            val halfBotStraight = ((right - r) - cx).coerceAtLeast(1f)
            val arcLen = (0.5 * Math.PI * r).toFloat()
            val halfPerimLen = halfTopStraight + arcLen + sideStraight + arcLen + halfBotStraight

            for (i in 0..flankSegs) {
                val u = i.toFloat() / flankSegs.toFloat()
                var s = u * halfPerimLen
                var rx: Float; var ry: Float; var rnx: Float; var rny: Float

                if (s <= halfTopStraight) {
                    rx = cx + s; ry = top; rnx = 0f; rny = -1f
                } else {
                    s -= halfTopStraight
                    if (s <= arcLen) {
                        val a = (-Math.PI * 0.5).toFloat() + (s / r)
                        rnx = cos(a.toDouble()).toFloat(); rny = sin(a.toDouble()).toFloat()
                        rx = (right - r) + r * rnx; ry = (top + r) + r * rny
                    } else {
                        s -= arcLen
                        if (s <= sideStraight) {
                            rx = right; ry = (top + r) + s; rnx = 1f; rny = 0f
                        } else {
                            s -= sideStraight
                            if (s <= arcLen) {
                                val a = s / r
                                rnx = cos(a.toDouble()).toFloat(); rny = sin(a.toDouble()).toFloat()
                                rx = (right - r) + r * rnx; ry = (bottom - r) + r * rny
                            } else {
                                s -= arcLen
                                rx = (right - r) - s.coerceAtMost(halfBotStraight); ry = bottom; rnx = 0f; rny = 1f
                            }
                        }
                    }
                }
                rightBx[i] = rx; rightBy[i] = ry; rightNx[i] = rnx; rightNy[i] = rny
                leftBx[i] = 2f * cx - rx; leftBy[i] = ry; leftNx[i] = -rnx; leftNy[i] = rny
            }
        }

        private fun updateDualCascadingMeshes(funnelSwell: Float) {
            if (!meshReady) return
            val innerTuckPx = -dp(context, 0.5f)
            val outerSpanPx = padSide.toFloat() - innerTuckPx
            val maxFunnelPull = dp(context, 4.8f) * (0.30f + 0.70f * funnelSwell)

            for (i in 0..flankSegs) {
                val u = i.toFloat() / flankSegs.toFloat()
                // Converge the outer ring smoothly downward right at 6:00 (u = 0.86..1.0) into the liquid drip spout!
                val spoutBell = if (u > 0.86f) {
                    val t = (u - 0.86f) / 0.14f
                    t * t * (3f - 2f * t)
                } else 0f

                for (j in 0..radSteps) {
                    val rFrac = j.toFloat() / radSteps.toFloat()
                    val dist = innerTuckPx + outerSpanPx * rFrac
                    val extraFunnelY = maxFunnelPull * spoutBell * rFrac * rFrac
                    val vIdx = (j * (flankSegs + 1) + i) * 2
                    rightMeshVerts[vIdx] = rightBx[i] + rightNx[i] * dist
                    rightMeshVerts[vIdx + 1] = rightBy[i] + rightNy[i] * dist + extraFunnelY
                    leftMeshVerts[vIdx] = leftBx[i] + leftNx[i] * dist
                    leftMeshVerts[vIdx + 1] = leftBy[i] + leftNy[i] * dist + extraFunnelY
                }
            }
        }

        private fun startMasterLoop() {
            masterLoop?.cancel()
            val startAt = SystemClock.elapsedRealtime()
            lastFrameMs = startAt
            masterLoop = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 8000L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val now = SystemClock.elapsedRealtime()
                    val dt = ((now - lastFrameMs).coerceIn(1L, 80L)).toFloat() / 1000f
                    lastFrameMs = now
                    val elapsed = (now - startAt).coerceAtLeast(0L)

                    val flowActive = (!isStandbyMode || hasUnreadRavenFlag) &&
                        (isSessionOpen || isSpeakingNow || hasUnreadRavenFlag || now < bootShowcaseUntilMs)

                    if (flowActive) {
                        val speedMult = if (isSpeakingNow) 1.24f else 1.0f
                        flowPhase = (flowPhase + (dt / 7.2f) * speedMult) % 1f
                        refreshScrolledStripBitmap()
                        val pulseRad = ((elapsed % 1100L).toDouble() / 1100.0) * Math.PI * 2.0
                        ringPaint.alpha = ((0.82 + 0.18 * sin(pulseRad)) * 255.0).toInt().coerceIn(165, 255)
                    } else {
                        ringPaint.alpha = if (isStandbyMode) 195 else 228
                    }

                    updateDualCascadingMeshes(if (flowActive) liveConfluenceSwell else 0f)

                    if (isAttachedToWindow) {
                        val mv = if (flowActive || moltenStreamOverlay != null) ensureMoltenStreamOverlay(context) else null
                        if (mv != null) {
                            val cLoc = IntArray(2); getLocationOnScreen(cLoc)
                            val mLoc = IntArray(2); if (mv.isAttachedToWindow) mv.getLocationOnScreen(mLoc)
                            val exactRimX = (cLoc[0] - mLoc[0]) + padSide + cardW * 0.50f
                            val exactRimY = (cLoc[1] - mLoc[1]) + padTop + cardH + padSide * 0.52f
                            val keep = mv.stepPhysics(dt, exactRimX, exactRimY, flowActive)
                            if (!keep && !flowActive) detachMoltenStreamOverlay()
                        }
                    }

                    livingArtView.stepLivingMotion(dt, now, elapsed)
                    runesDrawable?.tick(dt, elapsed)

                    if (!isStandbyMode || hasUnreadRavenFlag) {
                        skyBoltsDrawable.tick(now)
                    }

                    if (!showingRavenOnStone && !isStandbyMode && now >= nextMjolnirIdleArcAtMs) {
                        nextMjolnirIdleArcAtMs = now + Random.nextLong(4600L, 8400L)
                        triggerElectricStrikeBurst(durationMs = 280L, intensity = 0.72f)
                    }

                    invalidate()
                }
                start()
            }
        }

        fun stopLoop() {
            masterLoop?.cancel(); masterLoop = null
            strikeAnimator?.cancel(); strikeAnimator = null
        }

        override fun dispatchDraw(canvas: Canvas) {
            val strip = scrolledStripBmp ?: ringStripBmp
            if (strip != null && meshReady) {
                canvas.drawBitmapMesh(strip, flankSegs, radSteps, rightMeshVerts, 0, null, 0, ringPaint)
                canvas.drawBitmapMesh(strip, flankSegs, radSteps, leftMeshVerts, 0, null, 0, ringPaint)
            }
            super.dispatchDraw(canvas)
        }
    }

    /**
     * 60fps Sub-Pixel Living Raven Mesh + Synchronized Isolated Eye-Blink Renderer (0.000px cloud/stone jump!).
     */
    private class LivingPedestalArtMeshView(ctx: Context) : View(ctx) {
        private val mCols = 20
        private val mRows = 24
        private val verts = FloatArray((mCols + 1) * (mRows + 1) * 2)
        private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val blinkPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dstRect = RectF()

        private var showRaven = false
        private var curTiltRad = 0f; private var targetTiltRad = 0f
        private var curLookX = 0f;   private var targetLookX = 0f
        private var curLookY = 0f;   private var targetLookY = 0f
        private var nextPoseGoalAtMs = SystemClock.elapsedRealtime() + 1800L

        private var blinkAlpha = 0f
        private var blinkStartMs = 0L
        private var blinkDurMs = 190L
        private var nextBlinkAtMs = SystemClock.elapsedRealtime() + 2100L
        private var pendingSecondBlink = false

        fun setMode(showRaven: Boolean, preLandingFadeIn: Boolean) {
            this.showRaven = showRaven
            blinkAlpha = 0f
            blinkStartMs = 0L
            if (!showRaven) {
                curTiltRad = 0f; targetTiltRad = 0f
                curLookX = 0f; targetLookX = 0f; curLookY = 0f; targetLookY = 0f
            } else if (preLandingFadeIn) {
                // Slight forward settle recoil as talons touch stone!
                curLookY = dp(context, 1.8f); targetLookY = 0f
                curTiltRad = 0.04f; targetTiltRad = 0f
                nextPoseGoalAtMs = SystemClock.elapsedRealtime() + 1400L
            }
            invalidate()
        }

        fun stepLivingMotion(dt: Float, nowMs: Long, elapsedMs: Long) {
            if (!showRaven) return
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 4f || h <= 4f) return

            // 1. Smooth Damped-Spring Head Tilt & Inquisitive Look Goals (0 frame-cuts!)
            if (nowMs >= nextPoseGoalAtMs) {
                when (Random.nextInt(4)) {
                    0 -> { targetTiltRad = 0.082f;  targetLookX = dp(context, 2.2f);  targetLookY = dp(context, 1.4f) }  // Inquisitive cock down-right
                    1 -> { targetTiltRad = -0.058f; targetLookX = dp(context, 1.1f);  targetLookY = -dp(context, 1.6f) } // Alert sentinel head lift
                    2 -> { targetTiltRad = 0.045f;  targetLookX = -dp(context, 1.4f); targetLookY = dp(context, 0.5f) }  // Subtle tuck
                    else -> { targetTiltRad = 0f;   targetLookX = 0f;                 targetLookY = 0f }                 // Calm neutral
                }
                nextPoseGoalAtMs = nowMs + Random.nextLong(1900L, 3800L)
            }
            val spring = min(1f, dt * 3.8f)
            curTiltRad += (targetTiltRad - curTiltRad) * spring
            curLookX += (targetLookX - curLookX) * spring
            curLookY += (targetLookY - curLookY) * spring

            // 2. Crisp Natural Avian Single & Double Eyelid Blink (strictly on 40px eye patch!)
            if (blinkStartMs > 0L) {
                val bEl = nowMs - blinkStartMs
                if (bEl >= blinkDurMs) {
                    blinkStartMs = 0L
                    blinkAlpha = 0f
                    if (pendingSecondBlink) {
                        pendingSecondBlink = false
                        nextBlinkAtMs = nowMs + 130L
                    } else {
                        nextBlinkAtMs = nowMs + Random.nextLong(2200L, 4800L)
                    }
                } else {
                    blinkAlpha = sin((bEl.toDouble() / blinkDurMs.toDouble()) * Math.PI).toFloat().coerceIn(0f, 1f)
                }
            } else if (nowMs >= nextBlinkAtMs) {
                blinkStartMs = nowMs
                blinkDurMs = 185L
                pendingSecondBlink = Random.nextFloat() < 0.28f
            }

            // 3. Build 20x24 Continuous Mesh: Head pivots smoothly around neck (0.44w, 0.30h), chest breathes, stone & borders = 0.0px!
            val breath = sin((elapsedMs.toDouble() / 3200.0) * Math.PI * 2.0).toFloat()
            val pivotX = w * 0.44f
            val pivotY = h * 0.30f
            val cosT = cos(curTiltRad.toDouble()).toFloat()
            val sinT = sin(curTiltRad.toDouble()).toFloat()

            for (r in 0..mRows) {
                val ny = r.toFloat() / mRows.toFloat()
                val baseY = ny * h
                // Strictly lock card edges (0, 1) and stone pedestal (ny >= 0.55) to 0.000px!
                val borderGuardY = (min(ny / 0.08f, (0.55f - ny) / 0.09f)).coerceIn(0f, 1f)
                for (c in 0..mCols) {
                    val nx = c.toFloat() / mCols.toFloat()
                    val baseX = nx * w
                    val borderGuardX = (min(nx / 0.10f, (1f - nx) / 0.10f)).coerceIn(0f, 1f)
                    val guard = borderGuardX * borderGuardY

                    var dx = 0f
                    var dy = 0f
                    if (guard > 0.001f) {
                        // Smooth Gaussian head envelope centered on Raven head/beak (0.46w, 0.20h)
                        val hdx = (nx - 0.46f) / 0.19f
                        val hdy = (ny - 0.20f) / 0.14f
                        val wHead = exp((-0.5f * (hdx * hdx + hdy * hdy)).toDouble()).toFloat() * guard

                        val rx = baseX - pivotX
                        val ry = baseY - pivotY
                        val rotDx = (rx * cosT - ry * sinT) - rx
                        val rotDy = (rx * sinT + ry * cosT) - ry

                        // Smooth chest ribcage breath centered at (0.48w, 0.40h)
                        val cdx = (nx - 0.48f) / 0.16f
                        val cdy = (ny - 0.40f) / 0.11f
                        val wChest = exp((-0.5f * (cdx * cdx + cdy * cdy)).toDouble()).toFloat() * guard

                        dx = (rotDx + curLookX) * wHead + breath * dp(context, 1.05f) * wChest
                        dy = (rotDy + curLookY) * wHead - breath * dp(context, 0.55f) * wChest
                    }

                    val idx = (r * (mCols + 1) + c) * 2
                    verts[idx] = baseX + dx
                    verts[idx + 1] = baseY + dy
                }
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 4f || h <= 4f) return
            if (!showRaven) {
                val mBmp = mjolnirBaseBmp ?: ravenBaseBmp ?: return
                dstRect.set(0f, 0f, w, h)
                canvas.drawBitmap(mBmp, null, dstRect, artPaint)
                return
            }
            val rBmp = ravenBaseBmp ?: return
            canvas.drawBitmapMesh(rBmp, mCols, mRows, verts, 0, null, 0, artPaint)
            if (blinkAlpha > 0.02f) {
                ravenEyeBlinkPatchBmp?.let { eyeBmp ->
                    blinkPaint.alpha = (blinkAlpha * 255f).toInt().coerceIn(0, 255)
                    canvas.drawBitmapMesh(eyeBmp, mCols, mRows, verts, 0, null, 0, blinkPaint)
                }
            }
        }
    }

    /**
     * Deep Chiseled Ember + Dual Specular Sweep + Lightning-Resonance Pedestal Runes Drawable.
     */
    private class ShimmeringRuneOverlayDrawable(private val runesBitmap: Bitmap) : Drawable() {
        private val normRect = RectF(0.32031f, 0.61129f, 0.62813f, 0.89530f)
        private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(0xCC2A1502.toInt(), PorterDuff.Mode.SRC_IN)
        }
        private val basePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
        private val dstRect = RectF()
        private val shadowRect = RectF()
        private var shimmerPhase = 0f
        private var emberBreath = 0f
        private var strikeFlash = 0f

        fun triggerLightningResonance() {
            strikeFlash = 1.0f
            invalidateSelf()
        }

        fun tick(dt: Float, elapsedMs: Long) {
            shimmerPhase = (elapsedMs % 3400L).toFloat() / 3400f
            emberBreath = (0.5 + 0.5 * sin((elapsedMs.toDouble() / 2200.0) * Math.PI * 2.0)).toFloat()
            if (strikeFlash > 0f) strikeFlash = (strikeFlash - dt * 2.6f).coerceAtLeast(0f)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val b = bounds; if (b.isEmpty) return
            val bw = b.width().toFloat(); val bh = b.height().toFloat()
            dstRect.set(b.left + normRect.left * bw, b.top + normRect.top * bh, b.left + normRect.right * bw, b.top + normRect.bottom * bh)

            // 1. Carved stone depth offset shadow so runes look physically chiseled into the rock!
            val offPx = max(1f, bw * 0.008f)
            shadowRect.set(dstRect.left + offPx, dstRect.top + offPx, dstRect.right + offPx, dstRect.bottom + offPx)
            canvas.drawBitmap(runesBitmap, null, shadowRect, shadowPaint)

            // 2. Warm breathing molten-gold rune base
            basePaint.alpha = (225 + 30 * emberBreath).toInt().coerceIn(0, 255)
            canvas.drawBitmap(runesBitmap, null, dstRect, basePaint)

            // 3. Travelling 24k specular glint + electric lightning resonance masked strictly inside rune strokes
            val rw = dstRect.width(); val rh = dstRect.height()
            val lid = canvas.saveLayer(dstRect, null)
            canvas.drawBitmap(runesBitmap, null, dstRect, maskPaint)

            val sx = dstRect.left - rw * 0.55f + (rw * 2.10f) * shimmerPhase
            val sy = dstRect.top - rh * 0.20f + (rh * 0.65f) * shimmerPhase
            sweepPaint.shader = LinearGradient(
                sx, sy, sx + rw * 0.42f, sy + rh * 0.28f,
                intArrayOf(0x00966B12, 0x88D4A234.toInt(), 0xFFFFF08A.toInt(), 0xFFFFFBEB.toInt(), 0xFFFFF08A.toInt(), 0x88D4A234.toInt(), 0x00966B12),
                floatArrayOf(0f, 0.22f, 0.44f, 0.50f, 0.56f, 0.78f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(dstRect, sweepPaint)

            if (strikeFlash > 0.02f) {
                sweepPaint.shader = null
                sweepPaint.color = Color.argb((strikeFlash * 225f).toInt(), 255, 251, 235)
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
            RectF(0.00000f, 0.06207f, 0.44609f, 0.56677f),
            RectF(0.31562f, 0.00000f, 0.77578f, 0.22696f),
            RectF(0.30469f, 0.26583f, 0.77656f, 0.49843f),
            RectF(0.67812f, 0.19875f, 1.00000f, 0.56489f)
        )
        private val haloPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val corePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dstRect = RectF()
        private val startMs = LongArray(4); private val durMs = LongArray(4); private val nextAtMs = LongArray(4)
        private var lastAnyMs = 0L

        init {
            val now = SystemClock.elapsedRealtime()
            for (i in 0 until 4) {
                durMs[i] = Random.nextLong(460L, 780L)
                nextAtMs[i] = now + Random.nextLong(400L + i * 520L, 2100L + i * 750L)
            }
        }

        fun forceStrikeNow() {
            val now = SystemClock.elapsedRealtime()
            startMs[0] = now; durMs[0] = 500L
            startMs[3] = now + 45L; durMs[3] = 540L
            lastAnyMs = now
            invalidateSelf()
        }

        fun tick(nowMs: Long) {
            for (i in 0 until 4) {
                val active = startMs[i] > 0L && (nowMs - startMs[i]) <= durMs[i]
                if (!active && nowMs >= nextAtMs[i]) {
                    if ((nowMs - lastAnyMs) < 290L) {
                        nextAtMs[i] = lastAnyMs + Random.nextLong(320L, 820L)
                    } else {
                        startMs[i] = nowMs
                        durMs[i] = Random.nextLong(440L, 780L)
                        lastAnyMs = nowMs
                        nextAtMs[i] = nowMs + durMs[i] + Random.nextLong(1250L, 4400L)
                    }
                }
            }
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val b = bounds; if (b.isEmpty) return
            val bw = b.width().toFloat(); val bh = b.height().toFloat()
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
                canvas.save()
                canvas.clipRect(dstRect.left, dstRect.top, dstRect.right, frontBottom)
                canvas.drawBitmap(halo, null, dstRect, haloPaint)
                canvas.drawBitmap(core, null, dstRect, corePaint)
                canvas.restore()
            }
        }

        override fun setAlpha(alpha: Int) { corePaint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { corePaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

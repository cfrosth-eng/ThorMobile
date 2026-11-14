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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

object LivingPedestalDirector {
    private const val TAG = "LivingPedestalWO161"
    private val mainHandler = Handler(Looper.getMainLooper())

    // Sampled directly from raven_gold_ring_strip.png for 100% identical metallic Norse champagne-gold!
    private val GOLD_DARK = 0xFF7E570A.toInt()
    private val GOLD_MID  = 0xFFCA992F.toInt()
    private val GOLD_HI   = 0xFFFDE766.toInt()
    private val GOLD_SPEC = 0xFFFEFBA9.toInt()

    @Volatile var hasUnreadRaven: Boolean = false
        private set
    @Volatile var ravenCurrentlyPerched: Boolean = false
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
    private var mjolnirSwapCoreBmp: Bitmap? = null
    private var mjolnirSwapHaloBmp: Bitmap? = null
    private var ravenBaseBmp: Bitmap? = null
    private var ravenTurnBmp: Bitmap? = null
    private var ravenBlinkBmp: Bitmap? = null
    private var ringStripBmp: Bitmap? = null
    private var runesGoldBmp: Bitmap? = null
    private val boltCores = arrayOfNulls<Bitmap>(4)
    private val boltHalos = arrayOfNulls<Bitmap>(4)
    private val flyFrames = arrayOfNulls<Bitmap>(3)

    fun dp(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    fun dpInt(ctx: Context, v: Int): Int = dp(ctx, v.toFloat()).toInt()

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
        if (mjolnirBaseBmp != null && ravenBaseBmp != null && flyFrames[0] != null && ringStripBmp != null) return
        mjolnirBaseBmp = decodeRaw(app, R.drawable.mjolnir_pedestal_base_hd)
        mjolnirSwapCoreBmp = decodeRaw(app, R.drawable.mjolnir_swap_bolt_core)
        mjolnirSwapHaloBmp = decodeRaw(app, R.drawable.mjolnir_swap_bolt_halo)
        ravenBaseBmp = decodeRaw(app, R.drawable.raven_storm_base_hd)
        ravenTurnBmp = decodeRaw(app, R.drawable.raven_perch_pose_turn) ?: ravenBaseBmp
        ravenBlinkBmp = decodeRaw(app, R.drawable.raven_perch_pose_blink) ?: ravenBaseBmp
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
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        return try { wm.addView(view, lp); moltenStreamOverlay = view; view } catch (_: Exception) { null }
    }

    private fun detachMoltenStreamOverlay() {
        val v = moltenStreamOverlay
        moltenStreamOverlay = null
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

            // Ensure Mjolnir holds the pedestal during incoming approach until touchdown!
            crest.setOccupantImmediate(showRaven = false)
            launchBezierFlight(
                app = app,
                mode = FlightMode.ARRIVAL,
                onLiftoffImmediate = null,
                onTouchdownExact = {
                    ravenCurrentlyPerched = true
                    crest.setOccupantImmediate(showRaven = true)
                    crest.triggerElectricStrikeBurst(440L)
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

            launchBezierFlight(
                app = app,
                mode = FlightMode.DEPARTURE,
                onLiftoffImmediate = {
                    // Atomic t=0ms liftoff swap: Raven leaves stone immediately & Mjolnir reforms in electric lightning!
                    ravenCurrentlyPerched = false
                    crest.setOccupantImmediate(showRaven = false)
                    crest.triggerElectricStrikeBurst(480L)
                },
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
        val delayMs = Random.nextLong(13_500L, 22_000L)
        val r = Runnable {
            if (!hasUnreadRaven || ravenCardUnfoldedNow || flightInProgress || attachedCrestView == null) return@Runnable
            launchBezierFlight(
                app = app,
                mode = FlightMode.PATROL,
                onLiftoffImmediate = {
                    // Atomic t=0ms liftoff: ZERO chance of Raven remaining on pedestal while airborne!
                    ravenCurrentlyPerched = false
                    attachedCrestView?.setOccupantImmediate(showRaven = false)
                    attachedCrestView?.triggerElectricStrikeBurst(440L)
                },
                onTouchdownExact = {
                    if (hasUnreadRaven) {
                        ravenCurrentlyPerched = true
                        attachedCrestView?.setOccupantImmediate(showRaven = true)
                        attachedCrestView?.triggerElectricStrikeBurst(420L)
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
    private data class BezierLeg(val p0: PointF, val p1: PointF, val p2: PointF, val p3: PointF, val isLandingFlare: Boolean = false)

    /**
     * Returns exact perch coordinates in `targetView` local canvas space (0.0px status-bar/cutout offset!).
     */
    private fun getPerchInOverlayCoords(app: Context, targetView: View?): PointF {
        val crest = attachedCrestView
        if (crest != null && crest.isAttachedToWindow) {
            val cLoc = IntArray(2)
            crest.getLocationOnScreen(cLoc)
            val oLoc = IntArray(2)
            if (targetView != null && targetView.isAttachedToWindow) targetView.getLocationOnScreen(oLoc)
            val px = (cLoc[0] - oLoc[0]) + crest.padSide + crest.cardW * 0.50f
            val py = (cLoc[1] - oLoc[1]) + crest.padTop + crest.cardH * 0.36f
            return PointF(px, py)
        }
        return PointF(dp(app, 74f), dp(app, 86f))
    }

    /**
     * Builds physically logical bird flight paths:
     * - Perched Raven ALWAYS faces RIGHT (+X).
     * - Every Takeoff launches UP-AND-RIGHT (vx > 0, vy < 0) so the Raven springs forward in the exact direction it faces!
     * - Every Final Landing Leg approaches from UPPER-LEFT moving LEFT-TO-RIGHT (vx > 0, vy > 0) into an aerodynamic
     *   braking flare right onto the stone top—so the Raven NEVER flips 180 degrees and NEVER flies up from underneath!
     */
    private fun buildLogicalFlightLegs(app: Context, mode: FlightMode, perch: PointF): Pair<List<BezierLeg>, Long> {
        val dm = app.resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()

        // Left-to-right final approach entry point (sweeps in from upper-left off-screen bezel if pedestal is on left, or upper-left of pedestal if dragged right)
        val approachStart = PointF(perch.x - dp(app, 145f), max(-dp(app, 35f), perch.y - dp(app, 68f)))
        val approachC1    = PointF(perch.x - dp(app, 92f), max(dp(app, 4f), perch.y - dp(app, 46f)))
        val approachC2    = PointF(perch.x - dp(app, 38f), perch.y - dp(app, 14f))

        return when (mode) {
            FlightMode.ARRIVAL -> {
                // Sweeps across upper sky, banks off left edge, and flares Left-to-Right onto the stone!
                Pair(listOf(BezierLeg(approachStart, approachC1, approachC2, perch, isLandingFlare = true)), 1280L)
            }
            FlightMode.DEPARTURE -> {
                val exitPt = PointF(sw + dp(app, 120f), max(dp(app, 30f), perch.y - dp(app, 60f)))
                val c1 = PointF(perch.x + dp(app, 115f), perch.y - dp(app, 72f))
                val c2 = PointF(sw * 0.70f, sh * 0.24f)
                Pair(listOf(BezierLeg(perch, c1, c2, exitPt)), 1280L)
            }
            FlightMode.PATROL -> {
                when (Random.nextInt(3)) {
                    0 -> {
                        // Patrol A: Launches forward-right across screen, exits right bezel, wraps in from upper-left bezel Left-to-Right!
                        val exitR = PointF(sw + dp(app, 105f), sh * 0.30f)
                        val legOut = BezierLeg(
                            perch,
                            PointF(perch.x + dp(app, 115f), perch.y - dp(app, 58f)),
                            PointF(sw * 0.68f, sh * 0.16f),
                            exitR
                        )
                        val legIn = BezierLeg(approachStart, approachC1, approachC2, perch, isLandingFlare = true)
                        Pair(listOf(legOut, legIn), 3150L)
                    }
                    1 -> {
                        // Patrol B: Soars forward-right down to mid-screen, banks high across top-left off bezel, then flares Left-to-Right!
                        val midR = PointF(sw * 0.80f, sh * 0.46f)
                        val leg1 = BezierLeg(
                            perch,
                            PointF(perch.x + dp(app, 120f), perch.y - dp(app, 45f)),
                            PointF(sw * 0.88f, sh * 0.18f),
                            midR
                        )
                        val leg2 = BezierLeg(
                            midR,
                            PointF(sw * 0.45f, sh * 0.64f),
                            PointF(perch.x - dp(app, 185f), perch.y + dp(app, 55f)),
                            approachStart
                        )
                        val leg3 = BezierLeg(approachStart, approachC1, approachC2, perch, isLandingFlare = true)
                        Pair(listOf(leg1, leg2, leg3), 3850L)
                    }
                    else -> {
                        // Patrol C: Sweeping downward-right dive across display, exits right edge, re-enters upper-left Left-to-Right!
                        val exitR2 = PointF(sw + dp(app, 105f), sh * 0.52f)
                        val legOut = BezierLeg(
                            perch,
                            PointF(perch.x + dp(app, 105f), perch.y - dp(app, 40f)),
                            PointF(sw * 0.56f, sh * 0.58f),
                            exitR2
                        )
                        val legIn = BezierLeg(approachStart, approachC1, approachC2, perch, isLandingFlare = true)
                        Pair(listOf(legOut, legIn), 3250L)
                    }
                }
            }
        }
    }

    private fun launchBezierFlight(
        app: Context,
        mode: FlightMode,
        onLiftoffImmediate: (() -> Unit)?,
        onTouchdownExact: (() -> Unit)?,
        onComplete: (() -> Unit)?
    ) {
        if (!Settings.canDrawOverlays(app)) {
            onLiftoffImmediate?.invoke()
            onTouchdownExact?.invoke()
            onComplete?.invoke()
            return
        }
        stopActiveFlightOverlay()

        val wm = flightWindowManager ?: (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { flightWindowManager = it }
        val stageView = RavenFlightStageView(app, flyFrames)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        try {
            wm.addView(stageView, lp)
            activeFlightView = stageView
            flightInProgress = true
        } catch (e: Exception) {
            onTouchdownExact?.invoke()
            onComplete?.invoke()
            return
        }

        // Fire atomic liftoff swap BEFORE frame 1 renders so two Ravens never coexist!
        onLiftoffImmediate?.invoke()

        var legsBuilt: List<BezierLeg>? = null
        var touchdownFired = false
        val startElapsed = SystemClock.elapsedRealtime()
        val estDur = if (mode == FlightMode.PATROL) 3400L else 1300L

        flightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = estDur
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val rawU = (anim.animatedValue as Float).coerceIn(0f, 1f)
                val livePerch = getPerchInOverlayCoords(app, stageView)
                val legs = legsBuilt ?: buildLogicalFlightLegs(app, mode, livePerch).first.also { legsBuilt = it }

                val legIdx = min(legs.size - 1, (rawU * legs.size).toInt())
                var legT = ((rawU * legs.size) - legIdx).coerceIn(0f, 1f)
                val baseLeg = legs[legIdx]

                // Aerodynamic ease-out deceleration on the landing flare leg so Huginn brakes smoothly onto the stone!
                if (baseLeg.isLandingFlare) {
                    legT = (1.0 - (1.0 - legT.toDouble()).pow(1.85)).toFloat().coerceIn(0f, 1f)
                } else if (legIdx == 0 && mode != FlightMode.ARRIVAL) {
                    legT = legT.toDouble().pow(1.25).toFloat().coerceIn(0f, 1f)
                }

                val p0 = if (legIdx == 0 && mode != FlightMode.ARRIVAL) livePerch else baseLeg.p0
                val p3 = if (legIdx == legs.size - 1 && mode != FlightMode.DEPARTURE) livePerch else baseLeg.p3
                val p1 = if (baseLeg.isLandingFlare) PointF(livePerch.x - dp(app, 92f), max(dp(app, 4f), livePerch.y - dp(app, 46f))) else baseLeg.p1
                val p2 = if (baseLeg.isLandingFlare) PointF(livePerch.x - dp(app, 36f), livePerch.y - dp(app, 12f)) else baseLeg.p2

                val omt = 1f - legT
                val bx = omt * omt * omt * p0.x + 3f * omt * omt * legT * p1.x + 3f * omt * legT * legT * p2.x + legT * legT * legT * p3.x
                val by = omt * omt * omt * p0.y + 3f * omt * omt * legT * p1.y + 3f * omt * legT * legT * p2.y + legT * legT * legT * p3.y

                val dx = 3f * omt * omt * (p1.x - p0.x) + 6f * omt * legT * (p2.x - p1.x) + 3f * legT * legT * (p3.x - p2.x)
                val dy = 3f * omt * omt * (p1.y - p0.y) + 6f * omt * legT * (p2.y - p1.y) + 3f * legT * legT * (p3.y - p2.y)

                val nowMs = SystemClock.elapsedRealtime() - startElapsed
                val flapPeriod = if (baseLeg.isLandingFlare && legT > 0.55f) 85L else 125L
                val speedMag = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
                val isGlide = !baseLeg.isLandingFlare && (dy / speedMag > 0.22f)
                val flapSeq = intArrayOf(0, 1, 2, 1)
                val frameIdx = if (isGlide) 1 else flapSeq[((nowMs / flapPeriod) % 4L).toInt()]

                // Match exact perched Raven scale (0.64f) at liftoff & touchdown so transition is seamless!
                val scaleDepth = when {
                    baseLeg.isLandingFlare -> 1.02f - 0.38f * legT
                    legIdx == 0 && mode != FlightMode.ARRIVAL -> 0.64f + 0.38f * min(1f, legT / 0.45f)
                    else -> 1.02f
                }

                // Touchdown fires ONLY at legT >= 0.94 when centered right over the stone facing RIGHT!
                if (!touchdownFired && baseLeg.isLandingFlare && legT >= 0.94f && onTouchdownExact != null) {
                    touchdownFired = true
                    onTouchdownExact.invoke()
                }

                val alphaFade = when {
                    baseLeg.isLandingFlare && legT > 0.92f -> ((1f - legT) / 0.08f).coerceIn(0f, 1f)
                    mode == FlightMode.DEPARTURE && rawU > 0.88f -> ((1f - rawU) / 0.12f).coerceIn(0f, 1f)
                    else -> 1f
                }

                // On final landing flare, lock facingRight = true and level pitch to 0 deg as talons settle!
                val forceRight = baseLeg.isLandingFlare || (legIdx == 0 && mode != FlightMode.ARRIVAL && legT < 0.35f)
                val flareFlatten = if (baseLeg.isLandingFlare && legT > 0.65f) (1f - (legT - 0.65f) / 0.35f) else 1f

                stageView.updateBirdPose(bx, by, dx, dy * flareFlatten, frameIdx, scaleDepth, alphaFade, forceRight)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!touchdownFired && onTouchdownExact != null) {
                        touchdownFired = true
                        onTouchdownExact.invoke()
                    }
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

    private class RavenFlightStageView(ctx: Context, private val frames: Array<Bitmap?>) : View(ctx) {
        private val birdPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val baseSizePx = dp(ctx, 114f)
        private val dstRect = RectF()
        private var curX = -500f; private var curY = -500f
        private var facingRight = true; private var pitchDeg = 0f
        private var curFrame = 1; private var curScale = 1f; private var curAlpha = 1f

        fun updateBirdPose(x: Float, y: Float, vx: Float, vy: Float, frameIdx: Int, scale: Float, alpha: Float, forceRight: Boolean) {
            curX = x; curY = y
            facingRight = if (forceRight) true else if (abs(vx) > 0.5f) (vx >= 0f) else facingRight
            val absVx = abs(vx).coerceAtLeast(1f)
            pitchDeg = Math.toDegrees(atan2(vy.toDouble(), absVx.toDouble())).toFloat().coerceIn(-28f, 28f)
            curFrame = frameIdx.coerceIn(0, 2)
            curScale = scale
            curAlpha = alpha.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val bmp = frames.getOrNull(curFrame) ?: frames.firstOrNull { it != null } ?: return
            if (curAlpha <= 0.01f) return
            val half = (baseSizePx * curScale) * 0.5f
            canvas.save()
            canvas.translate(curX, curY)
            canvas.scale(if (facingRight) 1f else -1f, 1f)
            canvas.rotate(pitchDeg)
            birdPaint.alpha = (curAlpha * 255f).toInt().coerceIn(0, 255)
            dstRect.set(-half, -half, half, half)
            canvas.drawBitmap(bmp, null, dstRect, birdPaint)
            canvas.restore()
        }
    }

    /**
     * Crisp De-Hazed Electric Cyan-White Hammer Lightning Drawable (Swap + Periodic Idle Mjolnir Crackle).
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
            if (p < 0f || p > 1f) return
            val b = bounds
            if (b.isEmpty) return
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
     * WO-161 Precision Metallic Gold Drip & Flowing Ring-Matched Bottom Gold Stream:
     * - Zero detached blob: droplets neck directly off the exact 6:00 outer gold frame rim!
     * - Exact sampled champagne-gold palette (#C89D46 / #E8CA78 / #FFF6C8) + actual `raven_gold_ring_strip`
     *   flowing outward left & right along the visible bottom edge above the navigation bar!
     */
    private class MoltenGoldScreenStreamView(ctx: Context) : View(ctx) {
        private data class GoldDrop(
            var x: Float = 0f, var y: Float = 0f, var vy: Float = 0f,
            var r: Float = 0f, var neckLen: Float = 0f, var attached: Boolean = true,
            var reachesBottom: Boolean = true, var maxY: Float = 0f, var active: Boolean = false
        )
        private data class SplashRipple(var x: Float = 0f, var age: Float = 0f, var amp: Float = 0f, var active: Boolean = false)
        private data class GoldMote(
            var x: Float = 0f, var y: Float = 0f, var vx: Float = 0f, var vy: Float = 0f,
            var r: Float = 0f, var life: Float = 0f, var maxLife: Float = 0.6f, var active: Boolean = false
        )

        private val drops = Array(18) { GoldDrop() }
        private val ripples = Array(8) { SplashRipple() }
        private val motes = Array(36) { GoldMote() }

        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stripMeshPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val dropPath = Path()

        // Mesh for flowing raven_gold_ring_strip.png across the bottom edge (visibly moving stardust & glimmers!)
        private val botCols = 48
        private val botRows = 3
        private val botVertsR = FloatArray((botCols + 1) * (botRows + 1) * 2)
        private val botVertsL = FloatArray((botCols + 1) * (botRows + 1) * 2)
        private val botStripBuf: Bitmap? = ringStripBmp?.let { Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888) }
        private val botStripCanvas: Canvas? = botStripBuf?.let { Canvas(it) }

        private var spawnAccum = 0f
        private var poolFill = 0f
        private var scrollPhase = 0f
        private var rimX = dp(ctx, 74f)
        private var rimY = dp(ctx, 165f)
        private var isFlowing = false

        private fun getNavBarSafeBottomY(): Float {
            val h = height.toFloat().coerceAtLeast(resources.displayMetrics.heightPixels.toFloat())
            // Keep visible stream right above Samsung navigation bar (~48dp) if nav bar is present, or right at bottom edge!
            val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            val navH = if (resId > 0) resources.getDimensionPixelSize(resId).toFloat() else dp(context, 42f)
            return h - navH.coerceIn(0f, dp(context, 56f))
        }

        fun stepPhysics(dt: Float, exactRimX: Float, exactRimY: Float, flowActive: Boolean): Boolean {
            rimX = exactRimX
            rimY = exactRimY
            isFlowing = flowActive
            val sw = width.toFloat().coerceAtLeast(resources.displayMetrics.widthPixels.toFloat())
            val floorY = getNavBarSafeBottomY()
            val gravity = dp(context, 490f)

            if (flowActive || poolFill > 0.02f) {
                scrollPhase = (scrollPhase + dt * 0.24f) % 1f
                refreshBottomStripScroll()
            }

            if (flowActive) {
                spawnAccum += dt
                // Proportional cadence: sleek 2.5dp-3.8dp metallic droplet every ~0.58s right off the 6:00 frame rim
                if (spawnAccum >= 0.58f) {
                    spawnAccum = 0f
                    drops.firstOrNull { !it.active }?.apply {
                        val toBottom = Random.nextFloat() < 0.62f
                        active = true
                        attached = true
                        neckLen = 0f
                        x = rimX + (Random.nextFloat() - 0.5f) * dp(context, 2.2f)
                        y = rimY
                        vy = dp(context, 12f)
                        r = dp(context, if (toBottom) (Random.nextFloat() * 1.1f + 2.8f) else (Random.nextFloat() * 0.9f + 2.1f))
                        reachesBottom = toBottom
                        maxY = if (toBottom) floorY else (rimY + (floorY - rimY) * (0.28f + Random.nextFloat() * 0.44f))
                    }
                }
            } else {
                poolFill = (poolFill - dt * 0.16f).coerceAtLeast(0f)
            }

            var anyAlive = flowActive || poolFill > 0.01f
            val detachDist = dp(context, 11f)

            for (d in drops) {
                if (!d.active) continue
                anyAlive = true
                if (d.attached) {
                    // Bead swells right on the gold frame rim and stretches a thin metallic neck before snapping free!
                    d.neckLen += dp(context, 26f) * dt
                    d.x = rimX
                    d.y = rimY + d.neckLen
                    if (d.neckLen >= detachDist) {
                        d.attached = false
                        d.vy = dp(context, 38f)
                    }
                } else {
                    d.vy += gravity * dt
                    d.y += d.vy * dt
                }

                if (d.y >= d.maxY) {
                    d.active = false
                    if (d.reachesBottom) {
                        poolFill = (poolFill + 0.14f).coerceAtMost(1.0f)
                        ripples.firstOrNull { !it.active }?.apply {
                            active = true; x = d.x; age = 0f; amp = dp(context, 5.5f)
                        }
                        repeat(5) {
                            motes.firstOrNull { !it.active }?.apply {
                                active = true; x = d.x; y = floorY - dp(context, 2f)
                                vx = (Random.nextFloat() - 0.5f) * dp(context, 88f)
                                vy = -dp(context, Random.nextFloat() * 75f + 32f)
                                r = dp(context, Random.nextFloat() * 1.1f + 1.1f)
                                life = 0f; maxLife = Random.nextFloat() * 0.35f + 0.30f
                            }
                        }
                    } else {
                        // Mid-air dissolve into fine champagne stardust specks!
                        repeat(3) {
                            motes.firstOrNull { !it.active }?.apply {
                                active = true; x = d.x; y = d.y
                                vx = (Random.nextFloat() - 0.5f) * dp(context, 34f)
                                vy = dp(context, Random.nextFloat() * 24f - 8f)
                                r = dp(context, 1.2f); life = 0f; maxLife = 0.34f
                            }
                        }
                    }
                }
            }

            for (rp in ripples) {
                if (!rp.active) continue
                anyAlive = true
                rp.age += dt
                if (rp.age >= 1.25f) rp.active = false
            }

            for (m in motes) {
                if (!m.active) continue
                anyAlive = true
                m.life += dt
                if (m.life >= m.maxLife) { m.active = false; continue }
                m.x += m.vx * dt
                m.y += m.vy * dt
                m.vy += gravity * 0.22f * dt
            }

            if (poolFill > 0.01f) buildFlowingBottomMesh(sw, floorY)
            invalidate()
            return anyAlive
        }

        private fun refreshBottomStripScroll() {
            val src = ringStripBmp ?: return
            val c = botStripCanvas ?: return
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val w = src.width.toFloat()
            val shift = ((scrollPhase % 1f + 1f) % 1f) * w
            c.drawBitmap(src, shift, 0f, null)
            c.drawBitmap(src, shift - w, 0f, null)
        }

        private fun buildFlowingBottomMesh(sw: Float, floorY: Float) {
            val splitX = rimX.coerceIn(dp(context, 30f), sw - dp(context, 30f))
            val baseThick = dp(context, 14f) * (0.35f + 0.65f * poolFill)

            for (i in 0..botCols) {
                val u = i.toFloat() / botCols.toFloat()
                val rx = splitX + u * (sw - splitX)
                val lx = splitX - u * splitX

                // Outward-travelling wave + dynamic impact splash ripples!
                var waveR = sin((u * 9.0 - scrollPhase * Math.PI * 6.0)).toFloat() * dp(context, 1.8f) * poolFill
                var waveL = sin((u * 9.0 - scrollPhase * Math.PI * 6.0)).toFloat() * dp(context, 1.8f) * poolFill
                for (rp in ripples) {
                    if (!rp.active) continue
                    val travel = rp.age * dp(context, 240f)
                    val decay = (1f - rp.age / 1.25f).coerceIn(0f, 1f)
                    val dR = abs(abs(rx - rp.x) - travel)
                    if (dR < dp(context, 28f)) waveR += cos((dR / dp(context, 28f)) * Math.PI * 0.5).toFloat() * rp.amp * decay
                    val dL = abs(abs(lx - rp.x) - travel)
                    if (dL < dp(context, 28f)) waveL += cos((dL / dp(context, 28f)) * Math.PI * 0.5).toFloat() * rp.amp * decay
                }

                val mound = (1f - u * 0.55f) * baseThick
                for (j in 0..botRows) {
                    val vFrac = j.toFloat() / botRows.toFloat()
                    val idx = (j * (botCols + 1) + i) * 2
                    val topYR = floorY - (mound + waveR)
                    val topYL = floorY - (mound + waveL)
                    botVertsR[idx] = rx
                    botVertsR[idx + 1] = topYR + (floorY + dp(context, 4f) - topYR) * vFrac
                    botVertsL[idx] = lx
                    botVertsL[idx + 1] = topYL + (floorY + dp(context, 4f) - topYL) * vFrac
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            // 1. Sleek Frame-Matched Champagne-Gold Droplets (ZERO detached blob!)
            for (d in drops) {
                if (!d.active) continue
                val fade = if (!d.reachesBottom && d.y > d.maxY - dp(context, 55f)) ((d.maxY - d.y) / dp(context, 55f)).coerceIn(0f, 1f) else 1f
                val aInt = (fade * 255f).toInt().coerceIn(0, 255)

                dropPath.reset()
                if (d.attached) {
                    // Thin liquid-metal bridge emerging straight out of the 6:00 gold frame rim!
                    val neckHalf = (d.r * (1f - 0.55f * (d.neckLen / dp(context, 11f)))).coerceAtLeast(dp(context, 0.9f))
                    dropPath.moveTo(rimX - neckHalf, rimY - dp(context, 1.5f))
                    dropPath.quadTo(d.x - d.r * 0.5f, (rimY + d.y) * 0.5f, d.x - d.r, d.y)
                    dropPath.arcTo(d.x - d.r, d.y - d.r * 0.6f, d.x + d.r, d.y + d.r * 1.1f, 180f, -180f, false)
                    dropPath.quadTo(d.x + d.r * 0.5f, (rimY + d.y) * 0.5f, rimX + neckHalf, rimY - dp(context, 1.5f))
                    dropPath.close()
                } else {
                    val stretch = (1.5f + d.vy / dp(context, 480f)).coerceAtMost(2.7f)
                    val tailY = d.y - d.r * stretch
                    dropPath.moveTo(d.x, tailY)
                    dropPath.quadTo(d.x + d.r * 1.15f, d.y - d.r * 0.1f, d.x, d.y + d.r)
                    dropPath.quadTo(d.x - d.r * 1.15f, d.y - d.r * 0.1f, d.x, tailY)
                    dropPath.close()
                }

                // Metallic 3-stop champagne-gold shader matching raven_gold_ring_strip.png!
                bodyPaint.shader = LinearGradient(
                    d.x - d.r, d.y, d.x + d.r, d.y,
                    intArrayOf( withAlpha(GOLD_DARK, aInt), withAlpha(GOLD_HI, aInt), withAlpha(GOLD_MID, aInt) ),
                    floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawPath(dropPath, bodyPaint)

                // Crisp specular white-gold glint inside droplet
                glintPaint.color = withAlpha(GOLD_SPEC, (aInt * 0.92f).toInt())
                canvas.drawCircle(d.x - d.r * 0.24f, d.y + d.r * 0.12f, d.r * 0.34f, glintPaint)
            }

            // 2. Champagne Stardust Motes
            for (m in motes) {
                if (!m.active) continue
                val sa = ((1f - m.life / m.maxLife) * 255f).toInt().coerceIn(0, 255)
                glintPaint.color = withAlpha(GOLD_HI, sa)
                canvas.drawCircle(m.x, m.y, m.r, glintPaint)
            }

            // 3. Flowing Ring-Matched Champagne-Gold Stream at Bottom of Screen!
            val strip = botStripBuf ?: ringStripBmp
            if (poolFill > 0.01f && strip != null) {
                stripMeshPaint.alpha = (poolFill.coerceIn(0.25f, 1f) * 245f).toInt()
                canvas.drawBitmapMesh(strip, botCols, botRows, botVertsR, 0, null, 0, stripMeshPaint)
                canvas.drawBitmapMesh(strip, botCols, botRows, botVertsL, 0, null, 0, stripMeshPaint)
            }
        }

        private fun withAlpha(rgb: Int, a: Int): Int = (rgb and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
    }

    /**
     * Unified Living Pedestal Crest View (Zero-Gap 12:00 Split Ring, Living Mjolnir & Living Raven).
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

        private val baseImageView: ImageView
        private val poseOverlayView: ImageView
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
        private val ringPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { alpha = 242 }

        private var lastFrameMs = SystemClock.elapsedRealtime()
        private var flowPhase = 0f
        private var isStandbyMode = false
        private var isSessionOpen = false
        private var isSpeakingNow = false
        private var hasUnreadRavenFlag = false
        private var showingRavenOnStone = false

        private var nextPerchLifeAtMs = SystemClock.elapsedRealtime() + 2200L
        private var perchPoseRevertAtMs = 0L
        private var nextMjolnirIdleArcAtMs = SystemClock.elapsedRealtime() + 4200L
        private val bootShowcaseUntilMs = SystemClock.elapsedRealtime() + 28_000L

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

            baseImageView = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(mjolnirBaseBmp ?: ravenBaseBmp)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }
            roundedCard.addView(baseImageView)

            poseOverlayView = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                alpha = 0f
                visibility = View.GONE
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }
            roundedCard.addView(poseOverlayView)

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

        /**
         * Atomic Occupant Setter: hides `poseOverlayView` immediately when switching to Mjolnir so a ghost Raven can NEVER remain visible!
         */
        fun setOccupantImmediate(showRaven: Boolean) {
            showingRavenOnStone = showRaven
            perchPoseRevertAtMs = 0L
            poseOverlayView.animate().cancel()
            poseOverlayView.alpha = 0f
            poseOverlayView.visibility = if (showRaven) View.VISIBLE else View.GONE
            baseImageView.scaleX = 1f
            baseImageView.scaleY = 1f
            baseImageView.setImageBitmap(if (showRaven) ravenBaseBmp else (mjolnirBaseBmp ?: ravenBaseBmp))
            if (showRaven) {
                nextPerchLifeAtMs = SystemClock.elapsedRealtime() + Random.nextLong(1600L, 3000L)
            } else {
                nextMjolnirIdleArcAtMs = SystemClock.elapsedRealtime() + Random.nextLong(3800L, 7200L)
            }
            invalidate()
        }

        fun triggerElectricStrikeBurst(durationMs: Long = 420L, intensity: Float = 1.0f) {
            strikeAnimator?.cancel()
            skyBoltsDrawable.forceStrikeNow()
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
            baseImageView.alpha = if (standby && !unreadRaven) 0.78f else 1.0f
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
                updateDualCascadingMeshes()
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

        private fun updateDualCascadingMeshes() {
            if (!meshReady) return
            val innerTuckPx = -dp(context, 0.5f)
            val outerSpanPx = padSide.toFloat() - innerTuckPx
            for (i in 0..flankSegs) {
                for (j in 0..radSteps) {
                    val rFrac = j.toFloat() / radSteps.toFloat()
                    val dist = innerTuckPx + outerSpanPx * rFrac
                    val vIdx = (j * (flankSegs + 1) + i) * 2
                    rightMeshVerts[vIdx] = rightBx[i] + rightNx[i] * dist
                    rightMeshVerts[vIdx + 1] = rightBy[i] + rightNy[i] * dist
                    leftMeshVerts[vIdx] = leftBx[i] + leftNx[i] * dist
                    leftMeshVerts[vIdx + 1] = leftBy[i] + leftNy[i] * dist
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
                        ringPaint.alpha = ((0.80 + 0.20 * sin(pulseRad)) * 255.0).toInt().coerceIn(155, 255)
                    } else {
                        ringPaint.alpha = if (isStandbyMode) 195 else 228
                    }

                    // Exact relative screen coordinates -> 0.0px gap between 6:00 gold frame rim & falling droplets!
                    if (isAttachedToWindow) {
                        val mv = if (flowActive || moltenStreamOverlay != null) ensureMoltenStreamOverlay(context) else null
                        if (mv != null) {
                            val cLoc = IntArray(2); getLocationOnScreen(cLoc)
                            val mLoc = IntArray(2); if (mv.isAttachedToWindow) mv.getLocationOnScreen(mLoc)
                            val exactRimX = (cLoc[0] - mLoc[0]) + padSide + cardW * 0.50f
                            // Gold ring strip's bright outer ridge sits at +padSide * 0.52f below cardH!
                            val exactRimY = (cLoc[1] - mLoc[1]) + padTop + cardH + padSide * 0.52f
                            val keep = mv.stepPhysics(dt, exactRimX, exactRimY, flowActive)
                            if (!keep && !flowActive) detachMoltenStreamOverlay()
                        }
                    }

                    runesDrawable?.shimmerPhase = (elapsed % 3800L).toFloat() / 3800f
                    if (!isStandbyMode || hasUnreadRavenFlag) {
                        skyBoltsDrawable.tick(now)
                    }

                    // Alive micro-animations strictly guarded by occupant state!
                    if (showingRavenOnStone && !flightInProgress) {
                        if (perchPoseRevertAtMs > 0L && now >= perchPoseRevertAtMs) {
                            perchPoseRevertAtMs = 0L
                            poseOverlayView.animate().alpha(0f).setDuration(170L).start()
                        } else if (now >= nextPerchLifeAtMs) {
                            if (Random.nextFloat() < 0.52f) {
                                poseOverlayView.setImageBitmap(ravenBlinkBmp)
                                poseOverlayView.animate().alpha(1f).setDuration(65L).start()
                                perchPoseRevertAtMs = now + 175L
                                nextPerchLifeAtMs = now + Random.nextLong(1800L, 3600L)
                            } else {
                                poseOverlayView.setImageBitmap(ravenTurnBmp)
                                poseOverlayView.animate().alpha(1f).setDuration(210L).start()
                                perchPoseRevertAtMs = now + Random.nextLong(950L, 1750L)
                                nextPerchLifeAtMs = perchPoseRevertAtMs + Random.nextLong(1800L, 3800L)
                            }
                        }
                    } else if (!showingRavenOnStone && !isStandbyMode && now >= nextMjolnirIdleArcAtMs) {
                        // Living Mjolnir: subtle crisp electric crackle across hammer head every 4.5-8.5s!
                        nextMjolnirIdleArcAtMs = now + Random.nextLong(4600L, 8400L)
                        triggerElectricStrikeBurst(durationMs = 290L, intensity = 0.72f)
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

    private class ShimmeringRuneOverlayDrawable(private val runesBitmap: Bitmap) : Drawable() {
        private val normRect = RectF(0.32031f, 0.61129f, 0.62813f, 0.89530f)
        private val basePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { alpha = 248 }
        private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { alpha = 255 }
        private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
        private val dstRect = RectF()
        var shimmerPhase: Float = 0f

        override fun draw(canvas: Canvas) {
            val b = bounds; if (b.isEmpty) return
            val bw = b.width().toFloat(); val bh = b.height().toFloat()
            dstRect.set(b.left + normRect.left * bw, b.top + normRect.top * bh, b.left + normRect.right * bw, b.top + normRect.bottom * bh)
            canvas.drawBitmap(runesBitmap, null, dstRect, basePaint)
            val rw = dstRect.width(); val rh = dstRect.height()
            val lid = canvas.saveLayer(dstRect, null)
            canvas.drawBitmap(runesBitmap, null, dstRect, maskPaint)
            val p1 = (shimmerPhase % 1f + 1f) % 1f
            val sx = dstRect.left - rw * 0.65f + (rw * 2.30f) * p1
            val sy = dstRect.top - rh * 0.25f + (rh * 0.70f) * p1
            sweepPaint.shader = LinearGradient(
                sx, sy, sx + rw * 0.44f, sy + rh * 0.30f,
                intArrayOf(0x00B6862C, 0x66F1C833, 0xEEFFF791.toInt(), 0xFFFFFBEB.toInt(), 0xEEFFF791.toInt(), 0x66F1C833, 0x00B6862C),
                floatArrayOf(0f, 0.24f, 0.44f, 0.50f, 0.56f, 0.76f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(dstRect, sweepPaint)
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

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
import kotlin.math.sin
import kotlin.random.Random

/**
 * WO-153: Unified Living Norse Pedestal Director
 * - Replaces the legacy circular Mjolnir orb with the Ultra-HD Elder Futhark stone pedestal.
 * - Mjolnir rests on the pedestal by default (tap to activate/interrupt mic, double-tap for Standby/hang-up).
 * - 12:00 Split Dual-Arc Ring scrolls the actual golden stardust texture symmetrically down both left & right
 *   flanks at 31.4 dp/s, pooling into a glowing 24k meniscus at 6:00 bottom-center and dripping molten gold droplets.
 * - When a Raven arrives, Huginn swoops across the screen on a 60fps cubic Bezier path with wing-flapping,
 *   dissolving Mjolnir in a crack of golden lightning to perch on the stone.
 * - While unread, the perched Raven turns its head, blinks, shifts its talons, and takes random patrol
 *   flights across/around the screen (reforming Mjolnir while airborne) until Boss opens & acknowledges it.
 */
object LivingPedestalDirector {
    private const val TAG = "LivingPedestalWO153"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var hasUnreadRaven: Boolean = false
        private set
    @Volatile var ravenCurrentlyPerched: Boolean = false
        private set
    @Volatile var ravenCardUnfoldedNow: Boolean = false
        private set
    @Volatile var flightInProgress: Boolean = false
        private set

    private var activeRavenEventKey: String = ""
    @Volatile private var attachedCrestView: LivingPedestalCrestView? = null
    private var flightWindowManager: WindowManager? = null
    private var activeFlightView: RavenFlightStageView? = null
    private var flightAnimator: ValueAnimator? = null
    private var patrolRunnable: Runnable? = null

    fun isPedestalAttached(): Boolean = attachedCrestView != null

    // Shared Ultra-HD Bitmaps (loaded once with inScaled = false)
    private var mjolnirBaseBmp: Bitmap? = null
    private var mjolnirSwapCoreBmp: Bitmap? = null
    private var mjolnirSwapHaloBmp: Bitmap? = null
    private var moltenStreamOverlay: MoltenGoldScreenStreamView? = null
    private var ravenBaseBmp: Bitmap? = null
    private var ravenTurnBmp: Bitmap? = null
    private var ravenBlinkBmp: Bitmap? = null
    private var ringStripBmp: Bitmap? = null
    private var runesGoldBmp: Bitmap? = null
    private val boltCores = arrayOfNulls<Bitmap>(4)
    private val boltHalos = arrayOfNulls<Bitmap>(4)
    private val flyFrames = arrayOfNulls<Bitmap>(3) // 0=Upstroke, 1=Glide, 2=Downstroke

    fun dp(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    fun dpInt(ctx: Context, v: Int): Int = dp(ctx, v.toFloat()).toInt()

    private fun decodeRaw(ctx: Context, resId: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeResource(ctx.resources, resId, opts)?.apply {
                density = Bitmap.DENSITY_NONE
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bitmap decode note ($resId): ${e.message}")
            null
        }
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
            attachedCrestView?.updateOperationalState(
                standby = isStandby,
                sessionOpen = sessionActive,
                speaking = speakingActive,
                unreadRaven = hasUnreadRaven
            )
        }
    }

    fun triggerLightningPulse() {
        mainHandler.post {
            attachedCrestView?.triggerGoldenSwapBurst(durationMs = 420L)
        }
    }

    fun onRavenDispatched(ctx: Context, eventKey: String, title: String, cleanBase: String) {
        val app = ctx.applicationContext
        ensureAssetsLoaded(app)
        mainHandler.post {
            activeRavenEventKey = eventKey
            hasUnreadRaven = true
            ravenCardUnfoldedNow = false
            val crest = attachedCrestView ?: return@post
            crest.refreshDripActivity(unreadRaven = true)
            cancelPatrolSchedule()

            if (ravenCurrentlyPerched || flightInProgress) {
                crest.triggerGoldenSwapBurst(durationMs = 460L)
                if (!flightInProgress) scheduleNextPatrolFlight(app)
                return@post
            }

            launchBezierFlight(
                app = app,
                mode = FlightMode.ARRIVAL,
                onTakeoffSwap = null,
                onLandingTouchdownSwap = {
                    ravenCurrentlyPerched = true
                    crest.crossSwapOccupantWithGoldenLightning(toRaven = true)
                },
                onComplete = {
                    if (hasUnreadRaven && !ravenCardUnfoldedNow) {
                        scheduleNextPatrolFlight(app)
                    }
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
            attachedCrestView?.triggerGoldenSwapBurst(durationMs = 380L)
            attachedCrestView?.refreshDripActivity(unreadRaven = true)
            scheduleNextPatrolFlight(app)
        }
    }

    fun dismissUnreadRavenWithTakeoff(ctx: Context) {
        val app = ctx.applicationContext
        ensureAssetsLoaded(app)
        mainHandler.post {
            val wasShowingRaven = hasUnreadRaven || ravenCurrentlyPerched
            hasUnreadRaven = false
            ravenCardUnfoldedNow = false
            activeRavenEventKey = ""
            cancelPatrolSchedule()
            stopActiveFlightOverlay()

            val crest = attachedCrestView
            if (crest == null) {
                ravenCurrentlyPerched = false
                return@post
            }
            crest.refreshDripActivity(unreadRaven = false)
            if (!wasShowingRaven) {
                crest.setOccupantImmediate(showRaven = false)
                return@post
            }

            ravenCurrentlyPerched = false
            crest.crossSwapOccupantWithGoldenLightning(toRaven = false)
            launchBezierFlight(
                app = app,
                mode = FlightMode.DEPARTURE,
                onTakeoffSwap = null,
                onLandingTouchdownSwap = null,
                onComplete = {
                    crest.refreshDripActivity(unreadRaven = false)
                }
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
        val delayMs = Random.nextLong(12_500L, 21_500L)
        val r = Runnable {
            if (!hasUnreadRaven || ravenCardUnfoldedNow || flightInProgress || attachedCrestView == null) return@Runnable
            launchBezierFlight(
                app = app,
                mode = FlightMode.PATROL,
                onTakeoffSwap = {
                    ravenCurrentlyPerched = false
                    attachedCrestView?.crossSwapOccupantWithGoldenLightning(toRaven = false)
                },
                onLandingTouchdownSwap = {
                    if (hasUnreadRaven) {
                        ravenCurrentlyPerched = true
                        attachedCrestView?.crossSwapOccupantWithGoldenLightning(toRaven = true)
                    }
                },
                onComplete = {
                    if (hasUnreadRaven && !ravenCardUnfoldedNow) {
                        scheduleNextPatrolFlight(app)
                    }
                }
            )
        }
        patrolRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private enum class FlightMode { ARRIVAL, PATROL, DEPARTURE }

    private data class BezierLeg(
        val p0: PointF,
        val p1: PointF,
        val p2: PointF,
        val p3: PointF
    )

    private fun getPedestalPerchScreenPoint(app: Context): PointF {
        val crest = attachedCrestView
        if (crest != null && crest.isAttachedToWindow) {
            val loc = IntArray(2)
            crest.getLocationOnScreen(loc)
            val cx = loc[0] + crest.width * 0.50f
            val cy = loc[1] + crest.height * 0.36f
            return PointF(cx, cy)
        }
        return PointF(dp(app, 78f), dp(app, 92f))
    }

    private fun buildFlightLegs(app: Context, mode: FlightMode, perch: PointF): Pair<List<BezierLeg>, Long> {
        val dm = app.resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()

        return when (mode) {
            FlightMode.ARRIVAL -> {
                val fromRight = Random.nextBoolean()
                val startPt = if (fromRight) {
                    PointF(sw + dp(app, 95f), Random.nextFloat() * (sh * 0.28f) + dp(app, 40f))
                } else {
                    PointF(sw * Random.nextFloat().coerceIn(0.35f, 0.85f), -dp(app, 95f))
                }
                val midCtrl1 = PointF(sw * 0.78f, sh * 0.42f)
                val midCtrl2 = PointF(perch.x + dp(app, 145f), max(dp(app, 45f), perch.y - dp(app, 95f)))
                Pair(listOf(BezierLeg(startPt, midCtrl1, midCtrl2, perch)), 1650L)
            }
            FlightMode.DEPARTURE -> {
                val exitPt = PointF(sw + dp(app, 120f), dp(app, 55f))
                val c1 = PointF(perch.x + dp(app, 110f), max(dp(app, 20f), perch.y - dp(app, 115f)))
                val c2 = PointF(sw * 0.68f, sh * 0.26f)
                Pair(listOf(BezierLeg(perch, c1, c2, exitPt)), 1320L)
            }
            FlightMode.PATROL -> {
                when (Random.nextInt(4)) {
                    0 -> {
                        val offRight = PointF(sw + dp(app, 95f), (sh * 0.34f))
                        val leg1 = BezierLeg(
                            perch,
                            PointF(perch.x + dp(app, 130f), max(dp(app, 30f), perch.y - dp(app, 80f))),
                            PointF(sw * 0.72f, sh * 0.16f),
                            offRight
                        )
                        val leg2 = BezierLeg(
                            PointF(sw + dp(app, 95f), sh * 0.54f),
                            PointF(sw * 0.62f, sh * 0.68f),
                            PointF(perch.x + dp(app, 120f), perch.y + dp(app, 65f)),
                            perch
                        )
                        Pair(listOf(leg1, leg2), 3200L)
                    }
                    1 -> {
                        val wp = PointF(sw * 0.82f, sh * 0.56f)
                        val leg1 = BezierLeg(
                            perch,
                            PointF(sw * 0.55f, max(dp(app, 40f), perch.y - dp(app, 60f))),
                            PointF(sw * 0.92f, sh * 0.28f),
                            wp
                        )
                        val leg2 = BezierLeg(
                            wp,
                            PointF(sw * 0.66f, sh * 0.78f),
                            PointF(sw * 0.14f, sh * 0.50f),
                            perch
                        )
                        Pair(listOf(leg1, leg2), 3100L)
                    }
                    2 -> {
                        val exitR = PointF(sw + dp(app, 105f), sh * 0.28f)
                        val enterL = PointF(-dp(app, 105f), sh * 0.44f)
                        val leg1 = BezierLeg(
                            perch,
                            PointF(sw * 0.42f, perch.y + dp(app, 90f)),
                            PointF(sw * 0.78f, sh * 0.42f),
                            exitR
                        )
                        val leg2 = BezierLeg(
                            enterL,
                            PointF(sw * 0.25f, sh * 0.18f),
                            PointF(perch.x + dp(app, 60f), max(dp(app, 25f), perch.y - dp(app, 85f))),
                            perch
                        )
                        Pair(listOf(leg1, leg2), 3300L)
                    }
                    else -> {
                        val midLow = PointF(sw * 0.74f, sh * 0.64f)
                        val leg1 = BezierLeg(
                            perch,
                            PointF(perch.x + dp(app, 40f), sh * 0.48f),
                            PointF(sw * 0.48f, sh * 0.74f),
                            midLow
                        )
                        val leg2 = BezierLeg(
                            midLow,
                            PointF(sw * 0.94f, sh * 0.44f),
                            PointF(sw * 0.52f, dp(app, 36f)),
                            perch
                        )
                        Pair(listOf(leg1, leg2), 3050L)
                    }
                }
            }
        }
    }

    private fun launchBezierFlight(
        app: Context,
        mode: FlightMode,
        onTakeoffSwap: (() -> Unit)?,
        onLandingTouchdownSwap: (() -> Unit)?,
        onComplete: (() -> Unit)?
    ) {
        if (!Settings.canDrawOverlays(app)) {
            onTakeoffSwap?.invoke()
            onLandingTouchdownSwap?.invoke()
            onComplete?.invoke()
            return
        }
        stopActiveFlightOverlay()

        val wm = flightWindowManager ?: (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
            flightWindowManager = it
        }
        val initialPerch = getPedestalPerchScreenPoint(app)
        val (legs, totalDurMs) = buildFlightLegs(app, mode, initialPerch)
        val stageView = RavenFlightStageView(app, flyFrames)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            wm.addView(stageView, lp)
            activeFlightView = stageView
            flightInProgress = true
        } catch (e: Exception) {
            Log.w(TAG, "Flight overlay addView note: ${e.message}")
            onLandingTouchdownSwap?.invoke()
            onComplete?.invoke()
            return
        }

        onTakeoffSwap?.invoke()
        var touchdownFired = false
        val startElapsed = SystemClock.elapsedRealtime()

        flightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalDurMs
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val uAll = (anim.animatedValue as Float).coerceIn(0f, 1f)
                val livePerch = getPedestalPerchScreenPoint(app)

                val legIdx = min(legs.size - 1, (uAll * legs.size).toInt())
                val legT = ((uAll * legs.size) - legIdx).coerceIn(0f, 1f)
                val baseLeg = legs[legIdx]

                val p0 = if (legIdx == 0 && mode != FlightMode.ARRIVAL) livePerch else baseLeg.p0
                val p3 = if (legIdx == legs.size - 1 && mode != FlightMode.DEPARTURE) livePerch else baseLeg.p3
                val p1 = baseLeg.p1
                val p2 = baseLeg.p2

                val omt = 1f - legT
                val bx = omt * omt * omt * p0.x + 3f * omt * omt * legT * p1.x + 3f * omt * legT * legT * p2.x + legT * legT * legT * p3.x
                val by = omt * omt * omt * p0.y + 3f * omt * omt * legT * p1.y + 3f * omt * legT * legT * p2.y + legT * legT * legT * p3.y

                val dx = 3f * omt * omt * (p1.x - p0.x) + 6f * omt * legT * (p2.x - p1.x) + 3f * legT * legT * (p3.x - p2.x)
                val dy = 3f * omt * omt * (p1.y - p0.y) + 6f * omt * legT * (p2.y - p1.y) + 3f * legT * legT * (p3.y - p2.y)

                val nowMs = SystemClock.elapsedRealtime() - startElapsed
                val speedMag = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
                val isDivingGlide = (dy / speedMag > 0.24f) && (uAll in 0.24f..0.78f)
                val flapSeq = intArrayOf(0, 1, 2, 1)
                val frameIdx = if (isDivingGlide) 1 else flapSeq[((nowMs / 135L) % 4L).toInt()]

                val scaleDepth = when {
                    mode == FlightMode.ARRIVAL && uAll > 0.75f -> 1.08f - 0.22f * ((uAll - 0.75f) / 0.25f)
                    mode == FlightMode.DEPARTURE && uAll < 0.25f -> 0.86f + 0.24f * (uAll / 0.25f)
                    else -> 1.0f + 0.10f * sin(uAll * Math.PI).toFloat()
                }
                val alphaFade = when {
                    mode == FlightMode.ARRIVAL && uAll > 0.88f -> ((1f - uAll) / 0.12f).coerceIn(0f, 1f)
                    mode == FlightMode.DEPARTURE && uAll > 0.86f -> ((1f - uAll) / 0.14f).coerceIn(0f, 1f)
                    mode == FlightMode.PATROL && uAll > 0.90f -> ((1f - uAll) / 0.10f).coerceIn(0f, 1f)
                    else -> 1f
                }

                if (!touchdownFired && uAll >= 0.85f && onLandingTouchdownSwap != null) {
                    touchdownFired = true
                    onLandingTouchdownSwap.invoke()
                }

                stageView.updateBirdPose(
                    x = bx,
                    y = by,
                    vx = dx,
                    vy = dy,
                    frameIndex = frameIdx,
                    birdScale = scaleDepth,
                    birdAlpha = alphaFade
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!touchdownFired && onLandingTouchdownSwap != null) {
                        touchdownFired = true
                        onLandingTouchdownSwap.invoke()
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

    private class RavenFlightStageView(
        ctx: Context,
        private val frames: Array<Bitmap?>
    ) : View(ctx) {
        private val birdPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val baseSizePx = dp(ctx, 118f)
        private val dstRect = RectF()

        private var curX = -500f
        private var curY = -500f
        private var facingRight = true
        private var pitchDeg = 0f
        private var curFrame = 1
        private var curScale = 1f
        private var curAlpha = 1f

        fun updateBirdPose(
            x: Float,
            y: Float,
            vx: Float,
            vy: Float,
            frameIndex: Int,
            birdScale: Float,
            birdAlpha: Float
        ) {
            curX = x
            curY = y
            if (abs(vx) > 0.5f) {
                facingRight = vx >= 0f
            }
            val absVx = abs(vx).coerceAtLeast(1f)
            pitchDeg = Math.toDegrees(atan2(vy.toDouble(), absVx.toDouble())).toFloat().coerceIn(-36f, 36f)
            curFrame = frameIndex.coerceIn(0, 2)
            curScale = birdScale
            curAlpha = birdAlpha.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val bmp = frames.getOrNull(curFrame) ?: frames.firstOrNull { it != null } ?: return
            if (curAlpha <= 0.01f) return

            val half = (baseSizePx * curScale) * 0.5f
            canvas.save()
            canvas.translate(curX, curY)

            auraPaint.shader = RadialGradient(
                0f, 0f, half * 0.95f,
                intArrayOf(Color.argb((75 * curAlpha).toInt(), 251, 191, 36), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(0f, 0f, half * 0.95f, auraPaint)

            canvas.scale(if (facingRight) 1f else -1f, 1f)
            canvas.rotate(pitchDeg)

            birdPaint.alpha = (curAlpha * 255f).toInt().coerceIn(0, 255)
            dstRect.set(-half, -half, half, half)
            canvas.drawBitmap(bmp, null, dstRect, birdPaint)
            canvas.restore()
        }
    }

    private class GoldenPedestalSwapLightningDrawable : Drawable() {
        private val haloPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val corePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dstRect = RectF()
        var progress: Float = -1f
            set(value) { field = value; invalidateSelf() }

        override fun draw(canvas: Canvas) {
            val p = progress
            if (p < 0f || p > 1f) return
            val b = bounds
            if (b.isEmpty) return
            val core = mjolnirSwapCoreBmp ?: boltCores[0] ?: return
            val halo = mjolnirSwapHaloBmp ?: boltHalos[0] ?: core
            dstRect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            val h = dstRect.height()

            if (p < 0.34f) {
                val step = (((p / 0.34f) * 6f).toInt() + 1) / 6f
                val frontY = dstRect.top + h * step.coerceIn(0.18f, 1f)
                haloPaint.alpha = 235
                corePaint.alpha = 255
                canvas.save()
                canvas.clipRect(dstRect.left, dstRect.top, dstRect.right, frontY)
                canvas.drawBitmap(halo, null, dstRect, haloPaint)
                canvas.drawBitmap(core, null, dstRect, corePaint)
                canvas.restore()
            } else if (p <= 0.74f) {
                val crackle = if (p in 0.50f..0.55f) 0.58f else 1.0f
                haloPaint.alpha = (255 * crackle).toInt()
                corePaint.alpha = (255 * crackle).toInt()
                canvas.drawBitmap(halo, null, dstRect, haloPaint)
                canvas.drawBitmap(core, null, dstRect, corePaint)
                if (crackle > 0.9f) canvas.drawBitmap(core, null, dstRect, corePaint)
            } else {
                val fade = ((1f - p) / 0.26f).coerceIn(0f, 1f)
                haloPaint.alpha = (240 * fade).toInt()
                corePaint.alpha = (255 * fade).toInt()
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
     * WO-160: Click-Through Full-Screen 6:00 Molten Gold Cascade & Bottom-of-Phone Collection Pool.
     */
    private class MoltenGoldScreenStreamView(ctx: Context) : View(ctx) {
        private data class GoldGlob(
            var x: Float = 0f, var y: Float = 0f, var vx: Float = 0f, var vy: Float = 0f,
            var r: Float = 0f, var reachesBottom: Boolean = true, var maxY: Float = 0f,
            var detachY: Float = 0f, var active: Boolean = false
        )
        private data class GoldSpark(
            var x: Float = 0f, var y: Float = 0f, var vx: Float = 0f, var vy: Float = 0f,
            var r: Float = 0f, var life: Float = 0f, var maxLife: Float = 0.6f, var active: Boolean = false
        )
        private val globs = Array(26) { GoldGlob() }
        private val sparks = Array(42) { GoldSpark() }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val poolPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dropPath = Path()
        private val poolPath = Path()

        private var spawnTimer = 0f
        private var poolDepthPx = 0f
        private var wavePhase = 0f
        private var spoutX = dp(ctx, 74f)
        private var spoutY = dp(ctx, 195f)
        private var isFlowing = false

        fun stepPhysics(dt: Float, sixScreenX: Float, sixScreenY: Float, flowActive: Boolean): Boolean {
            spoutX = sixScreenX
            spoutY = sixScreenY
            isFlowing = flowActive
            wavePhase = (wavePhase + dt * 3.4f) % (Math.PI.toFloat() * 20f)
            val sh = height.toFloat().coerceAtLeast(resources.displayMetrics.heightPixels.toFloat())
            val sw = width.toFloat().coerceAtLeast(resources.displayMetrics.widthPixels.toFloat())
            val gravity = dp(context, 460f)
            val maxPoolPx = dp(context, 14f)

            if (flowActive) {
                spawnTimer += dt
                if (spawnTimer >= 0.34f) {
                    spawnTimer = 0f
                    val g = globs.firstOrNull { !it.active }
                    if (g != null) {
                        val heavy = Random.nextFloat() < 0.68f
                        g.active = true
                        g.x = spoutX + (Random.nextFloat() - 0.5f) * dp(context, 9f)
                        g.y = spoutY + dp(context, 2f)
                        g.detachY = spoutY + dp(context, if (heavy) 24f else 14f)
                        g.vx = (Random.nextFloat() - 0.5f) * dp(context, 10f)
                        g.vy = dp(context, if (heavy) 22f else 36f)
                        g.r = dp(context, if (heavy) (Random.nextFloat() * 2.8f + 5.4f) else (Random.nextFloat() * 1.8f + 3.0f))
                        g.reachesBottom = heavy
                        g.maxY = if (heavy) (sh - dp(context, 3f)) else (spoutY + (sh - spoutY) * (0.38f + Random.nextFloat() * 0.40f))
                    }
                }
            } else {
                poolDepthPx = (poolDepthPx - dp(context, 3.2f) * dt).coerceAtLeast(0f)
            }

            var anyAlive = poolDepthPx > 0.4f || flowActive
            for (g in globs) {
                if (!g.active) continue
                anyAlive = true
                if (g.y < g.detachY) {
                    g.y += dp(context, 34f) * dt
                    g.x = spoutX + (g.x - spoutX) * 0.94f
                } else {
                    g.vy += gravity * dt
                    g.y += g.vy * dt
                    g.x += g.vx * dt
                }
                if (g.y >= g.maxY) {
                    g.active = false
                    if (g.reachesBottom) {
                        poolDepthPx = (poolDepthPx + dp(context, 1.65f)).coerceAtMost(maxPoolPx)
                        repeat(4) {
                            sparks.firstOrNull { !it.active }?.apply {
                                active = true; x = g.x; y = sh - poolDepthPx * 0.5f
                                vx = (Random.nextFloat() - 0.5f) * dp(context, 95f)
                                vy = -dp(context, Random.nextFloat() * 90f + 45f)
                                r = dp(context, Random.nextFloat() * 1.6f + 1.4f)
                                life = 0f; maxLife = Random.nextFloat() * 0.35f + 0.35f
                            }
                        }
                    } else {
                        repeat(3) {
                            sparks.firstOrNull { !it.active }?.apply {
                                active = true; x = g.x; y = g.y
                                vx = (Random.nextFloat() - 0.5f) * dp(context, 45f)
                                vy = dp(context, Random.nextFloat() * 35f - 15f)
                                r = dp(context, 1.6f); life = 0f; maxLife = 0.38f
                            }
                        }
                    }
                }
            }

            if (poolDepthPx > dp(context, 2f) && Random.nextFloat() < 0.22f) {
                sparks.firstOrNull { !it.active }?.apply {
                    active = true; x = Random.nextFloat() * sw; y = sh - poolDepthPx
                    vx = (Random.nextFloat() - 0.5f) * dp(context, 14f)
                    vy = -dp(context, Random.nextFloat() * 32f + 14f)
                    r = dp(context, 1.5f); life = 0f; maxLife = 0.75f
                }
            }

            for (s in sparks) {
                if (!s.active) continue
                anyAlive = true
                s.life += dt
                if (s.life >= s.maxLife) { s.active = false; continue }
                s.x += s.vx * dt
                s.y += s.vy * dt
                s.vy += gravity * 0.25f * dt
            }
            invalidate()
            return anyAlive
        }

        override fun onDraw(canvas: Canvas) {
            val sw = width.toFloat()
            val sh = height.toFloat()
            if (sw <= 10f || sh <= 10f) return

            // 1. Proportional 6:00 Confluence Meniscus Spout right under the Pedestal
            if (isFlowing) {
                val mw = dp(context, 18f)
                val mh = dp(context, 7.5f)
                glowPaint.shader = RadialGradient(
                    spoutX, spoutY, mw * 1.35f,
                    intArrayOf(Color.argb(245, 255, 251, 235), Color.argb(210, 250, 204, 21), Color.argb(115, 217, 119, 6), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.42f, 0.76f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawOval(spoutX - mw, spoutY - mh * 0.7f, spoutX + mw, spoutY + mh * 1.15f, glowPaint)
            }

            // 2. Falling Viscous Molten Gold Teardrops
            for (g in globs) {
                if (!g.active) continue
                val fade = if (!g.reachesBottom && g.y > g.maxY - dp(context, 70f)) ((g.maxY - g.y) / dp(context, 70f)).coerceIn(0f, 1f) else 1f
                val aInt = (fade * 255f).toInt().coerceIn(0, 255)
                val stretch = (1.35f + (g.vy / dp(context, 520f))).coerceAtMost(2.85f)

                glowPaint.shader = RadialGradient(
                    g.x, g.y, g.r * 2.4f,
                    intArrayOf(Color.argb((aInt * 0.72f).toInt(), 245, 158, 11), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawCircle(g.x, g.y, g.r * 2.4f, glowPaint)

                dropPath.reset()
                val tailY = if (g.y < g.detachY) spoutY else (g.y - g.r * stretch)
                dropPath.moveTo(g.x, tailY)
                dropPath.quadTo(g.x + g.r * 1.22f, g.y - g.r * 0.15f, g.x, g.y + g.r)
                dropPath.quadTo(g.x - g.r * 1.22f, g.y - g.r * 0.15f, g.x, tailY)
                dropPath.close()

                corePaint.color = Color.argb(aInt, 250, 204, 21)
                canvas.drawPath(dropPath, corePaint)
                corePaint.color = Color.argb(aInt, 255, 251, 235)
                canvas.drawCircle(g.x - g.r * 0.18f, g.y + g.r * 0.18f, g.r * 0.44f, corePaint)
            }

            // 3. Splashing & Rising 24k Gold Sparks
            for (s in sparks) {
                if (!s.active) continue
                val sa = ((1f - s.life / s.maxLife) * 255f).toInt().coerceIn(0, 255)
                corePaint.color = Color.argb(sa, 254, 240, 138)
                canvas.drawCircle(s.x, s.y, s.r, corePaint)
            }

            // 4. Shimmering Molten Gold Collection Pool at the Very Bottom Edge of the Phone
            if (poolDepthPx > 0.4f) {
                val topY = sh - poolDepthPx
                poolPath.reset()
                poolPath.moveTo(0f, sh)
                val steps = 28
                for (k in 0..steps) {
                    val px = (k.toFloat() / steps.toFloat()) * sw
                    val distFromSpout = abs(px - spoutX) / (sw * 0.45f)
                    val mound = (1f - distFromSpout.coerceIn(0f, 1f)) * dp(context, 4.5f) * (poolDepthPx / dp(context, 14f))
                    val ripple = sin((k * 0.65f + wavePhase).toDouble()).toFloat() * dp(context, 1.3f)
                    poolPath.lineTo(px, topY - mound + ripple)
                }
                poolPath.lineTo(sw, sh)
                poolPath.close()

                poolPaint.shader = LinearGradient(
                    0f, topY - dp(context, 6f), 0f, sh,
                    intArrayOf(Color.argb(235, 255, 251, 235), Color.argb(230, 250, 204, 21), Color.argb(245, 180, 83, 9)),
                    floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawPath(poolPath, poolPaint)
            }
        }
    }

    /**
     * Unified Living Pedestal Crest View:
     * - Scrolls actual `raven_gold_ring_strip.png` stardust & glimmers downward from 12:00 -> 6:00 on both flanks
     * - 6:00 Molten Gold Meniscus & Gravity Teardrop Particle Emitter
     * - Ultra-HD Mjolnir <-> Living Raven Pedestal Scene + Shimmering Gold Runes + 4 Random Sky Bolts
     */
    class LivingPedestalCrestView(ctx: Context) : FrameLayout(ctx) {
        private val padSide = dpInt(ctx, 18)
        private val padTop = dpInt(ctx, 18)
        private val padBottom = dpInt(ctx, 34)
        private val cardW = dpInt(ctx, 112)
        private val cardH = dpInt(ctx, 140)
        private val cornerR = dp(ctx, 20f)

        val totalWindowWidthPx: Int = cardW + padSide * 2
        val totalWindowHeightPx: Int = cardH + padTop + padBottom

        private val baseImageView: ImageView
        private val poseOverlayView: ImageView
        private val swapBoltDrawable = GoldenPedestalSwapLightningDrawable()
        private val runesDrawable: ShimmeringRuneOverlayDrawable?
        private val skyBoltsDrawable: SkyStormBoltsDrawable

        private val flankSegs = 64
        private val radSteps = 8
        private val rightBx = FloatArray(flankSegs + 1)
        private val rightBy = FloatArray(flankSegs + 1)
        private val rightNx = FloatArray(flankSegs + 1)
        private val rightNy = FloatArray(flankSegs + 1)
        private val leftBx = FloatArray(flankSegs + 1)
        private val leftBy = FloatArray(flankSegs + 1)
        private val leftNx = FloatArray(flankSegs + 1)
        private val leftNy = FloatArray(flankSegs + 1)

        private val rightMeshVerts = FloatArray((flankSegs + 1) * (radSteps + 1) * 2)
        private val leftMeshVerts = FloatArray((flankSegs + 1) * (radSteps + 1) * 2)
        private var meshReady = false

        // Pre-allocated scrolling strip buffer so actual gold stardust specks physically flow from 12:00 -> 6:00!
        private val scrolledStripBmp: Bitmap? = ringStripBmp?.let {
            Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888)
        }
        private val scrolledStripCanvas: Canvas? = scrolledStripBmp?.let { Canvas(it) }
        private val stripDrawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val crownTaperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        private val ringPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            alpha = 240
        }
        private val moltenGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val moltenCorePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dripPath = Path()

        private data class MoltenDrop(
            var yOffsetPx: Float = 0f,
            var vyPxPerSec: Float = 0f,
            var xJitterPx: Float = 0f,
            var radiusPx: Float = 0f,
            var life: Float = 0f,
            var maxLife: Float = 1f,
            var active: Boolean = false
        )
        private val drops = Array(8) { MoltenDrop() }
        private var lastFrameMs = SystemClock.elapsedRealtime()
        private var flowPhase = 0f
        private var dripSpawnAccum = 0f

        private var isStandbyMode = false
        private var isSessionOpen = false
        private var isSpeakingNow = false
        private var hasUnreadRavenFlag = false
        private var showingRavenOnStone = false

        private var nextPerchLifeActionAtMs = SystemClock.elapsedRealtime() + 2600L
        private var perchPoseRevertAtMs = 0L
        private val bootShowcaseUntilMs = SystemClock.elapsedRealtime() + 28_000L

        private var masterLoop: ValueAnimator? = null
        private var swapAnimator: ValueAnimator? = null

        init {
            setWillNotDraw(false)
            clipChildren = false
            clipToPadding = false
            layoutParams = LayoutParams(totalWindowWidthPx, totalWindowHeightPx)

            scrolledStripBmp?.let { sb ->
                crownTaperPaint.shader = LinearGradient(
                    0f, 0f, sb.width * 0.065f, 0f,
                    intArrayOf(Color.TRANSPARENT, Color.WHITE),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }

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
                setImageDrawable(swapBoltDrawable)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            })

            addView(roundedCard)
            refreshScrolledStripBitmap()
            startMasterLoop()
        }

        private fun refreshScrolledStripBitmap() {
            val src = ringStripBmp ?: return
            val dst = scrolledStripBmp ?: return
            val c = scrolledStripCanvas ?: return
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val w = src.width.toFloat()
            val shiftPx = ((flowPhase % 1f + 1f) % 1f) * w
            c.drawBitmap(src, shiftPx, 0f, stripDrawPaint)
            c.drawBitmap(src, shiftPx - w, 0f, stripDrawPaint)
        }

        fun setOccupantImmediate(showRaven: Boolean) {
            showingRavenOnStone = showRaven
            poseOverlayView.animate().cancel()
            poseOverlayView.alpha = 0f
            baseImageView.setImageBitmap(if (showRaven) ravenBaseBmp else (mjolnirBaseBmp ?: ravenBaseBmp))
            invalidate()
        }

        fun crossSwapOccupantWithGoldenLightning(toRaven: Boolean) {
            swapAnimator?.cancel()
            skyBoltsDrawable.forceStrikeNow()
            poseOverlayView.animate().cancel()
            poseOverlayView.alpha = 0f
            var swappedMidFlash = false
            swapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 470L
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    val p = a.animatedValue as Float
                    swapBoltDrawable.progress = p
                    if (!swappedMidFlash && p >= 0.42f) {
                        swappedMidFlash = true
                        showingRavenOnStone = toRaven
                        baseImageView.setImageBitmap(if (toRaven) ravenBaseBmp else (mjolnirBaseBmp ?: ravenBaseBmp))
                        if (toRaven) {
                            nextPerchLifeActionAtMs = SystemClock.elapsedRealtime() + Random.nextLong(1800L, 3400L)
                        }
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        swapBoltDrawable.progress = -1f
                    }
                })
                start()
            }
        }

        fun triggerGoldenSwapBurst(durationMs: Long = 400L) {
            swapAnimator?.cancel()
            swapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = LinearInterpolator()
                addUpdateListener { a -> swapBoltDrawable.progress = a.animatedValue as Float }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        swapBoltDrawable.progress = -1f
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
            } else {
                null
            }
            baseImageView.alpha = if (standby && !unreadRaven) 0.76f else 1.0f
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
                updateDualCascadingMeshes(flowActive = true)
            }
        }

        private fun buildDualFlankGeometry() {
            val left = padSide.toFloat()
            val top = padTop.toFloat()
            val right = (padSide + cardW).toFloat()
            val bottom = (padTop + cardH).toFloat()
            val cx = (left + right) * 0.5f
            val r = cornerR.coerceAtMost(min(cardW, cardH) * 0.46f)

            val topCrownGap = 0f
            val halfTopStraight = ((right - r) - (cx + topCrownGap)).coerceAtLeast(1f)
            val sideStraight = ((bottom - r) - (top + r)).coerceAtLeast(1f)
            val halfBotStraight = ((right - r) - cx).coerceAtLeast(1f)
            val arcLen = (0.5 * Math.PI * r).toFloat()
            val halfPerimLen = halfTopStraight + arcLen + sideStraight + arcLen + halfBotStraight

            for (i in 0..flankSegs) {
                val u = i.toFloat() / flankSegs.toFloat()
                var s = u * halfPerimLen
                var rx: Float
                var ry: Float
                var rnx: Float
                var rny: Float

                if (s <= halfTopStraight) {
                    rx = (cx + topCrownGap) + s
                    ry = top
                    rnx = 0f; rny = -1f
                } else {
                    s -= halfTopStraight
                    if (s <= arcLen) {
                        val a = (-Math.PI * 0.5).toFloat() + (s / r)
                        rnx = cos(a.toDouble()).toFloat()
                        rny = sin(a.toDouble()).toFloat()
                        rx = (right - r) + r * rnx
                        ry = (top + r) + r * rny
                    } else {
                        s -= arcLen
                        if (s <= sideStraight) {
                            rx = right
                            ry = (top + r) + s
                            rnx = 1f; rny = 0f
                        } else {
                            s -= sideStraight
                            if (s <= arcLen) {
                                val a = s / r
                                rnx = cos(a.toDouble()).toFloat()
                                rny = sin(a.toDouble()).toFloat()
                                rx = (right - r) + r * rnx
                                ry = (bottom - r) + r * rny
                            } else {
                                s -= arcLen
                                rx = (right - r) - s.coerceAtMost(halfBotStraight)
                                ry = bottom
                                rnx = 0f; rny = 1f
                            }
                        }
                    }
                }

                rightBx[i] = rx
                rightBy[i] = ry
                rightNx[i] = rnx
                rightNy[i] = rny

                leftBx[i] = 2f * cx - rx
                leftBy[i] = ry
                leftNx[i] = -rnx
                leftNy[i] = rny
            }
        }

        private fun updateDualCascadingMeshes(flowActive: Boolean) {
            if (!meshReady) return
            val innerTuckPx = -dp(context, 0.5f)
            val outerSpanPx = padSide.toFloat() - innerTuckPx

            for (i in 0..flankSegs) {
                val u = i.toFloat() / flankSegs.toFloat()
                val bottomPoolSwell = if (flowActive && u > 0.82f) 1.0f + 0.38f * ((u - 0.82f) / 0.18f) else 1.0f

                for (j in 0..radSteps) {
                    val rFrac = j.toFloat() / radSteps.toFloat()
                    val dist = innerTuckPx + outerSpanPx * rFrac * bottomPoolSwell
                    val vIdx = (j * (flankSegs + 1) + i) * 2

                    rightMeshVerts[vIdx] = rightBx[i] + rightNx[i] * dist
                    rightMeshVerts[vIdx + 1] = rightBy[i] + rightNy[i] * dist

                    leftMeshVerts[vIdx] = leftBx[i] + leftNx[i] * dist
                    leftMeshVerts[vIdx + 1] = leftBy[i] + leftNy[i] * dist
                }
            }
        }

        private fun stepMoltenGoldDrops(dtSec: Float, flowActive: Boolean) {
            val maxFallPx = (padBottom - dpInt(context, 3)).toFloat().coerceAtLeast(dp(context, 18f))
            val gravity = dp(context, 92f)

            if (flowActive && !isStandbyMode) {
                dripSpawnAccum += dtSec
                if (dripSpawnAccum >= 0.88f) {
                    dripSpawnAccum = 0f
                    val slot = drops.firstOrNull { !it.active }
                    if (slot != null) {
                        slot.active = true
                        slot.yOffsetPx = dp(context, 1.5f)
                        slot.vyPxPerSec = dp(context, 8f)
                        slot.xJitterPx = (Random.nextFloat() - 0.5f) * dp(context, 3.0f)
                        slot.radiusPx = dp(context, Random.nextFloat() * 1.4f + 2.9f)
                        slot.life = 0f
                        slot.maxLife = Random.nextFloat() * 0.20f + 0.72f
                    }
                }
            }

            for (d in drops) {
                if (!d.active) continue
                d.life += dtSec
                if (d.life >= d.maxLife || d.yOffsetPx > maxFallPx) {
                    d.active = false
                    continue
                }
                if (d.life < d.maxLife * 0.22f) {
                    d.yOffsetPx += dp(context, 11f) * dtSec
                } else {
                    d.vyPxPerSec += gravity * dtSec
                    d.yOffsetPx += d.vyPxPerSec * dtSec
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

                    val flowActive = !isStandbyMode && (isSessionOpen || isSpeakingNow || hasUnreadRavenFlag || now < bootShowcaseUntilMs)
                    if (flowActive) {
                        // 7.48s down the 235dp flank == exact 31.4 dp/s original ring glide speed!
                        val speedMult = if (isSpeakingNow) 1.22f else 1.0f
                        flowPhase = (flowPhase + (dt / 7.48f) * speedMult) % 1f
                        refreshScrolledStripBitmap()
                        val pulseRad = ((elapsed % 1100L).toDouble() / 1100.0) * Math.PI * 2.0
                        ringPaint.alpha = ((0.78 + 0.22 * sin(pulseRad)) * 255.0).toInt().coerceIn(140, 255)
                    } else {
                        ringPaint.alpha = if (isStandbyMode) 195 else 222
                    }

                    updateDualCascadingMeshes(flowActive)
                    val streamActive = (!isStandbyMode || hasUnreadRavenFlag) && (isSessionOpen || isSpeakingNow || hasUnreadRavenFlag || now < bootShowcaseUntilMs)
                    if (isAttachedToWindow) {
                        val loc = IntArray(2)
                        getLocationOnScreen(loc)
                        val sixScreenX = loc[0] + padSide + cardW * 0.50f
                        val sixScreenY = loc[1] + padTop + cardH + dp(context, 2f)
                        if (streamActive || moltenStreamOverlay != null) {
                            val mv = ensureMoltenStreamOverlay(context)
                            val keep = mv?.stepPhysics(dt, sixScreenX, sixScreenY, streamActive) ?: false
                            if (!keep && !streamActive) detachMoltenStreamOverlay()
                        }
                    }

                    runesDrawable?.shimmerPhase = (elapsed % 4200L).toFloat() / 4200f
                    if (!isStandbyMode || hasUnreadRavenFlag) {
                        skyBoltsDrawable.tick(now)
                    }

                    if (showingRavenOnStone) {
                        if (perchPoseRevertAtMs > 0L && now >= perchPoseRevertAtMs) {
                            perchPoseRevertAtMs = 0L
                            poseOverlayView.animate().alpha(0f).setDuration(190L).start()
                        } else if (now >= nextPerchLifeActionAtMs) {
                            if (Random.nextFloat() < 0.46f) {
                                poseOverlayView.setImageBitmap(ravenBlinkBmp)
                                poseOverlayView.animate().alpha(1f).setDuration(75L).start()
                                perchPoseRevertAtMs = now + 195L
                                nextPerchLifeActionAtMs = now + Random.nextLong(2100L, 4200L)
                            } else {
                                poseOverlayView.setImageBitmap(ravenTurnBmp)
                                poseOverlayView.animate().alpha(1f).setDuration(220L).start()
                                perchPoseRevertAtMs = now + Random.nextLong(1100L, 1950L)
                                nextPerchLifeActionAtMs = perchPoseRevertAtMs + Random.nextLong(2000L, 4400L)
                            }
                        }
                    }

                    invalidate()
                }
                start()
            }
        }

        fun stopLoop() {
            masterLoop?.cancel()
            masterLoop = null
            swapAnimator?.cancel()
            swapAnimator = null
        }

        override fun dispatchDraw(canvas: Canvas) {
            val strip = scrolledStripBmp ?: ringStripBmp
            if (strip != null && meshReady) {
                canvas.drawBitmapMesh(strip, flankSegs, radSteps, rightMeshVerts, 0, null, 0, ringPaint)
                canvas.drawBitmapMesh(strip, flankSegs, radSteps, leftMeshVerts, 0, null, 0, ringPaint)
            }

            val sixX = padSide + cardW * 0.50f
            val sixY = (padTop + cardH).toFloat() + dp(context, 2.5f)
            val anyDrops = drops.any { it.active }
            val showPool = !isStandbyMode && (isSessionOpen || isSpeakingNow || hasUnreadRavenFlag || anyDrops || SystemClock.elapsedRealtime() < bootShowcaseUntilMs)

            if (showPool) {
                val poolPulse = 0.85f + 0.15f * sin(flowPhase * Math.PI * 8.0).toFloat()
                val poolR = dp(context, 7.5f) * poolPulse
                moltenGlowPaint.shader = RadialGradient(
                    sixX, sixY, poolR * 1.6f,
                    intArrayOf(
                        Color.argb(235, 255, 251, 235),
                        Color.argb(185, 250, 204, 21),
                        Color.argb(95, 217, 119, 6),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.38f, 0.72f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawOval(sixX - poolR * 1.45f, sixY - poolR * 0.65f, sixX + poolR * 1.45f, sixY + poolR * 0.95f, moltenGlowPaint)

                for (d in drops) {
                    if (!d.active) continue
                    val norm = (d.life / d.maxLife).coerceIn(0f, 1f)
                    val alpha = ((1f - norm * norm) * 255f).toInt().coerceIn(0, 255)
                    val dx = sixX + d.xJitterPx
                    val dy = sixY + d.yOffsetPx
                    val r = d.radiusPx * (1f - 0.35f * norm)

                    moltenGlowPaint.shader = RadialGradient(
                        dx, dy, r * 2.5f,
                        intArrayOf(Color.argb((alpha * 0.75f).toInt(), 245, 158, 11), Color.TRANSPARENT),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(dx, dy, r * 2.5f, moltenGlowPaint)

                    dripPath.reset()
                    val tailTop = if (norm < 0.26f) sixY else (dy - r * 2.1f)
                    dripPath.moveTo(dx, tailTop)
                    dripPath.quadTo(dx + r * 1.25f, dy - r * 0.2f, dx, dy + r)
                    dripPath.quadTo(dx - r * 1.25f, dy - r * 0.2f, dx, tailTop)
                    dripPath.close()

                    moltenCorePaint.color = Color.argb(alpha, 254, 240, 138)
                    canvas.drawPath(dripPath, moltenCorePaint)
                    moltenCorePaint.color = Color.argb(alpha, 255, 251, 235)
                    canvas.drawCircle(dx, dy + r * 0.15f, r * 0.48f, moltenCorePaint)
                }
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
            val b = bounds
            if (b.isEmpty) return
            val bw = b.width().toFloat()
            val bh = b.height().toFloat()
            dstRect.set(b.left + normRect.left * bw, b.top + normRect.top * bh, b.left + normRect.right * bw, b.top + normRect.bottom * bh)
            canvas.drawBitmap(runesBitmap, null, dstRect, basePaint)

            val rw = dstRect.width()
            val rh = dstRect.height()
            val lid = canvas.saveLayer(dstRect, null)
            canvas.drawBitmap(runesBitmap, null, dstRect, maskPaint)
            val p1 = (shimmerPhase % 1f + 1f) % 1f
            val sx = dstRect.left - rw * 0.65f + (rw * 2.30f) * p1
            val sy = dstRect.top - rh * 0.25f + (rh * 0.70f) * p1
            sweepPaint.shader = LinearGradient(
                sx, sy, sx + rw * 0.44f, sy + rh * 0.30f,
                intArrayOf(0x00B6862C, 0x66F1C833, 0xEEFFF791.toInt(), 0xFFFFFBEB.toInt(), 0xEEFFF791.toInt(), 0x66F1C833, 0x00B6862C),
                floatArrayOf(0f, 0.24f, 0.44f, 0.50f, 0.56f, 0.76f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(dstRect, sweepPaint)
            canvas.restoreToCount(lid)
        }

        override fun setAlpha(alpha: Int) { basePaint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { basePaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class SkyStormBoltsDrawable(
        private val cores: Array<Bitmap?>,
        private val halos: Array<Bitmap?>
    ) : Drawable() {
        private val normRects = arrayOf(
            RectF(0.00000f, 0.06207f, 0.44609f, 0.56677f),
            RectF(0.31562f, 0.00000f, 0.77578f, 0.22696f),
            RectF(0.30469f, 0.26583f, 0.77656f, 0.49843f),
            RectF(0.67812f, 0.19875f, 1.00000f, 0.56489f)
        )
        private val haloPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val corePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dstRect = RectF()
        private val startMs = LongArray(4)
        private val durMs = LongArray(4)
        private val nextAtMs = LongArray(4)
        private var lastAnyMs = 0L

        init {
            val now = SystemClock.elapsedRealtime()
            for (i in 0 until 4) {
                durMs[i] = Random.nextLong(520L, 840L)
                nextAtMs[i] = now + Random.nextLong(500L + i * 650L, 2600L + i * 900L)
            }
        }

        fun forceStrikeNow() {
            val now = SystemClock.elapsedRealtime()
            startMs[0] = now; durMs[0] = 540L
            startMs[3] = now + 55L; durMs[3] = 580L
            lastAnyMs = now
            invalidateSelf()
        }

        fun tick(nowMs: Long) {
            for (i in 0 until 4) {
                val active = startMs[i] > 0L && (nowMs - startMs[i]) <= durMs[i]
                if (!active && nowMs >= nextAtMs[i]) {
                    if ((nowMs - lastAnyMs) < 340L) {
                        nextAtMs[i] = lastAnyMs + Random.nextLong(380L, 950L)
                    } else {
                        startMs[i] = nowMs
                        durMs[i] = Random.nextLong(480L, 840L)
                        lastAnyMs = nowMs
                        nextAtMs[i] = nowMs + durMs[i] + Random.nextLong(1600L, 5800L)
                    }
                }
            }
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val bw = b.width().toFloat()
            val bh = b.height().toFloat()
            val nowMs = SystemClock.elapsedRealtime()

            for (i in 0 until 4) {
                val st = startMs[i]
                if (st <= 0L) continue
                val el = nowMs - st
                if (el < 0L || el > durMs[i]) continue
                val core = cores[i] ?: continue
                val halo = halos[i] ?: continue
                val nr = normRects[i]
                dstRect.set(b.left + nr.left * bw, b.top + nr.top * bh, b.left + nr.right * bw, b.top + nr.bottom * bh)
                val p = (el.toFloat() / durMs[i].toFloat()) * 1.30f
                val alpha = if (p <= 0.85f) 245 else ((1f - (p - 0.85f) / 0.45f) * 245f).toInt().coerceIn(0, 255)
                val frontBottom = if (p < 0.55f) dstRect.top + dstRect.height() * (p / 0.55f) else dstRect.bottom
                haloPaint.alpha = alpha
                corePaint.alpha = alpha
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

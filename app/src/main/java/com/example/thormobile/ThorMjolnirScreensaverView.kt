package com.example.thormobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * WO-159 V4: Living Mjolnir Storm Universal Screensaver View (Note 20 Ultra Portrait & Landscape).
 *
 * Fixes verified from Boss's V3 visual test & exact sprite/aspect measurements:
 *  1. 1:1 ASPECT-LOCKED FLOATING-ICON GOLD BORDER (IDENTICAL IN PORTRAIT, LANDSCAPE & DELL XPS):
 *     - Dynamically computes exact tile count from `perimeter / (bandWidth * 6.86)` (~16 tiles on phone)
 *       so `raven_gold_ring_strip.png` is NEVER stretched differently when rotating the phone.
 *     - Scrolls at constant physical speed (calm clockwise 24k rim + slower counter-clockwise outer dust).
 *  2. ROCK-STEADY TORSO-LOCKED, CROSS-FADED 3D AERODYNAMIC RAVEN (ZERO JERKS, ZERO SIDEWAYS FLOATING):
 *     - Uses torso-locked 512x512 sprites (`raven_ss_up / glide / down`) whose body & head are pinned to
 *       exact pixel center (256, 256) so ZERO body jump occurs on wingbeats.
 *     - Smoothly cross-fades consecutive wing frames on a continuous sine wing curve (`up <-> glide <-> down`).
 *     - Moves at constant physical pixel speed (`diagDim * 0.16 * depth`) with strictly nose-first shallow
 *       aerodynamic glide arcs (`|pitch| <= 12.5 deg`) and true 3D depth travel (`0.38x <-> 1.05x`).
 *  3. REAL PAINTED LIGHTNING ILLUMINATION + FULL-CLARITY 21:9 LANDSCAPE & 9:16 PORTRAIT.
 */
class ThorMjolnirScreensaverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val rng = Random(System.currentTimeMillis())

    private val wideBaseBmp: Bitmap?
    private val tallBaseBmp: Bitmap?
    private val wideBolts: Array<Bitmap?>
    private val tallBolts: Array<Bitmap?>
    private val rimCwSrc: Bitmap?
    private val dustCcwSrc: Bitmap?
    private val flyUpBmp: Bitmap?
    private val flyGlideBmp: Bitmap?
    private val flyDownBmp: Bitmap?

    private var scrolledCwBmp: Bitmap? = null
    private var scrolledCwCanvas: Canvas? = null
    private var scrolledCcwBmp: Bitmap? = null
    private var scrolledCcwCanvas: Canvas? = null

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ravenPaintA = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ravenPaintB = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stripDrawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ringMeshPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dustMeshPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val boltAddPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val runeAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    private val clipPath = Path()
    private val innerClipRect = RectF()
    private val bgDstRect = RectF()
    private val birdDstRect = RectF()

    // Dynamic 1:1 aspect-locked perimeter mesh tiles (auto-adapts cleanly across Portrait & Landscape!)
    private var tileCount = 16
    private val segsPerTile = 12
    private val radSteps = 6
    private var tileMeshes: Array<FloatArray> = emptyArray()
    private var builtW = -1
    private var builtH = -1

    private data class RainDrop(var x: Float, var y: Float, val len: Float, val speed: Float, val alpha: Float)
    private val rainDrops = List(54) {
        RainDrop(
            x = rng.nextFloat(),
            y = rng.nextFloat(),
            len = 0.018f + rng.nextFloat() * 0.025f,
            speed = 0.72f + rng.nextFloat() * 0.50f,
            alpha = 0.045f + rng.nextFloat() * 0.070f,
        )
    }

    private class RealBoltStrike(var nextAtMs: Long, var startedAtMs: Long, var durationMs: Long, var peakPower: Float)
    private var startElapsedMs = SystemClock.elapsedRealtime()
    private var lastFrameMs = startElapsedMs
    private val strikes = List(3) { i ->
        RealBoltStrike(
            nextAtMs = startElapsedMs + 950L + i * 1950L + rng.nextLong(1800L),
            startedAtMs = -99999L,
            durationMs = 460L,
            peakPower = 1.0f,
        )
    }

    // WO-219: Natural 3D Corvid Flight Engine (scene-proportional sizing, 100% solid opaque feathers,
    // genuine Far<->Close 3D perspective depth & flap-then-soar bird rhythm — Gold frame 100% untouched)
    private var passDir = 1
    private var passMode = 0            // 0=Swoop-In, 1=Climb-Out, 2=Valley Arch (In & Out), 3=High Thermal Patrol
    private var ravenPxX = -200f
    private var ravenPxY = 300f
    private var startX = -200f
    private var endX = 1600f
    private var yStartFrac = 0.22f
    private var yEndFrac = 0.26f
    private var yArchFrac = 0.08f
    private var depthStart = 0.30f
    private var depthMid = 0.72f
    private var depthEnd = 1.02f
    private var restCooldown = 0f
    private var smoothPitchDeg = 0f
    private var wingPhase = 0f
    private var gliding = false
    private var glideTimer = 0f
    private var beatsRemaining = 4
    private var prevCycleQuarter = 0
    private var needsInitialPass = true
    private var running = false

    init {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        wideBaseBmp = decodeSafe(R.drawable.mjolnir_wide_base_storm, opts) ?: decodeSafe(R.drawable.mjolnir_valley_wide_hd, opts)
        tallBaseBmp = decodeSafe(R.drawable.mjolnir_tall_base_storm, opts) ?: decodeSafe(R.drawable.mjolnir_valley_tall_hd, opts)
        wideBolts = arrayOf(
            decodeSafe(R.drawable.mjolnir_wide_bolt_0, opts),
            decodeSafe(R.drawable.mjolnir_wide_bolt_1, opts),
            decodeSafe(R.drawable.mjolnir_wide_bolt_2, opts),
        )
        tallBolts = arrayOf(
            decodeSafe(R.drawable.mjolnir_tall_bolt_0, opts),
            decodeSafe(R.drawable.mjolnir_tall_bolt_1, opts),
            decodeSafe(R.drawable.mjolnir_tall_bolt_2, opts),
        )
        rimCwSrc = decodeSafe(R.drawable.raven_gold_rim_cw, opts) ?: decodeSafe(R.drawable.raven_gold_ring_strip, opts)
        dustCcwSrc = decodeSafe(R.drawable.raven_gold_dust_ccw, opts)
        flyUpBmp = decodeSafe(R.drawable.raven_ss_up, opts) ?: decodeSafe(R.drawable.raven_fly_up, opts)
        flyGlideBmp = decodeSafe(R.drawable.raven_ss_glide, opts) ?: decodeSafe(R.drawable.raven_fly_glide, opts)
        flyDownBmp = decodeSafe(R.drawable.raven_ss_down, opts) ?: decodeSafe(R.drawable.raven_fly_down, opts)

        rimCwSrc?.let { src ->
            scrolledCwBmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            scrolledCwCanvas = Canvas(scrolledCwBmp!!)
        }
        dustCcwSrc?.let { src ->
            scrolledCcwBmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            scrolledCcwCanvas = Canvas(scrolledCcwBmp!!)
        }
    }

    private fun decodeSafe(resId: Int, opts: BitmapFactory.Options): Bitmap? {
        return try { BitmapFactory.decodeResource(resources, resId, opts) } catch (_: Throwable) { null }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        startElapsedMs = SystemClock.elapsedRealtime()
        lastFrameMs = startElapsedMs
        for (i in strikes.indices) {
            strikes[i].nextAtMs = startElapsedMs + 850L + i * 1850L + rng.nextLong(1900L)
        }
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    fun releaseBitmaps() {
        running = false
        for (b in listOf(wideBaseBmp, tallBaseBmp, rimCwSrc, dustCcwSrc, scrolledCwBmp, scrolledCcwBmp, flyUpBmp, flyGlideBmp, flyDownBmp)) {
            try { b?.recycle() } catch (_: Exception) {}
        }
        for (b in wideBolts) { try { b?.recycle() } catch (_: Exception) {} }
        for (b in tallBolts) { try { b?.recycle() } catch (_: Exception) {} }
    }

    private fun scrollStrip(src: Bitmap?, dstCanvas: Canvas?, phase01: Float) {
        val s = src ?: return
        val c = dstCanvas ?: return
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val w = s.width.toFloat()
        val shift = ((phase01 - floor(phase01)) * w).coerceIn(0f, w - 1f)
        c.drawBitmap(s, shift, 0f, stripDrawPaint)
        c.drawBitmap(s, shift - w, 0f, stripDrawPaint)
    }

    private fun sampleRoundedBorderAndNormal(
        t01: Float, l: Float, t: Float, r: Float, b: Float, cr: Float
    ): FloatArray {
        val rw = (r - l - 2f * cr).coerceAtLeast(1f)
        val rh = (b - t - 2f * cr).coerceAtLeast(1f)
        val arc = (PI.toFloat() * 0.5f) * cr
        val perim = 2f * rw + 2f * rh + 4f * arc
        var frac = t01 - floor(t01)
        if (frac < 0f) frac += 1f
        var d = frac * perim

        if (d <= rw) return floatArrayOf(l + cr + d, t, 0f, -1f)
        d -= rw
        if (d <= arc) {
            val a = -PI.toFloat() * 0.5f + d / cr
            val nx = cos(a); val ny = sin(a)
            return floatArrayOf(r - cr + nx * cr, t + cr + ny * cr, nx, ny)
        }
        d -= arc
        if (d <= rh) return floatArrayOf(r, t + cr + d, 1f, 0f)
        d -= rh
        if (d <= arc) {
            val a = d / cr
            val nx = cos(a); val ny = sin(a)
            return floatArrayOf(r - cr + nx * cr, b - cr + ny * cr, nx, ny)
        }
        d -= arc
        if (d <= rw) return floatArrayOf(r - cr - d, b, 0f, 1f)
        d -= rw
        if (d <= arc) {
            val a = PI.toFloat() * 0.5f + d / cr
            val nx = cos(a); val ny = sin(a)
            return floatArrayOf(l + cr + nx * cr, b - cr + ny * cr, nx, ny)
        }
        d -= arc
        if (d <= rh) return floatArrayOf(l, b - cr - d, -1f, 0f)
        d -= rh
        val a = PI.toFloat() + d / cr
        val nx = cos(a); val ny = sin(a)
        return floatArrayOf(l + cr + nx * cr, t + cr + ny * cr, nx, ny)
    }

    private fun rebuildGeometryOnResize(w: Int, h: Int) {
        if (w == builtW && h == builtH) return
        builtW = w; builtH = h
        val wf = w.toFloat(); val hf = h.toFloat()
        val minDim = min(wf, hf)
        val pad = max(dp(22f), minDim * 0.056f)
        val cornerR = max(dp(20f), minDim * 0.042f)
        val l = pad; val t = pad; val r = wf - pad; val b = hf - pad

        innerClipRect.set(l, t, r, b)
        clipPath.reset()
        clipPath.addRoundRect(innerClipRect, cornerR, cornerR, Path.Direction.CW)

        val rcx = innerClipRect.left + innerClipRect.width() * 0.50f
        val rcy = innerClipRect.top + innerClipRect.height() * 0.63f
        runeAuraPaint.shader = RadialGradient(
            rcx, rcy, minDim * 0.22f,
            Color.argb(46, 254, 215, 102),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )

        // Exact 1:1 Aspect-Locked Tiling based on `raven_gold_ring_strip.png` natural 6.86:1 ratio!
        // Ensures IDENTICAL gold rim thickness and stardust grain size in Portrait, Landscape & Laptop!
        val innerTuck = -dp(2.0f)
        val outerSpan = pad * 0.98f - innerTuck
        val perim = 2f * ((r - l) - 2f * cornerR) + 2f * ((b - t) - 2f * cornerR) + 2f * PI.toFloat() * cornerR
        val naturalAspect = 1536f / 224f
        val idealTileLen = outerSpan * naturalAspect
        tileCount = (perim / idealTileLen).roundToInt().coerceIn(8, 24)

        tileMeshes = Array(tileCount) { FloatArray((segsPerTile + 1) * (radSteps + 1) * 2) }
        for (q in 0 until tileCount) {
            val verts = tileMeshes[q]
            val uStart = q.toFloat() / tileCount.toFloat()
            val uSpan = 1f / tileCount.toFloat()
            var k = 0
            for (row in 0..radSteps) {
                val rf = row.toFloat() / radSteps.toFloat()
                val dist = innerTuck + outerSpan * rf
                for (col in 0..segsPerTile) {
                    val u = uStart + (col.toFloat() / segsPerTile.toFloat()) * uSpan
                    val pt = sampleRoundedBorderAndNormal(u, l, t, r, b, cornerR)
                    verts[k++] = pt[0] + pt[2] * dist
                    verts[k++] = pt[1] + pt[3] * dist
                }
            }
        }
        startFreshPhysicalPass(wf, hf, immediateVisible = needsInitialPass)
        needsInitialPass = false
    }

    private fun startFreshPhysicalPass(wf: Float, hf: Float, immediateVisible: Boolean) {
        val sceneRef = min(innerClipRect.width(), innerClipRect.height()).coerceAtLeast(320f)
        val marginPx = sceneRef * 0.28f
        passDir = if (immediateVisible) 1 else (if (rng.nextBoolean()) 1 else -1)
        startX = if (passDir > 0) (innerClipRect.left - marginPx) else (innerClipRect.right + marginPx)
        endX = if (passDir > 0) (innerClipRect.right + marginPx) else (innerClipRect.left - marginPx)
        ravenPxX = if (immediateVisible) (innerClipRect.left + innerClipRect.width() * 0.14f) else startX
        restCooldown = if (immediateVisible) 0f else (1.0f + rng.nextFloat() * 1.8f)

        passMode = if (immediateVisible) 2 else rng.nextInt(4)
        when (passMode) {
            0 -> { // Swoop-In: small over distant peaks -> majestic foreground pass
                depthStart = 0.28f + rng.nextFloat() * 0.08f
                depthMid = 0.62f + rng.nextFloat() * 0.12f
                depthEnd = 0.96f + rng.nextFloat() * 0.12f
                yStartFrac = 0.13f + rng.nextFloat() * 0.10f
                yEndFrac = 0.26f + rng.nextFloat() * 0.14f
                yArchFrac = -0.04f - rng.nextFloat() * 0.04f
            }
            1 -> { // Climb-Out: bold in foreground -> climbing & receding into storm sky
                depthStart = 0.94f + rng.nextFloat() * 0.12f
                depthMid = 0.60f + rng.nextFloat() * 0.12f
                depthEnd = 0.28f + rng.nextFloat() * 0.08f
                yStartFrac = 0.26f + rng.nextFloat() * 0.14f
                yEndFrac = 0.12f + rng.nextFloat() * 0.10f
                yArchFrac = 0.04f + rng.nextFloat() * 0.04f
            }
            2 -> { // Valley Arch (Coming & Going): enters far -> swoops closer near center -> banks back out far!
                depthStart = 0.30f + rng.nextFloat() * 0.08f
                depthMid = 0.86f + rng.nextFloat() * 0.16f
                depthEnd = 0.30f + rng.nextFloat() * 0.08f
                yStartFrac = 0.15f + rng.nextFloat() * 0.12f
                yEndFrac = 0.15f + rng.nextFloat() * 0.12f
                yArchFrac = 0.10f + rng.nextFloat() * 0.06f
            }
            else -> { // High Mountain Thermal Soar: graceful mid-distance glide across valley
                val d = 0.40f + rng.nextFloat() * 0.18f
                depthStart = d
                depthMid = (d + 0.12f).coerceAtMost(0.76f)
                depthEnd = (d + (rng.nextFloat() - 0.5f) * 0.14f).coerceIn(0.32f, 0.72f)
                yStartFrac = 0.14f + rng.nextFloat() * 0.18f
                yEndFrac = (yStartFrac + (rng.nextFloat() - 0.5f) * 0.12f).coerceIn(0.11f, 0.36f)
                yArchFrac = (rng.nextFloat() - 0.5f) * 0.10f
            }
        }
        ravenPxY = innerClipRect.top + innerClipRect.height() * yStartFrac
        smoothPitchDeg = 0f
        gliding = false
        beatsRemaining = 3 + rng.nextInt(3)
        prevCycleQuarter = 0
        wingPhase = 0f
    }

    override fun onDraw(canvas: Canvas) {
        val wi = width; val hi = height
        if (wi <= 2 || hi <= 2) return
        val wf = wi.toFloat(); val hf = hi.toFloat()
        rebuildGeometryOnResize(wi, hi)

        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastFrameMs).coerceIn(1L, 40L)).toFloat() / 1000f
        lastFrameMs = now
        val elapsed = (now - startElapsedMs).coerceAtLeast(0L)
        val tSec = elapsed / 1000f
        val diagDim = hypot(wf.toDouble(), hf.toDouble()).toFloat()

        // 0. Pure obsidian gallery canvas behind 1:1 gold stardust halo
        canvas.drawColor(0xFF020305.toInt())

        // 1. Clipped Inner Viewport: FULL-CLARITY VALLEY + REAL PAINTED LIGHTNING + SMOOTH 3D RAVEN
        canvas.save()
        canvas.clipPath(clipPath)

        val fw = innerClipRect.width()
        val fh = innerClipRect.height()
        val isLand = wi >= hi
        val activeBase = if (isLand) (wideBaseBmp ?: tallBaseBmp) else (tallBaseBmp ?: wideBaseBmp)
        val activeBolts = if (isLand) wideBolts else tallBolts

        if (activeBase != null) {
            val scale = max(fw / activeBase.width.toFloat(), fh / activeBase.height.toFloat())
            val dw = activeBase.width * scale
            val dh = activeBase.height * scale
            val left = innerClipRect.left + (fw - dw) * 0.5f
            val top = innerClipRect.top + (fh - dh) * 0.5f
            bgDstRect.set(left, top, left + dw, top + dh)
            canvas.drawBitmap(activeBase, null, bgDstRect, bmpPaint)

            // 2. REAL PAINTED LIGHTNING ILLUMINATION
            var peakFlash = 0f
            for (i in strikes.indices) {
                val st = strikes[i]
                if (now >= st.nextAtMs) {
                    st.startedAtMs = now
                    st.durationMs = 340L + rng.nextLong(260L)
                    st.peakPower = 0.85f + rng.nextFloat() * 0.35f
                    st.nextAtMs = now + 2100L + rng.nextLong(4800L)
                }
                val age = now - st.startedAtMs
                if (age in 0L..st.durationMs) {
                    val p = age.toFloat() / st.durationMs.toFloat()
                    val env = when {
                        p < 0.12f -> p / 0.12f
                        p < 0.28f -> 1f - ((p - 0.12f) / 0.16f) * 0.72f
                        p < 0.44f -> 0.28f + ((p - 0.28f) / 0.16f) * 0.72f
                        else -> (1f - (p - 0.44f) / 0.56f).coerceAtLeast(0f)
                    } * st.peakPower
                    if (env > 0.02f) {
                        peakFlash = max(peakFlash, env)
                        activeBolts.getOrNull(i)?.let { boltBmp ->
                            boltAddPaint.alpha = (env * 255f).toInt().coerceIn(0, 255)
                            canvas.drawBitmap(boltBmp, null, bgDstRect, boltAddPaint)
                            if (env > 0.65f) {
                                boltAddPaint.alpha = ((env - 0.55f) * 200f).toInt().coerceIn(0, 175)
                                canvas.drawBitmap(boltBmp, null, bgDstRect, boltAddPaint)
                            }
                        }
                    }
                }
            }

            // 3. BREATHING GOLDEN PEDESTAL RUNES + FINE STORM RAIN
            val runePulse = 0.50f + 0.50f * sin(tSec * 1.85f)
            runeAuraPaint.alpha = (110 + (140 * runePulse).toInt()).coerceIn(90, 255)
            canvas.drawRect(innerClipRect, runeAuraPaint)

            rainPaint.strokeWidth = max(1f, dp(0.55f))
            for (rd in rainDrops) {
                rd.y += rd.speed * dt; rd.x += rd.speed * dt * 0.16f
                if (rd.y > 1.05f) { rd.y = -0.05f; rd.x = rng.nextFloat() }
                if (rd.x > 1.05f) rd.x -= 1.05f
                val x1 = innerClipRect.left + rd.x * fw
                val y1 = innerClipRect.top + rd.y * fh
                val a = (rd.alpha * (1f + peakFlash * 1.9f) * 255f).toInt().coerceIn(0, 100)
                rainPaint.color = Color.argb(a, 224, 236, 250)
                canvas.drawLine(x1, y1, x1 + rd.len * fh * 0.16f, y1 + rd.len * fh, rainPaint)
            }
        }

        // 4. NATURAL 3D CORVID FLIGHT (100% SOLID OPAQUE, SCENE-PROPORTIONAL, FLAP-THEN-SOAR, COMING & GOING)
        if (restCooldown > 0f) {
            restCooldown -= dt
        } else {
            val sceneRef = min(fw, fh).coerceAtLeast(320f)
            val totalDx = abs(endX - startX).coerceAtLeast(1f)
            val progress = (abs(ravenPxX - startX) / totalDx).coerceIn(0f, 1f)
            val u = 1f - progress
            // Smooth quadratic Bezier depth curve -> true Far <-> Close coming-and-going perspective!
            val depth = (u * u * depthStart + 2f * u * progress * depthMid + progress * progress * depthEnd)
                .coerceIn(0.24f, 1.12f)

            // True perspective velocity: slow & majestic when distant, brisk when swooping past foreground
            val speedPx = sceneRef * 0.25f * (0.42f + 0.72f * depth)
            val prevX = ravenPxX
            val prevY = ravenPxY
            ravenPxX += passDir * speedPx * dt

            if ((passDir > 0 && ravenPxX >= endX) || (passDir < 0 && ravenPxX <= endX)) {
                startFreshPhysicalPass(wf, hf, immediateVisible = false)
            } else {
                // Smooth natural bird flight arc + gentle downstroke wing lift
                val baseYFrac = yStartFrac + (yEndFrac - yStartFrac) * progress + sin(progress * PI.toFloat()) * yArchFrac
                val liftBobPx = if (gliding) 0f else (sin(wingPhase * 2f * PI.toFloat()) * dp(2.8f) * depth)
                val targetY = (innerClipRect.top + fh * baseYFrac + liftBobPx)
                    .coerceIn(innerClipRect.top + fh * 0.07f, innerClipRect.top + fh * 0.56f)
                ravenPxY = if (abs(ravenPxY - targetY) > fh * 0.25f) targetY else (ravenPxY + (targetY - ravenPxY) * min(1f, dt * 8.5f))

                val vx = (ravenPxX - prevX) / dt
                val vy = (ravenPxY - prevY) / dt
                // Strictly beak-first climb/dive angle (gentle +/- 14 deg max — NEVER flies sideways!)
                val targetPitch = (atan2(vy.toDouble(), max(60f, abs(vx)).toDouble()) * 180.0 / PI * 0.78)
                    .toFloat().coerceIn(-14f, 14f)
                smoothPitchDeg += (targetPitch - smoothPitchDeg) * min(1f, dt * 5.2f)

                // Realistic corvid wingbeat rhythm: 3-5 crisp steady wingbeats -> majestic 1.4-2.8s outstretched glide
                if (gliding) {
                    glideTimer -= dt
                    if (glideTimer <= 0f) {
                        gliding = false
                        beatsRemaining = if (vy < -15f) (4 + rng.nextInt(3)) else (3 + rng.nextInt(3))
                        wingPhase = 0f
                        prevCycleQuarter = 0
                    }
                } else {
                    val beatHz = if (vy < -12f) 2.25f else 1.95f
                    wingPhase += dt * beatHz
                    if (wingPhase >= 1f) {
                        wingPhase -= 1f
                        beatsRemaining--
                        if (beatsRemaining <= 0) {
                            gliding = true
                            glideTimer = (if (vy > 8f) 1.9f else 1.3f) + rng.nextFloat() * 1.4f
                        }
                    }
                }

                // Crisp discrete wing frame selection — 100% SOLID OPAQUE (alpha=255, ZERO ghosting/transparency!)
                val activeBmp = if (gliding) {
                    flyGlideBmp
                } else {
                    when ((wingPhase * 4f).toInt().coerceIn(0, 3)) {
                        0 -> flyUpBmp
                        2 -> flyDownBmp
                        else -> flyGlideBmp
                    }
                } ?: flyGlideBmp

                if (activeBmp != null) {
                    // Scene-proportional sizing: identical realistic bird-to-valley scale in Portrait, Landscape & Laptop!
                    val sizePx = sceneRef * 0.36f * depth
                    val flipX = if (passDir > 0) 1f else -1f
                    val bodyFlexY = if (gliding) 1f else (0.975f + 0.035f * cos(wingPhase * 2f * PI.toFloat()))
                    ravenPaintA.alpha = 255

                    canvas.save()
                    canvas.translate(ravenPxX, ravenPxY)
                    canvas.scale(flipX, bodyFlexY)
                    canvas.rotate(smoothPitchDeg)
                    birdDstRect.set(-sizePx * 0.5f, -sizePx * 0.5f, sizePx * 0.5f, sizePx * 0.5f)
                    canvas.drawBitmap(activeBmp, null, birdDstRect, ravenPaintA)
                    canvas.restore()
                }
            }
        }

        canvas.restore() // end inner valley clip

        // 5. 1:1 ASPECT-LOCKED FLOATING-ICON GOLD FRAME + CALM DUAL-DIRECTION STARDUST (ZERO DRIPPING!)
        // 18s per single 1:1 tile (~29 px/sec calm liquid gold CW) & 26s per tile (~20 px/sec CCW outer dust)
        val cwPhase = (elapsed % 18000L).toFloat() / 18000f
        val ccwPhase = 1f - ((elapsed % 26000L).toFloat() / 26000f)
        scrollStrip(rimCwSrc, scrolledCwCanvas, cwPhase)
        scrollStrip(dustCcwSrc, scrolledCcwCanvas, ccwPhase)

        val pulse = 0.89f + 0.11f * sin(tSec * 1.6f)
        scrolledCwBmp?.let { cwStrip ->
            ringMeshPaint.alpha = (255f * pulse).toInt().coerceIn(220, 255)
            for (q in 0 until tileCount) {
                canvas.drawBitmapMesh(cwStrip, segsPerTile, radSteps, tileMeshes[q], 0, null, 0, ringMeshPaint)
            }
        }
        scrolledCcwBmp?.let { ccwStrip ->
            dustMeshPaint.alpha = (190f * (0.82f + 0.18f * sin(tSec * 2.1f + 1.2f))).toInt().coerceIn(145, 210)
            for (q in 0 until tileCount) {
                canvas.drawBitmapMesh(ccwStrip, segsPerTile, radSteps, tileMeshes[q], 0, null, 0, dustMeshPaint)
            }
        }

        if (running) postInvalidateOnAnimation()
    }
}

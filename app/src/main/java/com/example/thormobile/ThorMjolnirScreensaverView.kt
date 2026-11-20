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
import kotlin.math.sin
import kotlin.random.Random

/**
 * WO-159 V3: Living Mjolnir Storm Universal Screensaver View (Note 20 Ultra Portrait & Landscape).
 *
 * Fixes verified from Boss's V2 feedback & LivingPedestalDirector.kt row-by-row inspection:
 *  1. EXACT FLOATING-ICON GOLD ORIENTATION & 4x CRISP TILING (ZERO CHAOTIC ANTS, ZERO DRIPPING):
 *     - Matches `LivingPedestalDirector.kt` L1082-1083 verbatim: row=0 (y=0..55 solid 24k gold rim)
 *       hugs the inner artwork edge, and row=radSteps (y=56..223 sparkling gold stardust) sprays
 *       cleanly OUTWARD into the obsidian margin.
 *     - Tiled cleanly across 4 perimeter quadrants (1 strip per 25% of perimeter) so stardust density
 *       matches the floating icon 1:1 with zero horizontal stretching.
 *     - Calm, liquid-gold dual-direction flow: solid gold rim + inner stardust drifts slowly clockwise
 *       (36s per quadrant), while outer stardust drifts gently counter-clockwise (46s per quadrant).
 *  2. SMOOTH 60 FPS 3D AERODYNAMIC RAVEN (ZERO JERKINESS, ZERO SIDEWAYS FLYING):
 *     - Every pass glides smoothly forward Left->Right or Right->Left along wide sweeping mountain arcs
 *       with gentle +/-18 deg aerodynamic climb/dive pitch (never doubles back vertically or flips).
 *     - Genuine 3D coming-and-going depth perspective: enters small over distant peaks (0.36x) and
 *       swoops large across the foreground (1.18x), or enters close and recedes into the storm sky.
 *     - Zero per-frame object/gradient allocations inside `onDraw()` for silky locked 60 FPS.
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
    private val flyFrames: Array<Bitmap?>

    private var scrolledCwBmp: Bitmap? = null
    private var scrolledCwCanvas: Canvas? = null
    private var scrolledCcwBmp: Bitmap? = null
    private var scrolledCcwCanvas: Canvas? = null

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ravenPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
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

    // 4-Quadrant tiled perimeter meshes (matches LivingPedestalDirector.kt orientation & 1:1 grain density!)
    private val quadCount = 4
    private val segsPerQuad = 36
    private val radSteps = 6
    private val quadMeshes = Array(quadCount) { FloatArray((segsPerQuad + 1) * (radSteps + 1) * 2) }
    private var builtW = -1
    private var builtH = -1

    private data class RainDrop(var x: Float, var y: Float, val len: Float, val speed: Float, val alpha: Float)
    private val rainDrops = List(58) {
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

    // Smooth unidirectional aerodynamic 3D Raven pass state (NEVER loops vertically or flies sideways!)
    private var passDir = 1 // +1 = Left->Right, -1 = Right->Left
    private var passElapsed = 0f
    private var passDur = 7.8f
    private var restCooldown = 0f
    private var yStart = 0.24f
    private var yCtrl1 = 0.14f
    private var yCtrl2 = 0.36f
    private var yEnd = 0.22f
    private var depthStart = 0.42f
    private var depthEnd = 1.12f
    private var prevRavenPxX = 0f
    private var prevRavenPxY = 0f
    private var hasPrevPos = false
    private var smoothPitchDeg = 0f
    private var wingPhase = 0f
    private var gliding = false
    private var glideTimer = 0f
    private var nextGlideIn = 2.6f
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
        flyFrames = arrayOf(
            decodeSafe(R.drawable.raven_fly_up, opts),
            decodeSafe(R.drawable.raven_fly_glide, opts),
            decodeSafe(R.drawable.raven_fly_down, opts),
        )
        rimCwSrc?.let { src ->
            scrolledCwBmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            scrolledCwCanvas = Canvas(scrolledCwBmp!!)
        }
        dustCcwSrc?.let { src ->
            scrolledCcwBmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            scrolledCcwCanvas = Canvas(scrolledCcwBmp!!)
        }
        startFreshAerodynamicPass(initialEntry = true)
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
        for (b in listOf(wideBaseBmp, tallBaseBmp, rimCwSrc, dustCcwSrc, scrolledCwBmp, scrolledCcwBmp)) {
            try { b?.recycle() } catch (_: Exception) {}
        }
        for (b in wideBolts) { try { b?.recycle() } catch (_: Exception) {} }
        for (b in tallBolts) { try { b?.recycle() } catch (_: Exception) {} }
        for (b in flyFrames) { try { b?.recycle() } catch (_: Exception) {} }
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

        // Pre-build rune glow RadialGradient ONCE (zero per-frame allocation inside onDraw!)
        val rcx = innerClipRect.left + innerClipRect.width() * 0.50f
        val rcy = innerClipRect.top + innerClipRect.height() * 0.63f
        runeAuraPaint.shader = RadialGradient(
            rcx, rcy, minDim * 0.22f,
            Color.argb(46, 254, 215, 102),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )

        // EXACT LivingPedestalDirector.kt L1075-1085 orientation:
        // row=0 (y=0..55 solid 24k metallic rim) hugs inner picture edge (innerTuck = -2dp),
        // row=radSteps (y=56..223 sparkling outer stardust) sprays OUTWARD to +outerSpan!
        val innerTuck = -dp(2.0f)
        val outerSpan = pad * 0.98f - innerTuck

        for (q in 0 until quadCount) {
            val verts = quadMeshes[q]
            val uStart = q.toFloat() / quadCount.toFloat()
            val uSpan = 1f / quadCount.toFloat()
            var k = 0
            for (row in 0..radSteps) {
                val rf = row.toFloat() / radSteps.toFloat()
                val dist = innerTuck + outerSpan * rf
                for (col in 0..segsPerQuad) {
                    val u = uStart + (col.toFloat() / segsPerQuad.toFloat()) * uSpan
                    val pt = sampleRoundedBorderAndNormal(u, l, t, r, b, cornerR)
                    verts[k++] = pt[0] + pt[2] * dist
                    verts[k++] = pt[1] + pt[3] * dist
                }
            }
        }
    }

    private fun startFreshAerodynamicPass(initialEntry: Boolean = false) {
        passDir = if (initialEntry) 1 else (if (rng.nextBoolean()) 1 else -1)
        passElapsed = if (initialEntry) 1.4f else 0f
        restCooldown = if (initialEntry) 0f else (0.7f + rng.nextFloat() * 1.4f)

        // Choose one of 3 natural 3D flight profiles (coming closer, receding into mountains, or valley cruise)
        val mode = rng.nextInt(3)
        when (mode) {
            0 -> { // Coming from distant snowy peaks -> sweeping close across foreground
                depthStart = 0.36f + rng.nextFloat() * 0.14f
                depthEnd = 1.02f + rng.nextFloat() * 0.24f
                passDur = 7.4f + rng.nextFloat() * 2.4f
                yStart = 0.14f + rng.nextFloat() * 0.22f
                yEnd = 0.22f + rng.nextFloat() * 0.34f
            }
            1 -> { // Entering large in foreground -> climbing & receding into distant storm clouds
                depthStart = 1.00f + rng.nextFloat() * 0.22f
                depthEnd = 0.36f + rng.nextFloat() * 0.14f
                passDur = 7.2f + rng.nextFloat() * 2.2f
                yStart = 0.26f + rng.nextFloat() * 0.28f
                yEnd = 0.12f + rng.nextFloat() * 0.20f
            }
            else -> { // Mid-valley majestic patrol cruise
                val d = 0.56f + rng.nextFloat() * 0.26f
                depthStart = d
                depthEnd = (d + (rng.nextFloat() - 0.5f) * 0.24f).coerceIn(0.42f, 0.96f)
                passDur = 8.0f + rng.nextFloat() * 2.6f
                yStart = 0.16f + rng.nextFloat() * 0.34f
                yEnd = (yStart + (rng.nextFloat() - 0.5f) * 0.22f).coerceIn(0.12f, 0.54f)
            }
        }
        // Gentle aerodynamic vertical arc (strictly modest climb/dip so Raven NEVER flies sideways!)
        val midY = (yStart + yEnd) * 0.5f
        yCtrl1 = (midY + (rng.nextFloat() - 0.5f) * 0.24f).coerceIn(0.09f, 0.62f)
        yCtrl2 = (midY + (rng.nextFloat() - 0.5f) * 0.24f).coerceIn(0.09f, 0.62f)
        hasPrevPos = false
        smoothPitchDeg = 0f
        gliding = false
        nextGlideIn = 2.0f + rng.nextFloat() * 2.0f
    }

    private fun evalCubic(t: Float, a: Float, b: Float, c: Float, d: Float): Float {
        val u = 1f - t
        return u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c + t * t * t * d
    }

    override fun onDraw(canvas: Canvas) {
        val wi = width; val hi = height
        if (wi <= 2 || hi <= 2) return
        rebuildGeometryOnResize(wi, hi)

        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastFrameMs).coerceIn(1L, 45L)).toFloat() / 1000f
        lastFrameMs = now
        val elapsed = (now - startElapsedMs).coerceAtLeast(0L)
        val tSec = elapsed / 1000f
        val wf = wi.toFloat(); val hf = hi.toFloat()
        val diagDim = hypot(wf.toDouble(), hf.toDouble()).toFloat()

        // 0. Pure obsidian backdrop behind outer gold stardust halo
        canvas.drawColor(0xFF020305.toInt())

        // 1. Clipped Inner Viewport: FULL-CLARITY EDGE-TO-EDGE VALLEY + REAL PAINTED LIGHTNING + SMOOTH 3D RAVEN
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

            // 2. REAL PAINTED LIGHTNING ILLUMINATION (3 INDEPENDENT SKY ZONES)
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

            // 3. BREATHING GOLDEN PEDESTAL RUNES (ZERO ALLOCATION) + FINE STORM RAIN
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

        // 4. SMOOTH 60 FPS 3D AERODYNAMIC RAVEN (NEVER FLIES SIDEWAYS, NEVER JERKS!)
        if (restCooldown > 0f) {
            restCooldown -= dt
        } else {
            passElapsed += dt
            val t = passElapsed / passDur
            if (t >= 1f) {
                startFreshAerodynamicPass(initialEntry = false)
            } else {
                // Smooth forward horizontal travel strictly off-screen left (-0.22) <-> off-screen right (+1.22)
                val xNorm = if (passDir > 0) (-0.22f + t * 1.44f) else (1.22f - t * 1.44f)
                val yNorm = evalCubic(t, yStart, yCtrl1, yCtrl2, yEnd)
                val cx = innerClipRect.left + xNorm * fw
                val baseCy = innerClipRect.top + yNorm * fh

                // Natural aerodynamic wingbeat + glide timing
                nextGlideIn -= dt
                if (!gliding && nextGlideIn <= 0f) {
                    gliding = true
                    glideTimer = 1.3f + rng.nextFloat() * 1.5f
                }
                if (gliding) {
                    glideTimer -= dt
                    if (glideTimer <= 0f) {
                        gliding = false
                        nextGlideIn = 2.4f + rng.nextFloat() * 2.2f
                    }
                } else {
                    wingPhase = (wingPhase + dt * 1.65f) % 1f
                }

                // Subtle smooth lift bob synced to wing downstroke
                val liftBobPx = if (gliding) 0f else (sin(wingPhase * 2f * PI.toFloat()) * dp(2.2f))
                val cy = baseCy + liftBobPx

                if (hasPrevPos) {
                    val vPxX = (cx - prevRavenPxX) / dt
                    val vPxY = (cy - prevRavenPxY) / dt
                    // Strictly gentle forward climb/dive angle (max +/- 18 deg — NEVER sideways!)
                    val targetPitch = (atan2(vPxY.toDouble(), max(30f, abs(vPxX)).toDouble()) * 180.0 / PI * 0.72)
                        .toFloat().coerceIn(-18f, 18f)
                    smoothPitchDeg += (targetPitch - smoothPitchDeg) * min(1f, dt * 4.5f)
                }
                prevRavenPxX = cx
                prevRavenPxY = cy
                hasPrevPos = true

                val frameIdx = if (gliding) 1 else when ((wingPhase * 4f).toInt().coerceIn(0, 3)) {
                    0 -> 0
                    2 -> 2
                    else -> 1
                }
                val wingScaleY = if (gliding) 1f else (0.96f + 0.05f * sin(wingPhase * 2f * PI.toFloat()))
                val birdBmp = flyFrames.getOrNull(frameIdx) ?: flyFrames.firstOrNull { it != null }
                if (birdBmp != null) {
                    val smoothT = t * t * (3f - 2f * t)
                    val depth = depthStart + (depthEnd - depthStart) * smoothT
                    val sizePx = diagDim * 0.16f * depth
                    // Subtle atmospheric depth fade when soaring far away over the misty mountain peaks
                    ravenPaint.alpha = (212 + (43 * ((depth - 0.35f) / 0.85f).coerceIn(0f, 1f))).toInt()
                    val flipX = if (passDir > 0) 1f else -1f
                    canvas.save()
                    canvas.translate(cx, cy)
                    canvas.scale(flipX, wingScaleY)
                    canvas.rotate(smoothPitchDeg)
                    birdDstRect.set(-sizePx * 0.5f, -sizePx * 0.5f, sizePx * 0.5f, sizePx * 0.5f)
                    canvas.drawBitmap(birdBmp, null, birdDstRect, ravenPaint)
                    canvas.restore()
                }
            }
        }

        canvas.restore() // end inner valley clip

        // 5. EXACT FLOATING-ICON GOLD FRAME + CALM DUAL-DIRECTION STARDUST (4x TILED, ZERO CHAOTIC ANTS, ZERO DRIPPING!)
        val cwPhase = (elapsed % 36000L).toFloat() / 36000f
        val ccwPhase = 1f - ((elapsed % 46000L).toFloat() / 46000f)
        scrollStrip(rimCwSrc, scrolledCwCanvas, cwPhase)
        scrollStrip(dustCcwSrc, scrolledCcwCanvas, ccwPhase)

        val pulse = 0.88f + 0.12f * sin(tSec * 1.7f)
        scrolledCwBmp?.let { cwStrip ->
            ringMeshPaint.alpha = (255f * pulse).toInt().coerceIn(220, 255)
            for (q in 0 until quadCount) {
                canvas.drawBitmapMesh(cwStrip, segsPerQuad, radSteps, quadMeshes[q], 0, null, 0, ringMeshPaint)
            }
        }
        scrolledCcwBmp?.let { ccwStrip ->
            dustMeshPaint.alpha = (195f * (0.82f + 0.18f * sin(tSec * 2.2f + 1.2f))).toInt().coerceIn(145, 215)
            for (q in 0 until quadCount) {
                canvas.drawBitmapMesh(ccwStrip, segsPerQuad, radSteps, quadMeshes[q], 0, null, 0, dustMeshPaint)
            }
        }

        if (running) postInvalidateOnAnimation()
    }
}

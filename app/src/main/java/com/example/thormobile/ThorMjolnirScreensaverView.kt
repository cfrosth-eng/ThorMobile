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
 * WO-159 V2: Living Mjolnir Storm Universal Screensaver View (Note 20 Ultra Portrait & Landscape).
 *
 * Upgraded strictly to Boss's exact visual inspection feedback:
 *  1. REAL PAINTED LIGHTNING ONLY: Illuminates the actual lightning bolts painted inside the sky of the
 *     Wide & Tall valley masters via 3 independent extracted RGBA glow zones (zero synthetic added bolts).
 *  2. EXACT FLOATING-ICON GOLD (ZERO FAKE DOTS, ZERO DRIPPING): Warps the real photographic 1536x224
 *     `raven_gold_ring_strip.png` around the rounded screen border with `canvas.drawBitmapMesh` scrolling
 *     clockwise (18.2s) + `raven_gold_dust_outer_strip.png` counter-scrolling counter-clockwise in ADD mode!
 *  3. TRUE 3D COMING-AND-GOING RAVEN: Sized off screen diagonal so it is bold and proportional in BOTH
 *     Portrait and Landscape, swooping smoothly from distant mountain peaks (0.38x) to dramatic foreground
 *     passes (1.42x) and flying organically off and back onto the screen.
 *  4. CRYSTAL-CLEAR EDGE-TO-EDGE 21:9 LANDSCAPE & 9:16 PORTRAIT: Zero shaded ends, zero top cropping.
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
    private val ringStripBmp: Bitmap?
    private val dustStripBmp: Bitmap?
    private val flyFrames: Array<Bitmap?>

    private var scrolledCwBmp: Bitmap? = null
    private var scrolledCwCanvas: Canvas? = null
    private var scrolledCcwBmp: Bitmap? = null
    private var scrolledCcwCanvas: Canvas? = null

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stripDrawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ringMeshPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dustMeshPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val boltAddPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val runeAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    private val clipPath = Path()
    private val innerClipRect = RectF()
    private val drawDstRect = RectF()

    // Dual-flank perimeter mesh arrays (exact LivingPedestalDirector.kt drawBitmapMesh technique!)
    private val segs = 128
    private val radSteps = 6
    private val meshVerts = FloatArray((segs + 1) * (radSteps + 1) * 2)
    private var builtW = -1
    private var builtH = -1

    private data class RainDrop(var x: Float, var y: Float, val len: Float, val speed: Float, val alpha: Float)
    private val rainDrops = List(64) {
        RainDrop(
            x = rng.nextFloat(),
            y = rng.nextFloat(),
            len = 0.018f + rng.nextFloat() * 0.026f,
            speed = 0.72f + rng.nextFloat() * 0.52f,
            alpha = 0.045f + rng.nextFloat() * 0.075f,
        )
    }

    // 3 Independent real-painted lightning zone strike timers
    private class RealBoltStrike(var nextAtMs: Long, var startedAtMs: Long, var durationMs: Long, var peakPower: Float)
    private var startElapsedMs = SystemClock.elapsedRealtime()
    private var lastFrameMs = startElapsedMs
    private val strikes = List(3) { i ->
        RealBoltStrike(
            nextAtMs = startElapsedMs + 900L + i * 1950L + rng.nextLong(1800L),
            startedAtMs = -99999L,
            durationMs = 460L,
            peakPower = 1.0f,
        )
    }

    // 3D Coming-and-Going Raven Bezier flight state (includes off-screen entry/exit & real depth swoops)
    private var p0x = -0.15f; private var p0y = 0.24f
    private var p1x = 0.30f; private var p1y = 0.14f
    private var p2x = 0.70f; private var p2y = 0.44f
    private var p3x = 0.88f; private var p3y = 0.22f
    private var segT = 0f
    private var segDur = 5.4f
    private var depth0 = 0.44f
    private var depth1 = 1.18f
    private var ravenX = -0.15f
    private var ravenY = 0.24f
    private var facingSign = 1f
    private var smoothPitchDeg = 0f
    private var wingPhase = 0f
    private var gliding = false
    private var glideTimer = 0f
    private var nextGlideIn = 2.8f
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
        ringStripBmp = decodeSafe(R.drawable.raven_gold_ring_strip, opts)
        dustStripBmp = decodeSafe(R.drawable.raven_gold_dust_outer_strip, opts)
        flyFrames = arrayOf(
            decodeSafe(R.drawable.raven_fly_up, opts),
            decodeSafe(R.drawable.raven_fly_glide, opts),
            decodeSafe(R.drawable.raven_fly_down, opts),
        )
        ringStripBmp?.let { src ->
            scrolledCwBmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            scrolledCwCanvas = Canvas(scrolledCwBmp!!)
        }
        dustStripBmp?.let { src ->
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
        for (b in listOf(wideBaseBmp, tallBaseBmp, ringStripBmp, dustStripBmp, scrolledCwBmp, scrolledCcwBmp)) {
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

    /**
     * Exact rounded-rectangle point & outward unit normal sampler for wrapping `raven_gold_ring_strip.png`
     * cleanly around all 4 sides & 4 corners of the screen with ZERO seams and ZERO fake dots!
     */
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

    private fun rebuildPerimeterMeshIfNeeded(w: Int, h: Int) {
        if (w == builtW && h == builtH) return
        builtW = w; builtH = h
        val minDim = min(w, h).toFloat()
        val pad = max(dp(22f), minDim * 0.056f)
        val cornerR = max(dp(22f), minDim * 0.044f)
        val l = pad; val t = pad; val r = w - pad; val b = h - pad
        innerClipRect.set(l + dp(2f), t + dp(2f), r - dp(2f), b - dp(2f))

        // Map strip rows v=0 (outermost sparkling stardust) -> v=1 (inner edge against artwork)
        val outerReach = pad * 1.02f
        val innerReach = max(dp(5f), minDim * 0.014f)

        var k = 0
        for (row in 0..radSteps) {
            val v = row.toFloat() / radSteps.toFloat()
            // v=0 is outer dust tip (+outerReach along normal), v=1 is inner edge (-innerReach)
            val radialOff = outerReach * (1f - v) - innerReach * v
            for (col in 0..segs) {
                val u = col.toFloat() / segs.toFloat()
                val pt = sampleRoundedBorderAndNormal(u, l, t, r, b, cornerR)
                meshVerts[k++] = pt[0] + pt[2] * radialOff
                meshVerts[k++] = pt[1] + pt[3] * radialOff
            }
        }
    }

    private fun pickNext3DFlightSegment() {
        val sx = p3x; val sy = p3y
        // Occasionally let the Raven fly completely off-screen and re-enter from a fresh mountain edge (true coming & going!)
        val wentOffscreen = sx < -0.08f || sx > 1.08f || sy < -0.08f
        if (wentOffscreen) {
            val fromLeft = rng.nextBoolean()
            p0x = if (fromLeft) -0.18f else 1.18f
            p0y = 0.12f + rng.nextFloat() * 0.42f
            val goingClose = rng.nextBoolean()
            depth0 = if (goingClose) 0.40f + rng.nextFloat() * 0.18f else 1.05f + rng.nextFloat() * 0.30f
            depth1 = if (goingClose) 1.05f + rng.nextFloat() * 0.38f else 0.38f + rng.nextFloat() * 0.20f
            p1x = if (fromLeft) 0.22f + rng.nextFloat() * 0.22f else 0.56f + rng.nextFloat() * 0.22f
            p1y = 0.08f + rng.nextFloat() * 0.55f
            p2x = 0.20f + rng.nextFloat() * 0.60f
            p2y = 0.10f + rng.nextFloat() * 0.64f
            p3x = if (fromLeft) 0.65f + rng.nextFloat() * 0.26f else 0.10f + rng.nextFloat() * 0.26f
            p3y = 0.12f + rng.nextFloat() * 0.54f
            segT = 0f
            segDur = 4.6f + rng.nextFloat() * 3.0f
            return
        }

        val tx = p3x - p2x; val ty = p3y - p2y
        val c1x = (sx + tx * 0.68f).coerceIn(-0.08f, 1.08f)
        val c1y = (sy + ty * 0.68f).coerceIn(0.05f, 0.85f)

        // 30% chance to sweep dramatically off-screen into the storm clouds, 70% chance to weave through the valley
        val exitNow = rng.nextFloat() < 0.30f
        var ex: Float
        var ey: Float
        if (exitNow) {
            ex = if (sx < 0.5f) 1.18f else -0.18f
            ey = 0.08f + rng.nextFloat() * 0.46f
        } else {
            ex = 0.10f + rng.nextFloat() * 0.80f
            ey = 0.10f + rng.nextFloat() * 0.68f
            for (t in 0 until 6) {
                if (hypot((ex - sx).toDouble(), (ey - sy).toDouble()) >= 0.42) break
                ex = 0.08f + rng.nextFloat() * 0.84f
                ey = 0.10f + rng.nextFloat() * 0.68f
            }
        }
        p0x = sx; p0y = sy; p1x = c1x; p1y = c1y
        p2x = 0.12f + rng.nextFloat() * 0.76f; p2y = 0.08f + rng.nextFloat() * 0.72f
        p3x = ex; p3y = ey
        segT = 0f
        segDur = 4.0f + rng.nextFloat() * 3.4f
        depth0 = depth1
        // True coming-and-going 3D depth: alternates between distant mountain soar (0.38..0.62) and close foreground pass (0.98..1.42)
        depth1 = if (depth0 > 0.82f) {
            0.38f + rng.nextFloat() * 0.28f
        } else {
            0.96f + rng.nextFloat() * 0.46f
        }
    }

    private fun evalCubic(t: Float, a: Float, b: Float, c: Float, d: Float): Float {
        val u = 1f - t
        return u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c + t * t * t * d
    }

    override fun onDraw(canvas: Canvas) {
        val wi = width; val hi = height
        if (wi <= 2 || hi <= 2) return
        val w = wi.toFloat(); val h = hi.toFloat()
        rebuildPerimeterMeshIfNeeded(wi, hi)

        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastFrameMs).coerceIn(1L, 60L)).toFloat() / 1000f
        lastFrameMs = now
        val elapsed = (now - startElapsedMs).coerceAtLeast(0L)
        val tSec = elapsed / 1000f
        val minDim = min(w, h)
        val diagDim = hypot(w.toDouble(), h.toDouble()).toFloat()
        val cornerR = max(dp(22f), minDim * 0.044f)

        // 0. Pure obsidian gallery canvas behind floating-icon gold stardust halo
        canvas.drawColor(0xFF020305.toInt())

        // 1. Clipped Inner Viewport: FULL-CLARITY EDGE-TO-EDGE 21:9 LANDSCAPE / 9:16 PORTRAIT VALLEY
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(
            innerClipRect,
            (cornerR - dp(2f)).coerceAtLeast(6f),
            (cornerR - dp(2f)).coerceAtLeast(6f),
            Path.Direction.CW,
        )
        canvas.clipPath(clipPath)

        val fw = innerClipRect.width()
        val fh = innerClipRect.height()
        val isLand = wi >= hi
        val activeBase = if (isLand) (wideBaseBmp ?: tallBaseBmp) else (tallBaseBmp ?: wideBaseBmp)
        val activeBolts = if (isLand) wideBolts else tallBolts

        if (activeBase != null) {
            // Exact edge-to-edge full-clarity cover (with true 21:9 wide master, landscape has ZERO shaded ends & ZERO top chop!)
            val scale = max(fw / activeBase.width.toFloat(), fh / activeBase.height.toFloat())
            val dw = activeBase.width * scale
            val dh = activeBase.height * scale
            val left = innerClipRect.left + (fw - dw) * 0.5f
            val top = innerClipRect.top + (fh - dh) * 0.5f
            drawDstRect.set(left, top, left + dw, top + dh)
            canvas.drawBitmap(activeBase, null, drawDstRect, bmpPaint)

            // 2. REAL PAINTED LIGHTNING ILLUMINATION (3 INDEPENDENT SKY ZONES LIGHTING UP THE ACTUAL BOLTS IN THE PICTURE!)
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
                            canvas.drawBitmap(boltBmp, null, drawDstRect, boltAddPaint)
                            if (env > 0.65f) {
                                // Second crisp hot-core pass at strike peak for genuine electric brilliance
                                boltAddPaint.alpha = ((env - 0.55f) * 210f).toInt().coerceIn(0, 180)
                                canvas.drawBitmap(boltBmp, null, drawDstRect, boltAddPaint)
                            }
                        }
                    }
                }
            }

            // 3. BREATHING GOLDEN PEDESTAL RUNES + SUBTLE STORM RAIN
            val runePulse = 0.55f + 0.45f * sin(tSec * 1.9f)
            val rcx = innerClipRect.left + fw * 0.50f
            val rcy = innerClipRect.top + fh * 0.63f
            runeAuraPaint.shader = RadialGradient(
                rcx, rcy, minDim * 0.22f,
                Color.argb(((0.06f + 0.09f * runePulse) * 255f).toInt().coerceIn(0, 78), 254, 215, 102),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(innerClipRect, runeAuraPaint)

            rainPaint.strokeWidth = max(1f, dp(0.55f))
            for (rd in rainDrops) {
                rd.y += rd.speed * dt; rd.x += rd.speed * dt * 0.16f
                if (rd.y > 1.05f) { rd.y = -0.05f; rd.x = rng.nextFloat() }
                if (rd.x > 1.05f) rd.x -= 1.05f
                val x1 = innerClipRect.left + rd.x * fw
                val y1 = innerClipRect.top + rd.y * fh
                val a = (rd.alpha * (1f + peakFlash * 1.9f) * 255f).toInt().coerceIn(0, 105)
                rainPaint.color = Color.argb(a, 224, 236, 250)
                canvas.drawLine(x1, y1, x1 + rd.len * fh * 0.16f, y1 + rd.len * fh, rainPaint)
            }
        }

        // 4. TRUE 3D COMING-AND-GOING RAVEN (PROPORTIONAL IN BOTH PORTRAIT & LANDSCAPE!)
        segT += dt / segDur
        if (segT >= 1f) pickNext3DFlightSegment()
        val prevX = ravenX; val prevY = ravenY
        ravenX = evalCubic(segT, p0x, p1x, p2x, p3x)
        ravenY = evalCubic(segT, p0y, p1y, p2y, p3y)
        val vx = (ravenX - prevX) / dt
        val vy = (ravenY - prevY) / dt
        if (abs(vx) > 0.008f) facingSign = if (vx >= 0f) 1f else -1f
        val targetPitchDeg = (atan2(vy.toDouble(), max(0.04f, abs(vx)).toDouble()) * 180.0 / PI * 0.55)
            .toFloat().coerceIn(-22f, 22f)
        smoothPitchDeg += (targetPitchDeg - smoothPitchDeg) * min(1f, dt * 6f)

        nextGlideIn -= dt
        if (!gliding && nextGlideIn <= 0f) { gliding = true; glideTimer = 1.0f + rng.nextFloat() * 1.4f }
        if (gliding) {
            glideTimer -= dt
            if (glideTimer <= 0f) { gliding = false; nextGlideIn = 2.4f + rng.nextFloat() * 2.6f }
        } else {
            wingPhase = (wingPhase + dt * (if (vy < -0.02f) 2.45f else 1.80f)) % 1f
        }

        val frameIdx = if (gliding) 1 else when ((wingPhase * 4f).toInt().coerceIn(0, 3)) {
            0 -> 0; 2 -> 2; else -> 1
        }
        val wingScaleY = if (gliding) 1f else (0.94f + 0.08f * sin(wingPhase * 2f * PI.toFloat()))
        val birdBmp = flyFrames.getOrNull(frameIdx) ?: flyFrames.firstOrNull { it != null }
        if (birdBmp != null) {
            // Smooth ease on perspective depth (0.38x distant sky soar -> 1.42x dramatic foreground swoop)
            val depth = depth0 + (depth1 - depth0) * (segT * segT * (3f - 2f * segT))
            // Scaled off DIAGONAL so the Raven stays bold and well-proportioned even when holding phone upright!
            val sizePx = diagDim * 0.165f * depth
            val cx = innerClipRect.left + ravenX * fw
            val cy = innerClipRect.top + ravenY * fh
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(facingSign, wingScaleY)
            canvas.rotate(smoothPitchDeg)
            drawDstRect.set(-sizePx * 0.5f, -sizePx * 0.5f, sizePx * 0.5f, sizePx * 0.5f)
            canvas.drawBitmap(birdBmp, null, drawDstRect, bmpPaint)
            canvas.restore()
        }

        canvas.restore() // end inner valley clip

        // 5. EXACT FLOATING-ICON GOLD FRAME + DUAL-DIRECTION PHOTOGRAPHIC STARDUST HALO (ZERO FAKE DOTS, ZERO DRIPPING!)
        val cwPhase = (elapsed % 18200L).toFloat() / 18200f
        val ccwPhase = 1f - ((elapsed % 24500L).toFloat() / 24500f)
        scrollStrip(ringStripBmp, scrolledCwCanvas, cwPhase)
        scrollStrip(dustStripBmp, scrolledCcwCanvas, ccwPhase)

        val pulse = 0.86f + 0.14f * sin(tSec * 2.1f)
        scrolledCwBmp?.let { cwStrip ->
            ringMeshPaint.alpha = (255f * pulse).toInt().coerceIn(215, 255)
            canvas.drawBitmapMesh(cwStrip, segs, radSteps, meshVerts, 0, null, 0, ringMeshPaint)
        }
        scrolledCcwBmp?.let { ccwStrip ->
            dustMeshPaint.alpha = (225f * (0.80f + 0.20f * sin(tSec * 2.9f + 1.4f))).toInt().coerceIn(150, 240)
            canvas.drawBitmapMesh(ccwStrip, segs, radSteps, meshVerts, 0, null, 0, dustMeshPaint)
        }

        if (running) postInvalidateOnAnimation()
    }
}

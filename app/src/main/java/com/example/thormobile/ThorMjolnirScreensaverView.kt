package com.example.thormobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
 * WO-159 Polished: Living Mjolnir Storm Universal Screensaver View (Note 20 Ultra Portrait & Landscape).
 *
 * Fixes verified from Boss's live visual inspection:
 *  - Uncropped vertical-fit valley framing over darkened storm backdrop so Mjolnir's top & pedestal base
 *    are NEVER cut off or zoomed-in in Portrait (1440x3088) OR Ultra-Wide Landscape (3088x1440).
 *  - Real dark obsidian border margin (~6.8% minDim) outside the bold 24k metallic gold rim so all 260
 *    multi-size sparkling gold dust grains visibly swirl in BOTH directions (CW & CCW) with ZERO dripping.
 *  - Bright opposing 4-point star-flare glints gliding smoothly along the 18.2s clockwise gold rim.
 *  - 4 independent randomized branching sky lightning bolts + cloud flashes + storm rain + breathing runes +
 *    free-flying Raven across the entire valley.
 */
class ThorMjolnirScreensaverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val GOLD_SHADOW = 0xFF3A2203.toInt()
        private const val GOLD_WARM = 0xFFEAB308.toInt()
        private const val GOLD_GLINT = 0xFFFEF08A.toInt()
    }

    private val rng = Random(System.currentTimeMillis())

    private val wideValleyBmp: Bitmap?
    private val tallValleyBmp: Bitmap?
    private val ringStripBmp: Bitmap?
    private val flyFrames: Array<Bitmap?>

    private var scrolledStripBmp: Bitmap? = null
    private var scrolledStripCanvas: Canvas? = null
    private var ringShader: BitmapShader? = null
    private val ringMatrix = Matrix()

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimBgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 110 }
    private val seamFadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = GOLD_SHADOW }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = GOLD_WARM }
    private val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = GOLD_GLINT }
    private val dustPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val flarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val spikePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val skyFlashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val runeAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val boltHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val boltCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private val clipPath = Path()
    private val boltPath = Path()
    private val innerClipRect = RectF()
    private val frameRect = RectF()
    private val outerRect = RectF()
    private val outerBevelRect = RectF()
    private val innerBevelRect = RectF()
    private val drawDstRect = RectF()
    private val backdropRect = RectF()

    private data class DustMote(
        val pos01: Float,
        val bandFrac: Float,
        val radiusDp: Float,
        val speed: Float,
        val dir: Float,
        val twinklePhase: Float,
        val bright: Boolean,
        val hasHalo: Boolean,
    )

    private val dustMotes = List(260) { idx ->
        val h1 = (idx * 37 + 11) % 97
        val h2 = (idx * 53 + 23) % 89
        val h3 = (idx * 71 + 7) % 83
        val band = Math.pow((h2 / 89.0), 1.25).toFloat()
        DustMote(
            pos01 = (idx.toFloat() / 260f) + (h1 / 97f) * 0.006f,
            bandFrac = 0.08f + band * 0.84f,
            radiusDp = if (h3 < 20) 2.55f else if (h3 < 58) 1.65f else 1.0f,
            speed = 0.010f + (h1 / 97f) * 0.024f,
            dir = if (idx % 2 == 0) 1f else -1f, // Dual-direction CW & CCW
            twinklePhase = (h3 / 83f) * (2f * PI.toFloat()),
            bright = h2 > 36,
            hasHalo = h3 < 28,
        )
    }

    private data class RainDrop(var x: Float, var y: Float, val len: Float, val speed: Float, val alpha: Float)
    private val rainDrops = List(70) {
        RainDrop(
            x = rng.nextFloat(),
            y = rng.nextFloat(),
            len = 0.020f + rng.nextFloat() * 0.030f,
            speed = 0.70f + rng.nextFloat() * 0.55f,
            alpha = 0.06f + rng.nextFloat() * 0.095f,
        )
    }

    private data class BoltGeom(
        val trunk: List<Pair<Float, Float>>,
        val branches: List<List<Pair<Float, Float>>>,
        val originX: Float,
        val originY: Float,
        val isWarmGold: Boolean,
    )

    private class BoltSlot(
        val slot: Int,
        var nextAtMs: Long,
        var startedAtMs: Long,
        var durationMs: Long,
        var geom: BoltGeom,
    )

    private fun buildBoltGeometry(slot: Int): BoltGeom {
        val zMin = when (slot % 4) { 0 -> 0.06f; 1 -> 0.24f; 2 -> 0.54f; else -> 0.72f }
        val zMax = when (slot % 4) { 0 -> 0.28f; 1 -> 0.46f; 2 -> 0.76f; else -> 0.94f }
        val sx = zMin + rng.nextFloat() * (zMax - zMin)
        val endY = 0.32f + rng.nextFloat() * 0.24f
        val steps = 11 + rng.nextInt(5)
        val trunk = ArrayList<Pair<Float, Float>>(steps + 1)
        trunk.add(sx to 0f)
        var cx = sx
        val drift = (rng.nextFloat() - 0.5f) * 0.14f
        for (s in 1..steps) {
            val f = s.toFloat() / steps.toFloat()
            cx += (drift / steps) + (rng.nextFloat() - 0.5f) * 0.048f
            trunk.add(cx to (f * endY))
        }
        val branches = ArrayList<List<Pair<Float, Float>>>()
        val bCnt = 2 + rng.nextInt(2)
        for (b in 0 until bCnt) {
            val baseIdx = (2 + rng.nextInt(max(1, trunk.size - 4))).coerceIn(1, trunk.size - 2)
            var (bx, by) = trunk[baseIdx]
            val segs = ArrayList<Pair<Float, Float>>()
            segs.add(bx to by)
            val side = (if (b % 2 == 0) 1f else -1f) * (0.018f + rng.nextFloat() * 0.025f)
            val bSteps = 4 + rng.nextInt(3)
            for (k in 1..bSteps) {
                bx += side + (rng.nextFloat() - 0.5f) * 0.018f
                by += 0.022f + rng.nextFloat() * 0.022f
                segs.add(bx to by)
            }
            branches.add(segs)
        }
        val warmGold = (slot == 0 || slot == 2) && rng.nextFloat() < 0.65f
        return BoltGeom(trunk, branches, sx, endY * 0.45f, warmGold)
    }

    private var startElapsedMs = SystemClock.elapsedRealtime()
    private var lastFrameMs = startElapsedMs
    private val bolts = List(4) { i ->
        BoltSlot(
            slot = i,
            nextAtMs = startElapsedMs + 1100L + i * 1800L + rng.nextLong(2200L),
            startedAtMs = -99999L,
            durationMs = 400L,
            geom = buildBoltGeometry(i),
        )
    }

    private var p0x = 0.14f; private var p0y = 0.28f
    private var p1x = 0.38f; private var p1y = 0.12f
    private var p2x = 0.68f; private var p2y = 0.52f
    private var p3x = 0.86f; private var p3y = 0.24f
    private var segT = 0f
    private var segDur = 4.8f
    private var depth0 = 0.76f
    private var depth1 = 1.04f
    private var ravenX = 0.14f
    private var ravenY = 0.28f
    private var facingSign = 1f
    private var smoothPitchDeg = 0f
    private var wingPhase = 0f
    private var gliding = false
    private var glideTimer = 0f
    private var nextGlideIn = 3.0f
    private var running = false

    init {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        wideValleyBmp = decodeSafe(R.drawable.mjolnir_valley_wide_hd, opts)
        tallValleyBmp = decodeSafe(R.drawable.mjolnir_valley_tall_hd, opts)
        ringStripBmp = decodeSafe(R.drawable.raven_gold_ring_strip, opts)
        flyFrames = arrayOf(
            decodeSafe(R.drawable.raven_fly_up, opts),
            decodeSafe(R.drawable.raven_fly_glide, opts),
            decodeSafe(R.drawable.raven_fly_down, opts),
        )
        ringStripBmp?.let { src ->
            scrolledStripBmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            scrolledStripCanvas = Canvas(scrolledStripBmp!!)
            ringShader = BitmapShader(scrolledStripBmp!!, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
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
        for (i in bolts.indices) {
            bolts[i].nextAtMs = startElapsedMs + 1000L + i * 1700L + rng.nextLong(2100L)
        }
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    fun releaseBitmaps() {
        running = false
        try { scrolledStripBmp?.recycle() } catch (_: Exception) {}
        try { wideValleyBmp?.recycle() } catch (_: Exception) {}
        try { tallValleyBmp?.recycle() } catch (_: Exception) {}
        try { ringStripBmp?.recycle() } catch (_: Exception) {}
        for (b in flyFrames) { try { b?.recycle() } catch (_: Exception) {} }
    }

    private fun updateScrolledRingStrip(phase01: Float) {
        val src = ringStripBmp ?: return
        val c = scrolledStripCanvas ?: return
        val w = src.width
        val shift = ((phase01 - floor(phase01)) * w).toInt().coerceIn(0, w - 1)
        c.drawBitmap(src, -shift.toFloat(), 0f, null)
        if (shift > 0) c.drawBitmap(src, (w - shift).toFloat(), 0f, null)
    }

    private fun pickNextFlightSegment() {
        val sx = p3x; val sy = p3y
        val tx = p3x - p2x; val ty = p3y - p2y
        val c1x = (sx + tx * 0.72f).coerceIn(0.05f, 0.95f)
        val c1y = (sy + ty * 0.72f).coerceIn(0.07f, 0.86f)
        var ex = 0.09f + rng.nextFloat() * 0.82f
        var ey = 0.09f + rng.nextFloat() * 0.74f
        for (t in 0 until 6) {
            if (hypot((ex - sx).toDouble(), (ey - sy).toDouble()) >= 0.38) break
            ex = 0.08f + rng.nextFloat() * 0.84f
            ey = 0.09f + rng.nextFloat() * 0.76f
        }
        p0x = sx; p0y = sy; p1x = c1x; p1y = c1y
        p2x = 0.10f + rng.nextFloat() * 0.80f; p2y = 0.08f + rng.nextFloat() * 0.76f
        p3x = ex; p3y = ey; segT = 0f; segDur = 3.6f + rng.nextFloat() * 3.4f
        depth0 = depth1; depth1 = 0.56f + (ey * 0.42f) + rng.nextFloat() * 0.18f
    }

    private fun evalCubic(t: Float, a: Float, b: Float, c: Float, d: Float): Float {
        val u = 1f - t
        return u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c + t * t * t * d
    }

    private fun pointOnRoundedRect(t01: Float, l: Float, t: Float, r: Float, b: Float, cr: Float): Pair<Float, Float> {
        val rw = (r - l - 2f * cr).coerceAtLeast(1f)
        val rh = (b - t - 2f * cr).coerceAtLeast(1f)
        val arc = (PI.toFloat() * 0.5f) * cr
        val perim = 2f * rw + 2f * rh + 4f * arc
        var frac = t01 - floor(t01)
        if (frac < 0f) frac += 1f
        var d = frac * perim
        if (d <= rw) return (l + cr + d) to t
        d -= rw
        if (d <= arc) { val a = -PI.toFloat() * 0.5f + d / cr; return (r - cr + cos(a) * cr) to (t + cr + sin(a) * cr) }
        d -= arc
        if (d <= rh) return r to (t + cr + d)
        d -= rh
        if (d <= arc) { val a = d / cr; return (r - cr + cos(a) * cr) to (b - cr + sin(a) * cr) }
        d -= arc
        if (d <= rw) return (r - cr - d) to b
        d -= rw
        if (d <= arc) { val a = PI.toFloat() * 0.5f + d / cr; return (l + cr + cos(a) * cr) to (b - cr + sin(a) * cr) }
        d -= arc
        if (d <= rh) return l to (b - cr - d)
        d -= rh
        val a = PI.toFloat() + d / cr
        return (l + cr + cos(a) * cr) to (t + cr + sin(a) * cr)
    }

    private fun drawPolyline(canvas: Canvas, pts: List<Pair<Float, Float>>, left: Float, top: Float, fw: Float, fh: Float, paint: Paint) {
        if (pts.size < 2) return
        boltPath.reset()
        boltPath.moveTo(left + pts[0].first * fw, top + pts[0].second * fh)
        for (i in 1 until pts.size) {
            boltPath.lineTo(left + pts[i].first * fw, top + pts[i].second * fh)
        }
        canvas.drawPath(boltPath, paint)
    }

    private fun drawStarFlare(canvas: Canvas, x: Float, y: Float, radius: Float, alpha01: Float) {
        flarePaint.shader = RadialGradient(
            x, y, radius,
            intArrayOf(
                Color.argb((alpha01 * 252f).toInt().coerceIn(0, 255), 255, 254, 238),
                Color.argb((alpha01 * 205f).toInt().coerceIn(0, 255), 254, 240, 138),
                Color.argb((alpha01 * 76f).toInt().coerceIn(0, 255), 234, 179, 8),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.24f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(x, y, radius, flarePaint)
        spikePaint.color = Color.argb((alpha01 * 240f).toInt().coerceIn(0, 255), 255, 253, 230)
        spikePaint.strokeWidth = max(dp(1.2f), radius * 0.055f)
        val sLong = radius * 1.60f
        val sShort = radius * 0.94f
        canvas.drawLine(x - sLong, y, x + sLong, y, spikePaint)
        canvas.drawLine(x, y - sShort, x, y + sShort, spikePaint)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 2f || h <= 2f) return

        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastFrameMs).coerceIn(1L, 60L)).toFloat() / 1000f
        lastFrameMs = now
        val elapsed = (now - startElapsedMs).coerceAtLeast(0L)
        val tSec = elapsed / 1000f

        // REAL OBSIDIAN BORDER MARGIN + BOLD 24K GOLD FRAME SO OUTER GOLD DUST SHINES CLEARLY!
        val minDim = min(w, h)
        val outerPad = max(dp(26f), minDim * 0.068f)
        val ringThick = max(dp(7.2f), minDim * 0.0185f)
        val cornerR = max(dp(20f), minDim * 0.042f)

        frameRect.set(outerPad, outerPad, w - outerPad, h - outerPad)
        outerRect.set(
            frameRect.left - ringThick * 0.52f,
            frameRect.top - ringThick * 0.52f,
            frameRect.right + ringThick * 0.52f,
            frameRect.bottom + ringThick * 0.52f,
        )
        outerBevelRect.set(
            frameRect.left - ringThick * 0.26f,
            frameRect.top - ringThick * 0.26f,
            frameRect.right + ringThick * 0.26f,
            frameRect.bottom + ringThick * 0.26f,
        )
        innerBevelRect.set(
            frameRect.left + ringThick * 0.34f,
            frameRect.top + ringThick * 0.34f,
            frameRect.right - ringThick * 0.34f,
            frameRect.bottom - ringThick * 0.34f,
        )
        innerClipRect.set(
            frameRect.left + ringThick * 0.30f,
            frameRect.top + ringThick * 0.30f,
            frameRect.right - ringThick * 0.30f,
            frameRect.bottom - ringThick * 0.30f,
        )

        // 0. Pure obsidian background behind outer gold dust
        canvas.drawColor(0xFF020305.toInt())

        // 1. Clipped Inner Viewport: UNCROPPED VALLEY ARTWORK (Full Mjolnir & Mountains in BOTH Orientations!)
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
        val activeBg = if (w >= h) (wideValleyBmp ?: tallValleyBmp) else (tallValleyBmp ?: wideValleyBmp)
        activeBg?.let { bg ->
            // Dimmed full-bleed backdrop eliminates hard black bars on ultra-wide phone landscape (3088x1440)
            val covScale = max(fw / bg.width.toFloat(), fh / bg.height.toFloat())
            val cw = bg.width * covScale
            val ch = bg.height * covScale
            backdropRect.set(
                innerClipRect.left + (fw - cw) * 0.5f,
                innerClipRect.top + (fh - ch) * 0.5f,
                innerClipRect.left + (fw + cw) * 0.5f,
                innerClipRect.top + (fh + ch) * 0.5f,
            )
            canvas.drawBitmap(bg, null, backdropRect, dimBgPaint)

            // Primary sharp uncropped vertical-fit valley (NEVER crops Mjolnir's top or pedestal base!)
            val fitScale = fh / bg.height.toFloat()
            val dw = bg.width * fitScale
            val dh = bg.height * fitScale
            val maxPanX = max(0f, (dw - fw) * 0.5f)
            val panX = sin(tSec * 0.08f) * maxPanX * 0.45f
            val left = innerClipRect.left + (fw - dw) * 0.5f + panX
            val top = innerClipRect.top + (fh - dh) * 0.5f
            drawDstRect.set(left, top, left + dw, top + dh)
            canvas.drawBitmap(bg, null, drawDstRect, bmpPaint)

            // Soft feathered storm-shadow seam on left/right when screen is wider than 16:9 (phone landscape)
            if (dw < fw - 4f) {
                val seamW = min(dp(42f), dw * 0.12f)
                seamFadePaint.shader = LinearGradient(
                    left, 0f, left + seamW, 0f,
                    Color.argb(195, 6, 10, 18), Color.TRANSPARENT, Shader.TileMode.CLAMP,
                )
                canvas.drawRect(left, innerClipRect.top, left + seamW, innerClipRect.bottom, seamFadePaint)

                seamFadePaint.shader = LinearGradient(
                    left + dw - seamW, 0f, left + dw, 0f,
                    Color.TRANSPARENT, Color.argb(195, 6, 10, 18), Shader.TileMode.CLAMP,
                )
                canvas.drawRect(left + dw - seamW, innerClipRect.top, left + dw, innerClipRect.bottom, seamFadePaint)
            }
        }

        // 2. 4 INDEPENDENT BRANCHING SKY LIGHTNING BOLTS & STORM CLOUD FLASHES
        var peakFlash = 0f
        for (b in bolts) {
            if (now >= b.nextAtMs) {
                b.startedAtMs = now
                b.durationMs = 310L + rng.nextLong(240L)
                b.geom = buildBoltGeometry(b.slot)
                b.nextAtMs = now + 2200L + rng.nextLong(5200L)
            }
            val age = now - b.startedAtMs
            if (age in 0L..b.durationMs) {
                val p = age.toFloat() / b.durationMs.toFloat()
                val env = when {
                    p < 0.14f -> p / 0.14f
                    p < 0.30f -> 1f - ((p - 0.14f) / 0.16f) * 0.72f
                    p < 0.46f -> 0.28f + ((p - 0.30f) / 0.16f) * 0.68f
                    else -> (0.96f * (1f - (p - 0.46f) / 0.54f)).coerceAtLeast(0f)
                }
                if (env > 0.02f) {
                    peakFlash = max(peakFlash, env)
                    val ox = innerClipRect.left + b.geom.originX * fw
                    val oy = innerClipRect.top + b.geom.originY * fh
                    skyFlashPaint.shader = RadialGradient(
                        ox, oy, minDim * 0.72f,
                        if (b.geom.isWarmGold) Color.argb((env * 88f).toInt().coerceIn(0, 125), 254, 243, 199)
                        else Color.argb((env * 92f).toInt().coerceIn(0, 130), 224, 242, 254),
                        Color.TRANSPARENT,
                        Shader.TileMode.CLAMP,
                    )
                    canvas.drawRect(innerClipRect, skyFlashPaint)

                    boltHaloPaint.color = if (b.geom.isWarmGold) {
                        Color.argb((env * 120f).toInt().coerceIn(0, 165), 253, 224, 71)
                    } else {
                        Color.argb((env * 125f).toInt().coerceIn(0, 170), 147, 197, 253)
                    }
                    boltCorePaint.color = Color.argb((env * 250f).toInt().coerceIn(0, 255), 255, 255, 248)

                    boltHaloPaint.strokeWidth = max(dp(3.6f), minDim * 0.010f)
                    boltCorePaint.strokeWidth = max(dp(1.2f), minDim * 0.0028f)
                    drawPolyline(canvas, b.geom.trunk, innerClipRect.left, innerClipRect.top, fw, fh, boltHaloPaint)
                    drawPolyline(canvas, b.geom.trunk, innerClipRect.left, innerClipRect.top, fw, fh, boltCorePaint)

                    boltHaloPaint.strokeWidth = max(dp(2.1f), minDim * 0.0054f)
                    boltCorePaint.strokeWidth = max(dp(0.75f), minDim * 0.0016f)
                    for (br in b.geom.branches) {
                        drawPolyline(canvas, br, innerClipRect.left, innerClipRect.top, fw, fh, boltHaloPaint)
                        drawPolyline(canvas, br, innerClipRect.left, innerClipRect.top, fw, fh, boltCorePaint)
                    }
                }
            }
        }

        // 3. BREATHING GOLDEN PEDESTAL RUNE AURA + FINE STORM RAIN
        val runePulse = 0.55f + 0.45f * sin(tSec * 1.9f)
        val rcx = innerClipRect.left + fw * 0.50f
        val rcy = innerClipRect.top + fh * 0.62f
        runeAuraPaint.shader = RadialGradient(
            rcx, rcy, minDim * 0.24f,
            Color.argb(((0.07f + 0.09f * runePulse) * 255f).toInt().coerceIn(0, 82), 254, 215, 102),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(innerClipRect, runeAuraPaint)

        rainPaint.strokeWidth = max(1f, dp(0.6f))
        for (rd in rainDrops) {
            rd.y += rd.speed * dt; rd.x += rd.speed * dt * 0.16f
            if (rd.y > 1.05f) { rd.y = -0.05f; rd.x = rng.nextFloat() }
            if (rd.x > 1.05f) rd.x -= 1.05f
            val x1 = innerClipRect.left + rd.x * fw
            val y1 = innerClipRect.top + rd.y * fh
            val a = (rd.alpha * (1f + peakFlash * 1.8f) * 255f).toInt().coerceIn(0, 115)
            rainPaint.color = Color.argb(a, 220, 232, 248)
            canvas.drawLine(x1, y1, x1 + rd.len * fh * 0.16f, y1 + rd.len * fh, rainPaint)
        }

        // 4. FREE-FLYING RAVEN ACROSS THE ENTIRE VALLEY
        segT += dt / segDur
        if (segT >= 1f) pickNextFlightSegment()
        val prevX = ravenX; val prevY = ravenY
        ravenX = evalCubic(segT, p0x, p1x, p2x, p3x)
        ravenY = evalCubic(segT, p0y, p1y, p2y, p3y)
        val vx = (ravenX - prevX) / dt
        val vy = (ravenY - prevY) / dt
        if (abs(vx) > 0.008f) facingSign = if (vx >= 0f) 1f else -1f
        val targetPitchDeg = (atan2(vy.toDouble(), max(0.04f, abs(vx)).toDouble()) * 180.0 / PI * 0.58)
            .toFloat().coerceIn(-24f, 24f)
        smoothPitchDeg += (targetPitchDeg - smoothPitchDeg) * min(1f, dt * 6f)

        nextGlideIn -= dt
        if (!gliding && nextGlideIn <= 0f) { gliding = true; glideTimer = 0.95f + rng.nextFloat() * 1.4f }
        if (gliding) {
            glideTimer -= dt
            if (glideTimer <= 0f) { gliding = false; nextGlideIn = 2.5f + rng.nextFloat() * 2.8f }
        } else {
            wingPhase = (wingPhase + dt * (if (vy < -0.02f) 2.45f else 1.85f)) % 1f
        }

        val frameIdx = if (gliding) 1 else when ((wingPhase * 4f).toInt().coerceIn(0, 3)) {
            0 -> 0; 2 -> 2; else -> 1
        }
        val wingScaleY = if (gliding) 1f else (0.94f + 0.08f * sin(wingPhase * 2f * PI.toFloat()))
        val birdBmp = flyFrames.getOrNull(frameIdx) ?: flyFrames.firstOrNull { it != null }
        if (birdBmp != null) {
            val depth = depth0 + (depth1 - depth0) * segT
            val sizePx = minDim * 0.27f * depth
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

        // 5. BOLD 24K GOLDEN PERIMETER FRAME + WARM HALO + 260 DUAL-DIRECTION OUTER GOLD DUST + STAR FLARES
        val pulse = 0.72f + 0.28f * sin(tSec * 2.1f)
        val dustSpan = max(dp(12f), outerPad - ringThick * 0.65f)

        // 5a. Warm golden ambient glow halo outside the frame
        outerGlowPaint.color = Color.argb(((0.22f + 0.08f * pulse) * 255f).toInt().coerceIn(0, 100), 234, 179, 8)
        outerGlowPaint.strokeWidth = ringThick * 2.1f
        canvas.drawRoundRect(outerRect, cornerR + dp(2f), cornerR + dp(2f), outerGlowPaint)

        // 5b. 260 Sparkling Gold Dust Grains visibly orbiting BOTH directions strictly outside the gold frame
        for (dm in dustMotes) {
            val p01 = dm.pos01 + dm.dir * dm.speed * tSec
            val off = dm.bandFrac * dustSpan
            val (px, py) = pointOnRoundedRect(
                p01,
                outerRect.left - off,
                outerRect.top - off,
                outerRect.right + off,
                outerRect.bottom + off,
                cornerR + off * 0.28f,
            )
            val tw = 0.34f + 0.66f * (0.5f + 0.5f * sin(tSec * 3.6f + dm.twinklePhase))
            val rPx = dp(dm.radiusDp)
            if (dm.hasHalo) {
                dustPaint.color = Color.argb((tw * 80f).toInt().coerceIn(0, 100), 234, 179, 8)
                canvas.drawCircle(px, py, rPx * 2.3f, dustPaint)
            }
            dustPaint.color = if (dm.bright) GOLD_GLINT else GOLD_WARM
            dustPaint.alpha = (tw * (if (dm.bright) 250f else 205f)).toInt().coerceIn(0, 255)
            canvas.drawCircle(px, py, rPx, dustPaint)
        }

        // 5c. Deep bronze shadow recess + bold 18.2s clockwise rotating metallic 24k gold rim + specular bevels
        shadowPaint.strokeWidth = ringThick * 1.24f
        canvas.drawRoundRect(frameRect, cornerR, cornerR, shadowPaint)

        val ringPhase = (elapsed % 18200L).toFloat() / 18200f
        updateScrolledRingStrip(ringPhase)
        val sh = ringShader
        val strip = scrolledStripBmp
        if (sh != null && strip != null) {
            ringMatrix.reset()
            ringMatrix.setScale(w / strip.width.toFloat(), h / strip.height.toFloat())
            sh.setLocalMatrix(ringMatrix)
            framePaint.shader = sh
        } else {
            framePaint.shader = null
            framePaint.color = GOLD_WARM
        }
        framePaint.alpha = (255f * (0.96f + 0.04f * pulse)).toInt().coerceIn(0, 255)
        framePaint.strokeWidth = ringThick
        canvas.drawRoundRect(frameRect, cornerR, cornerR, framePaint)

        bevelPaint.strokeWidth = max(dp(1.2f), ringThick * 0.16f)
        bevelPaint.alpha = (145 + (65 * pulse).toInt()).coerceIn(0, 255)
        canvas.drawRoundRect(outerBevelRect, cornerR + dp(1f), cornerR + dp(1f), bevelPaint)

        bevelPaint.strokeWidth = max(dp(1.2f), ringThick * 0.18f)
        bevelPaint.alpha = (165 + (68 * pulse).toInt()).coerceIn(0, 255)
        canvas.drawRoundRect(innerBevelRect, (cornerR - dp(2f)).coerceAtLeast(4f), (cornerR - dp(2f)).coerceAtLeast(4f), bevelPaint)

        // Two opposing bright 4-point star-flare glints gliding along the gold rim
        val flareRadius = max(dp(16f), minDim * 0.046f)
        val (gxA, gyA) = pointOnRoundedRect(ringPhase + 0.08f, frameRect.left, frameRect.top, frameRect.right, frameRect.bottom, cornerR)
        val (gxB, gyB) = pointOnRoundedRect(ringPhase + 0.58f, frameRect.left, frameRect.top, frameRect.right, frameRect.bottom, cornerR)
        drawStarFlare(canvas, gxA, gyA, flareRadius, 0.85f + 0.15f * pulse)
        drawStarFlare(canvas, gxB, gyB, flareRadius * 0.94f, 0.78f + 0.18f * (1f - pulse * 0.4f))

        if (running) postInvalidateOnAnimation()
    }
}

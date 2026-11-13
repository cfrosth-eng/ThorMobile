package com.example.thormobile

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.SystemClock
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

object VikingPopupOverlayManager {
    private const val TAG = "VikingPopupHerald"
    @Volatile private var polling = false
    private var appCtx: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null

    // Full Message / Upgrade Scroll Card Window
    private var popupView: View? = null
    private var popupParams: WindowManager.LayoutParams? = null

    // Edge Recall Shield (for Approved Upgrades Undo)
    private var recallBadgeView: TextView? = null
    private var recallBadgeParams: WindowManager.LayoutParams? = null

    // Stage-1 Living Raven V3 Herald Window (WO-145: 1280x1595 Ultra-HD, Clockwise Floating-Icon Gold Ring, 4 Random Sky Bolts & Shimmering Gold Pedestal Runes)
    private var livingRavenView: LivingRavenCrestContainer? = null
    private var livingRavenParams: WindowManager.LayoutParams? = null
    private var ravenBoltDrawable: IndependentRavenBoltFieldDrawable? = null
    private var ravenRunesShimmerDrawable: ShimmeringPedestalRunesDrawable? = null
    private var ravenLoopAnimator: ValueAnimator? = null
    private var ravenBaseBmp: Bitmap? = null
    private var ravenRingStripBmp: Bitmap? = null
    private var ravenRunesBmp: Bitmap? = null
    private var ravenBoltCores: Array<Bitmap?> = arrayOfNulls(4)
    private var ravenBoltHalos: Array<Bitmap?> = arrayOfNulls(4)

    private var runeHeaderView: TextView? = null
    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var progressBar: ProgressBar? = null
    private var pctLabelView: TextView? = null
    private var approveBtn: Button? = null
    private var declineBtn: Button? = null
    private var undoBtn: Button? = null
    private var ravenThumbCard: FrameLayout? = null
    private var ravenThumbImage: ImageView? = null
    private var ravenLinksContainer: LinearLayout? = null

    @Volatile private var dismissedStagedKey: String = ""
    @Volatile private var dismissedShieldPatchKey: String = ""
    @Volatile private var currentEventKey: String = ""
    @Volatile private var currentPatchKey: String = ""
    @Volatile private var latestPatchTitle: String = "Active Upgrade"

    @Volatile private var lastRavenKey: String = ""
    @Volatile private var lastRavenCleanBase: String = "https://thor-api-443941171993.us-east4.run.app"
    @Volatile private var lastRavenTitle: String = ""
    @Volatile private var lastRavenMessage: String = ""
    @Volatile private var lastRavenLinks: JSONArray? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * WO-145: 4 Independent Random Sky Lightning Bolt Channels.
     * Each bolt fork across the stormy sky schedules its own random strike interval, duration,
     * and local top-to-bottom stepped leader + double return-stroke plasma flash so bolts NEVER drop in unison.
     */
    private class IndependentRavenBoltFieldDrawable(
        private val cores: Array<Bitmap?>,
        private val halos: Array<Bitmap?>
    ) : Drawable() {
        private val normRects = arrayOf(
                RectF(0.00000f, 0.06207f, 0.44609f, 0.56677f),
                RectF(0.31562f, 0.00000f, 0.77578f, 0.22696f),
                RectF(0.30469f, 0.26583f, 0.77656f, 0.49843f),
                RectF(0.67812f, 0.19875f, 1.00000f, 0.56489f)
        )
        private val haloPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val corePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val dstRect = RectF()
        private val startMs = LongArray(4)
        private val durMs = LongArray(4)
        private val nextAtMs = LongArray(4)
        private var lastAnyLaunchMs = 0L

        init {
            seedInitialSchedule(SystemClock.elapsedRealtime())
        }

        fun seedInitialSchedule(nowMs: Long) {
            val firstBolt = Random.nextInt(4)
            lastAnyLaunchMs = nowMs
            for (i in 0 until 4) {
                startMs[i] = 0L
                durMs[i] = Random.nextLong(520L, 840L)
                nextAtMs[i] = if (i == firstBolt) {
                    nowMs + Random.nextLong(90L, 240L)
                } else {
                    nowMs + Random.nextLong(850L + i * 520L, 2600L + i * 780L)
                }
            }
        }

        fun tick(nowMs: Long): Float {
            var maxSurge = 0f
            for (i in 0 until 4) {
                val st = startMs[i]
                val dur = durMs[i]
                val active = st > 0L && (nowMs - st) <= dur
                if (!active && nowMs >= nextAtMs[i]) {
                    if ((nowMs - lastAnyLaunchMs) < 310L) {
                        nextAtMs[i] = lastAnyLaunchMs + Random.nextLong(340L, 920L)
                    } else {
                        startMs[i] = nowMs
                        durMs[i] = Random.nextLong(480L, 860L)
                        lastAnyLaunchMs = nowMs
                        nextAtMs[i] = nowMs + durMs[i] + Random.nextLong(1550L, 5200L)
                    }
                }
                val stNow = startMs[i]
                if (stNow > 0L) {
                    val elapsed = nowMs - stNow
                    if (elapsed in 0L..durMs[i]) {
                        val p = (elapsed.toFloat() / durMs[i].toFloat()) * 1.32f
                        val s = when {
                            p in 0.64f..0.94f -> 1.0f
                            p < 0.64f -> (p / 0.64f) * 0.42f
                            else -> ((1.32f - p) / 0.38f).coerceIn(0f, 1f) * 0.55f
                        }
                        if (s > maxSurge) maxSurge = s
                    } else if (elapsed > durMs[i]) {
                        startMs[i] = 0L
                    }
                }
            }
            invalidateSelf()
            return maxSurge
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
                val dur = durMs[i].coerceAtLeast(300L)
                val elapsed = nowMs - st
                if (elapsed < 0L || elapsed > dur) continue

                val coreBmp = cores[i] ?: continue
                val haloBmp = halos[i] ?: continue
                val nr = normRects[i]
                dstRect.set(
                    b.left + nr.left * bw,
                    b.top + nr.top * bh,
                    b.left + nr.right * bw,
                    b.top + nr.bottom * bh
                )
                val boltH = dstRect.height()
                val p = (elapsed.toFloat() / dur.toFloat()) * 1.32f

                if (p <= 0.66f) {
                    val rawNorm = (p / 0.66f).coerceIn(0f, 1f)
                    val steppedNorm = ((rawNorm * 7f).toInt() + 1).toFloat() / 7f
                    val frontY = dstRect.top + boltH * steppedNorm.coerceIn(0f, 1f)
                    val leaderTop = (frontY - (boltH * 0.22f).coerceAtLeast(10f)).coerceAtLeast(dstRect.top)

                    haloPaint.alpha = 205
                    corePaint.alpha = 235
                    canvas.save()
                    canvas.clipRect(dstRect.left, dstRect.top, dstRect.right, frontY)
                    canvas.drawBitmap(haloBmp, null, dstRect, haloPaint)
                    canvas.drawBitmap(coreBmp, null, dstRect, corePaint)
                    canvas.restore()

                    haloPaint.alpha = 255
                    corePaint.alpha = 255
                    canvas.save()
                    canvas.clipRect(dstRect.left, leaderTop, dstRect.right, frontY)
                    canvas.drawBitmap(haloBmp, null, dstRect, haloPaint)
                    canvas.drawBitmap(coreBmp, null, dstRect, corePaint)
                    canvas.drawBitmap(coreBmp, null, dstRect, corePaint)
                    canvas.restore()
                } else if (p <= 0.94f) {
                    val crackle = when {
                        p in 0.75f..0.81f -> 0.35f
                        p in 0.86f..0.90f -> 0.72f
                        else -> 1.0f
                    }
                    haloPaint.alpha = (255f * crackle).toInt()
                    corePaint.alpha = (255f * crackle).toInt()
                    canvas.drawBitmap(haloBmp, null, dstRect, haloPaint)
                    canvas.drawBitmap(coreBmp, null, dstRect, corePaint)
                    if (crackle >= 0.95f) {
                        canvas.drawBitmap(coreBmp, null, dstRect, corePaint)
                    }
                } else {
                    val fadeNorm = ((p - 0.94f) / 0.38f).coerceIn(0f, 1f)
                    val tailTopY = dstRect.top + boltH * fadeNorm * 0.45f
                    val alpha = ((1f - fadeNorm) * 255f).toInt().coerceIn(0, 255)
                    haloPaint.alpha = alpha
                    corePaint.alpha = alpha
                    canvas.save()
                    canvas.clipRect(dstRect.left, tailTopY, dstRect.right, dstRect.bottom)
                    canvas.drawBitmap(haloBmp, null, dstRect, haloPaint)
                    canvas.drawBitmap(coreBmp, null, dstRect, corePaint)
                    canvas.restore()
                }
            }
        }

        override fun setAlpha(alpha: Int) { corePaint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { corePaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * WO-145: Steady Floating-Icon Gold Pedestal Runes (#B6862C / #F1C833) with Continuous Specular Shimmer.
     * Replaces whole-layer pulsating with a smooth travelling 24k/#FFF791 highlight glint masked strictly
     * inside the carved rune strokes via PorterDuff.Mode.SRC_ATOP.
     */
    private class ShimmeringPedestalRunesDrawable(
        private val runesBitmap: Bitmap
    ) : Drawable() {
        private val normRect = RectF(0.32031f, 0.61129f, 0.62813f, 0.89530f)
        private val basePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            alpha = 248
        }
        private val shimmerMaskPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            alpha = 255
        }
        private val shimmerSweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
        private val dstRect = RectF()
        var shimmerPhase: Float = 0f
            set(value) {
                field = value
                invalidateSelf()
            }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val bw = b.width().toFloat()
            val bh = b.height().toFloat()
            dstRect.set(
                b.left + normRect.left * bw,
                b.top + normRect.top * bh,
                b.left + normRect.right * bw,
                b.top + normRect.bottom * bh
            )

            canvas.drawBitmap(runesBitmap, null, dstRect, basePaint)

            val rw = dstRect.width()
            val rh = dstRect.height()
            val layerId = canvas.saveLayer(dstRect, null)
            canvas.drawBitmap(runesBitmap, null, dstRect, shimmerMaskPaint)

            val p1 = (shimmerPhase % 1f + 1f) % 1f
            val sweepStartX = dstRect.left - rw * 0.65f + (rw * 2.30f) * p1
            val sweepStartY = dstRect.top - rh * 0.25f + (rh * 0.70f) * p1
            val bandW = rw * 0.44f
            val bandH = rh * 0.30f

            shimmerSweepPaint.shader = LinearGradient(
                sweepStartX, sweepStartY,
                sweepStartX + bandW, sweepStartY + bandH,
                intArrayOf(
                    0x00B6862C,
                    0x66F1C833,
                    0xEEFFF791.toInt(),
                    0xFFFFFBEB.toInt(),
                    0xEEFFF791.toInt(),
                    0x66F1C833,
                    0x00B6862C
                ),
                floatArrayOf(0f, 0.24f, 0.44f, 0.50f, 0.56f, 0.76f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(dstRect, shimmerSweepPaint)

            val p2 = ((shimmerPhase * 1.618f + 0.47f) % 1f + 1f) % 1f
            val glX = dstRect.right + rw * 0.45f - (rw * 1.90f) * p2
            val glY = dstRect.top + (rh * 0.85f) * p2
            shimmerSweepPaint.shader = LinearGradient(
                glX, glY,
                glX + rw * 0.30f, glY + rh * 0.22f,
                intArrayOf(0x00B6862C, 0x99FFF791.toInt(), 0x00B6862C),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(dstRect, shimmerSweepPaint)
            canvas.restoreToCount(layerId)
        }

        override fun setAlpha(alpha: Int) { basePaint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { basePaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * WO-145: Exact FloatingThorService `glow_ring` Gold Ring Container.
     * Maps the unwrapped `glow_ring.png` polar strip (identical gold color, gold stardust specks,
     * and two opposite glimmer highlight spots) conformally around the rounded Raven card using a
     * zero-seam GPU `drawBitmapMesh`, rotating clockwise at the exact 7000ms linear speed of the floating icon
     * with ZERO pulsating.
     */
    private class LivingRavenCrestContainer(
        ctx: Context,
        private val padPx: Int,
        private val cornerPx: Float,
        private val ringStripBitmap: Bitmap?
    ) : FrameLayout(ctx) {
        private val segCount = 128
        private val radSteps = 8
        private val meshVerts = FloatArray((segCount + 1) * (radSteps + 1) * 2)
        private val baseBx = FloatArray(segCount)
        private val baseBy = FloatArray(segCount)
        private val baseNx = FloatArray(segCount)
        private val baseNy = FloatArray(segCount)
        private var geometryReady = false

        private val ringPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            alpha = 245
        }

        var ringPulseAlpha: Float = 0.88f
            set(value) {
                field = value.coerceIn(0.45f, 1.0f)
                ringPaint.alpha = (field * 255f).toInt().coerceIn(0, 255)
                invalidate()
            }

        var ringRotationDeg: Float = 0f
            set(value) {
                field = ((value % 360f) + 360f) % 360f
                if (geometryReady) updateRotatedMeshVerts()
                invalidate()
            }

        init {
            setWillNotDraw(false)
            clipChildren = false
            clipToPadding = false
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > padPx * 2 + 10 && h > padPx * 2 + 10) {
                buildRoundedRectPerimeterLut(w.toFloat(), h.toFloat())
                geometryReady = true
                updateRotatedMeshVerts()
            }
        }

        private fun buildRoundedRectPerimeterLut(w: Float, h: Float) {
            val left = padPx.toFloat()
            val top = padPx.toFloat()
            val right = w - padPx.toFloat()
            val bottom = h - padPx.toFloat()
            val cardW = right - left
            val cardH = bottom - top
            val r = cornerPx.coerceAtMost(minOf(cardW, cardH) * 0.48f)

            val topLen = (cardW - 2f * r).coerceAtLeast(0f)
            val rightLen = (cardH - 2f * r).coerceAtLeast(0f)
            val botLen = topLen
            val leftLen = rightLen
            val arcLen = (0.5 * Math.PI * r).toFloat()
            val totalPerim = topLen + rightLen + botLen + leftLen + 4f * arcLen
            val halfRight = rightLen * 0.5f

            for (k in 0 until segCount) {
                var s = (k.toFloat() / segCount.toFloat()) * totalPerim
                var bx: Float
                var by: Float
                var nx: Float
                var ny: Float

                if (s < halfRight) {
                    bx = right
                    by = (top + r + halfRight) + s
                    nx = 1f; ny = 0f
                } else {
                    s -= halfRight
                    if (s < arcLen) {
                        val a = s / r
                        val ca = kotlin.math.cos(a.toDouble()).toFloat()
                        val sa = kotlin.math.sin(a.toDouble()).toFloat()
                        bx = (right - r) + r * ca
                        by = (bottom - r) + r * sa
                        nx = ca; ny = sa
                    } else {
                        s -= arcLen
                        if (s < botLen) {
                            bx = (right - r) - s
                            by = bottom
                            nx = 0f; ny = 1f
                        } else {
                            s -= botLen
                            if (s < arcLen) {
                                val a = (Math.PI * 0.5).toFloat() + (s / r)
                                val ca = kotlin.math.cos(a.toDouble()).toFloat()
                                val sa = kotlin.math.sin(a.toDouble()).toFloat()
                                bx = (left + r) + r * ca
                                by = (bottom - r) + r * sa
                                nx = ca; ny = sa
                            } else {
                                s -= arcLen
                                if (s < leftLen) {
                                    bx = left
                                    by = (bottom - r) - s
                                    nx = -1f; ny = 0f
                                } else {
                                    s -= leftLen
                                    if (s < arcLen) {
                                        val a = Math.PI.toFloat() + (s / r)
                                        val ca = kotlin.math.cos(a.toDouble()).toFloat()
                                        val sa = kotlin.math.sin(a.toDouble()).toFloat()
                                        bx = (left + r) + r * ca
                                        by = (top + r) + r * sa
                                        nx = ca; ny = sa
                                    } else {
                                        s -= arcLen
                                        if (s < topLen) {
                                            bx = (left + r) + s
                                            by = top
                                            nx = 0f; ny = -1f
                                        } else {
                                            s -= topLen
                                            if (s < arcLen) {
                                                val a = (Math.PI * 1.5).toFloat() + (s / r)
                                                val ca = kotlin.math.cos(a.toDouble()).toFloat()
                                                val sa = kotlin.math.sin(a.toDouble()).toFloat()
                                                bx = (right - r) + r * ca
                                                by = (top + r) + r * sa
                                                nx = ca; ny = sa
                                            } else {
                                                s -= arcLen
                                                bx = right
                                                by = (top + r) + s.coerceAtMost(halfRight)
                                                nx = 1f; ny = 0f
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                baseBx[k] = bx
                baseBy[k] = by
                baseNx[k] = nx
                baseNy[k] = ny
            }
        }

        private fun updateRotatedMeshVerts() {
            val phase = (ringRotationDeg / 360f) * segCount.toFloat()
            val innerTuckPx = -dpStatic(context, 1).toFloat() * 0.35f
            val outerSpanPx = padPx.toFloat() - innerTuckPx

            for (i in 0..segCount) {
                val pos = ((i % segCount).toFloat() + phase) % segCount.toFloat()
                val k0 = pos.toInt() % segCount
                val k1 = (k0 + 1) % segCount
                val frac = pos - pos.toInt().toFloat()

                val bx = baseBx[k0] + (baseBx[k1] - baseBx[k0]) * frac
                val by = baseBy[k0] + (baseBy[k1] - baseBy[k0]) * frac
                var nx = baseNx[k0] + (baseNx[k1] - baseNx[k0]) * frac
                var ny = baseNy[k0] + (baseNy[k1] - baseNy[k0]) * frac
                val nLen = kotlin.math.hypot(nx.toDouble(), ny.toDouble()).toFloat().coerceAtLeast(1e-4f)
                nx /= nLen
                ny /= nLen

                for (j in 0..radSteps) {
                    val rFrac = j.toFloat() / radSteps.toFloat()
                    val dist = innerTuckPx + outerSpanPx * rFrac
                    val vIdx = (j * (segCount + 1) + i) * 2
                    meshVerts[vIdx] = bx + nx * dist
                    meshVerts[vIdx + 1] = by + ny * dist
                }
            }
        }

        override fun dispatchDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 10f || h <= 10f) {
                super.dispatchDraw(canvas)
                return
            }

            val strip = ringStripBitmap
            if (strip != null && geometryReady) {
                canvas.drawBitmapMesh(strip, segCount, radSteps, meshVerts, 0, null, 0, ringPaint)
            }

            super.dispatchDraw(canvas)
        }
    }

    private fun dpStatic(ctx: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun decodeMipmappedResource(ctx: Context, resId: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeResource(ctx.resources, resId, opts)?.apply {
            setHasMipMap(true)
        }
    }

    private fun ensureRavenBitmapsLoaded(ctx: Context) {
        if (ravenBaseBmp != null && ravenRingStripBmp != null && ravenRunesBmp != null && ravenBoltCores[0] != null) return
        try {
            ravenBaseBmp = decodeMipmappedResource(ctx, R.drawable.raven_storm_base_hd)
            ravenRingStripBmp = decodeMipmappedResource(ctx, R.drawable.raven_gold_ring_strip)
            ravenRunesBmp = decodeMipmappedResource(ctx, R.drawable.raven_runes_gold_hd)
            val coreIds = intArrayOf(
                R.drawable.raven_bolt_0_core,
                R.drawable.raven_bolt_1_core,
                R.drawable.raven_bolt_2_core,
                R.drawable.raven_bolt_3_core
            )
            val haloIds = intArrayOf(
                R.drawable.raven_bolt_0_halo,
                R.drawable.raven_bolt_1_halo,
                R.drawable.raven_bolt_2_halo,
                R.drawable.raven_bolt_3_halo
            )
            for (i in 0 until 4) {
                ravenBoltCores[i] = decodeMipmappedResource(ctx, coreIds[i])
                ravenBoltHalos[i] = decodeMipmappedResource(ctx, haloIds[i])
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed decoding Ultra-HD Raven V3 bitmaps", e)
        }
    }

    @Volatile private var fastPollUntilEpochMs: Long = 0L
    @Volatile private var lastHandledResumeId: String = ""

    fun enableFastReplyWatchPoll(context: Context, serverBaseUrl: String, durationMs: Long = 35_000L) {
        fastPollUntilEpochMs = System.currentTimeMillis() + durationMs
        pollNow(context, serverBaseUrl)
    }

    fun pollNow(context: Context, serverBaseUrl: String) {
        appCtx = context.applicationContext
        val cleanBase = serverBaseUrl.trimEnd('/')
        if (!polling) {
            start(context, cleanBase)
        }
        thread(start = true, isDaemon = true, name = "VikingPopupImmediateCheck") {
            try {
                val req = Request.Builder()
                    .url("$cleanBase/api/pre-approval?action=status&t=${System.currentTimeMillis()}")
                    .header("User-Agent", "ThorVikingHerald/19.1 (Samsung-Note20Ultra)")
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    val txt = resp.body?.string() ?: ""
                    if (resp.isSuccessful && txt.isNotEmpty()) {
                        evaluateVaultState(cleanBase, txt)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun showDirectVoiceFailoverCard(context: Context, serverBaseUrl: String, reasonLabel: String, replyText: String) {
        appCtx = context.applicationContext
        val cleanBase = serverBaseUrl.trimEnd('/')
        mainHandler.post {
            try {
                clearRavenLinkButtons()
                showOrUpdateVikingShield(
                    cleanBase = cleanBase,
                    eventKey = "VOICE_FAILOVER_${System.currentTimeMillis()}",
                    headerText = "THOR VOICE BACKUP • $reasonLabel",
                    mainTitle = "Thor Reply (Visual Failover):",
                    subText = replyText.take(680),
                    progressPct = 100,
                    mode = "THOR_REPLY"
                )
                clearRavenLinkButtons()
            } catch (e: Exception) {
                Log.w(TAG, "Direct voice failover card note: ${e.message}")
            }
        }
    }

    fun showRavenCard(
        context: Context,
        serverBaseUrl: String,
        title: String,
        message: String,
        linksJson: JSONArray? = null,
        eventKeyOverride: String = ""
    ) {
        appCtx = context.applicationContext
        val cleanBase = serverBaseUrl.trimEnd('/')
        val rKey = eventKeyOverride.ifBlank { "RAVEN_${System.currentTimeMillis()}" }
        lastRavenKey = rKey
        lastRavenCleanBase = cleanBase
        lastRavenTitle = title.ifBlank { "Raven Message" }
        lastRavenMessage = message
        lastRavenLinks = linksJson
        currentEventKey = rKey
        RavenCenterActivity.recordIncomingRavenCardAsync(
            context = context,
            msgId = rKey,
            title = lastRavenTitle,
            body = message,
            linksJson = linksJson
        )
        mainHandler.post {
            try {
                hideVikingShield()
                showLivingRavenEmblem(cleanBase)
            } catch (e: Exception) {
                Log.w(TAG, "Living Raven arrival note: ${e.message}")
            }
        }
    }

    private fun openUnfoldedRavenMessageCard(cleanBase: String) {
        val ctx = appCtx ?: return
        hideLivingRavenEmblem()
        ensureRavenBitmapsLoaded(ctx)
        val safeTitle = cleanShortTitle(lastRavenTitle.ifBlank { "Raven Message" })
        showOrUpdateVikingShield(
            cleanBase = cleanBase,
            eventKey = lastRavenKey.ifBlank { "RAVEN_${System.currentTimeMillis()}" },
            headerText = "ᚦᚱ • RAVEN MESSAGE • HUGINN & MUNINN",
            mainTitle = safeTitle,
            subText = lastRavenMessage.take(640),
            progressPct = 100,
            mode = "RAVEN"
        )
        val tidyText = extractAndPopulateRavenLinks(lastRavenMessage, lastRavenLinks)
        subtitleView?.text = tidyText.take(620)
    }

    private fun showLivingRavenEmblem(cleanBase: String) {
        val ctx = appCtx ?: return
        if (!Settings.canDrawOverlays(ctx)) return
        ensureRavenBitmapsLoaded(ctx)
        val baseB = ravenBaseBmp ?: run {
            openUnfoldedRavenMessageCard(cleanBase)
            return
        }

        if (windowManager == null) {
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }

        if (livingRavenView == null) {
            val auraPad = dp(ctx, 24)
            val radiusPx = dp(ctx, 22).toFloat()
            val artW = dp(ctx, 136)
            val artH = dp(ctx, 170)
            val totalW = artW + auraPad * 2
            val totalH = artH + auraPad * 2

            val outerContainer = LivingRavenCrestContainer(ctx, auraPad, radiusPx, ravenRingStripBmp)

            val roundedCard = FrameLayout(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(artW, artH, Gravity.CENTER)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                    }
                }
                elevation = dp(ctx, 8).toFloat()
            }

            val baseImg = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(baseB)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            roundedCard.addView(baseImg)

            ravenRunesBmp?.let { rBmp ->
                val shimmerDrawable = ShimmeringPedestalRunesDrawable(rBmp)
                ravenRunesShimmerDrawable = shimmerDrawable
                val runesImg = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    setImageDrawable(shimmerDrawable)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                roundedCard.addView(runesImg)
            }

            val bDrawable = IndependentRavenBoltFieldDrawable(ravenBoltCores, ravenBoltHalos)
            ravenBoltDrawable = bDrawable
            val boltImg = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageDrawable(bDrawable)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            roundedCard.addView(boltImg)

            outerContainer.addView(roundedCard)

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // WO-145: Arrive in Top-Left corner right underneath the clock (Gravity.TOP | START, x = 0, y = 24dp)
            val rParams = WindowManager.LayoutParams(
                totalW,
                totalH,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = -dp(ctx, 4)
                y = dp(ctx, 20)
            }

            var downRawX = 0f
            var downRawY = 0f
            var startX = 0
            var startY = 0
            var moved = false

            outerContainer.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        startX = rParams.x
                        startY = rParams.y
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downRawX
                        val dy = ev.rawY - downRawY
                        if (abs(dx) > dp(ctx, 9) || abs(dy) > dp(ctx, 9)) {
                            moved = true
                            val screenW = ctx.resources.displayMetrics.widthPixels
                            val screenH = ctx.resources.displayMetrics.heightPixels
                            rParams.x = (startX + dx.toInt()).coerceIn(-dp(ctx, 80), screenW - dp(ctx, 60))
                            rParams.y = (startY + dy.toInt()).coerceIn(dp(ctx, 24), screenH - dp(ctx, 140))
                            try { windowManager?.updateViewLayout(v, rParams) } catch (_: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val totalDx = ev.rawX - downRawX
                        val screenW = ctx.resources.displayMetrics.widthPixels
                        if (!moved) {
                            openUnfoldedRavenMessageCard(lastRavenCleanBase)
                        } else if (
                            (totalDx < -dp(ctx, 95) && rParams.x <= dp(ctx, 8)) ||
                            (totalDx > dp(ctx, 135) && rParams.x >= screenW - totalW - dp(ctx, 12))
                        ) {
                            dismissedStagedKey = lastRavenKey
                            triggerServerActionBg(lastRavenCleanBase, "dismiss_reply")
                            hideLivingRavenEmblem()
                        }
                        true
                    }
                    else -> false
                }
            }

            livingRavenView = outerContainer
            livingRavenParams = rParams
        }

        val rv = livingRavenView ?: return
        val lp = livingRavenParams

        if (rv.parent == null) {
            if (lp != null) {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = -dp(ctx, 4)
                lp.y = dp(ctx, 20)
            }
            ravenBoltDrawable?.seedInitialSchedule(SystemClock.elapsedRealtime())
            rv.alpha = 0f
            rv.scaleX = 0.88f
            rv.scaleY = 0.88f
            try {
                windowManager?.addView(rv, lp)
                rv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220L).start()
            } catch (e: Exception) {
                Log.e(TAG, "Failed adding Living Raven V3 overlay", e)
            }
        }

        ravenLoopAnimator?.cancel()
        val loopStartMs = SystemClock.elapsedRealtime()
        val startRot = rv.ringRotationDeg
        ravenLoopAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 18200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                val nowMs = SystemClock.elapsedRealtime()
                val elapsedMs = (nowMs - loopStartMs).coerceAtLeast(0L)
                // 18,200ms clockwise lap on 574dp card == exact 31.4 dp/s glide speed of 7,000ms on 220dp FloatingThorService icon!
                rv.ringRotationDeg = startRot + ((elapsedMs % 18200L).toFloat() / 18200f) * 360f
                // Light golden alpha pulse matching FloatingThorService (550ms half-cycle / 1100ms smooth sine breath: 0.56f .. 1.0f)
                val pulseRad = ((elapsedMs % 1100L).toDouble() / 1100.0) * Math.PI * 2.0
                rv.ringPulseAlpha = (0.78 + 0.22 * kotlin.math.sin(pulseRad)).toFloat()
                ravenRunesShimmerDrawable?.shimmerPhase = (elapsedMs % 4200L).toFloat() / 4200f
                ravenBoltDrawable?.tick(nowMs)
            }
            start()
        }
    }

    private fun hideLivingRavenEmblem() {
        ravenLoopAnimator?.cancel()
        ravenLoopAnimator = null
        val rv = livingRavenView ?: return
        if (rv.parent != null) {
            try { windowManager?.removeView(rv) } catch (_: Exception) {}
        }
    }

    private fun clearRavenLinkButtons() {
        ravenLinksContainer?.removeAllViews()
        ravenLinksContainer?.visibility = View.GONE
    }

    private fun launchExternalLinkFromRaven(rawUrl: String) {
        val ctx = appCtx ?: return
        var cleanUrl = rawUrl.trim()
        if (cleanUrl.isEmpty()) return
        if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
            cleanUrl = "https://$cleanUrl"
        }
        val isYt = cleanUrl.contains("youtube.com", ignoreCase = true) || cleanUrl.contains("youtu.be", ignoreCase = true)
        if (isYt) {
            try {
                val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
                    setPackage("com.google.android.youtube")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(ytIntent)
                return
            } catch (_: Exception) {}
        }
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(webIntent)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Could not open link: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractAndPopulateRavenLinks(rawBody: String, linksJson: JSONArray?): String {
        val ctx = appCtx ?: return rawBody
        val pairs = mutableListOf<Pair<String, String>>()
        val seenUrls = mutableSetOf<String>()

        if (linksJson != null) {
            for (i in 0 until linksJson.length()) {
                val item = linksJson.opt(i)
                when (item) {
                    is JSONObject -> {
                        val u = item.optString("url", "").trim()
                        val lbl = item.optString("label", item.optString("title", "")).trim()
                        if (u.startsWith("http", ignoreCase = true) && seenUrls.add(u)) {
                            pairs.add(Pair(lbl, u))
                        }
                    }
                    is String -> {
                        val u = item.trim()
                        if (u.startsWith("http", ignoreCase = true) && seenUrls.add(u)) {
                            pairs.add(Pair("", u))
                        }
                    }
                }
            }
        }

        val urlRegex = Regex("https?://[^\\s<>()\\[\\]]+")
        for (m in urlRegex.findAll(rawBody)) {
            val u = m.value.trim('"', '\'', '.', ',', ';', ':')
            if (u.isNotEmpty() && seenUrls.add(u)) {
                pairs.add(Pair("", u))
            }
        }

        var cleanedBody = rawBody
        if (pairs.isNotEmpty()) {
            cleanedBody = urlRegex.replace(cleanedBody, "")
                .replace(Regex("[ \\t]{2,}"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }

        val container = ravenLinksContainer
        if (container != null) {
            container.removeAllViews()
            val tacRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 6) }
            }
            var tacCount = 0
            val phoneM = Regex("(?:\\+?1[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]\\d{3}[-.\\s]\\d{4}").find(rawBody)
            if (phoneM != null) {
                val digits = phoneM.value.filter { it.isDigit() || it == '+' }
                tacRow.addView(Button(ctx).apply {
                    text = "📞 Call"
                    isAllCaps = false
                    setTextColor(Color.parseColor("#DCFCE7"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    background = makePillDrawable("#082E1A", "#4ADE80", 1, 99, ctx)
                    layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f).apply { marginEnd = dp(ctx, 5) }
                    setOnClickListener {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        } catch (_: Exception) {}
                    }
                })
                tacCount++
            }
            val addrM = Regex(
                "\\b\\d{1,6}\\s+[A-Za-z0-9.'\\-\\s]{2,36}\\b(?:Road|Rd|Street|St|Avenue|Ave|Boulevard|Blvd|Pike|Highway|Hwy|Drive|Dr|Lane|Ln|Way|Parkway|Pkwy)\\b",
                RegexOption.IGNORE_CASE
            ).find(rawBody)
            if (addrM != null) {
                val destAddr = addrM.value.trim()
                tacRow.addView(Button(ctx).apply {
                    text = "🧭 Navigate"
                    isAllCaps = false
                    setTextColor(Color.parseColor("#E0F2FE"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    background = makePillDrawable("#0B2545", "#38BDF8", 1, 99, ctx)
                    layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f).apply { marginEnd = dp(ctx, 5) }
                    setOnClickListener {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destAddr)}")).apply {
                                setPackage("com.google.android.apps.maps")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (_: Exception) {
                            launchExternalLinkFromRaven("https://www.google.com/maps/search/?api=1&query=${Uri.encode(destAddr)}")
                        }
                    }
                })
                tacCount++
            }
            if (rawBody.isNotBlank()) {
                tacRow.addView(Button(ctx).apply {
                    text = "🔊 Replay"
                    isAllCaps = false
                    setTextColor(Color.parseColor("#EDE9FE"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    background = makePillDrawable("#1E1B4B", "#A78BFA", 1, 99, ctx)
                    layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1.1f).apply { marginEnd = dp(ctx, 5) }
                    setOnClickListener {
                        RavenCenterActivity.requestCloudVoiceReplay(ctx, rawBody, null)
                    }
                })
                tacCount++
            }
            tacRow.addView(Button(ctx).apply {
                text = "🦅 Inbox"
                isAllCaps = false
                setTextColor(Color.parseColor("#FDE047"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                background = makePillDrawable("#172033", "#F5B942", 1, 99, ctx)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 0.95f)
                setOnClickListener {
                    hideVikingShield()
                    try {
                        ctx.startActivity(Intent(ctx, RavenCenterActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    } catch (_: Exception) {}
                }
            })
            tacCount++
            if (tacCount > 0) {
                container.visibility = View.VISIBLE
                container.addView(tacRow)
            }

            if (pairs.isNotEmpty()) {
                container.visibility = View.VISIBLE
                for ((idx, pair) in pairs.take(4).withIndex()) {
                    val rawLbl = pair.first
                    val targetUrl = pair.second
                    val isYt = targetUrl.contains("youtube.com", ignoreCase = true) || targetUrl.contains("youtu.be", ignoreCase = true)
                    val hostShort = try {
                        Uri.parse(targetUrl).host?.removePrefix("www.") ?: "Website"
                    } catch (_: Exception) { "Website" }
                    val btnTitle = when {
                        rawLbl.isNotBlank() -> if (isYt) "▶ $rawLbl" else "🔗 $rawLbl"
                        isYt -> "▶ Watch on YouTube" + (if (pairs.size > 1) " #${idx + 1}" else "")
                        else -> "🔗 Open $hostShort" + (if (pairs.size > 1) " #${idx + 1}" else "")
                    }
                    val linkBtn = Button(ctx).apply {
                        text = btnTitle.take(48)
                        isAllCaps = false
                        setTextColor(Color.parseColor("#FDE047"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        background = makePillDrawable("#0F233D", "#F5B942", 2, 99, ctx)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(ctx, 40)
                        ).apply {
                            bottomMargin = dp(ctx, 6)
                        }
                        setOnClickListener {
                            launchExternalLinkFromRaven(targetUrl)
                        }
                    }
                    container.addView(linkBtn)
                }
            }
        }
        return if (cleanedBody.isNotBlank()) cleanedBody else "Tap a gold link button below to open."
    }

    fun start(context: Context, serverBaseUrl: String) {
        appCtx = context.applicationContext
        val cleanBase = serverBaseUrl.trimEnd('/')
        if (polling) return
        polling = true
        Log.i(TAG, "Starting Viking Pop-Up Herald V8 (WO-145 Ultra-HD Living Raven V3 + Clockwise Floating Gold Ring)...")

        thread(start = true, isDaemon = true, name = "VikingPopupPoller") {
            while (polling) {
                try {
                    val req = Request.Builder()
                        .url("$cleanBase/api/pre-approval?action=status&t=${System.currentTimeMillis()}")
                        .header("User-Agent", "ThorVikingHerald/19.1 (Samsung-Note20Ultra)")
                        .get()
                        .build()
                    http.newCall(req).execute().use { resp ->
                        val txt = resp.body?.string() ?: ""
                        if (resp.isSuccessful && txt.isNotEmpty()) {
                            evaluateVaultState(cleanBase, txt)
                        }
                    }
                } catch (_: Exception) {}
                val sleepMs = if (System.currentTimeMillis() < fastPollUntilEpochMs) 2500L else 10000L
                Thread.sleep(sleepMs)
            }
        }
    }

    private fun cleanShortTitle(raw: String): String {
        val t = raw.trim()
        if (t.length <= 72) return t
        return t.take(69) + "..."
    }

    private fun evaluateVaultState(cleanBase: String, rawJson: String) {
        try {
            val root = JSONObject(rawJson)
            val vault = root.optJSONObject("vault") ?: return
            val pendingResume = vault.optJSONObject("pending_voice_resume")
            if (pendingResume != null) {
                val resId = pendingResume.optString("resume_id", "")
                val repTxt = pendingResume.optString("reply_text", "")
                val ctxNow = appCtx
                if (resId.isNotBlank() && resId != lastHandledResumeId && repTxt.isNotBlank() && ctxNow != null) {
                    lastHandledResumeId = resId
                    val stoppedByBoss = ctxNow.getSharedPreferences("thor_overlay_prefs", Context.MODE_PRIVATE)
                        .getBoolean("user_stopped", false)
                    if (!stoppedByBoss && FloatingThorService.canAutoResumeMic) {
                        Log.i(TAG, "WO-139: Active mode -> Thor autonomously re-opening mic to speak $resId")
                        mainHandler.post {
                            try {
                                val resumeIntent = android.content.Intent(ctxNow, FloatingThorService::class.java).apply {
                                    action = FloatingThorService.ACTION_THOR_RESUME_MIC
                                    putExtra("resume_id", resId)
                                }
                                ctxNow.startService(resumeIntent)
                            } catch (e: Exception) {
                                Log.w(TAG, "Auto mic resume startService note: ${e.message}")
                            }
                        }
                        return
                    } else {
                        // Long-Pressed Off or Red Standby -> NEVER open mic; clear voice flag & fall through to show silent Living Raven!
                        Log.i(TAG, "WO-139: Mic off/standby -> delivering $resId silently via Living Raven!")
                        thread(start = true, isDaemon = true) {
                            try {
                                val clr = Request.Builder().url("$cleanBase/api/pre-approval?action=clear_voice_resume&t=${System.currentTimeMillis()}").get().build()
                                http.newCall(clr).execute().close()
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
            val clones = vault.optJSONArray("active_clones")
            val staged = vault.optJSONObject("staged_upgrade")
            val patches = vault.optJSONArray("live_ui_patches")
            val prog = vault.optJSONObject("upgrade_progress")

            if (clones != null && clones.length() > 0) {
                val c = clones.getJSONObject(0)
                val upgId = c.optString("upgrade_id", c.optString("clone_id", "CLONE"))
                val task = cleanShortTitle(c.optString("task", "Building Upgrade..."))
                val pct = c.optInt("progress_pct", prog?.optInt("progress_pct", 55) ?: 55)
                val key = "${upgId}_RUNNING"
                if (dismissedStagedKey != key) {
                    mainHandler.post {
                        hideRecallBadge()
                        showOrUpdateVikingShield(
                            cleanBase = cleanBase,
                            eventKey = key,
                            headerText = "ᚦᚱ • SINDRI FORGE IN PROGRESS • ᚱᚦ",
                            mainTitle = "🧬 Building: $task",
                            subText = "Running 5-Layer Bifrost Safety Gate ($pct%)...",
                            progressPct = pct,
                            mode = "BUILDING"
                        )
                    }
                    return
                }
            }

            if (staged != null) {
                val upgId = staged.optString("upgrade_id", "UPG-LIVE")
                val title = cleanShortTitle(staged.optString("title", "Upgrade Ready"))
                val isHeadsUp = title.uppercase().startsWith("[HEADS-UP]") || title.uppercase().startsWith("HEADS UP:")
                val key = "${upgId}_READY"
                if (dismissedStagedKey != key) {
                    mainHandler.post {
                        hideRecallBadge()
                        showOrUpdateVikingShield(
                            cleanBase = cleanBase,
                            eventKey = key,
                            headerText = if (isHeadsUp) "ᚦᚱ • THOR HEADS-UP SCROLL • ᚱᚦ" else "ᚦᚱ • BIFRÖST UPGRADE READY • ᚱᚦ",
                            mainTitle = if (isHeadsUp) "🦅 $title" else "⚡ $title",
                            subText = if (isHeadsUp) "Tap GOT IT or swipe away." else "100% Verified ($upgId) • Tap APPROVE below to apply live.",
                            progressPct = 100,
                            mode = if (isHeadsUp) "HEADS_UP" else "READY"
                        )
                    }
                    return
                } else {
                    mainHandler.post {
                        showTemporaryRecallBadge(cleanBase, isForUndo = false)
                    }
                }
            }

            val replyPopup = vault.optJSONObject("latest_thor_reply_popup")
            if (replyPopup != null) {
                val repId = replyPopup.optString("reply_id", "REPLY")
                val repTitle = cleanShortTitle(replyPopup.optString("title", "Raven Message"))
                val repBody = replyPopup.optString("body", "")
                val repLinks = replyPopup.optJSONArray("links")
                val rKey = "${repId}_REPLY"
                if (dismissedStagedKey != rKey) {
                    if (lastRavenKey != rKey) {
                        val ctx = appCtx
                        if (ctx != null) {
                            showRavenCard(ctx, cleanBase, repTitle, repBody, repLinks, eventKeyOverride = rKey)
                        }
                    }
                    return
                }
            }

            if (patches != null && patches.length() > 0) {
                val latestPatch = patches.getJSONObject(patches.length() - 1)
                val patchId = latestPatch.optString("upgrade_id", "PATCH-LIVE")
                latestPatchTitle = cleanShortTitle(latestPatch.optString("title", "Active Screen Upgrade"))
                val pKey = "${patchId}_ACTIVE_PATCH"
                currentPatchKey = pKey
                if (dismissedShieldPatchKey != pKey) {
                    mainHandler.post {
                        showTemporaryRecallBadge(cleanBase, isForUndo = true)
                    }
                } else {
                    mainHandler.post {
                        hideRecallBadge()
                        if (!currentEventKey.startsWith("RAVEN_")) hideVikingShield()
                    }
                }
                return
            }

            mainHandler.post {
                hideRecallBadge()
                if (lastRavenKey.isEmpty() || dismissedStagedKey == lastRavenKey) {
                    hideLivingRavenEmblem()
                    hideVikingShield()
                }
            }
        } catch (_: Exception) {}
    }

    private fun dp(ctx: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun makePillDrawable(fillHex: String, strokeHex: String, strokeDp: Int, radiusDp: Int, ctx: Context): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(dp(ctx, strokeDp), Color.parseColor(strokeHex))
        }
    }

    private fun showTemporaryRecallBadge(cleanBase: String, isForUndo: Boolean) {
        val ctx = appCtx ?: return
        if (!Settings.canDrawOverlays(ctx)) return
        if (windowManager == null) {
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        if (recallBadgeView == null) {
            val badge = TextView(ctx).apply {
                text = "🛡️"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.parseColor("#F5172A4A"), Color.parseColor("#FA080E1C"))
                ).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(ctx, 2), Color.parseColor("#F5B942"))
                }
                elevation = dp(ctx, 16).toFloat()
            }

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val bParams = WindowManager.LayoutParams(
                dp(ctx, 50),
                dp(ctx, 50),
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(ctx, 8)
                y = dp(ctx, 260)
            }

            var downRawX = 0f
            var downRawY = 0f
            var startY = 0
            var moved = false

            badge.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        startY = bParams.y
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downRawX
                        val dy = ev.rawY - downRawY
                        if (abs(dx) > dp(ctx, 10) || abs(dy) > dp(ctx, 10)) {
                            moved = true
                            v.translationX = dx
                            bParams.y = (startY + dy.toInt()).coerceIn(dp(ctx, 40), dp(ctx, 740))
                            try { windowManager?.updateViewLayout(v, bParams) } catch (_: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dx = ev.rawX - downRawX
                        if (abs(dx) > dp(ctx, 85)) {
                            v.translationX = 0f
                            dismissedShieldPatchKey = currentPatchKey
                            dismissedStagedKey = currentEventKey
                            hideRecallBadge()
                            hideVikingShield()
                            triggerServerActionBg(cleanBase, "keep_and_bake")
                        } else if (!moved) {
                            v.translationX = 0f
                            showOrUpdateVikingShield(
                                cleanBase = cleanBase,
                                eventKey = currentPatchKey.ifEmpty { "MANUAL_UNDO" },
                                headerText = "ᚦᚱ • UPGRADE ACTIVE ON SCREEN • ᚱᚦ",
                                mainTitle = "✅ Active: $latestPatchTitle",
                                subText = "Tap UNDO CHANGE to revert & remove shield, or KEEP to dismiss shield.",
                                progressPct = 100,
                                mode = "APPLIED_UNDO_BAR"
                            )
                        } else {
                            v.animate().translationX(0f).setDuration(150L).start()
                        }
                        true
                    }
                    else -> false
                }
            }

            recallBadgeView = badge
            recallBadgeParams = bParams
        }

        val bv = recallBadgeView ?: return
        bv.translationX = 0f
        if (bv.parent == null) {
            try { windowManager?.addView(bv, recallBadgeParams) } catch (_: Exception) {}
        }
    }

    private fun hideRecallBadge() {
        val bv = recallBadgeView ?: return
        if (bv.parent != null) {
            try { windowManager?.removeView(bv) } catch (_: Exception) {}
        }
    }

    private fun showOrUpdateVikingShield(
        cleanBase: String,
        eventKey: String,
        headerText: String,
        mainTitle: String,
        subText: String,
        progressPct: Int,
        mode: String
    ) {
        val ctx = appCtx ?: return
        if (!Settings.canDrawOverlays(ctx)) return
        currentEventKey = eventKey

        if (windowManager == null) {
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }

        if (popupView == null) {
            buildVikingShieldView(ctx, cleanBase)
        }

        val view = popupView ?: return
        view.translationX = 0f
        view.rotation = 0f
        view.alpha = 1.0f

        runeHeaderView?.text = headerText
        titleView?.text = mainTitle
        subtitleView?.text = subText
        progressBar?.progress = progressPct.coerceIn(0, 100)

        val isRavenMode = (mode == "RAVEN")
        if (isRavenMode) {
            ensureRavenBitmapsLoaded(ctx)
            ravenBaseBmp?.let { ravenThumbImage?.setImageBitmap(it) }
            ravenThumbCard?.visibility = View.VISIBLE
            ravenThumbCard?.setOnClickListener {
                hideVikingShield()
                showLivingRavenEmblem(cleanBase)
            }
            runeHeaderView?.gravity = Gravity.START
        } else {
            ravenThumbCard?.visibility = View.GONE
            ravenThumbCard?.setOnClickListener(null)
            runeHeaderView?.gravity = Gravity.CENTER
            clearRavenLinkButtons()
        }

        when (mode) {
            "RAVEN" -> {
                pctLabelView?.text = "HUGINN & MUNINN • TAP PORTRAIT OR FOLD TO PERCH"
                pctLabelView?.setTextColor(Color.parseColor("#F5B942"))
                progressBar?.visibility = View.GONE
                undoBtn?.text = "🐦‍⬛ FOLD"
                undoBtn?.visibility = View.VISIBLE
                undoBtn?.setOnClickListener {
                    hideVikingShield()
                    showLivingRavenEmblem(cleanBase)
                }
                approveBtn?.text = "⚡ GOT IT"
                approveBtn?.alpha = 1.0f
                approveBtn?.visibility = View.VISIBLE
                approveBtn?.setOnClickListener {
                    dismissedStagedKey = currentEventKey
                    lastRavenKey = ""
                    triggerServerActionBg(cleanBase, "dismiss_reply")
                    hideVikingShield()
                    hideLivingRavenEmblem()
                    hideRecallBadge()
                }
                declineBtn?.visibility = View.GONE
            }
            "HEADS_UP", "THOR_REPLY" -> {
                pctLabelView?.text = "🦅 SILENT HEADS-UP NOTE"
                pctLabelView?.setTextColor(Color.parseColor("#4ADE80"))
                progressBar?.visibility = View.GONE
                approveBtn?.text = "⚡ GOT IT"
                approveBtn?.alpha = 1.0f
                approveBtn?.visibility = View.VISIBLE
                approveBtn?.setOnClickListener {
                    dismissedStagedKey = currentEventKey
                    triggerServerActionBg(cleanBase, if (mode == "THOR_REPLY") "dismiss_reply" else "reject")
                    hideVikingShield()
                    hideRecallBadge()
                }
                declineBtn?.visibility = View.GONE
                undoBtn?.visibility = View.GONE
            }
            "APPLIED_UNDO_BAR" -> {
                pctLabelView?.text = "🛡️ UNDO OR KEEP THIS UPGRADE"
                pctLabelView?.setTextColor(Color.parseColor("#4ADE80"))
                progressBar?.visibility = View.GONE
                undoBtn?.text = "⏪ UNDO CHANGE"
                undoBtn?.visibility = View.VISIBLE
                undoBtn?.setOnClickListener {
                    dismissedShieldPatchKey = currentPatchKey
                    Toast.makeText(ctx, "⏪ Change Undone! Shield & Pop-Up Closed.", Toast.LENGTH_SHORT).show()
                    triggerServerActionBg(cleanBase, "revert")
                    hideVikingShield()
                    hideRecallBadge()
                }
                approveBtn?.text = "✅ KEEP (REMOVE 🛡️)"
                approveBtn?.alpha = 1.0f
                approveBtn?.visibility = View.VISIBLE
                approveBtn?.setOnClickListener {
                    dismissedShieldPatchKey = currentPatchKey
                    hideVikingShield()
                    hideRecallBadge()
                    triggerServerActionBg(cleanBase, "keep_and_bake")
                    Toast.makeText(ctx, "✅ Upgrade Kept & Baked to Cloud!", Toast.LENGTH_SHORT).show()
                }
                declineBtn?.visibility = View.GONE
            }
            else -> {
                pctLabelView?.text = "PROGRESS: ${progressPct.coerceIn(0, 100)}%"
                pctLabelView?.setTextColor(Color.parseColor("#4ADE80"))
                progressBar?.visibility = View.VISIBLE
                approveBtn?.text = "⚡ APPROVE"
                approveBtn?.visibility = View.VISIBLE
                approveBtn?.alpha = if (mode == "READY") 1.0f else 0.55f
                approveBtn?.setOnClickListener {
                    if (mode != "READY") return@setOnClickListener
                    dismissedStagedKey = currentEventKey
                    latestPatchTitle = (titleView?.text ?: "Upgrade").toString().replace("⚡ ", "")
                    hideVikingShield()
                    showTemporaryRecallBadge(cleanBase, isForUndo = true)
                    Toast.makeText(ctx, "✅ Applied! Tap 🛡️ on edge to Undo, or swipe 🛡️ off screen to Keep.", Toast.LENGTH_LONG).show()
                    triggerServerActionBg(cleanBase, "approve")
                }
                declineBtn?.text = "🛡️ DECLINE"
                declineBtn?.visibility = View.VISIBLE
                declineBtn?.setOnClickListener {
                    dismissedStagedKey = currentEventKey
                    triggerServerActionBg(cleanBase, "reject")
                    hideVikingShield()
                    hideRecallBadge()
                }
                undoBtn?.visibility = View.GONE
            }
        }

        if (view.parent == null) {
            try {
                windowManager?.addView(view, popupParams)
            } catch (e: Exception) {
                Log.e(TAG, "Failed adding Viking Shield overlay", e)
            }
        } else {
            try { windowManager?.updateViewLayout(view, popupParams) } catch (_: Exception) {}
        }
    }

    private fun buildVikingShieldView(ctx: Context, cleanBase: String) {
        val rootCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(ctx, 16)
            val padV = dp(ctx, 12)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#F7132342"), Color.parseColor("#FA070C18"))
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, 28).toFloat()
                setStroke(dp(ctx, 2), Color.parseColor("#F5B942"))
            }
            elevation = dp(ctx, 18).toFloat()
        }

        val swipeHandle = View(ctx).apply {
            background = makePillDrawable("#F5B942", "#38BDF8", 1, 99, ctx)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 68), dp(ctx, 5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(ctx, 8)
            }
        }
        rootCard.addView(swipeHandle)

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(ctx, 6)
            }
        }

        val thumbRadius = dp(ctx, 12).toFloat()
        val thumbWrap = FrameLayout(ctx).apply {
            visibility = View.GONE
            val pad = dp(ctx, 2)
            setPadding(pad, pad, pad, pad)
            background = makePillDrawable("#0B1324", "#F5B942", 2, 14, ctx)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 62), dp(ctx, 77)).apply {
                marginEnd = dp(ctx, 12)
            }
        }
        val thumbImg = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, thumbRadius)
                }
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        thumbWrap.addView(thumbImg)
        ravenThumbCard = thumbWrap
        ravenThumbImage = thumbImg
        headerRow.addView(thumbWrap)

        val headerTextCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        runeHeaderView = TextView(ctx).apply {
            text = "ᚦᚱ • BIFRÖST UPGRADE READY • ᚱᚦ"
            setTextColor(Color.parseColor("#FDE047"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(ctx, 3)
            }
        }
        headerTextCol.addView(runeHeaderView)

        titleView = TextView(ctx).apply {
            text = "⚡ Upgrade Ready"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 12
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(ctx, 2)
            }
        }
        headerTextCol.addView(titleView)

        pctLabelView = TextView(ctx).apply {
            text = "PROGRESS: 100%"
            setTextColor(Color.parseColor("#4ADE80"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerTextCol.addView(pctLabelView)

        headerRow.addView(headerTextCol)
        rootCard.addView(headerRow)

        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 100
            val bgTrack = makePillDrawable("#050914", "#38BDF8", 1, 99, ctx)
            val fillGrad = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#38BDF8"), Color.parseColor("#F5B942"), Color.parseColor("#4ADE80"))
            ).apply {
                cornerRadius = dp(ctx, 99).toFloat()
            }
            val clipFill = ClipDrawable(fillGrad, Gravity.START, ClipDrawable.HORIZONTAL)
            progressDrawable = LayerDrawable(arrayOf(bgTrack, clipFill)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(ctx, 12)
            ).apply {
                bottomMargin = dp(ctx, 6)
            }
        }
        rootCard.addView(progressBar)

        subtitleView = TextView(ctx).apply {
            text = "Tap APPROVE below to apply live."
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxLines = 14
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(ctx, 8)
            }
        }
        rootCard.addView(subtitleView)

        ravenLinksContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(ctx, 8)
            }
        }
        rootCard.addView(ravenLinksContainer)

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        undoBtn = Button(ctx).apply {
            text = "⏪ UNDO CHANGE"
            isAllCaps = false
            setTextColor(Color.parseColor("#FDE047"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = makePillDrawable("#1E293B", "#F5B942", 2, 99, ctx)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1.0f).apply {
                marginEnd = dp(ctx, 6)
            }
        }
        btnRow.addView(undoBtn)

        approveBtn = Button(ctx).apply {
            text = "⚡ APPROVE"
            isAllCaps = false
            setTextColor(Color.parseColor("#04120A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = makePillDrawable("#4ADE80", "#FDE047", 2, 99, ctx)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1.35f).apply {
                marginEnd = dp(ctx, 6)
            }
        }
        btnRow.addView(approveBtn)

        declineBtn = Button(ctx).apply {
            text = "🛡️ DECLINE"
            isAllCaps = false
            setTextColor(Color.parseColor("#FECDD3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = makePillDrawable("#4C1D24", "#FB7185", 1, 99, ctx)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1.0f)
        }
        btnRow.addView(declineBtn)

        rootCard.addView(btnRow)

        val deliberateSwipePx = dp(ctx, 145).toFloat()
        val deadzonePx = dp(ctx, 18).toFloat()
        var startRawX = 0f
        var startRawY = 0f
        var startParamY = 0

        rootCard.setOnTouchListener { v, ev ->
            val p = popupParams ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    startParamY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    if (abs(dx) > deadzonePx && abs(dx) > abs(dy) * 1.3f) {
                        v.translationX = dx
                        v.rotation = (dx * 0.025f).coerceIn(-12f, 12f)
                        v.alpha = (1.0f - (abs(dx) / (deliberateSwipePx * 1.4f))).coerceIn(0.3f, 1.0f)
                    } else if (abs(dy) > deadzonePx) {
                        p.y = (startParamY + dy.toInt()).coerceIn(dp(ctx, 18), dp(ctx, 640))
                        try { windowManager?.updateViewLayout(v, p) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = ev.rawX - startRawX
                    if (abs(dx) > deliberateSwipePx) {
                        if (currentEventKey.startsWith("RAVEN_") || currentEventKey.endsWith("_REPLY")) {
                            hideVikingShield()
                            showLivingRavenEmblem(cleanBase)
                        } else {
                            dismissedStagedKey = currentEventKey
                            hideVikingShield()
                            showTemporaryRecallBadge(cleanBase, isForUndo = false)
                        }
                    } else {
                        v.animate().translationX(0f).rotation(0f).alpha(1.0f).setDuration(180L).start()
                    }
                    true
                }
                else -> false
            }
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val screenW = ctx.resources.displayMetrics.widthPixels
        val cardW = (screenW * 0.94f).toInt().coerceAtMost(dp(ctx, 430))

        popupParams = WindowManager.LayoutParams(
            cardW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(ctx, 48)
        }

        popupView = rootCard
    }

    private fun triggerServerActionBg(cleanBase: String, actionName: String) {
        thread(start = true, isDaemon = true) {
            try {
                val req = Request.Builder()
                    .url("$cleanBase/api/pre-approval?action=$actionName&t=${System.currentTimeMillis()}")
                    .header("User-Agent", "ThorVikingHerald/19.1 (Samsung-Note20Ultra)")
                    .get()
                    .build()
                http.newCall(req).execute().close()
            } catch (_: Exception) {}
        }
    }

    private fun hideVikingShield() {
        mainHandler.post {
            val v = popupView ?: return@post
            if (v.parent != null) {
                try { windowManager?.removeView(v) } catch (_: Exception) {}
            }
        }
    }
}

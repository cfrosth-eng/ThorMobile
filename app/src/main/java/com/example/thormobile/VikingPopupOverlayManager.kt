package com.example.thormobile

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

object VikingPopupOverlayManager {
    private const val TAG = "VikingPopupHerald"
    @Volatile private var polling = false
    private var appCtx: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null

    private var popupView: View? = null
    private var popupParams: WindowManager.LayoutParams? = null

    private var recallBadgeView: TextView? = null
    private var recallBadgeParams: WindowManager.LayoutParams? = null

    private var runeHeaderView: TextView? = null
    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var progressBar: ProgressBar? = null
    private var pctLabelView: TextView? = null
    private var approveBtn: Button? = null
    private var declineBtn: Button? = null
    private var undoBtn: Button? = null

    @Volatile private var dismissedStagedKey: String = ""
    @Volatile private var dismissedShieldPatchKey: String = ""
    @Volatile private var currentEventKey: String = ""
    @Volatile private var currentPatchKey: String = ""
    @Volatile private var latestPatchTitle: String = "Active Upgrade"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

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
                    .header("User-Agent", "ThorVikingHerald/18.6 (Samsung-Note20Ultra)")
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

    fun start(context: Context, serverBaseUrl: String) {
        appCtx = context.applicationContext
        val cleanBase = serverBaseUrl.trimEnd('/')
        if (polling) return
        polling = true
        Log.i(TAG, "Starting Viking Pop-Up Herald V5 (System-Wide Pop-Up + Temporary Swipe-Away Shield)...")

        thread(start = true, isDaemon = true, name = "VikingPopupPoller") {
            while (polling) {
                try {
                    val req = Request.Builder()
                        .url("$cleanBase/api/pre-approval?action=status&t=${System.currentTimeMillis()}")
                        .header("User-Agent", "ThorVikingHerald/18.6 (Samsung-Note20Ultra)")
                        .get()
                        .build()
                    http.newCall(req).execute().use { resp ->
                        val txt = resp.body?.string() ?: ""
                        if (resp.isSuccessful && txt.isNotEmpty()) {
                            evaluateVaultState(cleanBase, txt)
                        }
                    }
                } catch (_: Exception) {}
                Thread.sleep(15000L)
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
            val clones = vault.optJSONArray("active_clones")
            val staged = vault.optJSONObject("staged_upgrade")
            val patches = vault.optJSONArray("live_ui_patches")
            val prog = vault.optJSONObject("upgrade_progress")

            // CASE 1: A Parallel Clone is actively building (0%..99%) -> Pop up big card over any screen
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
                            headerText = "ᚠᚢᚦ • SINDRI FORGE IN PROGRESS • ᚦᚢᚠ",
                            mainTitle = "🧬 Building: $task",
                            subText = "Running 5-Layer Bifrost Safety Gate ($pct%)...",
                            progressPct = pct,
                            mode = "BUILDING"
                        )
                    }
                }
                return
            }

            // CASE 2: Upgrade or Heads-Up is staged at 100% -> Pop up big Approve/Decline card over ANY app!
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
                            headerText = if (isHeadsUp) "ᚠᚢᚦ • THOR HEADS-UP SCROLL • ᚦᚢᚠ" else "ᚠᚢᚦ • BIFRÖST UPGRADE READY • ᚦᚢᚠ",
                            mainTitle = if (isHeadsUp) "🦅 $title" else "⚡ $title",
                            subText = if (isHeadsUp) "Tap GOT IT or swipe away." else "100% Verified ($upgId) • Tap APPROVE below to apply live.",
                            progressPct = 100,
                            mode = if (isHeadsUp) "HEADS_UP" else "READY"
                        )
                    }
                } else {
                    // Boss swiped the staged card aside temporarily -> show the tiny 🛡️ shield so he can tap it to reopen
                    mainHandler.post {
                        showTemporaryRecallBadge(cleanBase, isForUndo = false)
                    }
                }
                return
            }

            // CASE 2B: Thor just replied to a Screenshot or Quick-Comms message (`latest_thor_reply_popup`)!
            // Pop up his answer right over ANY app on the phone so Boss doesn't have to stay inside the browser!
            val replyPopup = vault.optJSONObject("latest_thor_reply_popup")
            if (replyPopup != null) {
                val repId = replyPopup.optString("reply_id", "REPLY")
                val repTitle = cleanShortTitle(replyPopup.optString("title", "Thor Response"))
                val repBody = replyPopup.optString("body", "")
                val rKey = "${repId}_REPLY"
                if (dismissedStagedKey != rKey) {
                    mainHandler.post {
                        showOrUpdateVikingShield(
                            cleanBase = cleanBase,
                            eventKey = rKey,
                            headerText = "ᚠᚢᚦ • THOR LIVE RESPONSE SCROLL • ᚦᚢᚠ",
                            mainTitle = repTitle,
                            subText = repBody.take(520),
                            progressPct = 100,
                            mode = "THOR_REPLY"
                        )
                    }
                    return
                }
            }

            // CASE 3: Boss approved an upgrade (`live_ui_patches` is non-empty)!
            // Keep the big pop-up closed so he can see his screen, and show ONLY the tiny temporary 🛡️ shield on the edge
            // until he either (A) swipes the tiny 🛡️ shield off the screen to keep it, OR (B) taps 🛡️ -> taps UNDO CHANGE!
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
                        hideVikingShield()
                    }
                }
                return
            }

            // CASE 4: Nothing staged and no un-dismissed patch -> 100% clean screen (no pop-up and no tiny shield!)
            mainHandler.post {
                hideRecallBadge()
                hideVikingShield()
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
                y = dp(ctx, 230)
            }

            var downRawX = 0f
            var downRawY = 0f
            var startX = 0
            var startY = 0
            var moved = false

            badge.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        startX = bParams.x
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
                        // If Boss flicks/swipes the tiny 🛡️ shield horizontally (> 85dp), dismiss the shield completely!
                        if (abs(dx) > dp(ctx, 85)) {
                            v.translationX = 0f
                            dismissedShieldPatchKey = currentPatchKey
                            dismissedStagedKey = currentEventKey
                            hideRecallBadge()
                            hideVikingShield()
                            triggerServerActionBg(cleanBase, "keep_and_bake")
                            Toast.makeText(ctx, "🛡️ Shield Dismissed — Upgrade Kept & Baked to Cloud!", Toast.LENGTH_SHORT).show()
                        } else if (!moved) {
                            // Boss tapped the tiny 🛡️ shield -> open the Undo / Approval card!
                            v.translationX = 0f
                            showOrUpdateVikingShield(
                                cleanBase = cleanBase,
                                eventKey = currentPatchKey.ifEmpty { "MANUAL_UNDO" },
                                headerText = "ᚠᚢᚦ • UPGRADE ACTIVE ON SCREEN • ᚦᚢᚠ",
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
            try {
                windowManager?.addView(bv, recallBadgeParams)
            } catch (_: Exception) {}
        }
    }

    private fun hideRecallBadge() {
        val bv = recallBadgeView ?: return
        if (bv.parent != null) {
            try {
                windowManager?.removeView(bv)
            } catch (_: Exception) {}
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

        when (mode) {
            "HEADS_UP", "THOR_REPLY" -> {
                pctLabelView?.text = "🦅 SILENT HEADS-UP NOTE"
                progressBar?.visibility = View.GONE
                approveBtn?.text = "⚡ GOT IT"
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
                progressBar?.visibility = View.GONE
                undoBtn?.text = "⏪ UNDO CHANGE"
                undoBtn?.visibility = View.VISIBLE
                undoBtn?.setOnClickListener {
                    // Boss clicked UNDO CHANGE -> revert upgrade AND make both the card and the tiny 🛡️ shield vanish 100%!
                    dismissedShieldPatchKey = currentPatchKey
                    Toast.makeText(ctx, "⏪ Change Undone! Shield & Pop-Up Closed.", Toast.LENGTH_SHORT).show()
                    triggerServerActionBg(cleanBase, "revert")
                    hideVikingShield()
                    hideRecallBadge()
                }
                approveBtn?.text = "✅ KEEP (REMOVE 🛡️)"
                approveBtn?.visibility = View.VISIBLE
                approveBtn?.setOnClickListener {
                    // Boss wants to keep the upgrade and get rid of the tiny 🛡️ shield!
                    dismissedShieldPatchKey = currentPatchKey
                    hideVikingShield()
                    hideRecallBadge()
                    triggerServerActionBg(cleanBase, "keep_and_bake")
                    Toast.makeText(ctx, "✅ Upgrade Kept & Baked to Cloud — Shield Removed!", Toast.LENGTH_SHORT).show()
                }
                declineBtn?.visibility = View.GONE
            }
            else -> {
                pctLabelView?.text = "PROGRESS: ${progressPct.coerceIn(0, 100)}%"
                progressBar?.visibility = View.VISIBLE
                approveBtn?.text = "⚡ APPROVE"
                approveBtn?.visibility = View.VISIBLE
                approveBtn?.alpha = if (mode == "READY") 1.0f else 0.55f
                approveBtn?.setOnClickListener {
                    if (mode != "READY") return@setOnClickListener
                    dismissedStagedKey = currentEventKey
                    latestPatchTitle = (titleView?.text ?: "Upgrade").toString().replace("⚡ ", "")
                    // Hide big pop-up immediately so Boss can see his screen, and dock the temporary 🛡️ shield on the edge!
                    hideVikingShield()
                    showTemporaryRecallBadge(cleanBase, isForUndo = true)
                    Toast.makeText(ctx, "✅ Applied! Tap 🛡️ on edge to Undo, or swipe 🛡️ off screen to Keep.", Toast.LENGTH_LONG).show()
                    triggerServerActionBg(cleanBase, "approve")
                }
                declineBtn?.text = "🛡️ DECLINE"
                declineBtn?.visibility = View.VISIBLE
                declineBtn?.setOnClickListener {
                    dismissedStagedKey = currentEventKey
                    triggerServerActionBg(cleanBase, if (mode == "THOR_REPLY") "dismiss_reply" else "reject")
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
            try {
                windowManager?.updateViewLayout(view, popupParams)
            } catch (_: Exception) {}
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
                cornerRadius = dp(ctx, 30).toFloat()
                setStroke(dp(ctx, 2), Color.parseColor("#F5B942"))
            }
            elevation = dp(ctx, 18).toFloat()
        }

        val swipeHandle = View(ctx).apply {
            background = makePillDrawable("#F5B942", "#38BDF8", 1, 99, ctx)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 68), dp(ctx, 5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(ctx, 6)
            }
        }
        rootCard.addView(swipeHandle)

        runeHeaderView = TextView(ctx).apply {
            text = "ᚠᚢᚦ • BIFRÖST UPGRADE READY • ᚦᚢᚠ"
            setTextColor(Color.parseColor("#FDE047"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(ctx, 4)
            }
        }
        rootCard.addView(runeHeaderView)

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
                bottomMargin = dp(ctx, 3)
            }
        }
        rootCard.addView(titleView)

        pctLabelView = TextView(ctx).apply {
            text = "PROGRESS: 100%"
            setTextColor(Color.parseColor("#4ADE80"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(ctx, 4)
            }
        }
        rootCard.addView(pctLabelView)

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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 12
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(ctx, 8)
            }
        }
        rootCard.addView(subtitleView)

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
            background = makePillDrawable("#3B1D2E", "#F5B942", 2, 99, ctx)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1.35f).apply {
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
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1.3f).apply {
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
                        dismissedStagedKey = currentEventKey
                        hideVikingShield()
                        showTemporaryRecallBadge(cleanBase, isForUndo = false)
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
                    .header("User-Agent", "ThorVikingHerald/18.6 (Samsung-Note20Ultra)")
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
                try {
                    windowManager?.removeView(v)
                } catch (_: Exception) {}
            }
        }
    }
}

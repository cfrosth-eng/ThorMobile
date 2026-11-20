package com.example.thormobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * THOR SOVEREIGN COCKPIT & COMMAND HUB (WO-141 CLEAN ARCHITECTURE)
 * - Legacy runtime Norse view-tree restyler archived to data/archives/ per Boss directive.
 * - Floating Thor Voice ON/OFF button at top, with the Raven Messaging Center (using the Living Raven
 *   on a pedestal emblem) mounted DIRECTLY underneath it.
 */
class MainActivity : AppCompatActivity() {

    private val CLOUD_URL = "https://thor-api-443941171993.us-east4.run.app"
    private val SOVEREIGN_TOKEN = "9d943a8005992357c7d6ca29278e803b80521c4d0d703e1ad74fb141ac050d19"
    private val uiIo = Executors.newSingleThreadExecutor()

    private var voiceToggleTitleTv: TextView? = null
    private var voiceToggleSubTv: TextView? = null
    private var voiceToggleCardRef: LinearLayout? = null
    private var displayShareTitleTv: TextView? = null
    private var displayShareSubTv: TextView? = null
    private var displayShareCardRef: LinearLayout? = null
    private var torchCardRef: LinearLayout? = null
    private var torchTitleTv: TextView? = null
    private var rearCamCardRef: LinearLayout? = null
    private var rearCamTitleTv: TextView? = null
    private var ravenCenterBadgeTv: TextView? = null

    private val toggleUiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val toggleUiTicker = object : Runnable {
        override fun run() {
            updateVoiceToggleButtonAppearance()
            toggleUiHandler.postDelayed(this, 450L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { actionBar?.hide() } catch (_: Throwable) {}
        window.statusBarColor = Color.parseColor("#040812")
        window.navigationBarColor = Color.parseColor("#040812")

        requestNeededPermissions()
        ThorFlashlightAndLensService.ensureTorchListener(this)
        ObdLinkAutoWatchdog.startAutonomousWatchdog(CLOUD_URL)
        VikingPopupOverlayManager.start(this, CLOUD_URL)
        RavenHeraldService.ensureRunning(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#040812"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
            setBackgroundColor(Color.parseColor("#040812"))
        }

        root.addView(TextView(this).apply {
            text = "ᚠᚢᚦᚨᚱᚲ • PROJECT THOR SOVEREIGN COCKPIT • ᚲᚱᚨᚦᚢᚠ"
            setTextColor(Color.parseColor("#FDE047"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(4))
        })

        root.addView(TextView(this).apply {
            text = "⚡ GOD OF THUNDER COMMAND HUB"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        })

        // ====================================================================
        // SECTION 1: FLOATING THOR TOGGLE + RAVEN MESSAGING CENTER DIRECTLY BELOW
        // ====================================================================
        root.addView(buildSectionHeader("🎙️ VOICE OVERLAY & RAVEN MESSAGING"))

        val singleToggleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(15), dp(18), dp(15))
            background = makeCardDrawable("#310D13", "#EF4444", 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }

            val tView = TextView(this@MainActivity).apply {
                text = "🔴 FLOATING THOR: OFF"
                setTextColor(Color.parseColor("#FEE2E2"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            val sView = TextView(this@MainActivity).apply {
                text = "Tap to TURN ON floating voice icon over any screen"
                setTextColor(Color.parseColor("#FCA5A5"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(4), dp(6), 0)
            }
            voiceToggleTitleTv = tView
            voiceToggleSubTv = sView
            addView(tView)
            addView(sView)
            setOnClickListener { toggleFloatingThorOverlay() }
        }
        voiceToggleCardRef = singleToggleCard
        root.addView(singleToggleCard)

        val displayShareCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(13), dp(18), dp(13))
            background = makeCardDrawable("#0F172A", "#38BDF8", 22)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }

            val dsTitle = TextView(this@MainActivity).apply {
                text = "📱 DISPLAY SHARE (2-GEAR HD): OFF"
                setTextColor(Color.parseColor("#E0F2FE"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            val dsSub = TextView(this@MainActivity).apply {
                text = "Tap to share live screen with Thor (720p fast + 1:1 native UI auto-shift, zero files saved)"
                setTextColor(Color.parseColor("#93C5FD"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(3), dp(6), 0)
            }
            displayShareTitleTv = dsTitle
            displayShareSubTv = dsSub
            addView(dsTitle)
            addView(dsSub)
            setOnClickListener {
                if (ThorDisplayShareService.isDisplayShareActive) {
                    ThorDisplayShareService.stopSharing(this@MainActivity)
                    Toast.makeText(this@MainActivity, "Display Share OFF", Toast.LENGTH_SHORT).show()
                } else {
                    DisplayShareConsentActivity.launchConsent(this@MainActivity)
                }
                toggleUiHandler.postDelayed({ updateVoiceToggleButtonAppearance() }, 250L)
            }
        }
        displayShareCardRef = displayShareCard
        root.addView(displayShareCard)

        val hwRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        val tCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = makeCardDrawable("#172033", "#64748B", 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            val tt = TextView(this@MainActivity).apply {
                text = "🔦 TORCH: OFF"; setTextColor(Color.parseColor("#FEF08A")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            }
            val ts = TextView(this@MainActivity).apply {
                text = "Standalone LED"; setTextColor(Color.parseColor("#FDE68A")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f); gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0)
            }
            torchTitleTv = tt; addView(tt); addView(ts)
            setOnClickListener {
                val on = ThorFlashlightAndLensService.toggleTorch(this@MainActivity)
                Toast.makeText(this@MainActivity, if (on) "🔦 Flashlight ON" else "Flashlight OFF", Toast.LENGTH_SHORT).show()
                updateVoiceToggleButtonAppearance()
            }
        }
        torchCardRef = tCard

        val cCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = makeCardDrawable("#0F172A", "#64748B", 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) }
            val ct = TextView(this@MainActivity).apply {
                text = "📷 REAR CAM: OFF"; setTextColor(Color.parseColor("#D1FAE5")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            }
            val cs = TextView(this@MainActivity).apply {
                text = "Live Mechanic Lens"; setTextColor(Color.parseColor("#6EE7B7")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f); gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0)
            }
            rearCamTitleTv = ct; addView(ct); addView(cs)
            setOnClickListener {
                if (ThorFlashlightAndLensService.isRearCameraActive) {
                    ThorFlashlightAndLensService.stopRearCamera(this@MainActivity)
                    Toast.makeText(this@MainActivity, "Rear Camera OFF", Toast.LENGTH_SHORT).show()
                } else {
                    ThorFlashlightAndLensService.startRearCamera(this@MainActivity, ThorFlashlightAndLensService.isTorchOn)
                }
                toggleUiHandler.postDelayed({ updateVoiceToggleButtonAppearance() }, 250L)
            }
        }
        rearCamCardRef = cCard
        hwRow.addView(tCard); hwRow.addView(cCard); root.addView(hwRow)

        // DIRECTLY UNDERNEATH FLOATING THOR BUTTON: Raven Messaging Center with Raven-on-Pedestal Button
        root.addView(buildRavenMessagingCenterCard())

        // WO-159: 1-Tap Mjolnir Storm Screensaver Launcher + Android System Screen Saver Picker
        val ssRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (10 * resources.displayMetrics.density).toInt() }
        }
        fun makeGoldBtn(titleTxt: String, subTxt: String, marginEndDp: Int, action: () -> Unit): LinearLayout {
            val d = resources.displayMetrics.density
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (marginEndDp * d).toInt()
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#1A1305"))
                    setStroke((1.5f * d).toInt(), android.graphics.Color.parseColor("#EAB308"))
                    cornerRadius = 14f * d
                }
                addView(TextView(this@MainActivity).apply {
                    text = titleTxt
                    setTextColor(android.graphics.Color.parseColor("#FEF08A"))
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text = subTxt
                    setTextColor(android.graphics.Color.parseColor("#CBD5E1"))
                    textSize = 10.5f
                    gravity = android.view.Gravity.CENTER
                })
                setOnClickListener { action() }
            }
        }
        ssRow.addView(makeGoldBtn("Mjolnir Screensaver", "Tap to Launch Full-Screen", 6) {
            startActivity(Intent(this@MainActivity, ThorScreensaverActivity::class.java))
        })
        ssRow.addView(makeGoldBtn("Screen Saver Setup", "Set Default Charging Saver", 0) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_DREAM_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS))
            }
        })
        root.addView(ssRow)

        // ====================================================================
        // SECTION 2: THOR SOVEREIGN REALMS
        // ====================================================================
        root.addView(buildSectionHeader("🪐 THOR SOVEREIGN REALMS"))

        root.addView(buildCurvedNorseMedallion(
            title = "🪐 Open 3D Viking Sanctum & Note Orbits",
            subtitle = "3D Concentric Note Orbits, Car Dials, 2015 Impala TPMS Wizard & Quick-Comms",
            borderColor = "#4ADE80",
            bgColor = "#0D2B1D"
        ) {
            openInBrowser("$CLOUD_URL/notes?key=$SOVEREIGN_TOKEN&v=${System.currentTimeMillis()}")
        })

        root.addView(buildCurvedNorseMedallion(
            title = "💬 Thor Full Chat & Vision Console",
            subtitle = "Full-screen browser chat, Vision Studio & reasoning log",
            borderColor = "#F5B942",
            bgColor = "#271C09"
        ) {
            openInBrowser("$CLOUD_URL/chat?key=$SOVEREIGN_TOKEN&v=${System.currentTimeMillis()}")
        })

        root.addView(buildCurvedNorseMedallion(
            title = "🔨 3D Mjolnir Runic Altar (Observatory)",
            subtitle = "Interactive 3D bossed Viking war-shields, runestones & live code X-Ray",
            borderColor = "#A855F7",
            bgColor = "#1E1035"
        ) {
            openInBrowser("$CLOUD_URL/observatory?key=$SOVEREIGN_TOKEN&v=${System.currentTimeMillis()}")
        })

        // ====================================================================
        // SECTION 3: AI PLATFORMS, SUPABASE & CLOUD LINKS
        // ====================================================================
        root.addView(buildSectionHeader("🏛️ AI PLATFORMS, SUPABASE & CLOUD LINKS"))

        root.addView(buildCurvedNorseMedallion(
            title = "✨ Google Gemini",
            subtitle = "Direct link to Google Gemini chat workspace",
            borderColor = "#60A5FA",
            bgColor = "#0B192C"
        ) {
            openInBrowser("https://gemini.google.com/app")
        })

        root.addView(buildCurvedNorseMedallion(
            title = "🤖 OpenAI ChatGPT",
            subtitle = "Direct link to ChatGPT workspace",
            borderColor = "#34D399",
            bgColor = "#0A261E"
        ) {
            openInBrowser("https://chatgpt.com")
        })

        root.addView(buildCurvedNorseMedallion(
            title = "🧠 Anthropic Claude",
            subtitle = "Direct link to Claude AI workspace",
            borderColor = "#FB923C",
            bgColor = "#2A180B"
        ) {
            openInBrowser("https://claude.ai")
        })

        root.addView(buildCurvedNorseMedallion(
            title = "🧪 Google AI Studio (API Keys & Spend Cap)",
            subtitle = "Manage Gemini API keys, quotas, and monthly spend cap",
            borderColor = "#FBBF24",
            bgColor = "#261D08"
        ) {
            openInBrowser("https://aistudio.google.com/app/apikey")
        })

        root.addView(buildCurvedNorseMedallion(
            title = "💳 Google AI Studio Billing & Spend Cap",
            subtitle = "Direct link to https://ai.studio/spend to adjust monthly cap",
            borderColor = "#FDE047",
            bgColor = "#231F07"
        ) {
            openInBrowser("https://ai.studio/spend")
        })

        root.addView(buildCurvedNorseMedallion(
            title = "⚡ Supabase Database Dashboard",
            subtitle = "Manage Thor PostgreSQL tables, Hippocampus memory & vectors",
            borderColor = "#22C55E",
            bgColor = "#092415"
        ) {
            openInBrowser("https://supabase.com/dashboard")
        })

        root.addView(buildCurvedNorseMedallion(
            title = "☁️ Google Cloud Run (thor-cloud-engine)",
            subtitle = "Inspect thor-api Single-Cloud Engine & Local Pre-Deploy Gate",
            borderColor = "#38BDF8",
            bgColor = "#0A1E32"
        ) {
            openInBrowser("https://console.cloud.google.com/run?project=thor-cloud-engine")
        })

        root.addView(buildCurvedNorseMedallion(
            title = "🏛️ Anthropic API Console",
            subtitle = "Manage Claude API keys, credits & usage limits",
            borderColor = "#F97316",
            bgColor = "#271409"
        ) {
            openInBrowser("https://console.anthropic.com")
        })

        root.addView(buildCurvedNorseMedallion(
            title = "🔑 OpenAI API Platform",
            subtitle = "Manage OpenAI API keys, billing & audio/GPT usage",
            borderColor = "#10B981",
            bgColor = "#08221A"
        ) {
            openInBrowser("https://platform.openai.com")
        })

        root.addView(buildCurvedNorseMedallion(
            title = "🐙 GitHub Repository",
            subtitle = "Open Project Thor source code & commits on GitHub",
            borderColor = "#94A3B8",
            bgColor = "#111827"
        ) {
            openInBrowser("https://github.com/cfrosth-eng/Thor")
        })

        scroll.addView(root)
        setContentView(scroll)

        routeIncomingAndroidShareIntent(intent)
    }

    private fun routeIncomingAndroidShareIntent(src: Intent?) {
        if (src?.action != Intent.ACTION_SEND) return
        val mime = src.type ?: ""
        val sharedText = src.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            src.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            src.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (sharedText.isNotEmpty() || streamUri != null) {
            val rcIntent = Intent(this, RavenCenterActivity::class.java).apply {
                putExtra(RavenCenterActivity.EXTRA_OPEN_COMPOSE, true)
                putExtra(RavenCenterActivity.EXTRA_SHARED_TEXT, sharedText)
                putExtra(RavenCenterActivity.EXTRA_SHARED_URI, streamUri?.toString() ?: "")
                putExtra(RavenCenterActivity.EXTRA_SHARED_MIME, mime)
            }
            src.action = ""
            startActivity(rcIntent)
        }
    }

    private fun buildRavenMessagingCenterCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(16), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#13203A"), Color.parseColor("#091020"))
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setStroke(dp(2), Color.parseColor("#F5B942"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val thumbRadius = dp(14).toFloat()
        val ravenBtnFrame = FrameLayout(this).apply {
            background = makeCardDrawable("#060B16", "#FDE047", 16)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(dp(68), dp(84)).apply {
                marginEnd = dp(14)
            }
        }
        val clippedArt = FrameLayout(this).apply {
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
        clippedArt.addView(ImageView(this).apply {
            setImageResource(R.drawable.raven_storm_base_hd)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })
        clippedArt.addView(ImageView(this).apply {
            setImageResource(R.drawable.raven_runes_gold_hd)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })
        ravenBtnFrame.addView(clippedArt)
        card.addView(ravenBtnFrame)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = "🦅 RAVEN MESSAGING CENTER"
            setTextColor(Color.parseColor("#FDE047"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.DEFAULT_BOLD
        })
        textCol.addView(TextView(this).apply {
            text = "Open Received & Sent folders, or tap ＋ to send screenshots, photos, videos & files to Thor"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, dp(3), 0, dp(5))
        })
        ravenCenterBadgeTv = TextView(this).apply {
            text = "📥 Received • 📤 Sent • ✉ New Message (＋ Attach)"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        textCol.addView(ravenCenterBadgeTv)
        card.addView(textCol)

        val openCenter = {
            startActivity(Intent(this, RavenCenterActivity::class.java))
        }
        ravenBtnFrame.setOnClickListener { openCenter() }
        card.setOnClickListener { openCenter() }
        return card
    }

    private fun refreshRavenFolderCountsBadge() {
        uiIo.execute {
            try {
                val dao = ThorDatabase.getDatabase(applicationContext).ravenMessageDao()
                val recv = dao.getCountByFolder("RECEIVED")
                val unread = dao.getUnreadReceivedCount()
                val sent = dao.getCountByFolder("SENT")
                val label = if (unread > 0) {
                    "📥 Received: $recv ($unread NEW)  •  📤 Sent: $sent"
                } else {
                    "📥 Received: $recv  •  📤 Sent: $sent  •  ＋ New Message"
                }
                runOnUiThread { ravenCenterBadgeTv?.text = label }
            } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        updateVoiceToggleButtonAppearance()
        refreshRavenFolderCountsBadge()
        toggleUiHandler.removeCallbacks(toggleUiTicker)
        toggleUiHandler.post(toggleUiTicker)
    }

    override fun onPause() {
        super.onPause()
        toggleUiHandler.removeCallbacks(toggleUiTicker)
    }

    private fun buildSectionHeader(label: String): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
        }
    }

    private fun makeCardDrawable(bgHex: String, borderHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(bgHex))
            setStroke(dp(2), Color.parseColor(borderHex))
        }
    }

    private fun buildCurvedNorseMedallion(
        title: String,
        subtitle: String,
        borderColor: String,
        bgColor: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = makeCardDrawable(bgColor, borderColor, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(11)
            }

            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                setTextColor(Color.parseColor("#CBD5E1"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(3), dp(6), 0)
            })
            setOnClickListener { onClick() }
        }
    }

    private fun openInBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open link: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleFloatingThorOverlay() {
        if (FloatingThorService.isServiceRunning) {
            stopFloatingThorOverlay()
        } else {
            launchFloatingThorOverlay()
        }
        toggleUiHandler.postDelayed({ updateVoiceToggleButtonAppearance() }, 180L)
        toggleUiHandler.postDelayed({ updateVoiceToggleButtonAppearance() }, 500L)
    }

    private fun updateVoiceToggleButtonAppearance() {
        val card = voiceToggleCardRef ?: return
        val isOn = FloatingThorService.isServiceRunning
        voiceToggleTitleTv?.text = if (isOn) "🟢 FLOATING THOR: ON (ACTIVE)" else "🔴 FLOATING THOR: OFF"
        voiceToggleTitleTv?.setTextColor(Color.parseColor(if (isOn) "#DCFCE7" else "#FEE2E2"))
        voiceToggleSubTv?.text = if (isOn) {
            "Tap to TURN OFF floating icon & release microphone"
        } else {
            "Tap to TURN ON floating voice icon over any screen"
        }
        voiceToggleSubTv?.setTextColor(Color.parseColor(if (isOn) "#86EFAC" else "#FCA5A5"))
        card.background = makeCardDrawable(
            bgHex = if (isOn) "#082E1A" else "#310D13",
            borderHex = if (isOn) "#22C55E" else "#EF4444",
            radiusDp = 24
        )
        displayShareCardRef?.let { dsCard ->
            val dsOn = ThorDisplayShareService.isDisplayShareActive
            displayShareTitleTv?.text = if (dsOn) "🟢 DISPLAY SHARE (2-GEAR HD): ON" else "📱 DISPLAY SHARE (2-GEAR HD): OFF"
            displayShareTitleTv?.setTextColor(Color.parseColor(if (dsOn) "#DCFCE7" else "#E0F2FE"))
            displayShareSubTv?.text = if (dsOn) {
                "In-RAM 720p + 1:1 Native Full-Res auto-shift active — tap to STOP"
            } else {
                "Tap to share live screen with Thor (720p fast + 1:1 native UI auto-shift, zero files saved)"
            }
            displayShareSubTv?.setTextColor(Color.parseColor(if (dsOn) "#86EFAC" else "#93C5FD"))
            dsCard.background = makeCardDrawable(
                bgHex = if (dsOn) "#063224" else "#0F172A",
                borderHex = if (dsOn) "#34D399" else "#38BDF8",
                radiusDp = 22
            )
        }
        torchCardRef?.let { tc ->
            val tOn = ThorFlashlightAndLensService.isTorchOn
            torchTitleTv?.text = if (tOn) "🔦 TORCH: ON" else "🔦 TORCH: OFF"
            tc.background = makeCardDrawable(if (tOn) "#422006" else "#172033", if (tOn) "#FACC15" else "#64748B", 20)
        }
        rearCamCardRef?.let { cc ->
            val cOn = ThorFlashlightAndLensService.isRearCameraActive
            rearCamTitleTv?.text = if (cOn) "🟢 REAR CAM: ON" else "📷 REAR CAM: OFF"
            cc.background = makeCardDrawable(if (cOn) "#063224" else "#0F172A", if (cOn) "#34D399" else "#64748B", 20)
        }
    }

    private fun launchFloatingThorOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable 'Appear on top' for ThorMobile", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        try {
            getSharedPreferences("thor_overlay_prefs", MODE_PRIVATE).edit().putBoolean("user_stopped", false).apply()
            FloatingThorService.userRequestedStop = false
            ContextCompat.startForegroundService(this, Intent(this, FloatingThorService::class.java))
            Toast.makeText(this, "🟢 Floating Thor ON", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Overlay error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopFloatingThorOverlay() {
        try {
            getSharedPreferences("thor_overlay_prefs", MODE_PRIVATE).edit().putBoolean("user_stopped", true).apply()
            FloatingThorService.userRequestedStop = true
            val stopIntent = Intent(this, FloatingThorService::class.java).apply {
                action = FloatingThorService.ACTION_STOP
            }
            try {
                if (FloatingThorService.isServiceRunning) {
                    startService(stopIntent)
                }
            } catch (_: Exception) {}
            stopService(Intent(this, FloatingThorService::class.java))
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm?.cancel(1)
            toggleUiHandler.postDelayed({ updateVoiceToggleButtonAppearance() }, 250L)
            Toast.makeText(this, "🔴 Floating Thor OFF", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Stop note: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}

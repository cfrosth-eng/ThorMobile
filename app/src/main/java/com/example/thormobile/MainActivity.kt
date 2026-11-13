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

        // DIRECTLY UNDERNEATH FLOATING THOR BUTTON: Raven Messaging Center with Raven-on-Pedestal Button
        root.addView(buildRavenMessagingCenterCard())

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

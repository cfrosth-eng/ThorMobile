package com.example.thormobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * THOR V21.2 SOVEREIGN COCKPIT & COMMAND HUB
 * - Opens and STAYS inside the native app (NEVER auto-redirects to a web browser on launch).
 * - Prominent Floating Thor Voice controls right at the top.
 * - Restores all direct links for Gemini, ChatGPT, Claude, Google AI Studio, Supabase, Cloud Run, and GitHub.
 */
class MainActivity : AppCompatActivity() {

    private val CLOUD_URL = "https://thor-api-443941171993.us-east4.run.app"
    private val SOVEREIGN_TOKEN = "9d943a8005992357c7d6ca29278e803b80521c4d0d703e1ad74fb141ac050d19"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.post { applyThorNorseArtDirector() }
        window.statusBarColor = Color.parseColor("#040812")
        window.navigationBarColor = Color.parseColor("#040812")

        requestNeededPermissions()
        if (Settings.canDrawOverlays(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            // Manual toggle only: opening the app never auto-summons the floating icon
        }
        ObdLinkAutoWatchdog.startAutonomousWatchdog(CLOUD_URL)
        VikingPopupOverlayManager.start(this, CLOUD_URL)

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

        // Top Runic Header
        root.addView(TextView(this).apply {
            text = "ᚠᚢᚦᚨᚱᚲ • THOR V21.2 SOVEREIGN COCKPIT • ᚲᚱᚨᚦᚢᚠ"
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
        // SECTION 1: FLOATING THOR VOICE ASSISTANT CONTROLS
        // ====================================================================
        root.addView(buildSectionHeader("🎙️ FLOATING THOR VOICE OVERLAY"))

        val singleToggleCard = buildCurvedNorseMedallion(
            title = "🔴 FLOATING THOR: OFF",
            subtitle = "Tap to TURN ON floating voice icon",
            borderColor = "#EF4444",
            bgColor = "#310D13"
        ) {
            toggleFloatingThorOverlay()
        }.apply {
            tag = "THOR_SINGLE_VOICE_TOGGLE"
        }
        voiceToggleCardRef = singleToggleCard
        root.addView(singleToggleCard)

        // ====================================================================
        // SECTION 2: THOR 3D SANCTUM, CHAT & OBSERVATORY REALMS
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
            subtitle = "Full-screen chat, screenshot attachment & Behind-the-Curtain reasoning log",
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
        // SECTION 3: RESTORED DIRECT LINKS (AI PLATFORMS, SUPABASE & CLOUD)
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
            openInBrowser("https://github.com")
        })

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun buildSectionHeader(label: String): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
            layoutParams = lp
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
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor(bgColor))
                setStroke(dp(2), Color.parseColor(borderColor))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(11)
            }
            layoutParams = lp

            val tView = TextView(this@MainActivity).apply {
                text = title
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            val sView = TextView(this@MainActivity).apply {
                text = subtitle
                setTextColor(Color.parseColor("#CBD5E1"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(3), dp(6), 0)
            }
            addView(tView)
            addView(sView)
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

        private var voiceToggleCardRef: LinearLayout? = null
    private val toggleUiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val toggleUiTicker = object : Runnable {
        override fun run() {
            updateVoiceToggleButtonAppearance()
            toggleUiHandler.postDelayed(this, 400L)
        }
    }

    override fun onResume() {
        super.onResume()
        updateVoiceToggleButtonAppearance()
        toggleUiHandler.removeCallbacks(toggleUiTicker)
        toggleUiHandler.post(toggleUiTicker)
    }

    override fun onPause() {
        super.onPause()
        toggleUiHandler.removeCallbacks(toggleUiTicker)
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
        val density = resources.displayMetrics.density
        val tvs = mutableListOf<TextView>()
        collectNestedTextViews(card, tvs)
        if (tvs.isNotEmpty()) {
            tvs[0].text = if (isOn) "🟢 FLOATING THOR: ON (ACTIVE)" else "🔴 FLOATING THOR: OFF"
            tvs[0].setTextColor(Color.parseColor(if (isOn) "#DCFCE7" else "#FEE2E2"))
        }
        if (tvs.size >= 2) {
            tvs[1].text = if (isOn) "Tap to TURN OFF floating icon & release microphone" else "Tap to TURN ON floating voice icon over any screen"
            tvs[1].setTextColor(Color.parseColor(if (isOn) "#86EFAC" else "#FCA5A5"))
        }
        val topHex = if (isOn) "#082E1A" else "#310D13"
        val botHex = if (isOn) "#0D4728" else "#4C111B"
        val borderHex = if (isOn) "#22C55E" else "#EF4444"
        val glowHex = if (isOn) "#6622C55E" else "#66EF4444"
        val runeChar = if (isOn) "🟢" else "🔴"
        card.background = NorseForgedPlaqueDrawable(
            loadAiAssetBitmap("hero_sanctum.png"),
            Color.parseColor(topHex),
            Color.parseColor(botHex),
            Color.parseColor(borderHex),
            Color.parseColor(glowHex),
            runeChar,
            density,
            false
        )
        card.invalidate()
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
            Toast.makeText(this, "\uD83D\uDFE2 Floating Thor ON", Toast.LENGTH_SHORT).show()
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
            nm?.cancelAll()
            toggleUiHandler.postDelayed({
                nm?.cancel(1)
                nm?.cancelAll()
                updateVoiceToggleButtonAppearance()
            }, 250L)
            Toast.makeText(this, "\uD83D\uDD34 Floating Thor OFF", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Stop note: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)
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


// === [THOR NORSE ART DIRECTOR MOBILE ENGINE START] ===
    private data class ThorNorseEntry(
        val role: String,
        val rune: String,
        val norseName: String,
        val functionalSubtitle: String
    )

    private val thorNorseLexicon: Map<String, ThorNorseEntry> = mapOf(
        "🎙️ FLOATING THOR VOICE OVERLAY" to ThorNorseEntry("SECTION_HEADER", "ᚦ", "THOR RÖST", "Floating voice overlay controls"),
        "🪐 THOR SOVEREIGN REALMS" to ThorNorseEntry("SECTION_HEADER", "ᛟ", "NÍU HEIMAR", "3D sanctums and consoles"),
        "🏛️ AI PLATFORMS, SUPABASE & CLOUD LINKS" to ThorNorseEntry("SECTION_HEADER", "ᚱ", "ALLFÖÐR SMÍÐJA", "External cloud and AI bridges"),
        "⚡ Summon Floating Thor (Voice)" to ThorNorseEntry("MEDALLION_CARD", "ᛋ", "MJÖLNIR KALL", "Launch floating voice companion"),
        "🛑 Stop Floating Thor Overlay" to ThorNorseEntry("MEDALLION_CARD", "ᚺ", "ÞÖGN BÚR", "Dismiss overlay and microphone"),
        "🪐 Open 3D Viking Sanctum & Note Orbits" to ThorNorseEntry("MEDALLION_CARD", "ᛗ", "HUGINN BRAUT", "Launch 3D sanctum & telemetry"),
        "💬 Thor Full Chat & Vision Console" to ThorNorseEntry("MEDALLION_CARD", "ᚨ", "MUNINN SJÓN", "Open full vision chat console"),
        "🔨 3D Mjolnir Runic Altar (Observatory)" to ThorNorseEntry("MEDALLION_CARD", "ᛏ", "HAMARR HOLL", "Inspect interactive runic war altar"),
        "✨ Google Gemini" to ThorNorseEntry("MEDALLION_CARD", "ᚷ", "GLADSHEIM ELDR", "Open Google Gemini workspace"),
        "🤖 OpenAI ChatGPT" to ThorNorseEntry("MEDALLION_CARD", "ᚲ", "MÍMIR HOF", "Open ChatGPT intelligence bridge"),
        "🧠 Anthropic Claude" to ThorNorseEntry("MEDALLION_CARD", "ᛖ", "VÖLVA DÚMR", "Open Claude reasoning workspace"),
        "🧪 Google AI Studio (API Keys & Spend Cap)" to ThorNorseEntry("MEDALLION_CARD", "ᚹ", "RÚNA SÝN", "Configure Gemini API keys"),
        "💳 Google AI Studio Billing & Spend Cap" to ThorNorseEntry("MEDALLION_CARD", "ᚠ", "GULL BÚÐ", "Manage monthly API budget"),
        "⚡ Supabase Database Dashboard" to ThorNorseEntry("MEDALLION_CARD", "ᛞ", "URÐAR BRUNNR", "Access PostgreSQL neural memory"),
        "☁️ Google Cloud Run (thor-cloud-engine)" to ThorNorseEntry("MEDALLION_CARD", "ᛚ", "VINDHELLIR SMÍÐ", "Inspect live microservice instances"),
        "🏛️ Anthropic API Console" to ThorNorseEntry("MEDALLION_CARD", "ᛒ", "FRÓÐI SKRÁ", "Manage Claude tokens and quotas"),
        "🔑 OpenAI API Platform" to ThorNorseEntry("MEDALLION_CARD", "ᛉ", "LOKI LÝKILL", "Manage OpenAI keys and usage"),
        "🐙 GitHub Repository" to ThorNorseEntry("MEDALLION_CARD", "ᛟ", "KÓÐI SAGA", "Browse Thor codebase commits")
    )

    private data class ThorMobileRealmTheme(
        val id: String,
        val shortTab: String,
        val hallTitle: String,
        val hallSubtitle: String,
        val bgHex: String,
        val cardTopHex: String,
        val cardBottomHex: String,
        val borderHex: String,
        val glowHex: String,
        val titleTextHex: String,
        val subTextHex: String
    )

    private val thorMobileThemes: List<ThorMobileRealmTheme> = listOf(
        ThorMobileRealmTheme(
            id = "HLIDSKJALF",
            shortTab = "ᛟ Hlidskjálf",
            hallTitle = "ᛟ HLIDSKJÁLF — ODIN'S HIGH WATCHTOWER",
            hallSubtitle = "Forged Black Iron & Hearth Amber • 2-Column Portal Deck",
            bgHex = "#050811",
            cardTopHex = "#151B29",
            cardBottomHex = "#24170B",
            borderHex = "#F59E0B",
            glowHex = "#55F59E0B",
            titleTextHex = "#FEF3C7",
            subTextHex = "#FCD34D"
        ),
        ThorMobileRealmTheme(
            id = "NIFLHEIM",
            shortTab = "ᚺ Niflheim",
            hallTitle = "ᚺ BIFRÖST & NIFLHEIM — GLACIAL STARLIGHT",
            hallSubtitle = "Nordic Fjord Ice-Silver Runestones & Starlight Filigree",
            bgHex = "#030B17",
            cardTopHex = "#0A1E38",
            cardBottomHex = "#0E3150",
            borderHex = "#38BDF8",
            glowHex = "#5538BDF8",
            titleTextHex = "#E0F2FE",
            subTextHex = "#7DD3FC"
        ),
        ThorMobileRealmTheme(
            id = "VALHALLA",
            shortTab = "ᛏ Valhalla",
            hallTitle = "ᛏ VALHALLA GREAT HALL — DRAGON-OAK & BRONZE",
            hallSubtitle = "Stave-Church Carved Oak & Hammered Bronze Shields",
            bgHex = "#110905",
            cardTopHex = "#26150D",
            cardBottomHex = "#3B1E0E",
            borderHex = "#D97706",
            glowHex = "#55D97706",
            titleTextHex = "#FFEDD5",
            subTextHex = "#FDBA74"
        ),
        ThorMobileRealmTheme(
            id = "MUSPELHEIM",
            shortTab = "ᚲ Muspelheim",
            hallTitle = "ᚲ SURTR'S FORGE — OBSIDIAN & LIVING EMBER",
            hallSubtitle = "Volcanic Obsidian Glass & Molten Fire-Gold Runes",
            bgHex = "#0D0507",
            cardTopHex = "#210E12",
            cardBottomHex = "#38110B",
            borderHex = "#F97316",
            glowHex = "#55F97316",
            titleTextHex = "#FFEDD5",
            subTextHex = "#FB923C"
        ),
        ThorMobileRealmTheme(
            id = "YGGDRASIL",
            shortTab = "ᛉ Yggdrasil",
            hallTitle = "ᛉ YGGDRASIL — WORLD-TREE SANCTUARY",
            hallSubtitle = "Ancient Ash Bark & Living Emerald Runestones",
            bgHex = "#03100B",
            cardTopHex = "#0A261B",
            cardBottomHex = "#103B2A",
            borderHex = "#10B981",
            glowHex = "#5510B981",
            titleTextHex = "#D1FAE5",
            subTextHex = "#6EE7B7"
        ),
        ThorMobileRealmTheme(
            id = "CLASSIC",
            shortTab = "🏛️ Original",
            hallTitle = "GOD OF THUNDER COMMAND HUB (ORIGINAL)",
            hallSubtitle = "Original 1-Column Stack Restored",
            bgHex = "#040812",
            cardTopHex = "#0F172A",
            cardBottomHex = "#1E293B",
            borderHex = "#38BDF8",
            glowHex = "#2238BDF8",
            titleTextHex = "#F8FAFC",
            subTextHex = "#94A3B8"
        )
    )

    private val cachedAiBitmaps = mutableMapOf<String, android.graphics.Bitmap?>()

    private fun loadAiAssetBitmap(fileName: String): android.graphics.Bitmap? {
        if (cachedAiBitmaps.containsKey(fileName)) return cachedAiBitmaps[fileName]
        return try {
            assets.open("norse_art/$fileName").use { stream ->
                val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                cachedAiBitmaps[fileName] = bmp
                bmp
            }
        } catch (_: Throwable) {
            cachedAiBitmaps[fileName] = null
            null
        }
    }

    private class NorseForgedPlaqueDrawable(
        private val artBitmap: android.graphics.Bitmap?,
        private val topColor: Int,
        private val bottomColor: Int,
        private val borderColor: Int,
        private val glowColor: Int,
        private val runeText: String,
        private val density: Float,
        private val isSquareTile: Boolean
    ) : android.graphics.drawable.Drawable() {

        private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
        private val bmpPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            alpha = 195
        }
        private val scrimPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
        private val outerStrokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            color = borderColor
            strokeWidth = (if (isSquareTile) 1.8f else 2.2f) * density
        }
        private val innerStrokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            color = borderColor
            alpha = 115
            strokeWidth = 0.9f * density
        }
        private val rivetPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = borderColor
            alpha = 230
        }
        private val medallionRingPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            color = borderColor
            alpha = 195
            strokeWidth = 1.4f * density
        }
        private val runePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            alpha = 245
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
            textSize = (if (isSquareTile) 17f else 20f) * density
        }

        private fun buildChamferPath(left: Float, top: Float, right: Float, bottom: Float, cut: Float): android.graphics.Path {
            val p = android.graphics.Path()
            p.moveTo(left + cut, top)
            p.lineTo(right - cut, top)
            p.lineTo(right, top + cut)
            p.lineTo(right, bottom - cut)
            p.lineTo(right - cut, bottom)
            p.lineTo(left + cut, bottom)
            p.lineTo(left, bottom - cut)
            p.lineTo(left, top + cut)
            p.close()
            return p
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 10f || h <= 10f) return

            val pad = 2f * density
            val cut = (if (isSquareTile) 11f else 13f) * density
            val outerPath = buildChamferPath(pad, pad, w - pad, h - pad, cut)

            val saveCount = canvas.save()
            canvas.clipPath(outerPath)

            fillPaint.shader = android.graphics.LinearGradient(
                0f, 0f, w, h,
                intArrayOf(topColor, bottomColor, topColor),
                floatArrayOf(0f, 0.65f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, fillPaint)

            if (artBitmap != null && !artBitmap.isRecycled) {
                val dstRect = android.graphics.RectF(0f, 0f, w, h)
                canvas.drawBitmap(artBitmap, null, dstRect, bmpPaint)
                scrimPaint.shader = android.graphics.RadialGradient(
                    w * 0.45f, h * 0.55f, kotlin.math.max(w, h) * 0.78f,
                    intArrayOf(
                        android.graphics.Color.argb(205, 5, 8, 17),
                        android.graphics.Color.argb(145, 5, 8, 17),
                        topColor and 0x00FFFFFF or (175 shl 24)
                    ),
                    floatArrayOf(0f, 0.65f, 1f),
                    android.graphics.Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, scrimPaint)
            }
            canvas.restoreToCount(saveCount)

            val inPad = 5.5f * density
            val innerPath = buildChamferPath(inPad, inPad, w - inPad, h - inPad, cut * 0.7f)
            canvas.drawPath(innerPath, innerStrokePaint)
            canvas.drawPath(outerPath, outerStrokePaint)

            val rOff = 9f * density
            val rRad = 2.0f * density
            canvas.drawCircle(rOff, rOff, rRad, rivetPaint)
            canvas.drawCircle(w - rOff, rOff, rRad, rivetPaint)
            canvas.drawCircle(rOff, h - rOff, rRad, rivetPaint)
            canvas.drawCircle(w - rOff, h - rOff, rRad, rivetPaint)

            if (runeText.isNotEmpty()) {
                val radius = (if (isSquareTile) 13.5f else 17f) * density
                val cx = if (isSquareTile) (w - 20f * density) else (w - 26f * density)
                val cy = if (isSquareTile) (20f * density) else (h / 2f)

                val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.FILL
                    shader = android.graphics.RadialGradient(
                        cx, cy, radius * 1.6f,
                        intArrayOf(glowColor, android.graphics.Color.TRANSPARENT),
                        null,
                        android.graphics.Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(cx, cy, radius * 1.6f, glowPaint)
                canvas.drawCircle(cx, cy, radius, medallionRingPaint)
                val fm = runePaint.fontMetrics
                val ty = cy - (fm.ascent + fm.descent) / 2f
                canvas.drawText(runeText, cx, ty, runePaint)
            }
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private var thorOriginalRootChildren: List<android.view.View>? = null
    private val thorOriginalViewTexts = mutableMapOf<Int, CharSequence>()
    private val thorOriginalTextColors = mutableMapOf<Int, Int>()

    private fun lookupThorNorseEntry(rawText: String): ThorNorseEntry? {
        val cleaned = rawText.replace("&amp;", "&").trim()
        if (cleaned.isEmpty()) return null
        thorNorseLexicon[cleaned]?.let { return it }
        for ((k, v) in thorNorseLexicon) {
            val kClean = k.replace("&amp;", "&").trim()
            if (cleaned.equals(kClean, ignoreCase = true)) return v
            if (kClean.length >= 6 && (cleaned.contains(kClean, ignoreCase = true) || kClean.contains(cleaned, ignoreCase = true))) {
                return v
            }
        }
        return null
    }

    private fun detachFromParent(v: android.view.View) {
        (v.parent as? android.view.ViewGroup)?.removeView(v)
    }

    private fun collectNestedTextViews(v: android.view.View, out: MutableList<android.widget.TextView>) {
        if (v is android.widget.TextView) {
            out.add(v)
        } else if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                collectNestedTextViews(v.getChildAt(i), out)
            }
        }
    }

    private fun applyThorNorseArtDirector(selectedThemeId: String? = null) {
        try {
            // Hide any redundant system ActionBar title so 'ThorMobile / God of Thunder' never duplicates at the top
            try { actionBar?.hide() } catch (_: Throwable) {}

            val prefs = getSharedPreferences("thor_norse_mobile_vault", android.content.Context.MODE_PRIVATE)
            val autoRotate = prefs.getBoolean("auto_rotate_themes", false)
            var activeId = selectedThemeId ?: prefs.getString("active_theme_id", "HLIDSKJALF") ?: "HLIDSKJALF"

            if (selectedThemeId == null && autoRotate) {
                val nonClassic = thorMobileThemes.filter { it.id != "CLASSIC" }
                val nextIdx = ((prefs.getInt("last_rot_idx", -1) + 1) % nonClassic.size)
                prefs.edit().putInt("last_rot_idx", nextIdx).apply()
                activeId = nonClassic[nextIdx].id
            }
            prefs.edit().putString("active_theme_id", activeId).apply()

            val theme = thorMobileThemes.find { it.id == activeId } ?: thorMobileThemes.first()
            val density = resources.displayMetrics.density
            val contentRoot = window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return

            var scrollView: android.widget.ScrollView? = null
            var mainColumn: android.widget.LinearLayout? = null
            fun findScrollAndRoot(v: android.view.View) {
                if (v is android.widget.ScrollView && scrollView == null) {
                    scrollView = v
                    if (v.childCount > 0 && v.getChildAt(0) is android.widget.LinearLayout) {
                        mainColumn = v.getChildAt(0) as android.widget.LinearLayout
                    }
                } else if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) findScrollAndRoot(v.getChildAt(i))
                }
            }
            findScrollAndRoot(contentRoot)
            val col = mainColumn ?: return

            if (thorOriginalRootChildren == null) {
                val initialList = mutableListOf<android.view.View>()
                for (i in 0 until col.childCount) {
                    val ch = col.getChildAt(i)
                    if (ch.tag != "THOR_NORSE_SWITCHER_BAR") {
                        initialList.add(ch)
                    }
                }
                thorOriginalRootChildren = initialList

                val allTvs = mutableListOf<android.widget.TextView>()
                for (item in initialList) collectNestedTextViews(item, allTvs)
                for (tv in allTvs) {
                    val idKey = System.identityHashCode(tv)
                    thorOriginalViewTexts[idKey] = tv.text ?: ""
                    thorOriginalTextColors[idKey] = tv.currentTextColor
                }
            }

            val origChildren = thorOriginalRootChildren ?: return

            col.removeAllViews()
            for (item in origChildren) detachFromParent(item)

            val heroBmp = loadAiAssetBitmap("hero_sanctum.png")
            val tileBmp = loadAiAssetBitmap("portal_tile.png")

            scrollView?.setBackgroundColor(android.graphics.Color.parseColor(theme.bgHex))
            col.setBackgroundColor(android.graphics.Color.parseColor(theme.bgHex))

            // 1. Mount the Single Unified Norse Sanctum Crest & Theme Switcher at the very top
            val crestBox = android.widget.LinearLayout(this).apply {
                tag = "THOR_NORSE_SWITCHER_BAR"
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding((14 * density).toInt(), (13 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
                background = NorseForgedPlaqueDrawable(
                    heroBmp,
                    android.graphics.Color.parseColor(theme.cardTopHex),
                    android.graphics.Color.parseColor(theme.cardBottomHex),
                    android.graphics.Color.parseColor(theme.borderHex),
                    android.graphics.Color.parseColor(theme.glowHex),
                    "ᛟ",
                    density,
                    false
                )
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, (12 * density).toInt())
                layoutParams = lp
            }

            val runeRibbon = android.widget.TextView(this).apply {
                text = "ᚠ ᚢ ᚦ ᚨ ᚱ ᚲ • THOR SOVEREIGN SANCTUM • ᚷ ᚹ ᚺ ᚾ ᛁ ᛃ"
                setTextColor(android.graphics.Color.parseColor(theme.borderHex))
                textSize = 9.5f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
            }
            val titleTv = android.widget.TextView(this).apply {
                text = theme.hallTitle
                setTextColor(android.graphics.Color.parseColor(theme.titleTextHex))
                textSize = 14f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
                setPadding(0, (2 * density).toInt(), (44 * density).toInt(), (2 * density).toInt())
            }
            val subTv = android.widget.TextView(this).apply {
                text = theme.hallSubtitle
                setTextColor(android.graphics.Color.parseColor(theme.subTextHex))
                textSize = 10.5f
                setPadding(0, 0, (44 * density).toInt(), (8 * density).toInt())
            }
            crestBox.addView(runeRibbon)
            crestBox.addView(titleTv)
            crestBox.addView(subTv)

            val hScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val chipRow = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }

            for (t in thorMobileThemes) {
                val isSel = (t.id == theme.id)
                val chip = android.widget.Button(this).apply {
                    text = t.shortTab
                    isAllCaps = false
                    textSize = 10.5f
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.parseColor(if (isSel) "#050811" else t.titleTextHex))
                    setPadding((11 * density).toInt(), (4 * density).toInt(), (11 * density).toInt(), (4 * density).toInt())
                    minHeight = (30 * density).toInt()
                    minimumHeight = (30 * density).toInt()
                    backgroundTintList = null
                    val cBg = android.graphics.drawable.GradientDrawable()
                    cBg.cornerRadius = 999f
                    cBg.setColor(android.graphics.Color.parseColor(if (isSel) t.borderHex else "#111A2E"))
                    cBg.setStroke((1.3f * density).toInt(), android.graphics.Color.parseColor(t.borderHex))
                    background = cBg
                    val clp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    clp.setMargins(0, 0, (6 * density).toInt(), 0)
                    layoutParams = clp
                    setOnClickListener {
                        prefs.edit().putBoolean("auto_rotate_themes", false).apply()
                        applyThorNorseArtDirector(t.id)
                    }
                }
                chipRow.addView(chip)
            }
            hScroll.addView(chipRow)
            crestBox.addView(hScroll)
            col.addView(crestBox)

            if (theme.id == "CLASSIC") {
                for (item in origChildren) {
                    detachFromParent(item)
                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, 0, 0, (10 * density).toInt())
                    item.layoutParams = lp
                    val tvs = mutableListOf<android.widget.TextView>()
                    collectNestedTextViews(item, tvs)
                    for (tv in tvs) {
                        val k = System.identityHashCode(tv)
                        thorOriginalViewTexts[k]?.let { tv.text = it }
                        thorOriginalTextColors[k]?.let { tv.setTextColor(it) }
                        tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    col.addView(item)
                }
                return
            }

            // 2. Transform every Medallion Card recursively & arrange into 2-Column Norse Portal Grids!
            val runePalette = listOf("ᛟ", "ᛏ", "ᚺ", "ᛉ", "ᛗ", "ᛋ", "ᚷ", "ᚹ", "ᚨ", "ᚱ", "ᚲ", "ᛒ", "ᛖ", "ᛚ", "ᛞ")
            var runeCounter = 0

            fun styleCardAsNorseTile(card: android.view.ViewGroup, isSquareTile: Boolean, forcedBorderHex: String? = null) {
                val chosenRune = runePalette[runeCounter % runePalette.size]
                runeCounter++

                val childTvs = mutableListOf<android.widget.TextView>()
                collectNestedTextViews(card, childTvs)

                if (childTvs.isNotEmpty()) {
                    val titleView = childTvs[0]
                    val origTitle = (thorOriginalViewTexts[System.identityHashCode(titleView)] ?: titleView.text ?: "").toString().trim()
                    val mapped = lookupThorNorseEntry(origTitle)
                    val rChar = mapped?.rune ?: chosenRune
                    val norseHeadline = mapped?.norseName ?: origTitle

                    titleView.text = "$rChar $norseHeadline"
                    titleView.setTextColor(android.graphics.Color.parseColor(theme.titleTextHex))
                    titleView.textSize = if (isSquareTile) 12.5f else 14f
                    titleView.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
                    titleView.setPadding(0, 0, (26 * density).toInt(), (4 * density).toInt())

                    if (childTvs.size >= 2) {
                        val subView = childTvs[1]
                        val origSub = (thorOriginalViewTexts[System.identityHashCode(subView)] ?: subView.text ?: "").toString().trim()
                        val conciseSub = mapped?.functionalSubtitle ?: if (origSub.length > 52) origSub.take(50) + "…" else origSub
                        subView.text = conciseSub
                        subView.setTextColor(android.graphics.Color.parseColor(theme.subTextHex))
                        subView.textSize = if (isSquareTile) 10.5f else 11f
                        subView.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL)
                        subView.maxLines = if (isSquareTile) 3 else 2
                    }

                    val bHex = forcedBorderHex ?: theme.borderHex
                    card.setPadding((12 * density).toInt(), (12 * density).toInt(), (11 * density).toInt(), (12 * density).toInt())
                    card.minimumHeight = (if (isSquareTile) 112 else 90) * density.toInt()
                    card.background = NorseForgedPlaqueDrawable(
                        if (isSquareTile) tileBmp else heroBmp,
                        android.graphics.Color.parseColor(theme.cardTopHex),
                        android.graphics.Color.parseColor(theme.cardBottomHex),
                        android.graphics.Color.parseColor(bHex),
                        android.graphics.Color.parseColor(theme.glowHex),
                        rChar,
                        density,
                        isSquareTile
                    )
                }
            }

            val pendingCards = mutableListOf<android.view.ViewGroup>()

            fun flushCardGrid() {
                if (pendingCards.isEmpty()) return
                var idx = 0
                while (idx < pendingCards.size) {
                    if (idx + 1 < pendingCards.size) {
                        val cLeft = pendingCards[idx]
                        val cRight = pendingCards[idx + 1]
                        detachFromParent(cLeft)
                        detachFromParent(cRight)
                        styleCardAsNorseTile(cLeft, true)
                        styleCardAsNorseTile(cRight, true)

                        val row = android.widget.LinearLayout(this).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            weightSum = 2f
                            val rlp = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            rlp.setMargins(0, 0, 0, (10 * density).toInt())
                            layoutParams = rlp
                        }
                        val lpL = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                        lpL.setMargins(0, 0, (5 * density).toInt(), 0)
                        cLeft.layoutParams = lpL

                        val lpR = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                        lpR.setMargins((5 * density).toInt(), 0, 0, 0)
                        cRight.layoutParams = lpR

                        row.addView(cLeft)
                        row.addView(cRight)
                        col.addView(row)
                        idx += 2
                    } else {
                        val cSingle = pendingCards[idx]
                        detachFromParent(cSingle)
                        styleCardAsNorseTile(cSingle, false)
                        val slp = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        slp.setMargins(0, 0, 0, (10 * density).toInt())
                        cSingle.layoutParams = slp
                        col.addView(cSingle)
                        idx += 1
                    }
                }
                pendingCards.clear()
            }

            for (item in origChildren) {
                val itemTvs = mutableListOf<android.widget.TextView>()
                collectNestedTextViews(item, itemTvs)

                // Check if this item is one of the redundant top titles ('THOR V21.2 SOVEREIGN COCKPIT' or 'GOD OF THUNDER COMMAND HUB')
                if (item is android.widget.TextView || itemTvs.size == 1) {
                    val singleTv = if (item is android.widget.TextView) item else itemTvs[0]
                    val rawHeader = (thorOriginalViewTexts[System.identityHashCode(singleTv)] ?: singleTv.text ?: "").toString().trim()
                    if (rawHeader.contains("SOVEREIGN COCKPIT", ignoreCase = true) ||
                        rawHeader.contains("GOD OF THUNDER COMMAND HUB", ignoreCase = true)
                    ) {
                        // Skip adding duplicate top titles because the Forged Sanctum Crest is already at index 0!
                        continue
                    }

                    // Otherwise it is a Section Header divider
                    flushCardGrid()
                    detachFromParent(item)
                    val mappedH = lookupThorNorseEntry(rawHeader)
                    val hRune = mappedH?.rune ?: "ᛟ"
                    val hTitle = mappedH?.norseName ?: rawHeader
                    singleTv.text = "══  $hRune  $hTitle  ══"
                    singleTv.setTextColor(android.graphics.Color.parseColor(theme.borderHex))
                    singleTv.textSize = 11.5f
                    singleTv.gravity = android.view.Gravity.CENTER
                    singleTv.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
                    val hlp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    hlp.setMargins(0, (8 * density).toInt(), 0, (6 * density).toInt())
                    item.layoutParams = hlp
                    col.addView(item)
                } else if (item is android.view.ViewGroup && itemTvs.size >= 2) {
                    pendingCards.add(item)
                }
            }
            flushCardGrid()
            updateVoiceToggleButtonAppearance()

        } catch (e: Throwable) {
            android.util.Log.e("ThorNorseArt", "Grid layout error", e)
        }
    }
// === [THOR NORSE ART DIRECTOR MOBILE ENGINE END] ===

}

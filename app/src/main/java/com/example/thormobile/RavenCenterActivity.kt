package com.example.thormobile

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RavenCenterActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INITIAL_FOLDER = "initial_folder"
        const val EXTRA_OPEN_COMPOSE = "open_compose"
        const val EXTRA_SHARED_TEXT = "shared_text"
        const val EXTRA_SHARED_URI = "shared_uri"
        const val EXTRA_SHARED_MIME = "shared_mime"

        private const val CLOUD_URL = "https://thor-api-443941171993.us-east4.run.app"
        private const val SOVEREIGN_TOKEN = "9d943a8005992357c7d6ca29278e803b80521c4d0d703e1ad74fb141ac050d19"
        private val bgIo = Executors.newCachedThreadPool()

        fun formatEtNow(ms: Long = System.currentTimeMillis()): String {
            val sdf = SimpleDateFormat("MMM dd, hh:mm a 'ET'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("America/New_York")
            return sdf.format(Date(ms))
        }

        fun recordIncomingRavenCardAsync(
            context: Context,
            msgId: String,
            title: String,
            body: String,
            linksJson: JSONArray?
        ) {
            val app = context.applicationContext
            bgIo.execute {
                try {
                    val dao = ThorDatabase.getDatabase(app).ravenMessageDao()
                    val cleanId = msgId.ifBlank { "RAVEN_${System.currentTimeMillis()}" }
                    dao.upsert(
                        RavenMessageEntity(
                            msgId = cleanId,
                            folder = "RECEIVED",
                            sender = "Thor",
                            title = title.ifBlank { "Raven Dispatch" },
                            body = body,
                            linksJson = linksJson?.toString() ?: "[]",
                            timestampMs = System.currentTimeMillis(),
                            timestampEt = formatEtNow(),
                            isRead = false,
                            syncStatus = "SYNCED"
                        )
                    )
                } catch (_: Exception) {}
            }
        }

        fun requestCloudVoiceReplay(context: Context, textToSpeak: String, fallbackTts: TextToSpeech? = null) {
            val app = context.applicationContext
            Toast.makeText(app, "Replaying Raven briefing...", Toast.LENGTH_SHORT).show()
            bgIo.execute {
                val payload = JSONObject()
                    .put("action", "raven_center")
                    .put("sub_action", "tts_replay")
                    .put("text", textToSpeak.take(1300))
                val resp = sendLedgerWsRpcSync(payload, 25L)
                val wavB64 = resp?.optString("wav_b64", "") ?: ""
                if (wavB64.isNotEmpty()) {
                    try {
                        val bytes = Base64.decode(wavB64, Base64.DEFAULT)
                        val tmp = File(app.cacheDir, "raven_replay_${System.currentTimeMillis()}.wav")
                        tmp.writeBytes(bytes)
                        val mp = MediaPlayer()
                        mp.setDataSource(tmp.absolutePath)
                        mp.setOnCompletionListener { player ->
                            try { player.release() } catch (_: Exception) {}
                            try { tmp.delete() } catch (_: Exception) {}
                        }
                        mp.prepare()
                        mp.start()
                        return@execute
                    } catch (_: Exception) {}
                }
                if (fallbackTts != null) {
                    fallbackTts.speak(textToSpeak.take(900), TextToSpeech.QUEUE_FLUSH, null, "raven_tts")
                }
            }
        }

        fun sendLedgerWsRpcSync(reqJson: JSONObject, timeoutSec: Long = 55L): JSONObject? {
            return try {
                val wsUrl = CLOUD_URL.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
                    "/ws-ledger?key=$SOVEREIGN_TOKEN"
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(timeoutSec, TimeUnit.SECONDS)
                    .build()
                val latch = CountDownLatch(1)
                val ref = AtomicReference<JSONObject?>(null)
                val req = Request.Builder().url(wsUrl).build()
                val ws = client.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(reqJson.toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            ref.set(JSONObject(text))
                        } catch (_: Exception) {}
                        webSocket.close(1000, "done")
                        latch.countDown()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        latch.countDown()
                    }
                })
                latch.await(timeoutSec, TimeUnit.SECONDS)
                try { ws.cancel() } catch (_: Exception) {}
                ref.get()
            } catch (_: Exception) {
                null
            }
        }

        private val flushLock = Any()

        fun encodeAttachmentBase64Static(context: Context, uri: Uri, mime: String): Pair<String, String> {
            val cr = context.applicationContext.contentResolver
            if (mime.startsWith("image/")) {
                try {
                    val rawBytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return Pair("", mime)
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
                    val maxDim = maxOf(opts.outWidth, opts.outHeight)
                    if (maxDim > 1800) {
                        var sample = 1
                        while ((maxDim / sample) > 1800) sample *= 2
                        val decOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                        val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decOpts)
                        if (bmp != null) {
                            val bos = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos)
                            return Pair(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), "image/jpeg")
                        }
                    }
                    return Pair(Base64.encodeToString(rawBytes, Base64.NO_WRAP), mime)
                } catch (_: Exception) {}
            }
            val maxBytes = 18 * 1024 * 1024
            val bytes = cr.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16384)
                var total = 0
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    if (total + r > maxBytes) break
                    out.write(buf, 0, r)
                    total += r
                }
                out.toByteArray()
            } ?: ByteArray(0)
            return Pair(Base64.encodeToString(bytes, Base64.NO_WRAP), mime)
        }

        fun jsonToEntityStatic(o: JSONObject): RavenMessageEntity {
            val folder = if (o.optString("folder", "RECEIVED").equals("SENT", true)) "SENT" else "RECEIVED"
            val linksArr = o.optJSONArray("links") ?: JSONArray()
            return RavenMessageEntity(
                msgId = o.optString("msg_id", "RAVEN_${System.currentTimeMillis()}"),
                folder = folder,
                sender = o.optString("sender", if (folder == "SENT") "Boss" else "Thor"),
                title = o.optString("title", "Raven Message"),
                body = o.optString("body", ""),
                linksJson = linksArr.toString(),
                attachmentUri = "",
                attachmentName = o.optString("attachment_name", ""),
                attachmentMime = o.optString("attachment_mime", ""),
                stagedMediaId = o.optString("staged_media_id", ""),
                stagedExpiresUnix = o.optLong("staged_expires_unix", 0L),
                timestampMs = o.optLong("timestamp_ms", System.currentTimeMillis()),
                timestampEt = o.optString("timestamp_et", formatEtNow()),
                isRead = (folder == "SENT"),
                syncStatus = "SYNCED"
            )
        }

        fun flushPendingOfflineSync(context: Context, showOverlayOnReply: Boolean): Int {
            val app = context.applicationContext
            synchronized(flushLock) {
                val dao = ThorDatabase.getDatabase(app).ravenMessageDao()
                val pending = try { dao.getPendingOfflineMessages() } catch (_: Exception) { emptyList() }
                if (pending.isEmpty()) return 0
                Log.i("RavenCenterFlush", "WO-152: Flushing ${pending.size} QUEUED_OFFLINE Raven message(s) on signal restore...")
                var flushed = 0
                for (pm in pending) {
                    try {
                        var b64 = ""
                        var mime = pm.attachmentMime
                        if (pm.attachmentUri.isNotEmpty()) {
                            try {
                                val enc = encodeAttachmentBase64Static(app, Uri.parse(pm.attachmentUri), pm.attachmentMime)
                                b64 = enc.first
                                mime = enc.second
                            } catch (_: Exception) {}
                        }
                        val req = JSONObject()
                            .put("action", "raven_center")
                            .put("sub_action", "send")
                            .put("client_msg_id", pm.msgId)
                            .put("title", pm.title)
                            .put("message", pm.body)
                            .put("attachment_b64", b64)
                            .put("attachment_mime", mime)
                            .put("attachment_name", pm.attachmentName)
                        val r = sendLedgerWsRpcSync(req, 65L)
                        if (r != null && r.optString("status") == "ok") {
                            val sentObj = r.optJSONObject("sent_entry")
                            dao.upsert(
                                pm.copy(
                                    stagedMediaId = sentObj?.optString("staged_media_id", pm.stagedMediaId) ?: pm.stagedMediaId,
                                    stagedExpiresUnix = sentObj?.optLong("staged_expires_unix", pm.stagedExpiresUnix) ?: pm.stagedExpiresUnix,
                                    syncStatus = "SYNCED"
                                )
                            )
                            val recvObj = r.optJSONObject("received_entry")
                            if (recvObj != null) {
                                val recvEnt = jsonToEntityStatic(recvObj)
                                dao.upsert(recvEnt)
                                if (showOverlayOnReply) {
                                    val linksArr = try { JSONArray(recvEnt.linksJson) } catch (_: Exception) { null }
                                    Handler(Looper.getMainLooper()).post {
                                        VikingPopupOverlayManager.showRavenCard(
                                            context = app,
                                            serverBaseUrl = CLOUD_URL,
                                            title = recvEnt.title,
                                            message = recvEnt.body,
                                            linksJson = linksArr,
                                            eventKeyOverride = recvEnt.msgId
                                        )
                                    }
                                }
                            }
                            flushed++
                        }
                    } catch (e: Exception) {
                        Log.w("RavenCenterFlush", "Deferred offline Raven ${pm.msgId}: ${e.message}")
                    }
                }
                return flushed
            }
        }

        fun flushOfflineQueueInBackground(context: Context) {
            val app = context.applicationContext
            bgIo.execute {
                try {
                    flushPendingOfflineSync(app, showOverlayOnReply = true)
                } catch (_: Exception) {}
            }
        }
    }

    private var activeFolder: String = "RECEIVED"
    private var selectedAttachmentUri: Uri? = null
    private var selectedAttachmentName: String = ""
    private var selectedAttachmentMime: String = ""

    private lateinit var btnTabReceived: Button
    private lateinit var btnTabSent: Button
    private lateinit var statusSubTv: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var composeDrawer: LinearLayout
    private lateinit var composeSubjectInput: EditText
    private lateinit var composeBodyInput: EditText
    private lateinit var attachmentChipTv: TextView
    private lateinit var btnSendRaven: Button
    private var localTts: TextToSpeech? = null

    private val pickAttachmentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            attachUriToComposer(uri, contentResolver.getType(uri) ?: "application/octet-stream")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { actionBar?.hide() } catch (_: Throwable) {}
        window.statusBarColor = Color.parseColor("#040812")
        window.navigationBarColor = Color.parseColor("#040812")

        localTts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try { localTts?.language = Locale.UK } catch (_: Exception) {}
            }
        }

        activeFolder = intent?.getStringExtra(EXTRA_INITIAL_FOLDER) ?: "RECEIVED"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#040812"))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg("#0C1629", "#F5B942", 2, 20)
            setPadding(dp(10), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val backBtn = Button(this).apply {
            text = "← Hub"
            isAllCaps = false
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = pillBg("#1E293B", "#475569", 1, 14)
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(38)).apply { marginEnd = dp(8) }
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn)

        topBar.addView(buildRavenPedestalBadge().apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(56)).apply { marginEnd = dp(10) }
        })

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "RAVEN MESSAGING CENTER"
            setTextColor(Color.parseColor("#FDE047"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        })
        statusSubTv = TextView(this).apply {
            text = "Received & Sent Vault • Offline Ready"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        titleCol.addView(statusSubTv)
        topBar.addView(titleCol)

        val newMsgBtn = Button(this).apply {
            text = "✉ New"
            isAllCaps = false
            setTextColor(Color.parseColor("#04120A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = pillBg("#4ADE80", "#FDE047", 2, 99)
            layoutParams = LinearLayout.LayoutParams(dp(78), dp(40))
            setOnClickListener { toggleComposeDrawer(forceOpen = null) }
        }
        topBar.addView(newMsgBtn)
        root.addView(topBar)

        composeDrawer = buildComposeDrawer()
        root.addView(composeDrawer)

        val folderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        btnTabReceived = Button(this).apply {
            text = "📥 Received (0)"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
            setOnClickListener {
                activeFolder = "RECEIVED"
                refreshLocalFolderView()
            }
        }
        folderRow.addView(btnTabReceived)

        btnTabSent = Button(this).apply {
            text = "📤 Sent (0)"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
            setOnClickListener {
                activeFolder = "SENT"
                refreshLocalFolderView()
            }
        }
        folderRow.addView(btnTabSent)

        val btnSync = Button(this).apply {
            text = "⟳"
            isAllCaps = false
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            background = pillBg("#0F172A", "#38BDF8", 1, 14)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(42))
            setOnClickListener { syncWithCloudAndFlushOfflineQueue(showToast = true) }
        }
        folderRow.addView(btnSync)
        root.addView(folderRow)

        val listScroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(24))
        }
        listScroll.addView(listContainer)
        root.addView(listScroll)

        setContentView(root)

        handleIncomingShareExtras(intent)
        refreshLocalFolderView()
        syncWithCloudAndFlushOfflineQueue(showToast = false)
    }

    override fun onDestroy() {
        try { localTts?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun handleIncomingShareExtras(srcIntent: Intent?) {
        if (srcIntent == null) return
        val forceCompose = srcIntent.getBooleanExtra(EXTRA_OPEN_COMPOSE, false)
        val sharedTxt = srcIntent.getStringExtra(EXTRA_SHARED_TEXT) ?: ""
        val sharedUriStr = srcIntent.getStringExtra(EXTRA_SHARED_URI) ?: ""
        val sharedMime = srcIntent.getStringExtra(EXTRA_SHARED_MIME) ?: ""
        if (forceCompose || sharedTxt.isNotEmpty() || sharedUriStr.isNotEmpty()) {
            toggleComposeDrawer(forceOpen = true)
            if (sharedTxt.isNotEmpty()) {
                composeBodyInput.setText(sharedTxt)
            }
            if (sharedUriStr.isNotEmpty()) {
                try {
                    attachUriToComposer(Uri.parse(sharedUriStr), sharedMime.ifBlank { "image/*" })
                } catch (_: Exception) {}
            }
        }
    }

    private fun buildComposeDrawer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = pillBg("#0E1A2F", "#38BDF8", 2, 18)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }

            val hdr = LinearLayout(this@RavenCenterActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            hdr.addView(TextView(this@RavenCenterActivity).apply {
                text = "NEW RAVEN DISPATCH TO THOR"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            hdr.addView(Button(this@RavenCenterActivity).apply {
                text = "✕"
                isAllCaps = false
                setTextColor(Color.parseColor("#FDA4AF"))
                background = pillBg("#311018", "#FB7185", 1, 99)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(32))
                setOnClickListener { toggleComposeDrawer(forceOpen = false) }
            })
            addView(hdr)

            composeSubjectInput = EditText(this@RavenCenterActivity).apply {
                hint = "Subject (optional)"
                setHintTextColor(Color.parseColor("#64748B"))
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                background = pillBg("#070D19", "#334155", 1, 12)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            addView(composeSubjectInput)

            composeBodyInput = EditText(this@RavenCenterActivity).apply {
                hint = "Write a message, question, or instructions for Thor..."
                setHintTextColor(Color.parseColor("#64748B"))
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                minLines = 3
                maxLines = 6
                gravity = Gravity.TOP or Gravity.START
                background = pillBg("#070D19", "#334155", 1, 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            addView(composeBodyInput)

            attachmentChipTv = TextView(this@RavenCenterActivity).apply {
                visibility = View.GONE
                setTextColor(Color.parseColor("#FDE047"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                background = pillBg("#1F2937", "#F5B942", 1, 10)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                setOnClickListener {
                    clearComposerAttachment()
                    Toast.makeText(this@RavenCenterActivity, "Attachment removed", Toast.LENGTH_SHORT).show()
                }
            }
            addView(attachmentChipTv)

            val actionBarRow = LinearLayout(this@RavenCenterActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }

            val plusAttachBtn = Button(this@RavenCenterActivity).apply {
                text = "＋ Add File / Photo / Video"
                isAllCaps = false
                setTextColor(Color.parseColor("#FDE047"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                background = pillBg("#172554", "#F5B942", 2, 99)
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1.15f).apply { marginEnd = dp(8) }
                setOnClickListener {
                    pickAttachmentLauncher.launch(arrayOf("*/*"))
                }
            }
            actionBarRow.addView(plusAttachBtn)

            btnSendRaven = Button(this@RavenCenterActivity).apply {
                text = "🦅 Send to Thor"
                isAllCaps = false
                setTextColor(Color.parseColor("#04120A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                background = pillBg("#4ADE80", "#FDE047", 2, 99)
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
                setOnClickListener { dispatchComposedMessage() }
            }
            actionBarRow.addView(btnSendRaven)
            addView(actionBarRow)
        }
    }

    private fun toggleComposeDrawer(forceOpen: Boolean?) {
        val show = forceOpen ?: (composeDrawer.visibility != View.VISIBLE)
        composeDrawer.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun attachUriToComposer(uri: Uri, fallbackMime: String) {
        selectedAttachmentUri = uri
        selectedAttachmentMime = contentResolver.getType(uri) ?: fallbackMime
        var dispName = "attachment"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    dispName = cursor.getString(idx) ?: dispName
                }
            }
        } catch (_: Exception) {}
        selectedAttachmentName = dispName
        val badgeType = when {
            selectedAttachmentMime.startsWith("image/") -> "🖼 Image/Screenshot"
            selectedAttachmentMime.startsWith("video/") -> "🎬 Video (48h Cloud Stage)"
            else -> "📎 File"
        }
        attachmentChipTv.text = "$badgeType: $dispName  (Tap to remove)"
        attachmentChipTv.visibility = View.VISIBLE
    }

    private fun clearComposerAttachment() {
        selectedAttachmentUri = null
        selectedAttachmentName = ""
        selectedAttachmentMime = ""
        attachmentChipTv.visibility = View.GONE
    }

    private fun encodeAttachmentBase64(uri: Uri, mime: String): Pair<String, String> =
        encodeAttachmentBase64Static(applicationContext, uri, mime)

    private fun dispatchComposedMessage() {
        val subjRaw = composeSubjectInput.text?.toString()?.trim() ?: ""
        val bodyRaw = composeBodyInput.text?.toString()?.trim() ?: ""
        val attUri = selectedAttachmentUri
        val attName = selectedAttachmentName
        val attMime = selectedAttachmentMime

        if (bodyRaw.isEmpty() && attUri == null) {
            Toast.makeText(this, "Enter a message or tap + to attach a file first, Boss.", Toast.LENGTH_SHORT).show()
            return
        }

        val msgId = "SENT_${System.currentTimeMillis()}"
        val effectiveTitle = when {
            subjRaw.isNotEmpty() -> subjRaw
            attName.isNotEmpty() && bodyRaw.isEmpty() -> "Attachment: $attName"
            else -> bodyRaw.take(48)
        }

        btnSendRaven.isEnabled = false
        btnSendRaven.text = "Sending..."

        composeSubjectInput.setText("")
        composeBodyInput.setText("")
        clearComposerAttachment()
        toggleComposeDrawer(forceOpen = false)

        bgIo.execute {
            val dao = ThorDatabase.getDatabase(applicationContext).ravenMessageDao()
            val localSent = RavenMessageEntity(
                msgId = msgId,
                folder = "SENT",
                sender = "Boss",
                title = effectiveTitle,
                body = if (bodyRaw.isNotEmpty()) bodyRaw else "[Attached $attName]",
                attachmentUri = attUri?.toString() ?: "",
                attachmentName = attName,
                attachmentMime = attMime,
                timestampMs = System.currentTimeMillis(),
                timestampEt = formatEtNow(),
                isRead = true,
                syncStatus = "QUEUED_OFFLINE"
            )
            dao.upsert(localSent)

            runOnUiThread {
                activeFolder = "SENT"
                refreshLocalFolderView()
            }

            var b64Data = ""
            var finalMime = attMime
            if (attUri != null) {
                try {
                    val enc = encodeAttachmentBase64(attUri, attMime)
                    b64Data = enc.first
                    finalMime = enc.second
                } catch (_: Exception) {}
            }

            val req = JSONObject()
                .put("action", "raven_center")
                .put("sub_action", "send")
                .put("client_msg_id", msgId)
                .put("title", effectiveTitle)
                .put("message", bodyRaw)
                .put("attachment_b64", b64Data)
                .put("attachment_mime", finalMime)
                .put("attachment_name", attName)

            val resp = sendLedgerWsRpcSync(req, 65L)
            if (resp != null && resp.optString("status") == "ok") {
                val sentObj = resp.optJSONObject("sent_entry")
                dao.upsert(
                    localSent.copy(
                        stagedMediaId = sentObj?.optString("staged_media_id", "") ?: "",
                        stagedExpiresUnix = sentObj?.optLong("staged_expires_unix", 0L) ?: 0L,
                        syncStatus = "SYNCED"
                    )
                )
                val recvObj = resp.optJSONObject("received_entry")
                if (recvObj != null) {
                    dao.upsert(jsonToEntity(recvObj))
                }
                runOnUiThread {
                    btnSendRaven.isEnabled = true
                    btnSendRaven.text = "🦅 Send to Thor"
                    activeFolder = "RECEIVED"
                    refreshLocalFolderView()
                    Toast.makeText(this, "Raven reply received from Thor!", Toast.LENGTH_SHORT).show()
                }
            } else {
                runOnUiThread {
                    btnSendRaven.isEnabled = true
                    btnSendRaven.text = "🦅 Send to Thor"
                    refreshLocalFolderView()
                    Toast.makeText(this, "Saved in Sent (Offline Queue — will auto-send on signal).", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun jsonToEntity(o: JSONObject): RavenMessageEntity = jsonToEntityStatic(o)

    private fun syncWithCloudAndFlushOfflineQueue(showToast: Boolean) {
        bgIo.execute {
            flushPendingOfflineSync(applicationContext, showOverlayOnReply = false)
            val dao = ThorDatabase.getDatabase(applicationContext).ravenMessageDao()
            val syncResp = sendLedgerWsRpcSync(
                JSONObject().put("action", "raven_center").put("sub_action", "sync"),
                15L
            )
            if (syncResp != null && syncResp.optString("status") == "ok") {
                val existingById = dao.getAllMessages().associateBy { it.msgId }
                val arr = syncResp.optJSONArray("messages") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val incoming = jsonToEntity(obj)
                    val prev = existingById[incoming.msgId]
                    val merged = if (prev != null) {
                        incoming.copy(
                            isRead = prev.isRead || incoming.isRead,
                            attachmentUri = prev.attachmentUri.ifEmpty { incoming.attachmentUri }
                        )
                    } else {
                        incoming
                    }
                    dao.upsert(merged)
                }
                runOnUiThread {
                    statusSubTv.text = "Synced Live with Cloud & Offline SQLite Vault"
                    refreshLocalFolderView()
                    if (showToast) Toast.makeText(this, "Raven folders synced!", Toast.LENGTH_SHORT).show()
                }
            } else if (showToast) {
                runOnUiThread {
                    statusSubTv.text = "Offline Dead-Zone Mode • Showing Cached SQLite Vault"
                    Toast.makeText(this, "Offline — showing local SQLite Raven vault.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun refreshLocalFolderView() {
        bgIo.execute {
            val dao = ThorDatabase.getDatabase(applicationContext).ravenMessageDao()
            val recvCount = dao.getCountByFolder("RECEIVED")
            val unreadCount = dao.getUnreadReceivedCount()
            val sentCount = dao.getCountByFolder("SENT")
            val items = dao.getMessagesByFolder(activeFolder)

            runOnUiThread {
                val recvLabel = if (unreadCount > 0) "📥 Received ($unreadCount new / $recvCount)" else "📥 Received ($recvCount)"
                btnTabReceived.text = recvLabel
                btnTabSent.text = "📤 Sent ($sentCount)"

                val isRecv = (activeFolder == "RECEIVED")
                btnTabReceived.background = pillBg(if (isRecv) "#F5B942" else "#0F172A", "#F5B942", 2, 14)
                btnTabReceived.setTextColor(Color.parseColor(if (isRecv) "#050811" else "#FDE047"))

                btnTabSent.background = pillBg(if (!isRecv) "#38BDF8" else "#0F172A", "#38BDF8", 2, 14)
                btnTabSent.setTextColor(Color.parseColor(if (!isRecv) "#050811" else "#7DD3FC"))

                listContainer.removeAllViews()
                if (items.isEmpty()) {
                    listContainer.addView(TextView(this).apply {
                        text = if (isRecv) {
                            "No Received Ravens yet.\nAsk Thor by voice to 'send me a Raven card' or tap ✉ New above to message Thor."
                        } else {
                            "No Sent dispatches yet.\nTap ✉ New above (and ＋ to attach screenshots, photos, videos, or files) to message Thor."
                        }
                        setTextColor(Color.parseColor("#94A3B8"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                        gravity = Gravity.CENTER
                        setPadding(dp(20), dp(42), dp(20), dp(42))
                    })
                } else {
                    for (item in items) {
                        listContainer.addView(buildMessageRowCard(item))
                    }
                }
            }
        }
    }

    private fun buildMessageRowCard(item: RavenMessageEntity): LinearLayout {
        val isUnread = (item.folder == "RECEIVED" && !item.isRead)
        val borderHex = when {
            item.syncStatus == "QUEUED_OFFLINE" -> "#FB923C"
            isUnread -> "#FDE047"
            item.folder == "SENT" -> "#38BDF8"
            else -> "#334155"
        }
        val bgHex = if (isUnread) "#13203B" else "#0B1324"

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg(bgHex, borderHex, if (isUnread) 2 else 1, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }

            val metaRow = LinearLayout(this@RavenCenterActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            metaRow.addView(TextView(this@RavenCenterActivity).apply {
                val badge = when {
                    item.syncStatus == "QUEUED_OFFLINE" -> "⏳ QUEUED • "
                    isUnread -> "● NEW • "
                    else -> ""
                }
                text = "$badge${if (item.folder == "SENT") "To: Thor" else "From: ${item.sender}"}"
                setTextColor(Color.parseColor(if (item.folder == "SENT") "#7DD3FC" else "#FDE047"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            metaRow.addView(TextView(this@RavenCenterActivity).apply {
                text = item.timestampEt.ifBlank { formatEtNow(item.timestampMs) }
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            })
            addView(metaRow)

            addView(TextView(this@RavenCenterActivity).apply {
                text = item.title
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, dp(3))
            })

            if (item.attachmentName.isNotEmpty()) {
                addView(TextView(this@RavenCenterActivity).apply {
                    text = "📎 ${item.attachmentName}" + (if (item.stagedMediaId.isNotEmpty()) " (48h Cloud Staged)" else "")
                    setTextColor(Color.parseColor("#FBBF24"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setPadding(0, 0, 0, dp(3))
                })
            }

            addView(TextView(this@RavenCenterActivity).apply {
                text = item.body
                setTextColor(Color.parseColor("#CBD5E1"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            })

            val pillsScroll = HorizontalScrollView(this@RavenCenterActivity).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            val pillsRow = LinearLayout(this@RavenCenterActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            populateTacticalActionPills(pillsRow, item)
            pillsScroll.addView(pillsRow)
            addView(pillsScroll)

            setOnClickListener { openMessageDetailModal(item) }
        }
    }

    private fun populateTacticalActionPills(row: LinearLayout, item: RavenMessageEntity) {
        val seenUrls = mutableSetOf<String>()
        try {
            val arr = JSONArray(item.linksJson)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                val u = o?.optString("url", "")?.trim() ?: ""
                val lbl = o?.optString("label", "")?.trim() ?: ""
                if (u.startsWith("http", true) && seenUrls.add(u)) {
                    val host = Uri.parse(u).host?.removePrefix("www.") ?: "Link"
                    row.addView(makeActionPill(if (lbl.isNotEmpty()) "🔗 ${lbl.take(22)}" else "🔗 $host", "#0F233D", "#F5B942") {
                        launchExternalUrl(u)
                    })
                }
            }
        } catch (_: Exception) {}
        for (m in Regex("https?://[^\\s<>()\\[\\]]+").findAll(item.body)) {
            val u = m.value.trim('.', ',', ';', ':', ')')
            if (u.isNotEmpty() && seenUrls.add(u)) {
                val host = try { Uri.parse(u).host?.removePrefix("www.") ?: "Link" } catch (_: Exception) { "Link" }
                row.addView(makeActionPill("🔗 $host", "#0F233D", "#F5B942") { launchExternalUrl(u) })
            }
        }

        val phoneMatch = Regex("(?:\\+?1[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]\\d{3}[-.\\s]\\d{4}").find(item.body)
        if (phoneMatch != null) {
            val dialDigits = phoneMatch.value.filter { it.isDigit() || it == '+' }
            row.addView(makeActionPill("📞 Call $dialDigits", "#082E1A", "#4ADE80") {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dialDigits")))
            })
        }

        val addrMatch = Regex(
            "\\b\\d{1,6}\\s+[A-Za-z0-9.'\\-\\s]{2,38}\\b(?:Road|Rd|Street|St|Avenue|Ave|Boulevard|Blvd|Pike|Highway|Hwy|Drive|Dr|Lane|Ln|Way|Parkway|Pkwy)\\b(?:[,\\s]+[A-Za-z\\s]{2,22})?",
            RegexOption.IGNORE_CASE
        ).find(item.body)
        if (addrMatch != null) {
            val navTarget = addrMatch.value.trim()
            row.addView(makeActionPill("🧭 Navigate", "#0B2545", "#38BDF8") {
                val gmm = Uri.parse("google.navigation:q=${Uri.encode(navTarget)}")
                val mapIntent = Intent(Intent.ACTION_VIEW, gmm).apply { setPackage("com.google.android.apps.maps") }
                try {
                    startActivity(mapIntent)
                } catch (_: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(navTarget)}")))
                }
            })
        }

        if (item.body.isNotBlank()) {
            row.addView(makeActionPill("🔊 Replay", "#1E1B4B", "#A78BFA") {
                requestCloudVoiceReplay(this, item.body, localTts)
            })
        }
    }

    private fun openMessageDetailModal(item: RavenMessageEntity) {
        bgIo.execute {
            ThorDatabase.getDatabase(applicationContext).ravenMessageDao().markRead(item.msgId)
            runOnUiThread { refreshLocalFolderView() }
        }

        val detailWrap = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#070D19"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        box.addView(TextView(this).apply {
            text = "${if (item.folder == "SENT") "SENT TO THOR" else "RECEIVED FROM THOR"} • ${item.timestampEt}"
            setTextColor(Color.parseColor("#F5B942"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = item.title
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(8))
        })
        if (item.attachmentName.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = "📎 Attachment: ${item.attachmentName} (${item.attachmentMime})"
                setTextColor(Color.parseColor("#FDE047"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, 0, 0, dp(8))
            })
        }
        box.addView(TextView(this).apply {
            text = item.body
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextIsSelectable(true)
        })

        val modalPillsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(14), 0, dp(4))
        }
        val modalPillsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        populateTacticalActionPills(modalPillsRow, item)
        modalPillsScroll.addView(modalPillsRow)
        box.addView(modalPillsScroll)
        detailWrap.addView(box)

        AlertDialog.Builder(this)
            .setView(detailWrap)
            .setPositiveButton("Reply to Thor") { d, _ ->
                d.dismiss()
                toggleComposeDrawer(forceOpen = true)
                composeSubjectInput.setText("Re: ${item.title.removePrefix("Re: ").take(42)}")
                composeBodyInput.requestFocus()
            }
            .setNeutralButton("Delete") { d, _ ->
                d.dismiss()
                bgIo.execute {
                    ThorDatabase.getDatabase(applicationContext).ravenMessageDao().deleteById(item.msgId)
                    sendLedgerWsRpcSync(
                        JSONObject().put("action", "raven_center").put("sub_action", "delete").put("msg_id", item.msgId),
                        10L
                    )
                    runOnUiThread { refreshLocalFolderView() }
                }
            }
            .setNegativeButton("Close", null)
            .create()
            .show()
    }

    private fun makeActionPill(label: String, bgHex: String, strokeHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = pillBg(bgHex, strokeHex, 1, 99)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            minHeight = dp(34)
            minimumHeight = dp(34)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(34)
            ).apply { marginEnd = dp(6) }
            setOnClickListener { onClick() }
        }
    }

    private fun launchExternalUrl(rawUrl: String) {
        try {
            val clean = if (rawUrl.startsWith("http", true)) rawUrl else "https://$rawUrl"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(clean)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Link error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildRavenPedestalBadge(): FrameLayout {
        val radiusPx = dp(10).toFloat()
        return FrameLayout(this).apply {
            background = pillBg("#091122", "#F5B942", 2, 12)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            val inner = FrameLayout(this@RavenCenterActivity).apply {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                    }
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            inner.addView(ImageView(this@RavenCenterActivity).apply {
                setImageResource(R.drawable.raven_storm_base_hd)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            inner.addView(ImageView(this@RavenCenterActivity).apply {
                setImageResource(R.drawable.raven_runes_gold_hd)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            addView(inner)
        }
    }

    private fun pillBg(fillHex: String, strokeHex: String, strokeDp: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(dp(strokeDp), Color.parseColor(strokeHex))
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}

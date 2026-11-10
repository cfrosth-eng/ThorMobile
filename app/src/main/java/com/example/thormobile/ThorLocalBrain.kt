package com.example.thormobile

import android.content.Context
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ThorLocalBrain(private val context: Context) {

    companion object {
        private const val TAG = "ThorLocalBrain"
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        private const val SYSTEM_PROMPT =
            "You are Thor, Centh's loyal, sharp-witted British AI assistant running in offline dead-zone mode on his Samsung Galaxy Note 20 Ultra. " +
            "Answer in 1 to 3 concise, direct spoken sentences with zero markdown formatting."
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val isLoaded = AtomicBoolean(false)
    private val isInitializing = AtomicBoolean(false)
    private var engine: Engine? = null
    private var tts: TextToSpeech? = null
    @Volatile
    private var ttsReady = false

    init {
        initLocalTts()
        val f = resolveModelFile()
        if (f != null) {
            Log.i(TAG, "Gemma 4 E2B model verified on storage: ${f.absolutePath} (${f.length() / (1024 * 1024)} MB) -> Pre-warming in RAM...")
            executor.execute {
                try { ensureEngineLoaded() } catch (e: Throwable) { Log.w(TAG, "Prewarm note: ${e.message}") }
            }
        } else {
            Log.w(TAG, "Gemma 4 E2B model NOT found on storage at boot.")
        }
    }

    private fun initLocalTts() {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale.UK)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.US)
                    }
                    tts?.setSpeechRate(1.12f)
                    ttsReady = true
                    Log.i(TAG, "Offline British TTS initialized and ready.")
                } else {
                    Log.w(TAG, "Offline TTS initialization failed with status: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize local TTS", e)
        }
    }

    fun resolveModelFile(): File? {
        val candidates = mutableListOf<File>()
        context.getExternalFilesDir(null)?.let {
            candidates.add(File(it, MODEL_FILENAME))
        }
        candidates.add(File("/sdcard/Android/data/com.example.thormobile/files/$MODEL_FILENAME"))
        candidates.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), MODEL_FILENAME))
        candidates.add(File("/sdcard/Download/$MODEL_FILENAME"))
        candidates.add(File(context.filesDir, MODEL_FILENAME))
        return candidates.firstOrNull { it.exists() && it.length() > 100_000_000L }
    }

    @Synchronized
    private fun ensureEngineLoaded(): Engine? {
        if (isLoaded.get() && engine != null) return engine
        if (isInitializing.getAndSet(true)) return engine
        try {
            val modelFile = resolveModelFile()
            if (modelFile == null) {
                Log.w(TAG, "Gemma 4 E2B model file ($MODEL_FILENAME) not found on device storage.")
                return null
            }
            Log.i(TAG, "Loading Gemma 4 E2B from ${modelFile.absolutePath} (${modelFile.length() / (1024 * 1024)} MB)...")

            val loadedEngine = try {
                val gpuConfig = EngineConfig(
                    modelFile.absolutePath,
                    Backend.GPU(),
                    Backend.CPU(),
                    Backend.CPU(),
                    1024,
                    null,
                    context.cacheDir.absolutePath
                )
                Engine(gpuConfig).also { it.initialize() }
            } catch (gpuErr: Throwable) {
                Log.w(TAG, "GPU backend fallback to CPU for Gemma 4 E2B: ${gpuErr.message}")
                val cpuConfig = EngineConfig(
                    modelFile.absolutePath,
                    Backend.CPU(),
                    Backend.CPU(),
                    Backend.CPU(),
                    1024,
                    null,
                    context.cacheDir.absolutePath
                )
                Engine(cpuConfig).also { it.initialize() }
            }

            engine = loadedEngine
            isLoaded.set(true)
            Log.i(TAG, "Gemma 4 E2B offline engine initialized successfully.")
            return loadedEngine
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load Gemma 4 E2B engine", t)
            return null
        } finally {
            isInitializing.set(false)
        }
    }

    fun processOfflineVoiceTurn(
        pcm16kMono: ByteArray,
        onStartSpeaking: (() -> Unit)? = null,
        onFinishedSpeaking: (() -> Unit)? = null
    ) {
        executor.execute {
            try {
                val eng = ensureEngineLoaded()
                if (eng == null) {
                    speakOffline(
                        "Boss, we're in a dead zone and my local Gemma 4 model isn't loaded on the phone yet. I've saved your message to the outbox.",
                        onStartSpeaking,
                        onFinishedSpeaking
                    )
                    return@execute
                }

                val wavBytes = wrapPcm16MonoInWav(pcm16kMono, 16000)
                val convConfig = ConversationConfig(
                    Contents.of(SYSTEM_PROMPT),
                    emptyList(),
                    emptyList(),
                    SamplerConfig(40, 0.9, 0.4, 42)
                )

                eng.createConversation(convConfig).use { conversation ->
                    val contents = Contents.of(
                        Content.AudioBytes(wavBytes),
                        Content.Text("Listen to Centh's voice recording and reply directly in 1 to 3 concise spoken sentences.")
                    )
                    val response = conversation.sendMessage(contents)
                    val replyText = response.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                        .trim()
                        .ifEmpty { response.toString().trim() }

                    Log.i(TAG, "Gemma 4 E2B offline reply: $replyText")
                    speakOffline(
                        replyText.ifEmpty { "I heard you, Boss, and logged your audio to the offline outbox." },
                        onStartSpeaking,
                        onFinishedSpeaking
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Offline voice inference failed", t)
                speakOffline(
                    "Boss, signal is down. Your voice note is safely queued in the outbox for when we're back in range.",
                    onStartSpeaking,
                    onFinishedSpeaking
                )
            }
        }
    }

    fun speakOffline(
        text: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        val engineTts = tts
        if (!ttsReady || engineTts == null) {
            onStart?.invoke()
            onDone?.invoke()
            return
        }
        val utteranceId = UUID.randomUUID().toString()
        engineTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                onStart?.invoke()
            }
            override fun onDone(id: String?) {
                onDone?.invoke()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                onDone?.invoke()
            }
        })
        engineTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun wrapPcm16MonoInWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // AudioFormat = 1 (PCM)
        header.putShort(1) // NumChannels = 1 (Mono)
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2) // BlockAlign = 2 bytes
        header.putShort(16) // BitsPerSample = 16
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmData.size)

        val out = ByteArrayOutputStream(44 + pcmData.size)
        out.write(header.array())
        out.write(pcmData)
        return out.toByteArray()
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        try {
            engine?.close()
            engine = null
            isLoaded.set(false)
        } catch (_: Exception) {}
        executor.shutdownNow()
    }
}

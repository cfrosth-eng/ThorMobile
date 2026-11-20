package com.example.thormobile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * WO-210: On-Device Neural Michael Caine Voice Engine (Kyutai Pocket-TTS INT8 via sherpa-onnx).
 *
 * Uses Boss's exact Option 1A reference audio (`alfred_headroom_1A.wav`) from external app files
 * so Tier 1 standalone offline dead-zone replies match Cloud & Laptop Thor's Michael Caine voice.
 * Lazy-loaded on first offline utterance so online driving uses 0 MB extra RAM.
 */
class ThorPocketCaineEngine(private val context: Context) {

    companion object {
        private const val TAG = "ThorPocketCaine"
        private const val POCKET_DIR_NAME = "pocket_tts"
        private const val REF_WAV_NAME = "alfred_headroom_1A.wav"
        private const val SPEAK_SPEED = 1.12f
        private const val FLOW_STEPS = 5
    }

    private val lock = Any()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var refAudio: FloatArray? = null
    @Volatile private var refSampleRate: Int = 24000
    @Volatile private var activeTrack: AudioTrack? = null
    @Volatile private var initAttempted = false

    fun resolvePocketDir(): File? {
        val candidates = mutableListOf<File>()
        context.getExternalFilesDir(null)?.let { candidates.add(File(it, POCKET_DIR_NAME)) }
        candidates.add(File("/sdcard/Android/data/com.example.thormobile/files/$POCKET_DIR_NAME"))
        candidates.add(File(context.filesDir, POCKET_DIR_NAME))
        return candidates.firstOrNull { dir ->
            dir.isDirectory &&
                File(dir, "lm_main.int8.onnx").exists() &&
                File(dir, "lm_flow.int8.onnx").exists() &&
                File(dir, "encoder.onnx").exists() &&
                File(dir, "decoder.int8.onnx").exists() &&
                File(dir, "text_conditioner.onnx").exists() &&
                File(dir, "vocab.json").exists() &&
                File(dir, "token_scores.json").exists() &&
                File(dir, REF_WAV_NAME).exists()
        }
    }

    fun isModelAvailable(): Boolean = resolvePocketDir() != null

    fun ensureLoaded(): Boolean {
        synchronized(lock) {
            if (tts != null && refAudio != null) return true
            val dir = resolvePocketDir()
            if (dir == null) {
                if (!initAttempted) {
                    Log.w(TAG, "Pocket-TTS INT8 weights or $REF_WAV_NAME not found on device storage.")
                    initAttempted = true
                }
                return false
            }
            initAttempted = true
            try {
                val t0 = System.currentTimeMillis()
                val loadedRef = loadWavMonoFloat(File(dir, REF_WAV_NAME))
                if (loadedRef == null || loadedRef.first.isEmpty()) {
                    Log.w(TAG, "Failed to parse reference audio $REF_WAV_NAME")
                    return false
                }
                refAudio = loadedRef.first
                refSampleRate = loadedRef.second

                val pocketCfg = OfflineTtsPocketModelConfig().apply {
                    lmFlow = File(dir, "lm_flow.int8.onnx").absolutePath
                    lmMain = File(dir, "lm_main.int8.onnx").absolutePath
                    encoder = File(dir, "encoder.onnx").absolutePath
                    decoder = File(dir, "decoder.int8.onnx").absolutePath
                    textConditioner = File(dir, "text_conditioner.onnx").absolutePath
                    vocabJson = File(dir, "vocab.json").absolutePath
                    tokenScoresJson = File(dir, "token_scores.json").absolutePath
                    voiceEmbeddingCacheCapacity = 4
                }
                val modelCfg = OfflineTtsModelConfig(
                    pocket = pocketCfg,
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                )
                val ttsCfg = OfflineTtsConfig(
                    model = modelCfg,
                    maxNumSentences = 1,
                    silenceScale = 0.2f,
                )
                tts = OfflineTts(assetManager = null, config = ttsCfg)
                val elapsed = System.currentTimeMillis() - t0
                Log.i(TAG, "Michael Caine Pocket-TTS INT8 ready in ${elapsed}ms (sr=${tts?.sampleRate()}, ref=${refAudio?.size} samples @ ${refSampleRate}Hz)")
                return true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize Pocket-TTS INT8 engine: ${t.message}", t)
                tts = null
                return false
            }
        }
    }

    fun speakBlocking(
        rawText: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
    ): Boolean {
        if (!ensureLoaded()) return false
        val engine = tts ?: return false
        val ref = refAudio ?: return false

        val clean = applyThorPhonetics(rawText)
        if (clean.isBlank()) {
            onStart?.invoke()
            onDone?.invoke()
            return true
        }

        cancelled.set(false)
        val clauses = splitSpokenClauses(clean)
        var startedNotified = false
        try {
            val genCfg = GenerationConfig().apply {
                speed = SPEAK_SPEED
                silenceScale = 0.2f
                referenceAudio = ref
                referenceSampleRate = this@ThorPocketCaineEngine.refSampleRate
                numSteps = FLOW_STEPS
            }
            for (clause in clauses) {
                if (cancelled.get()) break
                val t0 = System.currentTimeMillis()
                val audio = engine.generateWithConfig(text = clause, config = genCfg)
                val samples = audio.samples
                val sr = if (audio.sampleRate > 0) audio.sampleRate else 24000
                if (samples.isEmpty() || cancelled.get()) continue
                val genMs = System.currentTimeMillis() - t0
                Log.i(TAG, "Pocket-TTS INT8 clause (${clause.length} chars) -> ${samples.size} samples in ${genMs}ms")

                val pcm16 = polishToPcm16(samples)
                if (!startedNotified) {
                    startedNotified = true
                    onStart?.invoke()
                }
                playPcm16Blocking(pcm16, sr)
            }
            if (!startedNotified) {
                onStart?.invoke()
            }
            onDone?.invoke()
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Pocket-TTS INT8 synthesis/playback error: ${t.message}", t)
            return false
        }
    }

    fun stopPlayback() {
        cancelled.set(true)
        synchronized(lock) {
            try { activeTrack?.pause() } catch (_: Exception) {}
            try { activeTrack?.flush() } catch (_: Exception) {}
            try { activeTrack?.stop() } catch (_: Exception) {}
            try { activeTrack?.release() } catch (_: Exception) {}
            activeTrack = null
        }
    }

    fun release() {
        stopPlayback()
        synchronized(lock) {
            try { tts?.release() } catch (_: Throwable) {}
            tts = null
            refAudio = null
        }
    }

    private fun applyThorPhonetics(text: String): String {
        var s = text
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[*_`#]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Centh -> Kent spoken phonetic rule (matches Cloud/Laptop WO-208/WO-209)
        s = s.replace(Regex("\\bCenth's\\b", RegexOption.IGNORE_CASE), "Kent's")
        s = s.replace(Regex("\\bCenth\\b", RegexOption.IGNORE_CASE), "Kent")
        return s
    }

    private fun splitSpokenClauses(text: String): List<String> {
        val raw = text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (raw.isEmpty()) listOf(text) else raw
    }

    /**
     * 98.5th-percentile RMS polish targeting ~ -16 dBFS with gentle tanh headroom (matches Thor audio discipline).
     */
    private fun polishToPcm16(samples: FloatArray): ByteArray {
        val n = samples.size
        var sumSq = 0.0
        for (i in 0 until n) {
            val v = samples[i].toDouble()
            sumSq += v * v
        }
        val rms = sqrt(sumSq / max(1, n))
        val targetRms = 0.155 // ~ -16.2 dBFS
        val gain = if (rms > 1e-4) min(2.4, targetRms / rms) else 1.0

        val padTailSamples = 1440 // 60ms clean zero tail so Bluetooth doesn't clip final consonant
        val out = ByteArray((n + padTailSamples) * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val scaled = samples[i].toDouble() * gain
            val soft = tanh(scaled * 1.15) / 1.15
            val clamped = max(-0.96, min(0.96, soft))
            bb.putShort((clamped * 32767.0).toInt().toShort())
        }
        return out
    }

    private fun playPcm16Blocking(pcm16: ByteArray, sampleRate: Int) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, 16384))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        synchronized(lock) {
            activeTrack = track
        }
        try {
            track.play()
            var offset = 0
            while (offset < pcm16.size && !cancelled.get()) {
                val chunkLen = min(3840, pcm16.size - offset)
                val written = track.write(pcm16, offset, chunkLen)
                if (written <= 0) break
                offset += written
            }
            if (!cancelled.get()) {
                val durMs = ((pcm16.size / 2.0) / sampleRate * 1000.0).toLong()
                val tailWait = min(220L, max(60L, durMs / 10L))
                Thread.sleep(tailWait)
            }
        } finally {
            synchronized(lock) {
                if (activeTrack === track) activeTrack = null
            }
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
        }
    }

    private fun loadWavMonoFloat(file: File): Pair<FloatArray, Int>? {
        val bytes = file.readBytes()
        if (bytes.size < 44) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null

        var pos = 12
        var sr = 24000
        var channels = 1
        var bits = 16
        var dataOffset = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4)
            val chunkSize = bb.getInt(pos + 4)
            val nextPos = pos + 8 + chunkSize + (chunkSize and 1)
            if (chunkId == "fmt " && pos + 24 <= bytes.size) {
                channels = bb.getShort(pos + 10).toInt()
                sr = bb.getInt(pos + 12)
                bits = bb.getShort(pos + 22).toInt()
            } else if (chunkId == "data") {
                dataOffset = pos + 8
                dataLen = min(chunkSize, bytes.size - dataOffset)
                break
            }
            if (nextPos <= pos) break
            pos = nextPos
        }
        if (dataOffset < 0 || dataLen <= 0 || bits != 16) return null
        val frameStride = max(1, channels) * 2
        val frames = dataLen / frameStride
        val floats = FloatArray(frames)
        for (i in 0 until frames) {
            val sample = bb.getShort(dataOffset + i * frameStride).toInt()
            floats[i] = sample / 32768.0f
        }
        return Pair(floats, sr)
    }
}

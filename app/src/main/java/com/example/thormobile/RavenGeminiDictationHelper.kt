package com.example.thormobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * ISS-0013 / WO-264: Shared 16kHz PCM16 dictation recorder that routes Raven Messaging Center
 * and Floating Raven Card dictation through thor-api's live Gemini Audio STT + Mimir phonetic ledger.
 */
class RavenGeminiDictationHelper(
    private val context: Context,
    private val onStateChanged: (State) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onNotice: (String) -> Unit,
) {
    enum class State { IDLE, RECORDING, TRANSCRIBING }

    companion object {
        private const val TAG = "RavenGeminiSTT"
        private const val SAMPLE_RATE = 16000
        private const val MAX_RECORD_MS = 45_000L
        private const val MAX_BYTES = SAMPLE_RATE * 2 * 45 // 1.44 MB raw PCM16 cap
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recording = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    @Volatile private var state: State = State.IDLE
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var pcmBuffer: ByteArrayOutputStream? = null

    fun currentState(): State = state

    fun isActive(): Boolean = state != State.IDLE

    private fun postState(newState: State) {
        state = newState
        mainHandler.post { onStateChanged(newState) }
    }

    private fun postNotice(msg: String) {
        mainHandler.post { onNotice(msg) }
    }

    fun toggle() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndTranscribe()
            State.TRANSCRIBING -> postNotice("Transcribing with Thor Gemini STT...")
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (state != State.IDLE) return
        val hasPerm = ContextCompat.checkSelfPermission(
            context.applicationContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            postNotice("Microphone permission is needed to dictate.")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            postNotice("Microphone audio hardware unavailable right now.")
            return
        }

        val bufSize = maxOf(minBuf * 2, 6400)
        val ar = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord init failed: ${e.message}")
            null
        }

        if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
            try { ar?.release() } catch (_: Exception) {}
            postNotice("Mic busy — pause Floating Thor or tap 🎤 again.")
            return
        }

        cancelled.set(false)
        val out = ByteArrayOutputStream(65536)
        pcmBuffer = out
        recorder = ar
        recording.set(true)
        postState(State.RECORDING)

        val startedAt = System.currentTimeMillis()
        thread(start = true, isDaemon = true, name = "RavenGeminiSttRec") {
            val chunk = ByteArray(3200) // 100ms at 16kHz PCM16
            var hitLimit = false
            try {
                ar.startRecording()
                while (recording.get() && !cancelled.get()) {
                    val n = ar.read(chunk, 0, chunk.size)
                    if (n > 0) {
                        synchronized(out) {
                            if (out.size() + n <= MAX_BYTES) {
                                out.write(chunk, 0, n)
                            } else {
                                hitLimit = true
                            }
                        }
                    } else if (n < 0) {
                        break
                    }
                    if (hitLimit || (System.currentTimeMillis() - startedAt) >= MAX_RECORD_MS) {
                        recording.set(false)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Record loop note: ${e.message}")
            } finally {
                try { ar.stop() } catch (_: Exception) {}
                try { ar.release() } catch (_: Exception) {}
                if (recorder === ar) recorder = null
            }

            if (cancelled.get()) {
                postState(State.IDLE)
                return@thread
            }

            val rawPcm = synchronized(out) { out.toByteArray() }
            if (rawPcm.size < 3200) {
                postState(State.IDLE)
                postNotice("Didn't catch any speech — tap 🎤, speak, then tap 🔴 to send.")
                return@thread
            }

            postState(State.TRANSCRIBING)
            try {
                val b64 = Base64.encodeToString(rawPcm, Base64.NO_WRAP)
                val req = JSONObject()
                    .put("action", "raven_center")
                    .put("sub_action", "stt_transcribe")
                    .put("sample_rate", SAMPLE_RATE)
                    .put("pcm_b64", b64)
                val resp = RavenCenterActivity.sendLedgerWsRpcSync(req, timeoutSec = 26L)
                if (cancelled.get()) {
                    postState(State.IDLE)
                    return@thread
                }
                if (resp != null && resp.optString("status") == "ok") {
                    val txt = resp.optString("text", "").trim()
                    postState(State.IDLE)
                    if (txt.isNotEmpty()) {
                        mainHandler.post { onTranscript(txt) }
                    } else {
                        postNotice("No speech detected — tap 🎤 to try again.")
                    }
                } else {
                    val errMsg = resp?.optString("message")?.ifBlank { null }
                        ?: "Offline or Thor STT busy — check signal or type your message."
                    postState(State.IDLE)
                    postNotice(errMsg)
                }
            } catch (e: Exception) {
                Log.w(TAG, "STT upload failed: ${e.message}")
                postState(State.IDLE)
                postNotice("Thor STT note: ${e.message ?: "connection interrupted"}")
            }
        }
    }

    fun stopAndTranscribe() {
        if (state == State.RECORDING) {
            recording.set(false)
        }
    }

    fun cancel() {
        cancelled.set(true)
        recording.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        postState(State.IDLE)
    }
}

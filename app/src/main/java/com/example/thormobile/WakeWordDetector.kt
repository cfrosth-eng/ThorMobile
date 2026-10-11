package com.example.thormobile

import com.example.thormobile.WakeWordModels.Companion.EMB_DIM
import com.example.thormobile.WakeWordModels.Companion.EMB_FRAMES
import com.example.thormobile.WakeWordModels.Companion.MEL_BINS
import com.example.thormobile.WakeWordModels.Companion.MEL_WINDOW

/**
 * On-device wake-word detector: a Kotlin port of openWakeWord's streaming pipeline
 * (openwakeword/utils.py AudioFeatures._streaming_features + model.py).
 *
 *   16 kHz PCM16 --1280-sample steps (80 ms)--> mel --> embedding --> classifier score --> trigger rule
 *
 * Reference implementation: wakeword/ww_common.py StreamingDetector. WakeWordDetectorTest feeds identical audio to
 * both and requires the scores to agree, so drift from the Python behaviour fails the build.
 *
 * Not thread-safe: call [process] from one thread. Pure Kotlin (no Android classes).
 */
class WakeWordDetector(
    private val models: WakeWordModels,
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val patience: Int = DEFAULT_PATIENCE,
    private val cooldownSteps: Int = DEFAULT_COOLDOWN_STEPS,
    private val warmupSteps: Int = DEFAULT_WARMUP_STEPS,
) {
    companion object {
        const val CHUNK = 1280                 // samples per step: 80 ms at 16 kHz
        const val DEFAULT_THRESHOLD = 0.5f
        const val DEFAULT_PATIENCE = 2         // consecutive steps at/above threshold
        const val DEFAULT_COOLDOWN_STEPS = 25  // 2.0 s of deaf time after a trigger
        const val DEFAULT_WARMUP_STEPS = 26    // buffers still hold start-up filler before this (see ww_common.py)
        private const val TAIL = 480           // 160 * 3 samples of overlap the mel model needs
    }

    private val pending = FloatArray(CHUNK)    // samples waiting for a full step
    private var pendingCount = 0
    private val tail = FloatArray(TAIL)        // last 480 samples of the previous step
    private var tailCount = 0
    private val melBuf = FloatArray(MEL_WINDOW * MEL_BINS)   // last 76 mel frames
    private val featBuf = FloatArray(EMB_FRAMES * EMB_DIM)   // last 16 embeddings

    private var stepIndex = 0
    private var consecutive = 0
    private var cooldown = 0

    /** Score of the most recent completed step. */
    var lastScore = 0f
        private set

    init {
        reset()
    }

    /** Forget all audio history (call when listening resumes after a gap, e.g. after a voice session). */
    fun reset() {
        pendingCount = 0
        tailCount = 0
        java.util.Arrays.fill(melBuf, 1f)   // AudioFeatures starts its mel buffer as ones
        java.util.Arrays.fill(featBuf, 0f)
        stepIndex = 0
        consecutive = 0
        cooldown = 0
        lastScore = 0f
    }

    /**
     * Feed PCM16 samples (any length). Returns true if the wake word triggered during this call.
     * [onStep] receives (1-based step index, score) for every completed 80 ms step (used by tests / tuning logs).
     */
    fun process(samples: ShortArray, count: Int, onStep: ((Int, Float) -> Unit)? = null): Boolean {
        var triggered = false
        var i = 0
        while (i < count) {
            val take = minOf(CHUNK - pendingCount, count - i)
            for (k in 0 until take) pending[pendingCount + k] = samples[i + k].toFloat()
            pendingCount += take
            i += take
            if (pendingCount == CHUNK) {
                pendingCount = 0
                if (step()) triggered = true
                onStep?.invoke(stepIndex, lastScore)
            }
        }
        return triggered
    }

    private fun step(): Boolean {
        // 1) mel of [previous 480 samples] + [this 1280 samples] (first step has no tail -> 5 frames, then 8)
        val n = tailCount + CHUNK
        val melIn = FloatArray(n)
        System.arraycopy(tail, 0, melIn, 0, tailCount)
        System.arraycopy(pending, 0, melIn, tailCount, CHUNK)
        val frames = models.mel(melIn)
        val t = frames.size / MEL_BINS
        System.arraycopy(melBuf, t * MEL_BINS, melBuf, 0, (MEL_WINDOW - t) * MEL_BINS)
        System.arraycopy(frames, 0, melBuf, (MEL_WINDOW - t) * MEL_BINS, t * MEL_BINS)
        System.arraycopy(melIn, n - TAIL, tail, 0, TAIL)
        tailCount = TAIL

        // 2) embedding of the last 76 mel frames, appended to the 16-deep feature buffer
        val emb = models.embedding(melBuf)
        System.arraycopy(featBuf, EMB_DIM, featBuf, 0, (EMB_FRAMES - 1) * EMB_DIM)
        System.arraycopy(emb, 0, featBuf, (EMB_FRAMES - 1) * EMB_DIM, EMB_DIM)

        // 3) classify, then apply the trigger rule (identical to StreamingDetector.step in ww_common.py)
        lastScore = models.score(featBuf)
        stepIndex++
        if (stepIndex <= warmupSteps) return false
        if (cooldown > 0) {
            cooldown--
            consecutive = 0
            return false
        }
        consecutive = if (lastScore >= threshold) consecutive + 1 else 0
        if (consecutive >= patience) {
            cooldown = cooldownSteps
            consecutive = 0
            return true
        }
        return false
    }
}

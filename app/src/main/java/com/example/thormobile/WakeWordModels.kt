package com.example.thormobile

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * The three ONNX models of the openWakeWord pipeline, wrapped as plain float-array functions:
 *   melspectrogram.onnx : (1, samples) raw PCM16 values as float  -> (1, 1, T, 32) mel frames
 *   embedding_model.onnx: (1, 76, 32, 1) mel window               -> (1, 1, 1, 96) embedding
 *   thor.onnx           : (1, 16, 96) embeddings                  -> (1, 1) wake-word score
 * Tensor names are read from the models, so a retrained classifier with a different input name still loads.
 * Pure Kotlin + ONNX Runtime (no Android classes) so it runs in plain JVM unit tests.
 */
class WakeWordModels(melModel: ByteArray, embeddingModel: ByteArray, classifierModel: ByteArray) : AutoCloseable {

    companion object {
        const val MEL_BINS = 32
        const val MEL_WINDOW = 76   // mel frames per embedding
        const val EMB_DIM = 96
        const val EMB_FRAMES = 16   // embeddings per classification
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }
    private val melSession = env.createSession(melModel, opts)
    private val embSession = env.createSession(embeddingModel, opts)
    private val clfSession = env.createSession(classifierModel, opts)
    private val melInput = melSession.inputNames.first()
    private val embInput = embSession.inputNames.first()
    private val clfInput = clfSession.inputNames.first()

    /** Mel frames (T x 32, row-major) for [samples], with openWakeWord's `x / 10 + 2` transform applied. */
    fun mel(samples: FloatArray): FloatArray =
        run(melSession, melInput, samples, longArrayOf(1, samples.size.toLong())).also {
            for (k in it.indices) it[k] = it[k] / 10f + 2f
        }

    /** 96-dim embedding of a (76 x 32) mel window. */
    fun embedding(mel: FloatArray): FloatArray =
        run(embSession, embInput, mel, longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1))

    /** Wake-word score in 0..1 for the last 16 embeddings (16 x 96). */
    fun score(features: FloatArray): Float =
        run(clfSession, clfInput, features, longArrayOf(1, EMB_FRAMES.toLong(), EMB_DIM.toLong()))[0]

    private fun run(session: OrtSession, inputName: String, data: FloatArray, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf(inputName to input)).use { out ->
                val fb = (out[0] as OnnxTensor).floatBuffer
                return FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
    }

    override fun close() {
        melSession.close()
        embSession.close()
        clfSession.close()
        opts.close()
    }
}

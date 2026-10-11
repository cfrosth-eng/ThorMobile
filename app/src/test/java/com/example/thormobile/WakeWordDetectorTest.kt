package com.example.thormobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Proves the Kotlin port of openWakeWord's streaming pipeline matches the Python reference (wakeword/ww_common.py).
 * Fixtures are written by wakeword/export_to_android.py: a ~9 s clip and the reference score for every 80 ms step.
 * Runs on the JVM with desktop ONNX Runtime (testImplementation), no device needed.
 */
class WakeWordDetectorTest {

    private val assets = File("src/main/assets/wakeword")
    private val fixtures = File("src/test/resources/wakeword")

    private fun models() = WakeWordModels(
        File(assets, "melspectrogram.onnx").readBytes(),
        File(assets, "embedding_model.onnx").readBytes(),
        File(assets, "thor.onnx").readBytes(),
    )

    private fun loadClip(): ShortArray {
        val bytes = File(fixtures, "golden_clip.pcm").readBytes()
        val out = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    private class Golden(val threshold: Float, val triggers: List<Int>, val scores: FloatArray)

    private fun loadGolden(): Golden {
        val lines = File(fixtures, "golden_scores.txt").readLines().filter { it.isNotBlank() }
        val threshold = lines[0].split(" ")[1].toFloat()
        val triggers = lines[1].removePrefix("triggers").trim().split(" ").filter { it.isNotEmpty() }.map { it.toInt() }
        return Golden(threshold, triggers, FloatArray(lines.size - 2) { lines[it + 2].toFloat() })
    }

    @Test
    fun scoresMatchThePythonReference() {
        val golden = loadGolden()
        val clip = loadClip()
        models().use { m ->
            val detector = WakeWordDetector(m, threshold = golden.threshold)
            val got = ArrayList<Float>()
            // Feed in odd-sized pieces on purpose: the detector must re-chunk into exact 1280-sample steps.
            var i = 0
            val sizes = intArrayOf(800, 1600, 320, 1280, 3000)
            var k = 0
            while (i < clip.size) {
                val n = minOf(sizes[k++ % sizes.size], clip.size - i)
                detector.process(clip.copyOfRange(i, i + n), n) { _, s -> got.add(s) }
                i += n
            }
            assertEquals("number of 80 ms steps", golden.scores.size, got.size)
            var worst = 0f
            for (s in golden.scores.indices) worst = maxOf(worst, kotlin.math.abs(golden.scores[s] - got[s]))
            println("max |kotlin - python| score difference over ${got.size} steps = $worst")
            assertTrue("scores drift from the Python reference (max diff $worst)", worst < 2e-3f)
        }
    }

    @Test
    fun triggersAtTheSameStepsAsThePythonReference() {
        val golden = loadGolden()
        val clip = loadClip()
        models().use { m ->
            val detector = WakeWordDetector(m, threshold = golden.threshold)
            val hits = ArrayList<Int>()
            var step = 0
            var i = 0
            while (i + WakeWordDetector.CHUNK <= clip.size) {
                val chunk = clip.copyOfRange(i, i + WakeWordDetector.CHUNK)
                if (detector.process(chunk, chunk.size)) hits.add(step)
                step++
                i += WakeWordDetector.CHUNK
            }
            assertEquals("trigger steps", golden.triggers, hits)
        }
    }

    @Test
    fun silenceNeverTriggers() {
        models().use { m ->
            val detector = WakeWordDetector(m)
            val silence = ShortArray(WakeWordDetector.CHUNK * 100)
            assertTrue(!detector.process(silence, silence.size))
        }
    }

    @Test
    fun resetForgetsHistory() {
        val clip = loadClip()
        models().use { m ->
            val detector = WakeWordDetector(m)
            detector.process(clip, clip.size)
            detector.reset()
            assertEquals(0f, detector.lastScore, 0f)
        }
    }

    @Test
    fun preRollBufferKeepsOrderAndRespectsItsLimit() {
        val buf = PreRollBuffer()
        for (i in 1..10) buf.add(ByteArray(100) { i.toByte() })
        buf.trimTo(350)
        assertEquals(300, buf.size)
        val order = ArrayList<Int>()
        buf.drain { order.add(it[0].toInt()) }
        assertEquals(listOf(8, 9, 10), order)
        assertEquals(0, buf.size)
    }
}

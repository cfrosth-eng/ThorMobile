package com.example.thormobile

/**
 * Bounded FIFO of recent microphone frames (raw PCM16 bytes).
 *
 * While the phone is only listening for the wake word, the last ~1.5 s of audio is kept here. When the wake word (or
 * a tap) starts a session, the WebSocket takes a moment to open; the mic keeps running meanwhile and its frames
 * pile up here too. On connect everything is replayed in order, so "Thor, what is the torque..." is never clipped.
 *
 * Not thread-safe: the caller (the service) guards it with its own lock.
 */
class PreRollBuffer {
    private val frames = ArrayDeque<ByteArray>()
    private var bytes = 0

    /** Total buffered bytes. */
    val size: Int get() = bytes

    fun add(frame: ByteArray) {
        frames.addLast(frame)
        bytes += frame.size
    }

    /** Drop the oldest frames until at most [maxBytes] remain (always keeps the newest frame). */
    fun trimTo(maxBytes: Int) {
        while (bytes > maxBytes && frames.size > 1) {
            bytes -= frames.removeFirst().size
        }
    }

    /** Hand every buffered frame, oldest first, to [consumer] and empty the buffer. */
    fun drain(consumer: (ByteArray) -> Unit) {
        while (frames.isNotEmpty()) consumer(frames.removeFirst())
        bytes = 0
    }

    fun clear() {
        frames.clear()
        bytes = 0
    }
}

package com.devchew.rajdex_streamer

import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps MJPEG decoding, YUV conversion, MediaCodec and muxing off the USB receive thread. */
class MjpegMp4RecordingWorker(
    file: File,
    width: Int,
    height: Int,
    fps: Int,
    private val onError: (Exception) -> Unit
) : AutoCloseable {
    private data class CapturedFrame(val jpeg: ByteArray, val timestampNs: Long)

    private val queue = ArrayBlockingQueue<CapturedFrame>(QUEUE_CAPACITY)
    private val stopping = AtomicBoolean(false)
    @Volatile private var failure: Exception? = null
    private val worker = Thread({
        var recorder: MjpegMp4Recorder? = null
        try {
            recorder = MjpegMp4Recorder(file, width, height, fps)
            while (!stopping.get() || queue.isNotEmpty()) {
                val frame = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: continue
                recorder.writeFrame(frame.jpeg, frame.timestampNs)
            }
        } catch (error: Exception) {
            failure = error
            queue.clear()
            onError(error)
        } finally {
            try {
                recorder?.close()
            } catch (error: Exception) {
                if (failure == null) {
                    failure = error
                    onError(error)
                }
            }
        }
    }, "MJPEG-H264-encoder").apply { isDaemon = true }

    init { worker.start() }

    /** Non-blocking. If encoding falls behind, discard the oldest queued frame. */
    fun offerFrame(jpeg: ByteArray, timestampNs: Long = System.nanoTime()): Boolean {
        if (stopping.get() || failure != null) return false
        val frame = CapturedFrame(jpeg, timestampNs)
        if (queue.offer(frame)) return true
        queue.poll()
        return queue.offer(frame)
    }

    @Synchronized
    override fun close() {
        if (Thread.currentThread() === worker) return
        stopping.set(true)
        worker.join(CLOSE_TIMEOUT_MS)
        if (worker.isAlive) throw IOException("Wątek kodowania MP4 nie zakończył się na czas")
        failure?.let { throw it }
    }

    companion object {
        private const val QUEUE_CAPACITY = 3
        private const val POLL_TIMEOUT_MS = 100L
        private const val CLOSE_TIMEOUT_MS = 10_000L
    }
}

package com.devchew.rajdex_streamer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Decodes UVC MJPEG frames and encodes them as hardware AVC in an MP4 container. */
class MjpegMp4Recorder(
    private val file: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int
) : AutoCloseable {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var frameCount = 0L
    private var sampleCount = 0L
    private var lastInputTimestampNs = Long.MIN_VALUE
    private var lastPtsUs = -1L
    private var closed = false

    init {
        val codecInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.isHardwareAccelerated && !info.isSoftwareOnly &&
                info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats
                    .contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        } ?: throw IOException("Nie znaleziono sprzętowego enkodera H.264 z wejściem YUV420")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * fps / 8).coerceIn(1_000_000, 8_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        codec = MediaCodec.createByCodecName(codecInfo.name)
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            Log.i(TAG, "MP4 encoder=${codecInfo.name}, hw=${codecInfo.isHardwareAccelerated}, ${width}x$height@$fps")
        } catch (error: Exception) {
            runCatching { codec.release() }
            runCatching { muxer.release() }
            file.delete()
            throw error
        }
    }

    @Synchronized
    fun writeFrame(jpeg: ByteArray, capturedAtNs: Long) {
        check(!closed) { "Enkoder jest zamknięty" }
        val framePeriodNs = 1_000_000_000L / fps.coerceAtLeast(1)
        if (lastInputTimestampNs != Long.MIN_VALUE && capturedAtNs - lastInputTimestampNs < framePeriodNs) return
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        try {
            if (bitmap.width != width || bitmap.height != height) return
            val targetPtsUs = (frameCount * 1_000_000L / fps.coerceAtLeast(1)).coerceAtLeast(lastPtsUs + 1)
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(0)
                return
            }
            val image = codec.getInputImage(index)
            if (image == null || image.planes.size < 3) {
                codec.queueInputBuffer(index, 0, 0, targetPtsUs, 0)
                throw IOException("Enkoder nie udostępnił obrazu wejściowego YUV")
            }
            try {
                fillYuv420(bitmap, image.planes[0], image.planes[1], image.planes[2])
            } finally {
                image.close()
            }
            codec.queueInputBuffer(index, 0, width * height * 3 / 2, targetPtsUs, 0)
            lastPtsUs = targetPtsUs
            lastInputTimestampNs = capturedAtNs
            frameCount++
            drain(0)
        } finally {
            bitmap.recycle()
        }
    }

    private fun fillYuv420(bitmap: Bitmap, yPlane: android.media.Image.Plane, uPlane: android.media.Image.Plane, vPlane: android.media.Image.Plane) {
        val planes = arrayOf(yPlane, uPlane, vPlane)
        if (NativeYuvConverter.convert(bitmap, planes, width, height)) return
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val y = yPlane.buffer
        val u = uPlane.buffer
        val v = vPlane.buffer
        for (row in 0 until height) {
            for (col in 0 until width) {
                val color = pixels[row * width + col]
                val r = color shr 16 and 0xff
                val g = color shr 8 and 0xff
                val b = color and 0xff
                putPlane(y, row, col, yPlane.rowStride, yPlane.pixelStride, ((66*r + 129*g + 25*b + 128) shr 8) + 16)
                if (row % 2 == 0 && col % 2 == 0) {
                    putPlane(u, row / 2, col / 2, uPlane.rowStride, uPlane.pixelStride, ((-38*r - 74*g + 112*b + 128) shr 8) + 128)
                    putPlane(v, row / 2, col / 2, vPlane.rowStride, vPlane.pixelStride, ((112*r - 94*g - 18*b + 128) shr 8) + 128)
                }
            }
        }
    }

    private fun putPlane(buffer: ByteBuffer, row: Int, col: Int, rowStride: Int, pixelStride: Int, value: Int) {
        val offset = row * rowStride + col * pixelStride
        if (offset < buffer.limit()) buffer.put(offset, value.coerceIn(0, 255).toByte())
    }

    private fun drain(timeoutUs: Long): Boolean {
        while (true) {
            when (val result = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Format enkodera zmienił się ponownie" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (result >= 0) {
                    val output = codec.getOutputBuffer(result)
                    val codecConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (bufferInfo.size > 0 && !codecConfig && muxerStarted && output != null) {
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, output, bufferInfo)
                        sampleCount++
                    }
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(result, false)
                    if (eos) return true
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        try {
            val input = codec.dequeueInputBuffer(TIMEOUT_US)
            if (input >= 0) codec.queueInputBuffer(input, 0, 0, (lastPtsUs + 1).coerceAtLeast(0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            var eosReceived = false
            var drainAttempts = 0
            while (!eosReceived && drainAttempts++ < 200) {
                eosReceived = drain(10_000)
            }
            if (!eosReceived) throw IOException("Enkoder nie zakończył strumienia MP4")
            if (frameCount == 0L || sampleCount == 0L || !muxerStarted) {
                throw IOException("Enkoder nie zwrócił żadnych klatek MP4 (wejście: $frameCount)")
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
        if (failure != null) {
            file.delete()
            throw failure as Exception
        }
        Log.i(TAG, "MP4 complete: input=$frameCount, output=$sampleCount, ${file.length()} bytes")
    }

    companion object {
        private const val TAG = "MjpegMp4Recorder"
        private const val TIMEOUT_US = 10_000L
    }
}

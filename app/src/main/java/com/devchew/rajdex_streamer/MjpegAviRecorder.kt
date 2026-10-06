package com.devchew.rajdex_streamer

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/** Writes UVC MJPEG frames directly into an AVI container without decoding or re-encoding. */
internal class MjpegAviRecorder(
    file: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int
) {
    private val output = RandomAccessFile(file, "rw")
    private val index = ArrayList<IndexEntry>()
    private val frameCountOffset: Long
    private val streamLengthOffset: Long
    private val frameDurationOffset: Long
    private val streamRateOffset: Long
    private val moviSizeOffset: Long
    private val moviDataOffset: Long
    private val startedAtNanos = System.nanoTime()
    private var closed = false

    init {
        output.setLength(0)
        fourcc("RIFF"); val riffSize = reserveInt(); fourcc("AVI ")
        fourcc("LIST"); val hdrlSize = reserveInt(); fourcc("hdrl")
        fourcc("avih"); int32(56)
        frameDurationOffset = output.filePointer; int32(1_000_000 / fps)
        int32(width * height * 3 * fps); int32(0); int32(0x10)
        frameCountOffset = output.filePointer; int32(0)
        int32(0); int32(1); int32(width * height * 3); int32(width); int32(height)
        repeat(4) { int32(0) }
        fourcc("LIST"); val strlSize = reserveInt(); fourcc("strl")
        fourcc("strh"); int32(56)
        fourcc("vids"); fourcc("MJPG"); int32(0); int16(0); int16(0)
        int32(0); int32(1); streamRateOffset = output.filePointer; int32(fps); int32(0)
        streamLengthOffset = output.filePointer; int32(0)
        int32(width * height * 3); int32(-1); int32(0)
        int16(0); int16(0); int16(width); int16(height)
        fourcc("strf"); int32(40); int32(40); int32(width); int32(height)
        int16(1); int16(24); fourcc("MJPG"); int32(width * height * 3)
        int32(0); int32(0); int32(0); int32(0)
        patchInt(strlSize, (output.filePointer - strlSize - 4).toInt())
        patchInt(hdrlSize, (output.filePointer - hdrlSize - 4).toInt())
        fourcc("LIST"); moviSizeOffset = reserveInt(); fourcc("movi")
        moviDataOffset = output.filePointer
    }

    @Synchronized
    fun writeFrame(jpeg: ByteArray) {
        check(!closed) { "AVI recording has ended" }
        val chunkOffset = output.filePointer - moviDataOffset
        fourcc("00dc"); int32(jpeg.size); output.write(jpeg)
        if (jpeg.size and 1 != 0) output.write(0)
        index += IndexEntry(chunkOffset, jpeg.size)
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        val moviEnd = output.filePointer
        patchInt(moviSizeOffset, (moviEnd - moviSizeOffset - 4).toInt())
        fourcc("idx1"); int32(index.size * 16)
        index.forEach { entry ->
            fourcc("00dc"); int32(0x10); int32(entry.offset.toInt()); int32(entry.size)
        }
        val end = output.filePointer
        val elapsedNanos = (System.nanoTime() - startedAtNanos).coerceAtLeast(1L)
        val measuredFps = if (index.size > 1) {
            (((index.size - 1) * 1_000_000_000L) / elapsedNanos).toInt().coerceIn(1, 120)
        } else fps
        patchInt(frameDurationOffset, 1_000_000 / measuredFps)
        patchInt(streamRateOffset, measuredFps)
        patchInt(frameCountOffset, index.size)
        patchInt(streamLengthOffset, index.size)
        patchInt(riffSize, (end - 8).toInt())
        output.close()
    }

    private val riffSize: Long get() = 4L
    private fun reserveInt(): Long = output.filePointer.also { int32(0) }
    private fun patchInt(offset: Long, value: Int) {
        val current = output.filePointer
        output.seek(offset); int32(value); output.seek(current)
    }
    private fun fourcc(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
    private fun int16(value: Int) { output.write(value and 0xff); output.write((value ushr 8) and 0xff) }
    private fun int32(value: Int) {
        output.write(value and 0xff); output.write((value ushr 8) and 0xff)
        output.write((value ushr 16) and 0xff); output.write((value ushr 24) and 0xff)
    }
    private data class IndexEntry(val offset: Long, val size: Int)
}

/** Captures PCM from a named USB audio input into a standard 16-bit PCM WAV file. */
internal class UsbWavRecorder private constructor(
    private val audioRecord: AudioRecord,
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int
) {
    private val running = AtomicBoolean(false)
    private var writerThread: Thread? = null
    private var pcmBytes = 0L

    fun start() {
        RandomAccessFile(file, "rw").use { out -> out.write(ByteArray(44)) }
        audioRecord.startRecording()
        check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "USB audio did not start" }
        android.util.Log.i("UsbWav", "requested input=${audioRecord.preferredDevice?.productName}, routed input=${audioRecord.routedDevice?.productName}")
        running.set(true)
        writerThread = Thread({ writePcm() }, "USB-audio-wav-writer").apply { start() }
    }

    fun stop(): File {
        if (running.getAndSet(false)) runCatching { audioRecord.stop() }
        writerThread?.join(2500)
        audioRecord.release()
        RandomAccessFile(file, "rw").use { out ->
            out.seek(0)
            out.writeAscii("RIFF"); out.writeLe32((36L + pcmBytes).toInt()); out.writeAscii("WAVE")
            out.writeAscii("fmt "); out.writeLe32(16); out.writeLe16(1); out.writeLe16(channels)
            out.writeLe32(sampleRate); out.writeLe32(sampleRate * channels * 2)
            out.writeLe16(channels * 2); out.writeLe16(16)
            out.writeAscii("data"); out.writeLe32(pcmBytes.toInt())
        }
        return file
    }

    private fun writePcm() {
        val buffer = ByteArray(16 * 1024)
        RandomAccessFile(file, "rw").use { out ->
            out.seek(44)
            while (running.get()) {
                val count = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    out.write(buffer, 0, count)
                    pcmBytes += count
                } else if (count < 0) {
                    break
                }
            }
        }
    }

    companion object {
        fun create(file: File, device: AudioDeviceInfo): UsbWavRecorder {
            val sampleRate = 48_000
            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val minBytes = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            check(minBytes > 0) { "USB audio format is unsupported" }
            val recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(minBytes * 2)
                .build()
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize USB audio" }
            check(recorder.setPreferredDevice(device)) { "Could not select ${device.productName} audio input" }
            return UsbWavRecorder(recorder, file, sampleRate, 2)
        }
    }
}

private fun RandomAccessFile.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))
private fun RandomAccessFile.writeLe16(value: Int) {
    write(value and 0xff); write((value ushr 8) and 0xff)
}
private fun RandomAccessFile.writeLe32(value: Int) {
    write(value and 0xff); write((value ushr 8) and 0xff)
    write((value ushr 16) and 0xff); write((value ushr 24) and 0xff)
}

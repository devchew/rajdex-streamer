package com.devchew.rajdex_streamer

import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import java.nio.ByteBuffer

internal object NativeYuvConverter {
    val available: Boolean = runCatching {
        System.loadLibrary("rajdex_yuv")
        true
    }.onFailure { Log.e(TAG, "Native YUV converter unavailable", it) }.getOrDefault(false)

    external fun convert(
        bitmap: Bitmap,
        y: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        u: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        v: ByteBuffer, vRowStride: Int, vPixelStride: Int,
        width: Int, height: Int
    ): Boolean

    fun convert(bitmap: Bitmap, planes: Array<Image.Plane>, width: Int, height: Int): Boolean {
        if (!available || planes.size < 3) return false
        return runCatching {
            convert(
                bitmap,
                planes[0].buffer, planes[0].rowStride, planes[0].pixelStride,
                planes[1].buffer, planes[1].rowStride, planes[1].pixelStride,
                planes[2].buffer, planes[2].rowStride, planes[2].pixelStride,
                width, height
            )
        }.onFailure { Log.e(TAG, "Native YUV conversion failed", it) }.getOrDefault(false)
    }

    private const val TAG = "NativeYuvConverter"
}

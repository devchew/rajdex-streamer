#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cstdint>
#include <cstddef>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_devchew_rajdex_1streamer_NativeYuvConverter_convert(
        JNIEnv *env, jobject /* this */, jobject bitmap,
        jobject yBuffer, jint yRowStride, jint yPixelStride,
        jobject uBuffer, jint uRowStride, jint uPixelStride,
        jobject vBuffer, jint vRowStride, jint vPixelStride,
        jint width, jint height) {
    auto *y = static_cast<uint8_t *>(env->GetDirectBufferAddress(yBuffer));
    auto *u = static_cast<uint8_t *>(env->GetDirectBufferAddress(uBuffer));
    auto *v = static_cast<uint8_t *>(env->GetDirectBufferAddress(vBuffer));
    const jlong yCapacity = env->GetDirectBufferCapacity(yBuffer);
    const jlong uCapacity = env->GetDirectBufferCapacity(uBuffer);
    const jlong vCapacity = env->GetDirectBufferCapacity(vBuffer);
    if (!y || !u || !v || yCapacity <= 0 || uCapacity <= 0 || vCapacity <= 0) return JNI_FALSE;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        info.width != static_cast<uint32_t>(width) || info.height != static_cast<uint32_t>(height)) {
        return JNI_FALSE;
    }

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        return JNI_FALSE;
    }
    const auto *base = static_cast<const uint8_t *>(pixels);
    for (int row = 0; row < height; ++row) {
        const auto *src = reinterpret_cast<const uint32_t *>(base + static_cast<size_t>(row) * info.stride);
        const jlong yBase = static_cast<jlong>(row) * yRowStride;
        const jlong uvBase = static_cast<jlong>(row / 2) * uRowStride;
        const jlong vBase = static_cast<jlong>(row / 2) * vRowStride;
        for (int col = 0; col < width; ++col) {
            const uint32_t pixel = src[col]; // Android RGBA_8888 packed as 0xAARRGGBB.
            const int r = static_cast<int>((pixel >> 16) & 0xff);
            const int g = static_cast<int>((pixel >> 8) & 0xff);
            const int b = static_cast<int>(pixel & 0xff);
            const jlong yOffset = yBase + static_cast<jlong>(col) * yPixelStride;
            if (yOffset < yCapacity) {
                y[yOffset] = static_cast<uint8_t>(std::clamp(((66*r + 129*g + 25*b + 128) >> 8) + 16, 0, 255));
            }
            if ((row & 1) == 0 && (col & 1) == 0) {
                const jlong uOffset = uvBase + static_cast<jlong>(col / 2) * uPixelStride;
                const jlong vOffset = vBase + static_cast<jlong>(col / 2) * vPixelStride;
                if (uOffset < uCapacity) {
                    u[uOffset] = static_cast<uint8_t>(std::clamp(((-38*r - 74*g + 112*b + 128) >> 8) + 128, 0, 255));
                }
                if (vOffset < vCapacity) {
                    v[vOffset] = static_cast<uint8_t>(std::clamp(((112*r - 94*g - 18*b + 128) >> 8) + 128, 0, 255));
                }
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

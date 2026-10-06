package com.devchew.rajdex_streamer

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Publishes completed recordings through MediaStore to shared media folders. */
internal object RecordingPublisher {
    fun publish(resolver: ContentResolver, source: File, mimeType: String): Uri {
        check(source.isFile) { "Recording file does not exist: ${source.name}" }
        val collection = when {
            mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }
        val relativePath = if (mimeType.startsWith("audio/")) {
            "${Environment.DIRECTORY_MUSIC}/Rajdex"
        } else {
            "${Environment.DIRECTORY_DCIM}/Rajdex"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection, values)) { "Could not create public recording" }
        try {
            resolver.openOutputStream(uri, "w").use { output ->
                val destination = requireNotNull(output) { "Could not open public recording" }
                source.inputStream().use { input -> input.copyTo(destination) }
            }
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(resolver.update(uri, published, null, null) == 1) { "Could not publish recording" }
            source.delete()
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}

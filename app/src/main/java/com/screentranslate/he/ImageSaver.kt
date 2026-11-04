package com.screentranslate.he

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps a camera photo in memory only, between the camera and the translation screen. */
object CapturedImage {
    @Volatile
    private var bitmap: Bitmap? = null

    fun put(value: Bitmap) {
        bitmap = value
    }

    fun take(): Bitmap? = bitmap.also { bitmap = null }
}

/** Saves a translated image to the gallery (Pictures/ScreenTranslator). */
object ImageSaver {
    private const val FOLDER = "ScreenTranslator"

    /** On Android 9 and older this needs WRITE_EXTERNAL_STORAGE. */
    fun needsPermission() = Build.VERSION.SDK_INT < 29

    fun save(context: Context, image: Bitmap) {
        val name = "translated_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create the image")
            try {
                resolver.openOutputStream(uri)?.use { image.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    ?: error("Could not write the image")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
            dir.mkdirs()
            val file = File(dir, name)
            file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
        }
    }
}

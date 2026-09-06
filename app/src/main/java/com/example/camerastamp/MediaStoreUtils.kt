package com.example.camerastamp

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object MediaStoreUtils {

    private const val FOLDER_NAME = "GeoTagCamera"

    /** A media file that was just saved, with a content Uri usable for preview/sharing. */
    data class SavedMedia(val uri: Uri, val displayName: String)

    /** Copies a finished MP4 file into Movies/GeoTagCamera and returns the saved media, or null on failure. */
    fun saveMp4(context: Context, sourceFile: File, fileName: String): SavedMedia? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER_NAME")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { input -> input.copyTo(out) }
                } ?: return null
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                SavedMedia(uri, fileName)
            } else {
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val appDir = File(moviesDir, FOLDER_NAME)
                if (!appDir.exists()) appDir.mkdirs()
                val dest = File(appDir, fileName)
                sourceFile.inputStream().use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
                SavedMedia(fileProviderUri(context, dest), fileName)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Copies an already-stamped JPEG file into Pictures/GeoTagCamera and returns the saved media, or null on failure. */
    fun saveJpegFile(context: Context, sourceFile: File, fileName: String): SavedMedia? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER_NAME")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { input -> input.copyTo(out) }
                } ?: return null
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                SavedMedia(uri, fileName)
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val appDir = File(picturesDir, FOLDER_NAME)
                if (!appDir.exists()) appDir.mkdirs()
                val dest = File(appDir, fileName)
                sourceFile.inputStream().use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
                SavedMedia(fileProviderUri(context, dest), fileName)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Saves [bitmap] as a JPEG in Pictures/GeoTagCamera and returns the saved media, or null on failure. */
    fun saveJpeg(context: Context, bitmap: Bitmap, fileName: String): SavedMedia? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, bitmap, fileName)
            } else {
                saveLegacy(context, bitmap, fileName)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Finds the most recently saved photo in Pictures/GeoTagCamera, if any (used to prefill the thumbnail on launch). */
    fun queryLatestPhoto(context: Context): SavedMedia? {
        return try {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%$FOLDER_NAME%")
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) selection else null,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) selectionArgs else null,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol)
                    val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    SavedMedia(uri, name)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveViaMediaStore(context: Context, bitmap: Bitmap, fileName: String): SavedMedia? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER_NAME")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out: OutputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        } ?: return null
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return SavedMedia(uri, fileName)
    }

    private fun saveLegacy(context: Context, bitmap: Bitmap, fileName: String): SavedMedia? {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val appDir = File(picturesDir, FOLDER_NAME)
        if (!appDir.exists()) appDir.mkdirs()
        val file = File(appDir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        return SavedMedia(fileProviderUri(context, file), fileName)
    }

    /** Wraps a plain file:// path in a content:// Uri so it can be safely shared via Intents (required since API 24). */
    private fun fileProviderUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

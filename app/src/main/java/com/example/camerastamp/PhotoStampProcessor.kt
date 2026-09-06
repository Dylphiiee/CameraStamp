package com.example.camerastamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream

/**
 * Burns the stamp into a captured photo using FFmpeg's `overlay` filter, the same
 * approach used for video: render the stamp as a transparent PNG the size of the
 * photo, then let FFmpeg composite it in a single pass instead of drawing on the
 * Bitmap's Canvas directly.
 */
object PhotoStampProcessor {

    data class Result(val outputFile: File?, val errorMessage: String?)

    /** [rawPhotoFile] must be a plain, un-stamped JPEG. Call from a background thread. */
    fun process(context: Context, rawPhotoFile: File, stampData: StampRenderer.StampData): Result {
        val workDir = File(context.cacheDir, "photowork_${System.currentTimeMillis()}")
        workDir.mkdirs()

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(rawPhotoFile.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) {
                return Result(null, "Tidak bisa membaca dimensi foto")
            }

            val overlayBitmap = StampRenderer.renderOverlayBitmap(width, height, stampData)
            val overlayPath = File(workDir, "overlay.png")
            FileOutputStream(overlayPath).use { overlayBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            overlayBitmap.recycle()

            val outputFile = File(workDir, "output.jpg")
            val cmd = "-y -i \"${rawPhotoFile.absolutePath}\" -i \"${overlayPath.absolutePath}\" " +
                "-filter_complex \"overlay=0:0\" -frames:v 1 -q:v 2 " +
                "\"${outputFile.absolutePath}\""

            val session = FFmpegKit.execute(cmd)
            if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                Result(outputFile, null)
            } else {
                Result(null, session.failStackTrace ?: "FFmpeg gagal memproses foto")
            }
        } catch (e: Exception) {
            Result(null, e.message)
        }
    }

    /** Deletes everything in [workDir] except [keep] (usually the already-copied-out output). */
    fun cleanup(workDir: File, keep: File?) {
        workDir.listFiles()?.forEach { f ->
            if (keep == null || f.absolutePath != keep.absolutePath) f.delete()
        }
        if (keep == null) workDir.delete()
    }
}

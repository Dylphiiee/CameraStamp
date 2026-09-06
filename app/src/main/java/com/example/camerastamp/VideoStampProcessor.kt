package com.example.camerastamp

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * Burns the timestamp/locstamp/logo permanently into a just-recorded video file.
 *
 * Approach: instead of a custom OpenGL decode/encode pipeline, this renders the stamp
 * as plain PNG overlays and asks FFmpeg to composite them onto the video with the
 * `overlay` filter, copying the audio track through unchanged. This keeps the code
 * small and avoids needing a live text-rendering filter (drawtext/libfreetype), which
 * isn't guaranteed to be present in every FFmpegKit build tier.
 */
object VideoStampProcessor {

    /** One video codec + audio arg combination to try, in order, until one of them succeeds. */
    private data class EncodeAttempt(val videoArgs: String, val audioArgs: String)

    private val ENCODE_ATTEMPTS = listOf(
        // Hardware encoder via Android's MediaCodec - fastest, no GPL codec involved.
        EncodeAttempt("-c:v h264_mediacodec -b:v 8M", "-c:a copy"),
        // Cisco OpenH264 - LGPL, software, widely bundled in LGPL-only FFmpeg builds.
        EncodeAttempt("-c:v libopenh264 -b:v 8M", "-c:a copy"),
        // Last-resort fallback: base FFmpeg codec, always present, lower quality/compat.
        EncodeAttempt("-c:v mpeg4 -q:v 4", "-c:a aac -b:a 128k")
    )

    data class Result(val outputFile: File?, val errorMessage: String?)

    /**
     * Runs the whole pipeline synchronously (call from a background thread).
     * [onProgress] is invoked with short human-readable status updates for the UI.
     */
    fun process(
        context: Context,
        rawVideoFile: File,
        recordingStartWallTimeMillis: Long,
        companyName: String,
        locationText: String,
        logo: Bitmap?,
        onProgress: (String) -> Unit
    ): Result {
        val workDir = File(context.cacheDir, "stampwork_${System.currentTimeMillis()}")
        workDir.mkdirs()

        try {
            val retriever = MediaMetadataRetriever()
            val width: Int
            val height: Int
            val durationMs: Long
            val rotation: Int
            try {
                retriever.setDataSource(rawVideoFile.absolutePath)
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } finally {
                retriever.release()
            }

            val outW = if (rotation == 90 || rotation == 270) height else width
            val outH = if (rotation == 90 || rotation == 270) width else height

            val totalSeconds = maxOf(1, ceil(durationMs / 1000.0).toInt())

            onProgress("Menyiapkan stempel…")

            val staticData = StampRenderer.StampData(
                companyName = companyName,
                timeText = "",
                dateText = "",
                dayText = "",
                locationText = locationText,
                logo = logo
            )
            val staticBitmap = StampRenderer.renderOverlayBitmap(outW, outH, staticData)
            val staticPath = File(workDir, "static_overlay.png")
            FileOutputStream(staticPath).use { staticBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            staticBitmap.recycle()

            val timeFmt = SimpleDateFormat("HH.mm", Locale("in", "ID"))
            val dateFmt = SimpleDateFormat("dd MMMM", Locale("in", "ID"))
            val dayFmt = SimpleDateFormat("EEEE", Locale("in", "ID"))

            for (i in 0 until totalSeconds) {
                val wallTime = Date(recordingStartWallTimeMillis + i * 1000L)
                val clockData = StampRenderer.StampData(
                    companyName = "",
                    timeText = timeFmt.format(wallTime),
                    dateText = dateFmt.format(wallTime).uppercase(Locale("in", "ID")),
                    dayText = dayFmt.format(wallTime).uppercase(Locale("in", "ID")),
                    locationText = "",
                    logo = null
                )
                val bmp = StampRenderer.renderOverlayBitmap(outW, outH, clockData)
                val f = File(workDir, "clock_%04d.png".format(i))
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                onProgress("Menyiapkan stempel… ${i + 1}/$totalSeconds")
            }

            val clockPattern = File(workDir, "clock_%04d.png").absolutePath
            val outputFile = File(workDir, "output.mp4")

            onProgress("Menggabungkan video…")

            for ((index, attempt) in ENCODE_ATTEMPTS.withIndex()) {
                if (outputFile.exists()) outputFile.delete()

                val cmd = buildString {
                    append("-y ")
                    append("-i \"${rawVideoFile.absolutePath}\" ")
                    append("-loop 1 -i \"${staticPath.absolutePath}\" ")
                    append("-framerate 1 -i \"$clockPattern\" ")
                    append(
                        "-filter_complex " +
                            "\"[0:v][1:v]overlay=0:0[tmp];" +
                            "[tmp][2:v]overlay=0:0:eof_action=pass[outv]\" "
                    )
                    append("-map \"[outv]\" -map 0:a? ")
                    append("${attempt.videoArgs} ${attempt.audioArgs} ")
                    append("-shortest ")
                    append("\"${outputFile.absolutePath}\"")
                }

                val session = FFmpegKit.execute(cmd)
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                    return Result(outputFile, null)
                }
                if (index == ENCODE_ATTEMPTS.lastIndex) {
                    return Result(null, session.failStackTrace ?: "Semua percobaan encoding gagal")
                }
            }
            return Result(null, "Tidak ada encoder yang berhasil")
        } catch (e: Exception) {
            return Result(null, e.message)
        }
    }

    /** Deletes every file left behind in [workDir] except [keep] (usually the already-copied-out output). */
    fun cleanup(workDir: File, keep: File?) {
        workDir.listFiles()?.forEach { f ->
            if (keep == null || f.absolutePath != keep.absolutePath) f.delete()
        }
        if (keep == null) workDir.delete()
    }
}

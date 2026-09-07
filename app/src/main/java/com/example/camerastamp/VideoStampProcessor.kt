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

/**
 * Burns the timestamp/locstamp/logo permanently into a just-recorded video file.
 *
 * Deliberately simple: ONE overlay image (company + a single frozen timestamp from
 * when recording started + location + logo) composited onto the video with a single
 * `overlay` filter pass. Audio is copied through unchanged.
 *
 * An earlier version generated one extra PNG per second of footage (so the clock
 * would visibly tick) and chained two `overlay` filters together with an image-sequence
 * input. That is a much less common FFmpeg usage pattern and turned out to be unstable
 * on-device (crashes). A single static overlay + single overlay filter is the standard,
 * heavily-tested "watermark a video" recipe, which is far less likely to crash — the
 * trade-off is that the on-video clock is frozen at the moment recording started rather
 * than ticking through the clip, same as how most GPS-camera apps stamp video anyway.
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
            val rotation: Int
            try {
                retriever.setDataSource(rawVideoFile.absolutePath)
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } finally {
                retriever.release()
            }

            val outW = if (rotation == 90 || rotation == 270) height else width
            val outH = if (rotation == 90 || rotation == 270) width else height

            onProgress("Menyiapkan stempel…")

            val startTime = Date(recordingStartWallTimeMillis)
            val timeFmt = SimpleDateFormat("HH.mm", Locale("in", "ID"))
            val dateFmt = SimpleDateFormat("dd MMMM", Locale("in", "ID"))
            val dayFmt = SimpleDateFormat("EEEE", Locale("in", "ID"))

            val stampData = StampRenderer.StampData(
                companyName = companyName,
                timeText = timeFmt.format(startTime),
                dateText = dateFmt.format(startTime).uppercase(Locale("in", "ID")),
                dayText = dayFmt.format(startTime).uppercase(Locale("in", "ID")),
                locationText = locationText,
                logo = logo
            )
            val overlayBitmap = StampRenderer.renderOverlayBitmap(outW, outH, stampData)
            val overlayPath = File(workDir, "overlay.png")
            FileOutputStream(overlayPath).use { overlayBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            overlayBitmap.recycle()

            val outputFile = File(workDir, "output.mp4")

            onProgress("Menggabungkan video…")

            for ((index, attempt) in ENCODE_ATTEMPTS.withIndex()) {
                if (outputFile.exists()) outputFile.delete()

                val cmd = buildString {
                    append("-y ")
                    append("-i \"${rawVideoFile.absolutePath}\" ")
                    append("-loop 1 -i \"${overlayPath.absolutePath}\" ")
                    append("-filter_complex \"[0:v][1:v]overlay=0:0[outv]\" ")
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

package com.example.camerastamp

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.camerastamp.gl.EglCore
import com.example.camerastamp.gl.GlRenderer
import com.example.camerastamp.gl.OutputSurface
import com.example.camerastamp.gl.WindowSurface
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Burns the timestamp/locstamp/logo permanently into a just-recorded video file using
 * only Android's built-in MediaCodec + OpenGL ES + EGL - no third-party native library.
 *
 * Pipeline: decode the raw clip's video track to an OES texture (via a SurfaceTexture),
 * draw it (rotated back to upright) plus the stamp overlay texture onto the encoder's
 * input Surface with OpenGL, and mux the newly-encoded video track back together with
 * the ORIGINAL audio track copied through untouched (no re-encoding).
 *
 * The stamp is a single static overlay - the timestamp is frozen at the moment
 * recording started rather than ticking live through the clip - which keeps this
 * already-nontrivial pipeline as simple (and as low-risk) as possible.
 */
object VideoOverlayProcessor {

    data class Result(val outputFile: File?, val errorMessage: String?)

    private const val TIMEOUT_US = 10_000L

    fun process(
        context: Context,
        rawVideoFile: File,
        recordingStartWallTimeMillis: Long,
        companyName: String,
        locationText: String,
        logo: Bitmap?,
        onProgress: (String) -> Unit
    ): Result {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var outputSurface: OutputSurface? = null
        var windowSurface: WindowSurface? = null
        var eglCore: EglCore? = null

        val outputFile = File(context.cacheDir, "stamped_${System.currentTimeMillis()}.mp4")

        try {
            onProgress("Membaca video…")

            extractor = MediaExtractor().apply { setDataSource(rawVideoFile.absolutePath) }

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                    videoFormat = format
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i
                    audioFormat = format
                }
            }
            val srcVideoFormat = videoFormat ?: return Result(null, "Tidak ada track video di rekaman")
            val srcMime = srcVideoFormat.getString(MediaFormat.KEY_MIME)!!
            val width = srcVideoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = srcVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = srcVideoFormat.getInteger("rotation-degrees", 0)
            val frameRate = try {
                srcVideoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            } catch (e: Exception) {
                30
            }

            // Output dimensions are the POST-rotation (display) dimensions: we bake the
            // rotation into the pixels ourselves in GL, so the encoded track needs no
            // rotation hint of its own.
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

            // ---- Encoder (its input Surface is what we render into with GL) ----
            val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = encoder.createInputSurface()
            encoder.start()

            // ---- GL setup ----
            eglCore = EglCore()
            windowSurface = WindowSurface(eglCore, encoderInputSurface)
            windowSurface.makeCurrent()
            val glRenderer = GlRenderer()
            glRenderer.setViewportSize(outW, outH)
            glRenderer.updateOverlayTexture(overlayBitmap)
            overlayBitmap.recycle()

            outputSurface = OutputSurface()

            // ---- Decoder (decodes into the OutputSurface's OES texture) ----
            decoder = MediaCodec.createDecoderByType(srcMime)
            decoder.configure(srcVideoFormat, outputSurface.surface, null, 0)
            decoder.start()

            // ---- Muxer ----
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerAudioTrack = -1
            if (audioTrackIndex != -1 && audioFormat != null) {
                muxerAudioTrack = muxer.addTrack(audioFormat)
            }
            var muxerVideoTrack = -1
            var muxerStarted = false

            extractor.selectTrack(videoTrackIndex)

            onProgress("Memproses video…")

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderOutputDone = false
            var encoderOutputDone = false

            while (!encoderOutputDone) {
                // Feed compressed video samples into the decoder.
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer: ByteBuffer = decoder.getInputBuffer(inIndex) ?: ByteBuffer.allocate(0)
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Pull decoded frames, render them (+ overlay) onto the encoder's input surface.
                if (!decoderOutputDone) {
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outIndex >= 0) {
                        val doRender = bufferInfo.size > 0
                        decoder.releaseOutputBuffer(outIndex, doRender)
                        if (doRender) {
                            if (outputSurface.awaitNewImage()) {
                                windowSurface.makeCurrent()
                                val transform = FloatArray(16)
                                outputSurface.getTransformMatrix(transform)
                                glRenderer.drawVideoFrame(outputSurface.textureId, transform, rotation)
                                glRenderer.drawOverlay()
                                windowSurface.setPresentationTime(bufferInfo.presentationTimeUs * 1000)
                                windowSurface.swapBuffers()
                            }
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderOutputDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                // Drain whatever the encoder has produced so far into the muxer.
                var draining = true
                while (draining) {
                    val encOutIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                    when {
                        encOutIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> draining = false
                        encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerVideoTrack != -1) {
                                draining = false
                            } else {
                                muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        encOutIndex >= 0 -> {
                            val encodedData = encoder.getOutputBuffer(encOutIndex)
                                ?: throw IllegalStateException("Encoder output buffer $encOutIndex null")
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size != 0 && muxerStarted) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(encOutIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoderOutputDone = true
                                draining = false
                            }
                        }
                        else -> draining = false
                    }
                }
            }

            // ---- Copy the audio track through untouched (no re-encoding) ----
            if (audioTrackIndex != -1 && muxerAudioTrack != -1 && muxerStarted) {
                onProgress("Menyalin audio…")
                extractor.unselectTrack(videoTrackIndex)
                extractor.selectTrack(audioTrackIndex)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                val audioBuffer = ByteBuffer.allocate(1 shl 20) // 1MB scratch buffer
                val audioInfo = MediaCodec.BufferInfo()
                while (true) {
                    val size = extractor.readSampleData(audioBuffer, 0)
                    if (size < 0) break
                    audioInfo.offset = 0
                    audioInfo.size = size
                    audioInfo.presentationTimeUs = extractor.sampleTime
                    audioInfo.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioInfo)
                    extractor.advance()
                }
            }

            return Result(outputFile, null)
        } catch (e: Exception) {
            return Result(null, e.message ?: e.toString())
        } finally {
            try { decoder?.stop() } catch (e: Exception) { }
            try { decoder?.release() } catch (e: Exception) { }
            try { encoder?.stop() } catch (e: Exception) { }
            try { encoder?.release() } catch (e: Exception) { }
            try { muxer?.stop() } catch (e: Exception) { }
            try { muxer?.release() } catch (e: Exception) { }
            try { extractor?.release() } catch (e: Exception) { }
            try { outputSurface?.release() } catch (e: Exception) { }
            try { windowSurface?.release() } catch (e: Exception) { }
            try { eglCore?.release() } catch (e: Exception) { }
        }
    }

    /** Deletes every file left behind in [workDir] except [keep]. */
    fun cleanup(workDir: File, keep: File?) {
        workDir.listFiles()?.forEach { f ->
            if (keep == null || f.absolutePath != keep.absolutePath) f.delete()
        }
    }
}

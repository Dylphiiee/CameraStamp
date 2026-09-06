package com.example.camerastamp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.example.camerastamp.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class CaptureMode { PHOTO, VIDEO }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var locationHelper: LocationHelper

    private var captureMode = CaptureMode.PHOTO
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var recordingStartWallTimeMillis: Long = 0L

    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashEnabled = false

    private var lastPhotoUri: Uri? = null
    private var lastMediaMime: String = "image/jpeg"

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateTimeUi()
            clockHandler.postDelayed(this, 1000)
        }
    }

    private val recordingTimerHandler = Handler(Looper.getMainLooper())
    private val recordingTimerRunnable = object : Runnable {
        override fun run() {
            val elapsedSec = (System.currentTimeMillis() - recordingStartWallTimeMillis) / 1000
            binding.tvRecordingTime.text = String.format(Locale.US, "%02d:%02d", elapsedSec / 60, elapsedSec % 60)
            recordingTimerHandler.postDelayed(this, 1000)
        }
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val cameraGranted = grants[Manifest.permission.CAMERA] == true
            val locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (cameraGranted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.permission_rationale, Toast.LENGTH_LONG).show()
            }
            if (locationGranted) {
                locationHelper.start { text, _ -> runOnUiThread { binding.tvLocation.text = text } }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        locationHelper = LocationHelper(this)

        binding.tvCompany.text = getString(R.string.company_default)

        binding.btnCapture.setOnClickListener {
            when (captureMode) {
                CaptureMode.PHOTO -> takePhoto()
                CaptureMode.VIDEO -> if (activeRecording == null) startRecording() else stopRecording()
            }
        }
        binding.btnSwitchCamera.setOnClickListener { switchCamera() }
        binding.btnFlash.setOnClickListener { toggleFlash() }
        binding.ivLastPhoto.setOnClickListener { openPreview() }
        binding.tvModePhoto.setOnClickListener { setCaptureMode(CaptureMode.PHOTO) }
        binding.tvModeVideo.setOnClickListener { setCaptureMode(CaptureMode.VIDEO) }

        loadInitialThumbnail()

        requestNeededPermissions()
    }

    private fun setCaptureMode(mode: CaptureMode) {
        if (activeRecording != null) return // don't switch mid-recording
        if (captureMode == mode) return
        captureMode = mode

        val yellow = ContextCompat.getColor(this, R.color.brand_yellow)
        val white = ContextCompat.getColor(this, R.color.stamp_white)
        binding.tvModePhoto.setTextColor(if (mode == CaptureMode.PHOTO) yellow else white)
        binding.tvModePhoto.alpha = if (mode == CaptureMode.PHOTO) 1f else 0.6f
        binding.tvModeVideo.setTextColor(if (mode == CaptureMode.VIDEO) yellow else white)
        binding.tvModeVideo.alpha = if (mode == CaptureMode.VIDEO) 1f else 0.6f

        bindCameraUseCases()
    }

    private fun loadInitialThumbnail() {
        Thread {
            val latest = MediaStoreUtils.queryLatestPhoto(this)
            if (latest != null) {
                lastPhotoUri = latest.uri
                lastMediaMime = "image/jpeg"
                val thumb = try {
                    contentResolver.openInputStream(latest.uri)?.use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    null
                }
                if (thumb != null) {
                    runOnUiThread { binding.ivLastPhoto.setImageBitmap(thumb) }
                }
            }
        }.start()
    }

    private fun openPreview() {
        val uri = lastPhotoUri ?: return
        val intent = Intent(this, PreviewActivity::class.java).apply {
            putExtra(PreviewActivity.EXTRA_URI, uri.toString())
            putExtra(PreviewActivity.EXTRA_MIME, lastMediaMime)
        }
        startActivity(intent)
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isEmpty()) {
            startCamera()
            locationHelper.start { text, _ -> runOnUiThread { binding.tvLocation.text = text } }
        } else {
            requestPermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        try {
            provider.unbindAll()
            when (captureMode) {
                CaptureMode.PHOTO -> {
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                        .build()
                    provider.bindToLifecycle(this, selector, preview, imageCapture)
                }
                CaptureMode.VIDEO -> {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(
                            QualitySelector.from(
                                Quality.FHD,
                                FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                            )
                        )
                        .build()
                    videoCapture = VideoCapture.withOutput(recorder)
                    provider.bindToLifecycle(this, selector, preview, videoCapture)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Camera bind failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchCamera() {
        if (activeRecording != null) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCameraUseCases()
    }

    private fun toggleFlash() {
        flashEnabled = !flashEnabled
        imageCapture?.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        Toast.makeText(this, if (flashEnabled) "Flash ON" else "Flash OFF", Toast.LENGTH_SHORT).show()
    }

    private fun updateTimeUi() {
        val now = Date()
        val timeFmt = SimpleDateFormat("HH.mm", Locale("in", "ID"))
        val dateFmt = SimpleDateFormat("dd MMMM", Locale("in", "ID"))
        val dayFmt = SimpleDateFormat("EEEE", Locale("in", "ID"))

        binding.tvTime.text = timeFmt.format(now)
        binding.tvDate.text = dateFmt.format(now).uppercase(Locale("in", "ID"))
        binding.tvDay.text = dayFmt.format(now).uppercase(Locale("in", "ID"))
    }

    // ---------------------------------------------------------------------
    // Photo capture
    // ---------------------------------------------------------------------

    private fun takePhoto() {
        val capture = imageCapture ?: return

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = imageProxyToBitmap(image)
                image.close()
                if (bitmap == null) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, R.string.failed_toast, Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                val now = Date()
                val timeFmt = SimpleDateFormat("HH.mm", Locale("in", "ID"))
                val dateFmt = SimpleDateFormat("dd MMMM", Locale("in", "ID"))
                val dayFmt = SimpleDateFormat("EEEE", Locale("in", "ID"))

                val logoBitmap = try {
                    BitmapFactory.decodeResource(resources, R.drawable.logo)
                } catch (e: Exception) {
                    null
                }

                val stampData = StampRenderer.StampData(
                    companyName = getString(R.string.company_default),
                    timeText = timeFmt.format(now),
                    dateText = dateFmt.format(now).uppercase(Locale("in", "ID")),
                    dayText = dayFmt.format(now).uppercase(Locale("in", "ID")),
                    locationText = locationHelper.lastAddressText.ifBlank { "Lokasi tidak tersedia" },
                    logo = logoBitmap
                )

                // Save the raw (un-stamped) capture to a temp file, then let FFmpeg
                // composite the stamp onto it (same overlay approach used for video).
                val rawFile = File(cacheDir, "raw_photo_${System.currentTimeMillis()}.jpg")
                FileOutputStream(rawFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }

                val result = PhotoStampProcessor.process(this@MainActivity, rawFile, stampData)
                rawFile.delete()

                if (result.outputFile == null) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, R.string.failed_toast, Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                val fileName = "IMG_${System.currentTimeMillis()}.jpg"
                val saved = MediaStoreUtils.saveJpegFile(this@MainActivity, result.outputFile, fileName)

                val thumbBitmap = try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(result.outputFile.absolutePath, opts)
                } catch (e: Exception) {
                    null
                }

                result.outputFile.parentFile?.let { PhotoStampProcessor.cleanup(it, null) }

                runOnUiThread {
                    if (saved != null) {
                        lastPhotoUri = saved.uri
                        lastMediaMime = "image/jpeg"
                        if (thumbBitmap != null) binding.ivLastPhoto.setImageBitmap(thumbBitmap)
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.saved_toast, saved.displayName),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(this@MainActivity, R.string.failed_toast, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, exception.message, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------
    // Video capture
    // ---------------------------------------------------------------------

    @android.annotation.SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: return

        val file = File(cacheDir, "raw_${System.currentTimeMillis()}.mp4")
        recordingStartWallTimeMillis = System.currentTimeMillis()

        val outputOptions = FileOutputOptions.Builder(file).build()
        val hasAudioPermission =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        var pending = capture.output.prepareRecording(this, outputOptions)
        if (hasAudioPermission) pending = pending.withAudioEnabled()

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                onRecordingFinalized(event, file)
            }
        }

        binding.recordingIndicator.visibility = View.VISIBLE
        binding.tvRecordingTime.text = "00:00"
        recordingTimerHandler.post(recordingTimerRunnable)
        binding.tvModePhoto.isEnabled = false
        binding.tvModeVideo.isEnabled = false
    }

    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable)
        binding.recordingIndicator.visibility = View.GONE
        binding.tvModePhoto.isEnabled = true
        binding.tvModeVideo.isEnabled = true
    }

    private fun onRecordingFinalized(event: VideoRecordEvent.Finalize, file: File) {
        if (event.hasError()) {
            Toast.makeText(this, "Rekam gagal (kode ${event.error})", Toast.LENGTH_SHORT).show()
            file.delete()
            return
        }
        processRecordedVideo(file)
    }

    private fun processRecordedVideo(rawFile: File) {
        binding.processingOverlay.visibility = View.VISIBLE
        binding.tvProcessingStatus.text = getString(R.string.processing_video)

        val startWallTime = recordingStartWallTimeMillis
        val locationSnapshot = locationHelper.lastAddressText.ifBlank { "Lokasi tidak tersedia" }
        val logoBitmap = try {
            BitmapFactory.decodeResource(resources, R.drawable.logo)
        } catch (e: Exception) {
            null
        }

        Thread {
            val result = VideoStampProcessor.process(
                context = this,
                rawVideoFile = rawFile,
                recordingStartWallTimeMillis = startWallTime,
                companyName = getString(R.string.company_default),
                locationText = locationSnapshot,
                logo = logoBitmap,
                onProgress = { status -> runOnUiThread { binding.tvProcessingStatus.text = status } }
            )

            runOnUiThread { binding.processingOverlay.visibility = View.GONE }

            if (result.outputFile != null) {
                val outFileName = "VID_${System.currentTimeMillis()}.mp4"
                val saved = MediaStoreUtils.saveMp4(this, result.outputFile, outFileName)
                result.outputFile.parentFile?.let { VideoStampProcessor.cleanup(it, null) }
                rawFile.delete()

                runOnUiThread {
                    if (saved != null) {
                        lastPhotoUri = saved.uri
                        lastMediaMime = "video/mp4"
                        updateThumbnailFromVideo(saved.uri)
                        Toast.makeText(
                            this,
                            getString(R.string.video_saved_toast, saved.displayName),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(this, R.string.video_failed_toast, Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                rawFile.delete()
                runOnUiThread {
                    Toast.makeText(this, R.string.video_failed_toast, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun updateThumbnailFromVideo(uri: Uri) {
        Thread {
            val retriever = MediaMetadataRetriever()
            val frame = try {
                retriever.setDataSource(this, uri)
                retriever.frameAtTime
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }
            if (frame != null) {
                val scaled = Bitmap.createScaledBitmap(frame, 200, 200 * frame.height / frame.width, true)
                runOnUiThread { binding.ivLastPhoto.setImageBitmap(scaled) }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockRunnable)
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        locationHelper.stop()
        cameraExecutor.shutdown()
    }
}

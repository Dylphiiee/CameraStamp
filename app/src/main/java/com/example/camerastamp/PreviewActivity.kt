package com.example.camerastamp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri

/**
 * Shows the photo/video that was just captured (or the latest one from the gallery)
 * and offers quick actions to share it to WhatsApp, share generically, or open it
 * in the device's default gallery/viewer app.
 */
class PreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_MIME = "extra_mime" // "image/jpeg" or "video/mp4"
    }

    private var mediaUri: Uri? = null
    private var mimeType: String = "image/jpeg"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        val uriString = intent.getStringExtra(EXTRA_URI)
        mimeType = intent.getStringExtra(EXTRA_MIME) ?: "image/jpeg"
        mediaUri = uriString?.toUri()

        if (mediaUri == null) {
            Toast.makeText(this, "Media tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val ivPreview = findViewById<ImageView>(R.id.ivPreview)
        loadPreviewImage(ivPreview, mediaUri!!, mimeType)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<LinearLayout>(R.id.btnShareWhatsapp).setOnClickListener { shareToWhatsapp() }
        findViewById<LinearLayout>(R.id.btnShareOther).setOnClickListener { shareGeneric() }
        findViewById<LinearLayout>(R.id.btnOpenGallery).setOnClickListener { openInGallery() }
    }

    private fun loadPreviewImage(imageView: ImageView, uri: Uri, mime: String) {
        Thread {
            val bitmap: Bitmap? = try {
                if (mime.startsWith("video")) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(this, uri)
                        retriever.frameAtTime
                    } finally {
                        retriever.release()
                    }
                } else {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (bitmap != null) imageView.setImageBitmap(bitmap)
            }
        }.start()
    }

    private fun shareToWhatsapp() {
        val uri = mediaUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "WhatsApp tidak terpasang di perangkat ini", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareGeneric() {
        val uri = mediaUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Bagikan"))
    }

    private fun openInGallery() {
        val uri = mediaUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Tidak ada aplikasi galeri yang bisa membuka ini", Toast.LENGTH_SHORT).show()
        }
    }
}

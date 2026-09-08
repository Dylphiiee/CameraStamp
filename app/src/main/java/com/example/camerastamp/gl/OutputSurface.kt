package com.example.camerastamp.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface

/** Creates an OES texture + SurfaceTexture to be used as a MediaCodec decoder's output surface. */
class OutputSurface {

    val textureId: Int
    val surfaceTexture: SurfaceTexture
    val surface: Surface

    private val lock = Object()
    private var frameAvailable = false

    init {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener {
            synchronized(lock) {
                frameAvailable = true
                lock.notifyAll()
            }
        }
        surface = Surface(surfaceTexture)
    }

    /** Blocks (with timeout) until a new decoded frame is available, then updates the texture image. */
    fun awaitNewImage(timeoutMs: Long = 5000L): Boolean {
        synchronized(lock) {
            var waited = 0L
            while (!frameAvailable) {
                lock.wait(100)
                waited += 100
                if (waited >= timeoutMs) return false
            }
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
        return true
    }

    fun getTransformMatrix(matrix: FloatArray) = surfaceTexture.getTransformMatrix(matrix)

    fun release() {
        surface.release()
        surfaceTexture.release()
    }
}

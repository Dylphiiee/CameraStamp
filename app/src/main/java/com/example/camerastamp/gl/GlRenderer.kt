package com.example.camerastamp.gl

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders a full-screen external (OES) texture (the decoded video frame), optionally
 * rotated to correct for the source video's recorded orientation, then blends a
 * full-screen 2D RGBA texture (the stamp overlay) on top of it.
 */
class GlRenderer {

    private val quadVertices = floatArrayOf(
        -1f, -1f, 0f,
        1f, -1f, 0f,
        -1f, 1f, 0f,
        1f, 1f, 0f
    )
    // Texture coords flipped vertically (GL origin bottom-left vs bitmap top-left)
    private val quadTexCoords = floatArrayOf(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f
    )

    private val vertexBuffer = toFloatBuffer(quadVertices)
    private val texCoordBuffer = toFloatBuffer(quadTexCoords)

    private var oesProgram = 0
    private var oesPositionHandle = 0
    private var oesTexCoordHandle = 0
    private var oesMvpHandle = 0
    private var oesTexMatrixHandle = 0
    private var oesTextureHandle = 0

    private var tex2dProgram = 0
    private var tex2dPositionHandle = 0
    private var tex2dTexCoordHandle = 0
    private var tex2dMvpHandle = 0
    private var tex2dTextureHandle = 0

    private var overlayTextureId = -1
    private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val rotationMatrix = FloatArray(16)

    private var viewportW = 0
    private var viewportH = 0

    companion object {
        private const val OES_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uMvp;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private const val TEX2D_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMvp;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val TEX2D_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }

    init {
        oesProgram = buildProgram(OES_VERTEX_SHADER, OES_FRAGMENT_SHADER)
        oesPositionHandle = GLES20.glGetAttribLocation(oesProgram, "aPosition")
        oesTexCoordHandle = GLES20.glGetAttribLocation(oesProgram, "aTexCoord")
        oesMvpHandle = GLES20.glGetUniformLocation(oesProgram, "uMvp")
        oesTexMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix")
        oesTextureHandle = GLES20.glGetUniformLocation(oesProgram, "sTexture")

        tex2dProgram = buildProgram(TEX2D_VERTEX_SHADER, TEX2D_FRAGMENT_SHADER)
        tex2dPositionHandle = GLES20.glGetAttribLocation(tex2dProgram, "aPosition")
        tex2dTexCoordHandle = GLES20.glGetAttribLocation(tex2dProgram, "aTexCoord")
        tex2dMvpHandle = GLES20.glGetUniformLocation(tex2dProgram, "uMvp")
        tex2dTextureHandle = GLES20.glGetUniformLocation(tex2dProgram, "sTexture")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        overlayTextureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun setViewportSize(w: Int, h: Int) {
        viewportW = w
        viewportH = h
    }

    /**
     * [rotationDegrees] is the source video's rotation hint (0/90/180/270 - how much the
     * decoded buffer must be rotated clockwise to appear upright). The output surface's
     * width/height are already the POST-rotation (display) dimensions, so we rotate the
     * geometry here to bake that same correction directly into the encoded pixels.
     */
    fun drawVideoFrame(textureId: Int, texMatrix: FloatArray, rotationDegrees: Int) {
        GLES20.glViewport(0, 0, viewportW, viewportH)
        GLES20.glDisable(GLES20.GL_BLEND)

        Matrix.setIdentityM(rotationMatrix, 0)
        if (rotationDegrees != 0) {
            Matrix.rotateM(rotationMatrix, 0, rotationDegrees.toFloat(), 0f, 0f, 1f)
        }

        GLES20.glUseProgram(oesProgram)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(oesPositionHandle)
        GLES20.glVertexAttribPointer(oesPositionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(oesTexCoordHandle)
        GLES20.glVertexAttribPointer(oesTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glUniformMatrix4fv(oesMvpHandle, 1, false, rotationMatrix, 0)
        GLES20.glUniformMatrix4fv(oesTexMatrixHandle, 1, false, texMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(oesTextureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(oesPositionHandle)
        GLES20.glDisableVertexAttribArray(oesTexCoordHandle)
    }

    /** Uploads [bitmap] as the overlay texture. Call once before the render loop (the stamp is static). */
    fun updateOverlayTexture(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    fun drawOverlay() {
        GLES20.glViewport(0, 0, viewportW, viewportH)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(tex2dProgram)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(tex2dPositionHandle)
        GLES20.glVertexAttribPointer(tex2dPositionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(tex2dTexCoordHandle)
        GLES20.glVertexAttribPointer(tex2dTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glUniformMatrix4fv(tex2dMvpHandle, 1, false, identityMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glUniform1i(tex2dTextureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(tex2dPositionHandle)
        GLES20.glDisableVertexAttribArray(tex2dTexCoordHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("Program link failed: $log")
        }
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Shader compile failed: $log")
        }
        return shader
    }

    private fun toFloatBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(data); position(0) }
    }
}

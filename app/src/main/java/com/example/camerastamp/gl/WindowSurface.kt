package com.example.camerastamp.gl

import android.view.Surface

/** Wraps an EGL window surface backed by a MediaCodec encoder's input Surface. */
class WindowSurface(private val eglCore: EglCore, surface: Surface) {

    private val eglSurface = eglCore.createWindowSurface(surface)

    fun makeCurrent() = eglCore.makeCurrent(eglSurface)

    fun swapBuffers(): Boolean = eglCore.swapBuffers(eglSurface)

    fun setPresentationTime(nsecs: Long) = eglCore.setPresentationTime(eglSurface, nsecs)

    fun release() = eglCore.releaseSurface(eglSurface)
}

@file:Suppress("MemberVisibilityCanBePrivate")

package com.immortalalexsan.oscilloscope

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.opengl.GLES10.glLineWidth
import android.opengl.GLES20.GL_BLEND
import android.opengl.GLES20.GL_COLOR_BUFFER_BIT
import android.opengl.GLES20.GL_DEPTH_BUFFER_BIT
import android.opengl.GLES20.GL_FLOAT
import android.opengl.GLES20.GL_FRAGMENT_SHADER
import android.opengl.GLES20.GL_LINE_STRIP
import android.opengl.GLES20.GL_ONE_MINUS_SRC_ALPHA
import android.opengl.GLES20.GL_SRC_ALPHA
import android.opengl.GLES20.GL_VERTEX_SHADER
import android.opengl.GLES20.glBlendFunc
import android.opengl.GLES20.glClear
import android.opengl.GLES20.glClearColor
import android.opengl.GLES20.glDrawArrays
import android.opengl.GLES20.glEnable
import android.opengl.GLES20.glEnableVertexAttribArray
import android.opengl.GLES20.glGetAttribLocation
import android.opengl.GLES20.glGetUniformLocation
import android.opengl.GLES20.glUniform4f
import android.opengl.GLES20.glUseProgram
import android.opengl.GLES20.glVertexAttribPointer
import android.opengl.GLES20.glViewport
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.immortalalexsan.oscilloscope.helpers.ShaderHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

class Oscilloscope @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    GLSurfaceView(context, attrs), GLSurfaceView.Renderer, GLSurfaceView.EGLConfigChooser {

    /**
     * Signal with samples.
     */
    private val signal = arrayListOf<Sample>()

    /**
     * Buffer containing data to draw.
     */
    private var vertexData: FloatBuffer? = null

    /**
     * Identifier of the program including vertex and fragment shader.
     */
    private var programId = 0

    /**
     * Number of lines to draw.
     */
    private var linesCount = 0

    /**
     * How many seconds are contained in one pixel along the X axis.
     * The larger this value, the narrower the signal time will be.
     * Changes automatically and does not require editing.
     * Indirectly controlled via [timeScaleFactor].
     */
    @Volatile
    private var timeInPx = 0.0

    /**
     * How many units of amplitude are contained in one pixel along the Y axis.
     * The larger this value, the narrower the signal amplitude will be.
     * Changes automatically and does not require editing.
     * Indirectly controlled via [ampScaleFactor].
     */
    @Volatile
    private var ampInPx = 0.0

    /**
     * The shift of the signal in seconds along the X axis.
     * Changes automatically and does not require editing.
     */
    private var timeTranslation = 0.0

    /**
     * The index from which the signal display starts.
     */
    private var beginIndex = 0

    /**
     * The index from which the signal display ends.
     */
    private var endIndex = 0

    /**
     * Oscilloscope screen color.
     */
    @Volatile
    var screenColor = SCREEN_COLOR

    /**
     * Signal line color.
     */
    @Volatile
    var signalColor = SIGNAL_COLOR

    /**
     * Signal line thickness.
     */
    @Volatile
    var signalLineWidth = SIGNAL_LINE_WIDTH

    /**
     * The shift of the signal in units of amplitude along the Y axis.
     */
    @Volatile
    var ampTranslation = 0.5

    /**
     * How many seconds can fit on the X axis.
     */
    var timeScaleFactor = 4.0
        set(value) {
            field = value
            timeInPx = value / width
        }

    /**
     * How many units of amplitude can fit on the Y axis.
     */
    var ampScaleFactor = 2.0
        set(value) {
            field = value
            ampInPx = value / height
        }

    init {
        holder.setFormat(PixelFormat.RGBA_8888)
        setZOrderOnTop(true)
        setEGLContextClientVersion(2)
        setEGLConfigChooser(this)
        setRenderer(this)
    }

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val attrs = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, 16,
            EGL10.EGL_RENDERABLE_TYPE, 4,
            EGL10.EGL_SAMPLE_BUFFERS, 1,
            EGL10.EGL_SAMPLES, 4,
            EGL10.EGL_NONE
        )

        val configs = Array<EGLConfig?>(1) { null }
        val configCounts = IntArray(1)
        egl.eglChooseConfig(display, attrs, configs, 1, configCounts)

        if (configCounts[0] == 0) throw IllegalArgumentException("OpenGL was not configured")
        return configs[0]!!
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        createProgram()
        glUseProgram(programId)
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        timeInPx = timeScaleFactor / width
        ampInPx = ampScaleFactor / height
    }

    @Synchronized
    override fun onDrawFrame(gl: GL10) {
        paintBackground()
        paintSignal()
    }

    private fun getTime(i: Int) = signal[i].x - signal[0].x

    private fun timeToPx(i: Int) = ((getTime(i) - timeTranslation) / timeInPx)

    private fun ampToPx(i: Int, minAmp: Double, ampRange: Double) =
        (((signal[i].y - minAmp) / ampRange + ampTranslation) / ampInPx)

    private fun pxToPlaneX(px: Double) = (2.0 * (px + 0.5) / width - 1.0).toFloat()

    private fun pxToPlaneY(px: Double) = (2.0 * (px + 0.5) / height - 1.0).toFloat()

    private fun createProgram() {
        context?.let {
            programId = ShaderHelper.createProgram(
                ShaderHelper.createShader(it, GL_VERTEX_SHADER, R.raw.vertex_shader),
                ShaderHelper.createShader(it, GL_FRAGMENT_SHADER, R.raw.fragment_shader)
            )
        }
    }

    private fun paintBackground() {
        val r = Color.red(screenColor) / 255.0f
        val g = Color.green(screenColor) / 255.0f
        val b = Color.blue(screenColor) / 255.0f
        val a = Color.alpha(screenColor) / 255.0f

        glClearColor(r, g, b, a)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
    }

    private fun paintSignal() {
        if (linesCount != 0) {
            glLineWidth(signalLineWidth)

            val r = Color.red(signalColor) / 255.0f
            val g = Color.green(signalColor) / 255.0f
            val b = Color.blue(signalColor) / 255.0f
            val a = Color.alpha(signalColor) / 255.0f

            val uColorLocation = glGetUniformLocation(programId, "u_Color")
            glUniform4f(uColorLocation, r, g, b, a)

            val aPositionLocation = glGetAttribLocation(programId, "a_Position")
            glVertexAttribPointer(aPositionLocation, 2, GL_FLOAT, false, 0, vertexData)
            glEnableVertexAttribArray(aPositionLocation)

            glDrawArrays(GL_LINE_STRIP, 0, linesCount)
        }
    }

    @Synchronized
    fun updateData(sample: Sample) {
        signal.add(sample)

        val signalTime = getTime(signal.lastIndex)
        val subTime = signalTime - timeScaleFactor
        if (subTime > 0.0) {
            val index = (subTime / (signalTime / signal.size)).toInt() - 1
            timeTranslation = subTime
            beginIndex = if (index < 0) 0 else index
        }

        endIndex = signal.lastIndex
        linesCount = endIndex - beginIndex

        var minAmp = signal[beginIndex].y
        var maxAmp = signal[beginIndex].y
        for (i in beginIndex + 1..endIndex) {
            if (signal[i].y < minAmp) minAmp = signal[i].y
            if (signal[i].y > maxAmp) maxAmp = signal[i].y
        }
        val ampRange = maxAmp - minAmp

        val size = (linesCount + 1) * 4 * 2
        vertexData = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in beginIndex..endIndex) {
            val x = pxToPlaneX(timeToPx(i))
            val y = pxToPlaneY(ampToPx(i, minAmp, ampRange))
            vertexData!!.put(x).put(y)
        }
        vertexData!!.position(0)
    }

    @Synchronized
    fun changeState(enabled: Boolean) {
        if (enabled) {
            timeTranslation = 0.0
            beginIndex = 0
            endIndex = 0
            linesCount = 0
            signal.clear()
            vertexData = null
        }
    }

    companion object {
        const val SIGNAL_LINE_WIDTH = 5.0f

        const val SIGNAL_COLOR = Color.BLACK
        const val SCREEN_COLOR = Color.TRANSPARENT
    }

    data class Sample(val x: Double, val y: Double)
}

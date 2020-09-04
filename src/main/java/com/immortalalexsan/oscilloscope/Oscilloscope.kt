package com.immortalalexsan.oscilloscope

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class Oscilloscope @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : SurfaceView(context, attrs, defStyleAttr, defStyleRes), SurfaceHolder.Callback {

    /**
     * Signal with samples.
     */
    private val signal = arrayListOf<Sample>()

    /**
     * The [Paint] instance holds the style and color information about how to draw signal data.
     */
    private val signalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = SIGNAL_WIDTH
        color = SIGNAL_COLOR
    }

    /**
     * Oscilloscope screen color.
     */
    private var screenColor: Drawable? = null

    /**
     * How many seconds are contained in one pixel along the X axis.
     * The larger this value, the narrower the signal time will be.
     * Changes automatically and does not require editing.
     * Indirectly controlled via [timeScaleFactor].
     */
    @Volatile
    private var timeInPx = 0f
        @Synchronized set

    /**
     * How many units of amplitude are contained in one pixel along the Y axis.
     * The larger this value, the narrower the signal amplitude will be.
     * Changes automatically and does not require editing.
     * Indirectly controlled via [ampScaleFactor].
     */
    @Volatile
    private var ampInPx = 0f
        @Synchronized set

    /**
     * The shift of the signal in seconds along the X axis.
     * Changes automatically and does not require editing.
     */
    private var timeTranslation = 0f

    /**
     * The minimum amplitude of the visible part of the signal.
     * Used to scale the signal by amplitude.
     */
    private var minAmp = 0f

    /**
     * The maximum amplitude of the visible part of the signal.
     * Used to scale the signal by amplitude.
     */
    private var maxAmp = 0f

    /**
     * The range between [maxAmp] and [minAmp].
     * Used to scale the signal by amplitude.
     */
    private var ampRange = 0f

    /**
     * The index from which the signal display starts.
     */
    private var beginIndex = 0

    /**
     * The index from which the signal display ends.
     */
    private var endIndex = 0

    /**
     * An instance of [Job] for painting on canvas.
     */
    private var paintJob: Job? = null

    /**
     * The shift of the signal in units of amplitude along the Y axis.
     */
    @Volatile
    var ampTranslation = 0.5f
        @Synchronized set

    /**
     * How many seconds can fit on the X axis.
     */
    var timeScaleFactor = 4f
        set(value) {
            field = value
            timeInPx = value / width
        }

    /**
     * How many units of amplitude can fit on the Y axis.
     */
    var ampScaleFactor = 2f
        set(value) {
            field = value
            ampInPx = value / height
        }

    init {
        setZOrderOnTop(true)
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSPARENT)
    }

    override fun setBackgroundColor(color: Int) {
        screenColor = ColorDrawable(color)
    }

    override fun setBackground(background: Drawable?) {
        screenColor = background
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        screenColor?.apply { setBounds(left, top, right, bottom) }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        paintJob = GlobalScope.launch {
            while (isActive) {
                val canvas = holder.lockCanvas() ?: continue
                try {
                    synchronized(holder) {
                        paint(canvas)
                    }
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
            }
        }
        paintJob?.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        timeInPx = timeScaleFactor / width
        ampInPx = ampScaleFactor / height
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        runBlocking {
            paintJob?.cancelAndJoin()
        }
    }

    private fun getTime(i: Int) = signal[i].x - signal[0].x

    private fun timeToPx(i: Int) = ((getTime(i) - timeTranslation) / timeInPx)

    private fun ampToPx(i: Int) = (((signal[i].y - minAmp) / ampRange + ampTranslation) / ampInPx)

    private fun paintBackground(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        screenColor?.draw(canvas)
    }

    private fun paintSignal(canvas: Canvas) {
        if (signal.size > 1) {
            var xTemp = timeToPx(beginIndex)
            var yTemp = ampToPx(beginIndex)
            for (i in beginIndex + 1..endIndex) {
                val x0 = xTemp
                val y0 = yTemp
                val x1 = timeToPx(i)
                val y1 = ampToPx(i)
                xTemp = x1
                yTemp = y1
                canvas.drawLine(x0, y0, x1, y1, signalPaint)
            }
        }
    }

    @Synchronized
    private fun paint(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, canvas.height.toFloat())
        canvas.scale(1f, -1f)
        paintBackground(canvas)
        paintSignal(canvas)
        canvas.restore()
    }

    @Synchronized
    fun setSignalWidth(strokeWidth: Float) {
        signalPaint.strokeWidth = strokeWidth
    }

    @Synchronized
    fun setSignalColor(color: Int) {
        signalPaint.color = color
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

        var min = signal[beginIndex].y
        var max = signal[beginIndex].y
        for (i in beginIndex + 1..endIndex) {
            if (signal[i].y < min) min = signal[i].y
            if (signal[i].y > max) max = signal[i].y
        }

        minAmp = min
        maxAmp = max
        ampRange = max - min
    }

    @Synchronized
    fun changeState(enabled: Boolean) {
        if (enabled) {
            timeTranslation = 0f
            minAmp = 0f
            maxAmp = 0f
            ampRange = 0f
            beginIndex = 0
            endIndex = 0
            signal.clear()
        }
    }

    fun release() {
        holder.removeCallback(this)
    }

    companion object {
        const val SIGNAL_WIDTH = 7f
        const val SIGNAL_COLOR = Color.BLACK
    }

    data class Sample(val x: Float, val y: Float)
}

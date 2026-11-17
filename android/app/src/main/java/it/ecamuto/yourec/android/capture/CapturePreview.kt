package it.ecamuto.yourec.android.capture

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import kotlin.math.min

/** SurfaceView handles the camera producer's rotation; fit the rotated 16:9 buffer. */
class CapturePreview(context: Context) : FrameLayout(context), SurfaceHolder.Callback {
    private val preview = SurfaceView(context)
    var onAvailable: (() -> Unit)? = null
    var onGone: (() -> Unit)? = null
    val surface: Surface? get() = preview.holder.surface.takeIf { it.isValid }
    private var portrait = true

    init {
        setBackgroundColor(Color.BLACK)
        addView(preview, LayoutParams(1, 1, Gravity.CENTER))
        preview.holder.addCallback(this)
        preview.holder.setFixedSize(1920, 1080)
    }

    fun configure(rotationDegrees: Int) {
        portrait = rotationDegrees % 180 != 0
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val imageWidth = if (portrait) 1080 else 1920
        val imageHeight = if (portrait) 1920 else 1080
        val scale = min(measuredWidth.toFloat() / imageWidth, measuredHeight.toFloat() / imageHeight)
        val w = maxOf(1, (imageWidth * scale).toInt())
        val h = maxOf(1, (imageHeight * scale).toInt())
        preview.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // Measure in the current pass: controls appearing during capture change the
        // available height. Updating LayoutParams in onSizeChanged loses that pass.
        val x = (width - preview.measuredWidth) / 2
        val y = (height - preview.measuredHeight) / 2
        preview.layout(x, y, x + preview.measuredWidth, y + preview.measuredHeight)
    }

    override fun surfaceCreated(holder: SurfaceHolder) { onAvailable?.invoke() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { onAvailable?.invoke() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { onGone?.invoke() }
}

package com.ahusan.airmirror

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** Keeps its child at the video's aspect ratio, letterboxed inside the screen. */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var ratio = 0.0

    fun setAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val r = width.toDouble() / height
        if (r != ratio) {
            ratio = r
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (ratio <= 0.0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        var w = MeasureSpec.getSize(widthMeasureSpec)
        var h = MeasureSpec.getSize(heightMeasureSpec)
        if (w.toDouble() / h > ratio) {
            w = (h * ratio).toInt()
        } else {
            h = (w / ratio).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }
}

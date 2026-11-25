package com.hobby.aa2carlife.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** 固定宽高比的预览容器：只按视频宽高比缩放并居中，不旋转。 */
class PreviewLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    /** 视频宽高比 width/height（CarLife 1412x654 ≈ 2.16）。 */
    var videoAspect: Float = 16f / 9f
        private set

    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val aspect = width.toFloat() / height
        if (kotlin.math.abs(aspect - videoAspect) < 0.001f) return
        videoAspect = aspect
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = getChildAt(0)
        val maxW = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val maxH = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        if (child == null) {
            setMeasuredDimension(maxW, maxH)
            return
        }
        var cw = maxW
        var ch = (cw / videoAspect).toInt()
        if (ch > maxH) {
            ch = maxH
            cw = (ch * videoAspect).toInt()
        }
        cw = cw.coerceAtLeast(1)
        ch = ch.coerceAtLeast(1)
        child.measure(
            MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(cw, ch)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        val cw = child.measuredWidth
        val ch = child.measuredHeight
        val l = (width - cw) / 2
        val t = (height - ch) / 2
        child.layout(l, t, l + cw, t + ch)
    }
}
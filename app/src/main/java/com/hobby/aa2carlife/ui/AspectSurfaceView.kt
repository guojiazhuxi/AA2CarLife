package com.hobby.aa2carlife.ui

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView

/**
 * 固定宽高比的 SurfaceView。
 *
 * - `rotate90 = false`：16:9（CarLife 画面横向显示）
 * - `rotate90 = true` ：9:16（解码器把画面顺时针转 90°，预览时内容侧躺但能最大填充竖屏）
 *
 * onMeasure 里按父容器可用空间取「保持比例的最大区域」。
 */
class AspectSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : SurfaceView(context, attrs, defStyle) {

    /** 是否把画面旋转 90° 显示（宽高比取倒数）。 */
    var rotate90: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** 实际视频宽高比：width / height（CarLife 1412x654 ≈ 2.159）。 */
    var videoAspect: Float = 16f / 9f
        private set

    /** 设置实际码流尺寸，让预览按真实比例显示（横屏 2.16:1，屏占比更高）。 */
    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val aspect = width.toFloat() / height
        if (kotlin.math.abs(aspect - videoAspect) < 0.001f) return
        videoAspect = aspect
        requestLayout()
    }

    /** 内容（视频）宽高比：width / height；旋转时取倒数。 */
    private val contentAspect: Float
        get() = if (rotate90) 1f / videoAspect else videoAspect

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        var width = widthSize
        var height = (width / contentAspect).toInt()
        if (height > heightSize) {
            height = heightSize
            width = (height * contentAspect).toInt()
        }
        setMeasuredDimension(width.coerceAtLeast(0), height.coerceAtLeast(0))
    }
}

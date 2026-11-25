package com.hobby.aa2carlife.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * 固定宽高比的预览容器：
 * - rotate90=false：内容横向，容器 W/H = videoAspect；
 * - rotate90=true ：子 View 保持横向绘制，整个子 View 顺时针转 90°，容器 W/H = 1/videoAspect。
 *
 * 子 View 用 TextureView：旋转只改 View 的 rotation/尺寸，不重建解码器，也不碰 MediaCodec
 * 的 KEY_ROTATION。
 */
class RotateLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    var rotate90: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

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
        if (!rotate90) {
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
        } else {
            // 显示区域是竖的：W/H = 1/videoAspect，即 H = W * videoAspect。
            var pw = maxW
            var ph = (pw * videoAspect).toInt()
            if (ph > maxH) {
                ph = maxH
                pw = (ph / videoAspect).toInt()
            }
            pw = pw.coerceAtLeast(1)
            ph = ph.coerceAtLeast(1)
            // 子 View 未旋转前是横的：宽 = 显示高，高 = 显示宽。
            val cw = ph
            val ch = pw
            child.measure(
                MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY),
            )
            setMeasuredDimension(pw, ph)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        val cw = child.measuredWidth
        val ch = child.measuredHeight
        val l = (width - cw) / 2
        val t = (height - ch) / 2
        child.layout(l, t, l + cw, t + ch)
        child.rotation = if (rotate90) 90f else 0f
    }
}
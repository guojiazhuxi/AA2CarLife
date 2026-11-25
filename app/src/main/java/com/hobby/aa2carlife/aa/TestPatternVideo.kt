package com.hobby.aa2carlife.aa

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.SystemClock
import com.hobby.aa2carlife.diag.RingLog
import java.nio.ByteBuffer

/**
 * 独立于 CarLife 的 USB 测试图：设备编码器直接生成 30fps 动态画面，走 AA 视频通道。
 *
 * 用途：把 AA 视频链路和 CarLife/转码链路彻底分开。车机能显示这块移动色条，
 * 就说明 AA 协议、编码、USB 传输都没问题；不显示则问题在 AA 手机端/车机侧。
 */
class TestPatternVideo(
    private val width: Int,
    private val height: Int,
    private val onFrame: (ByteArray) -> Unit,
) {
    @Volatile
    private var running = true

    private val thread = Thread({ loop() }, "aa-test-pattern").apply { isDaemon = true }

    fun start() {
        if (!thread.isAlive) thread.start()
    }

    fun stop() {
        running = false
        thread.interrupt()
    }

    private fun loop() {
        var encoder: MediaCodec? = null
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var window: EGLSurface = EGL14.EGL_NO_SURFACE
        var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "eglChooseConfig" }
            val cfg = configs[0]!!
            context = EGL14.eglCreateContext(
                display, cfg, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
            pbuffer = EGL14.eglCreatePbufferSurface(
                display, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent(pbuffer)" }

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, 0x7F000789) // COLOR_FormatSurface
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                runCatching {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                }
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()
            window = EGL14.eglCreateWindowSurface(display, cfg, inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
            check(window != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
            check(EGL14.eglMakeCurrent(display, window, window, context)) { "eglMakeCurrent(window)" }
            GLES20.glViewport(0, 0, width, height)

            val info = MediaCodec.BufferInfo()
            var csd: ByteArray? = null
            var first = true
            val startNs = System.nanoTime()
            RingLog.add("TESTPAT: 开始 ${width}x$height@30fps（不依赖 CarLife）")
            while (running) {
                val t = (System.nanoTime() - startNs) / 1_000_000_000.0
                val barW = (width / 6).coerceAtLeast(1)
                val travel = (width + barW)
                val barX = ((t * width / 2.0).toInt() % travel) - barW
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                val r = (0.12 + 0.06 * kotlin.math.sin(t * 2.0)).toFloat()
                val g = (0.22 + 0.06 * kotlin.math.sin(t * 2.0 + 2.0)).toFloat()
                val b = (0.34 + 0.06 * kotlin.math.sin(t * 2.0 + 4.0)).toFloat()
                GLES20.glClearColor(r, g, b, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                val x = barX.coerceIn(0, width - 1)
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glScissor(x, height / 4, barW.coerceAtMost(width), height / 2)
                GLES20.glClearColor(1f, 0.9f, 0.2f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                EGLExt.eglPresentationTimeANDROID(display, window, System.nanoTime())
                EGL14.eglSwapBuffers(display, window)

                while (true) {
                    val idx = encoder.dequeueOutputBuffer(info, 0)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = encoder.outputFormat
                        val c0 = f.getByteBuffer("csd-0")?.let { copy(it) }
                        val c1 = f.getByteBuffer("csd-1")?.let { copy(it) }
                        val c = ByteArray((c0?.size ?: 0) + (c1?.size ?: 0))
                        c0?.copyInto(c, 0, 0, c0.size)
                        c1?.copyInto(c, c0?.size ?: 0, 0, c1.size)
                        csd = c
                    } else if (idx >= 0) {
                        val buf = encoder.getOutputBuffer(idx) ?: continue
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        encoder.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) continue
                        if (data.isEmpty()) continue
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        var out = data
                        if (key && first) {
                            first = false
                            csd?.let { c ->
                                out = ByteArray(c.size + data.size)
                                c.copyInto(out, 0)
                                data.copyInto(out, c.size)
                            }
                        }
                        onFrame(out)
                    }
                }
                SystemClock.sleep(33)
            }
        } catch (t: Throwable) {
            if (running) RingLog.add("TESTPAT: 异常 ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            running = false
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (display != EGL14.EGL_NO_DISPLAY) {
                runCatching {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                }
                if (window != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, window) }
                if (pbuffer != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, pbuffer) }
                runCatching { EGL14.eglDestroyContext(display, context) }
                runCatching { EGL14.eglTerminate(display) }
            }
            RingLog.add("TESTPAT: 已停止")
        }
    }

    private fun copy(b: ByteBuffer): ByteArray {
        val d = b.duplicate()
        val out = ByteArray(d.remaining())
        d.get(out)
        return out
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

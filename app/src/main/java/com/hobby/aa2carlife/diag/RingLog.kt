package com.hobby.aa2carlife.diag

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 极简环形日志：App 内显示 + 可复制 + 写 logcat + 落盘文件。
 *
 * 落盘文件：`files/aa2cl.log`（debug 包可用 `adb shell run-as com.hobby.aa2carlife`
 * 直接读取；上车后 adb 不可用时也能在 App 内复制）。
 */
object RingLog {

    private const val TAG = "AA2CL"
    private const val MAX_LINES = 400
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024L

    private val lines = ArrayDeque<String>()

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    var listener: ((String) -> Unit)? = null

    private var writer: BufferedWriter? = null

    /** 在 Activity/Service 启动时调用一次；重复调用无副作用。 */
    @Synchronized
    fun init(context: Context) {
        if (writer != null) return
        try {
            val file = File(context.filesDir, "aa2cl.log")
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                file.delete()
            }
            writer = BufferedWriter(FileWriter(file, true))
        } catch (e: Exception) {
            Log.w(TAG, "初始化文件日志失败: ${e.message}")
        }
    }

    fun add(message: String) {
        val line = "${fmt.format(Date())} $message"
        // 1) logcat（开发/联调时我可直接用 adb 读取）
        Log.i(TAG, message)
        // 2) 内存环形缓冲（App 内显示）
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        // 3) 落盘
        synchronized(this) {
            try {
                writer?.apply {
                    append(line)
                    append('\n')
                    flush()
                }
            } catch (_: Exception) {
                // 文件写入失败不影响主流程
            }
        }
        listener?.invoke(line)
    }

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() = synchronized(lines) { lines.clear() }
}

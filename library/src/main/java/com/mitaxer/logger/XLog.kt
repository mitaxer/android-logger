package com.mitaxer.logger

import android.content.Context
import android.util.Log

/**
 * XLog — Native 日志库
 *
 * 基于 MMAP + 异步写入的 C++ 日志引擎，不加密、零依赖。
 *
 * 使用方式：
 *   // Application.onCreate
 *   XLog.init(this)
 *
 *   // 任意位置
 *   XLog.i("TAG", "message")
 *   XLog.e("TAG", "exception: ${e.message}")
 *   XLog.full("API", responseBody)     // 长日志自动分多条
 *
 * 日志文件输出到 ${filesDir}/log/ 目录，纯文本 .xlog 格式。
 */
object XLog {

    private const val TAG = "XLog"
    private var initialized = false

    // 日志级别（与 Android Log 一致）
    const val LEVEL_SILENT  = 1  // 关闭输出
    const val LEVEL_VERBOSE = 2  // Android: VERBOSE
    const val LEVEL_DEBUG   = 3  // Android: DEBUG
    const val LEVEL_INFO    = 4  // Android: INFO
    const val LEVEL_WARNING = 5  // Android: WARN
    const val LEVEL_ERROR   = 6  // Android: ERROR

    // 类加载时立即加载 .so，确保在任何方法调用前 native 方法可用
    init {
        System.loadLibrary("android-logger")
    }

    /** 格式缓冲/单条日志 body 上下限（与 C++ 层一致） */
    const val MIN_BODY_SIZE = 256
    const val MAX_BODY_SIZE = 64 * 1024
    const val DEFAULT_BODY_SIZE = 14 * 1024

    /** MMAP 大小上下限 */
    const val MIN_MMAP_SIZE = 64 * 1024
    const val MAX_MMAP_SIZE = 1024 * 1024
    const val DEFAULT_MMAP_SIZE = 150 * 1024

    /** 单文件分割大小，0=不分割 */
    const val DEFAULT_MAX_FILE_SIZE = 50 * 1024 * 1024

    /** full() 每条预留头部分片标记 */
    private const val PART_HEADER = 80

    /**
     * 初始化 Native 日志引擎
     *
     * @param context       Application context
     * @param namePrefix    日志文件名前缀，默认 "APP"
     * @param fileLevel     文件输出级别，低于此级别不写文件。默认 LEVEL_VERBOSE（全量）
     * @param consoleLevel  logcat 输出级别，低于此级别不输出。默认 LEVEL_SILENT（关闭）
     * @param bodySize      格式缓冲大小，低于 MIN_BODY_SIZE 取 MIN，高于 MAX_BODY_SIZE 取 MAX
     * @param mmapSize      MMAP 缓冲大小，低于 MIN_MMAP_SIZE 取 MIN，高于 MAX_MMAP_SIZE 取 MAX
     * @param maxFileSize   单文件分割大小（字节），0=不分割。默认 50MB
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        context: Context,
        namePrefix: String = "APP",
        fileLevel: Int = LEVEL_VERBOSE,
        consoleLevel: Int = LEVEL_VERBOSE,
        bodySize: Int = DEFAULT_BODY_SIZE,
        mmapSize: Int = DEFAULT_MMAP_SIZE,
        maxFileSize: Int = DEFAULT_MAX_FILE_SIZE
    ) {
        if (initialized) {
            Log.w(TAG, "already initialized")
            return
        }

        try {
            val logDir = "${context.filesDir.absolutePath}/log"
            val cacheDir = "${context.cacheDir.absolutePath}/xlog"

            java.io.File(logDir).mkdirs()
            java.io.File(cacheDir).mkdirs()

            val result = nativeInit(logDir, cacheDir, namePrefix, fileLevel, consoleLevel, bodySize, mmapSize, maxFileSize)
            if (result) {
                initialized = true
                val fileLevelName = levelName(fileLevel)
                val consoleLevelName = levelName(consoleLevel)
                i(TAG, "=== XLog init complete ===")
                i(TAG, "  namePrefix:  $namePrefix")
                i(TAG, "  logDir:      $logDir")
                i(TAG, "  cacheDir:    $cacheDir")
                i(TAG, "  fileLevel:   $fileLevel ($fileLevelName)")
                i(TAG, "  consoleLevel:$consoleLevel ($consoleLevelName)")
                i(TAG, "  bodySize:    $bodySize")
                i(TAG, "  mmapSize:    $mmapSize")
                i(TAG, "  maxFileSize: $maxFileSize")
                i(TAG, "  maxAliveSec: 604800 (7.0 天)")
                i(TAG, "======================")
            } else {
                Log.e(TAG, "nativeInit failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "init failed: ${e.message}")
        }
    }

    // ── 日志接口 ───────────────────────────────────────

    @JvmStatic
    fun v(tag: String, msg: String?) = write(LEVEL_VERBOSE, tag, msg)

    @JvmStatic
    fun d(tag: String, msg: String?) = write(LEVEL_DEBUG, tag, msg)

    @JvmStatic
    fun i(tag: String, msg: String?) = write(LEVEL_INFO, tag, msg)

    @JvmStatic
    fun w(tag: String, msg: String?) = write(LEVEL_WARNING, tag, msg)

    @JvmStatic
    fun e(tag: String, msg: String?) = write(LEVEL_ERROR, tag, msg)

    // ── 内部写入口 ───────────────────────────────────

    private fun write(level: Int, tag: String, msg: String?) {
        if (msg == null) return
        nativeWrite(level, tag, msg)
    }

    private fun levelName(level: Int): String = when (level) {
        LEVEL_SILENT  -> "SILENT"
        LEVEL_VERBOSE -> "VERBOSE"
        LEVEL_DEBUG   -> "DEBUG"
        LEVEL_INFO    -> "INFO"
        LEVEL_WARNING -> "WARNING"
        LEVEL_ERROR   -> "ERROR"
        else          -> "UNKNOWN"
    }

    // ── 长日志专用 ────────────────────────────────────

    /**
     * 写入长日志，不截断，超过 bodySize 自动分多条。
     * 适合接口响应体等需要完整内容但可能超过 C++ 格式缓冲的场景。
     *
     * @param tag      TAG
     * @param msg      日志内容
     * @param level    日志级别，默认 INFO
     * @param bodySize 每条上限，默认 DEFAULT_BODY_SIZE（应与 init() 中 bodySize 一致）
     */
    @JvmStatic
    @JvmOverloads
    fun full(tag: String, msg: String, level: Int = LEVEL_INFO, bodySize: Int = DEFAULT_BODY_SIZE) {
        if (msg.length <= bodySize) {
            nativeWrite(level, tag, msg)
            return
        }
        val chunkSize = bodySize - PART_HEADER
        var offset = 0
        var part = 0
        while (offset < msg.length) {
            val end = minOf(offset + chunkSize, msg.length)
            nativeWrite(level, tag, if (part == 0) msg.substring(offset, end) else "[P$part] ${msg.substring(offset, end)}")
            offset = end
            part++
        }
    }

    // ── 控制 ──────────────────────────────────────────

    /** 触发异步刷盘 */
    @JvmStatic
    fun flush() {
        if (!initialized) return
        nativeFlush()
    }

    /** 关闭日志引擎 */
    @JvmStatic
    fun close() {
        if (!initialized) return
        nativeClose()
        initialized = false
    }

    // ── JNI ──────────────────────────────────────────

    private external fun nativeInit(
        logDir: String,
        cacheDir: String,
        namePrefix: String,
        fileLevel: Int,
        consoleLevel: Int,
        bodySize: Int,
        mmapSize: Int,
        maxFileSize: Int
    ): Boolean

    private external fun nativeWrite(level: Int, tag: String, msg: String)
    private external fun nativeFlush()
    private external fun nativeClose()
}

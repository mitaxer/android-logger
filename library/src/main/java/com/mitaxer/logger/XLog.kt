package com.mitaxer.logger

import android.content.Context
import android.os.Build
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
object XLog : ILog {

    private const val TAG = "XLog"

    /**
     * 是否已成功初始化。
     *
     * 用 @Volatile 保证跨线程可见性（init/close 写入，write/isInitialized 读取）；
     * 双初始化竞态由 init() 的 @Synchronized 串行化解决，不需要 AtomicBoolean+CAS——
     * CAS 先置位再初始化的模式会让并发调用方在初始化完成前就看到 true。
     */
    @Volatile
    private var initialized = false

    // 日志级别（使用 Android SDK 官方常量）
    const val LEVEL_SILENT  = 1   // 关闭输出（非 Android 标准）
    const val LEVEL_VERBOSE = Log.VERBOSE
    const val LEVEL_DEBUG   = Log.DEBUG
    const val LEVEL_INFO    = Log.INFO
    const val LEVEL_WARNING = Log.WARN
    const val LEVEL_ERROR   = Log.ERROR

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

    /** init 时实际生效的 bodySize（clamp 后），分条逻辑以此为准 */
    private var bodySize = DEFAULT_BODY_SIZE

    /** full() 每条预留头部分片标记 */
    private const val PART_HEADER = 80

    /**
     * 初始化 Native 日志引擎
     *
     * @param context       Application context
     * @param namePrefix    日志文件名前缀，默认 "APP"
     * @param fileLevel     文件输出级别，低于此级别不写文件。默认 LEVEL_VERBOSE（全量）
     * @param consoleLevel  logcat 输出级别，低于此级别不输出。默认 LEVEL_VERBOSE（全量）
     * @param bodySize      格式缓冲大小，低于 MIN_BODY_SIZE 取 MIN，高于 MAX_BODY_SIZE 取 MAX
     * @param mmapSize      MMAP 缓冲大小，低于 MIN_MMAP_SIZE 取 MIN，高于 MAX_MMAP_SIZE 取 MAX
     * @param maxFileSize   单文件分割大小（字节），0=不分割。默认 50MB
     * @param appVersion    版本号。不传时自动取 PackageManager 的 versionName，
     *                      可传自定义值覆盖（如 hotfix/渠道标记 "5.2.0-patch3"）
     * @param deviceInfo    设备信息。不传时自动收集（品牌/型号/系统版本/ABI）
     */
    @Synchronized
    @JvmStatic
    @JvmOverloads
    fun init(
        context: Context,
        namePrefix: String = "APP",
        fileLevel: Int = LEVEL_VERBOSE,
        consoleLevel: Int = LEVEL_VERBOSE,
        bodySize: Int = DEFAULT_BODY_SIZE,
        mmapSize: Int = DEFAULT_MMAP_SIZE,
        maxFileSize: Int = DEFAULT_MAX_FILE_SIZE,
        appVersion: String? = null,
        deviceInfo: String? = null
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

            val version = appVersion ?: fetchAppVersion(context)
            val device = deviceInfo ?: buildDeviceInfo()

            val result = nativeInit(logDir, cacheDir, namePrefix, fileLevel, consoleLevel, bodySize, mmapSize, maxFileSize, version, device)
            if (result) {
                initialized = true
                this.bodySize = bodySize.coerceIn(MIN_BODY_SIZE, MAX_BODY_SIZE)
                i(TAG, "init succeeded")
            } else {
                Log.e(TAG, "nativeInit failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "init failed: ${e.message}")
        }
    }

    // ── init 辅助：版本号/设备信息收集 ────────────────

    /** 取当前 app 版本号（versionName），取不到返回 "unknown" */
    private fun fetchAppVersion(context: Context): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: Exception) {
        null
    } ?: "unknown"

    /** 收集非标识性设备信息：品牌 型号, 系统版本 (API level), ABI */
    private fun buildDeviceInfo(): String =
        "${Build.BRAND} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")

    // ── 日志接口（ILog 实现）──────────────────────────

    /** ILog 实现：直接写本地文件 */
    override fun log(level: Int, tag: String, msg: String) = write(level, tag, msg)

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

    /**
     * 内部写入口：所有日志级别的统一出口。
     *
     * 超长内容（超过 bodySize）自动分多条写入，每条带 P$n 分片标记——
     * native 层对放不进格式缓冲的条目会直接丢弃，因此分条必须在 Kotlin 层完成。
     * 每片正文取 bodySize - PART_HEADER（预留 native 格式头部 + 分片前缀）。
     */
    private fun write(level: Int, tag: String, msg: String?) {
        // 未初始化直接丢弃，避免在 native 未就绪时调用（native 层同样会静默 no-op）
        if (!initialized) return
        if (msg == null) return
        if (msg.length <= bodySize) {
            nativeWrite(level, tag, msg)
            return
        }
        val cs = bodySize - PART_HEADER
        var offset = 0
        var part = 0
        while (offset < msg.length) {
            val end = minOf(offset + cs, msg.length)
            nativeWrite(level, tag, if (part == 0) msg.substring(offset, end) else "[P$part] ${msg.substring(offset, end)}")
            offset = end
            part++
        }
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

    // ── 控制 ──────────────────────────────────────────

    /** 触发异步刷盘，并追加一条分隔标记到日志 */
    @JvmStatic
    fun flush() {
        if (!initialized) return
        nativeFlush()
    }

    /** 是否已成功初始化（可查询，便于调用方决定是否打日志/是否需要重新 init） */
    @JvmStatic
    fun isInitialized(): Boolean = initialized

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
        maxFileSize: Int,
        appVersion: String,
        deviceInfo: String
    ): Boolean

    private external fun nativeWrite(level: Int, tag: String, msg: String)
    private external fun nativeFlush()
    private external fun nativeClose()
}

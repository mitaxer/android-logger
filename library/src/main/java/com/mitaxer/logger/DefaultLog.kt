package com.mitaxer.logger

import android.util.Log

/**
 * 默认 [ILog] 实现：输出到 logcat。
 *
 * 用途：作为未收到父进程注入时的兜底。进程 B 的 ILog 变量初始值设为 [DefaultLog]，
 * 保证日志调用永不判空、永不静默丢弃（至少 logcat 可见）；
 * 父进程通过 AIDL 赋值后，变量切换到父进程的 XLog。
 *
 * ```
 * var ilog: ILog = DefaultLog          // 兜底：logcat
 * // 父进程 AIDL 赋值后：
 * ilog = BinderLog { l, t, m -> remote.log(l, t, m) }
 * ```
 */
object DefaultLog : ILog {
    override fun log(level: Int, tag: String, msg: String) {
        Log.println(level, tag, msg)
    }
}

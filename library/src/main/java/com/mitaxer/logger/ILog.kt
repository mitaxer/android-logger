package com.mitaxer.logger

/**
 * 日志输出接口（进程内抽象）
 *
 * 覆盖 Android 规定的全部日志级别：VERBOSE / DEBUG / INFO / WARN / ERROR / ASSERT，
 * 由 [XLog] 自身实现（写文件）；具体项目如需跨进程回传日志，可自行定义 AIDL
 * 接口并在此提供实现，注册给 [XLog.attachSink]。
 *
 * 实现约定：实现内不得再次回调 [XLog] 的写方法，避免递归。
 */
interface ILog {
    /** 输出一条日志 */
    fun log(level: Int, tag: String, msg: String)
}

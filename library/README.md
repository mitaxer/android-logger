# XLog — Android Native 日志库

基于 MMAP 的 Android 日志引擎，纯 C++ 实现、零外部依赖、高性能、崩溃安全。

## 特性

- **MMAP 持久化** — 日志先写入内存映射文件，崩溃后重启自动恢复
- **异步刷盘** — 调用线程只做内存拷贝（μs 级），C++ 后台线程每 2 秒定时写入 .xlog
- **按天 + 按大小自动分片** — 每天一个文件，超 50MB 自动分割（可配置）
- **自动清理** — 默认保留 7 天，过期文件自动删除
- **双级别独立控制** — 文件输出和 logcat 输出可分别设置级别
- **大日志分条** — 超长内容（超过 bodySize）自动拆成多条写入，不截断
- **零依赖** — 仅依赖 Android NDK + log 库，无第三方
- **纯文本** — 不加密、不压缩，.xlog 文件可直接 cat 查看

## 集成

### Gradle

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}

// module build.gradle.kts
dependencies {
    implementation("com.github.mitaxer:android-logger:1.0.0")
}
```

### 源码集成

将 `library/` 作为 module 引入：

```kotlin
// settings.gradle.kts
include(":logger")
project(":logger").projectDir = File("../android-logger/library")

// module build.gradle.kts
implementation(project(":logger"))
```

## 快速开始

```kotlin
// Application.onCreate
XLog.init(this)

// 任意位置 — 签名与 android.util.Log 一致，超长内容自动分条
XLog.v("TAG", "verbose")
XLog.d("TAG", "debug")
XLog.i("TAG", "info")
XLog.e("TAG", "error: ${e.message}")
XLog.i("API", responseBody)   // 长响应体自动分多条，无需专用方法
```

## 多进程日志回传（子进程不初始化）

场景：进程 A 已初始化 XLog，进程 B 是 A 创建的独立进程。B 不想再初始化一轮 XLog
（单独初始化会把日志写到 B 自己的文件，与 A 不在一起），而是把日志回传给 A 落盘。

思路：**B 持有一个回调引用（AIDL 接口，项目自定义），A 实现该回调（内部调 XLog）
并通过 AIDL 传给 B；B 打日志时把 level/tag/content 三个参数回调给 A，由 A 的 XLog 落盘**。

```kotlin
// ① 项目内定义 AIDL 回调接口（注意 log 必须 oneway，避免 B 调用线程阻塞）
//    IXLogCallback.aidl:
//    interface IXLogCallback {
//        oneway void log(int level, String tag, String content);
//    }

// ② 父进程 A（已 init）：实现回调，内部调 XLog 写入自身文件
val callback = object : IXLogCallback.Stub() {
    override fun log(level: Int, tag: String, content: String) {
        XLog.log(level, tag, content)   // XLog 已初始化 → 写 A 的文件（超长自动分条）
    }
}
// 通过 Intent extra 或 bindService 把 callback（binder）传给进程 B

// ③ 子进程 B：持有回调引用，直接回调 level/tag/content
var callback: IXLogCallback? = null   // A 通过 AIDL 赋值
// 打日志：
callback?.log(XLog.LEVEL_INFO, "TAG", "content")
```

B 无需 init；未赋值时判空跳过。如需 logcat 兜底（不判空、不静默丢），可自行包一层
`DefaultLog`（库提供，输出到 logcat）。

## 初始化参数

```kotlin
XLog.init(
    context,                     // Application context
    namePrefix  = "APP",         // 日志文件名前缀
    fileLevel   = VERBOSE,       // 文件输出级别（低于此不写文件）
    consoleLevel= SILENT,        // logcat 输出级别（SILENT=关闭）
    bodySize    = 14 * 1024,     // 单条格式化缓冲上限（自动 clamp 256~64KB）
    mmapSize    = 150 * 1024,    // MMAP 缓存大小（自动 clamp 64KB~1MB）
    maxFileSize = 50 * 1024 * 1024  // 单文件分割大小，0=不分割
)
```

所有参数都有默认值，仅传 `context` 也能工作。

### 级别对照

| 常量 | 值 | 说明 |
|------|----|------|
| `SILENT` | 1 | 关闭输出（用于 consoleLevel 关 logcat） |
| `VERBOSE` | 2 | 全量输出 |
| `DEBUG` | 3 | debug 及以上 |
| `INFO` | 4 | info 及以上 |
| `WARNING` | 5 | warning + error |
| `ERROR` | 6 | 仅 error |

### 典型配置场景

```kotlin
// 发布版：文件记录 WARNING+，logcat 关闭
XLog.init(this, "WZCRM",
    fileLevel = XLog.LEVEL_WARNING,
    consoleLevel = XLog.LEVEL_SILENT
)

// 调试版：文件全量，logcat 开 DEBUG+
XLog.init(this, "WZCRM_DEBUG",
    fileLevel = XLog.LEVEL_VERBOSE,
    consoleLevel = XLog.LEVEL_DEBUG
)
```

## 日志格式

```
2026-07-27 12:05:58.444  13693  13693  I  TAG  message
├───────────────────────┘  ├───┘  ├───┘  ├  ├───┘
        时间 (含 ms)       PID    TID   级别 TAG

主线程上 PID == TID，子线程 TID 不同，可据此区分线程。
```

## 日志文件

```
${filesDir}/log/
├── APP_20260726.xlog          ← 当天日志
├── APP_20260726_1.xlog        ← 超过 50MB 后的第 2 片
├── APP_20260726_2.xlog        ← 超过 50MB 后的第 3 片
├── APP_20260725.xlog          ← 前一天（7 天后自动清理）
└── ...
```

文件纯文本，`adb shell cat /path/to/file.xlog` 可直接查看。

## 架构

```
[调用线程]
  XLog.i() → nativeWrite()
    → 格式化（时间/PID/TID/级别/TAG → 定长缓冲）
    → lock → memcpy 写入 MMAP → unlock

         ║  （后台线程切换）
         ║

[flush 线程 — 2s 定时器]
  async_flush_loop()
    → lock → flush_all()
      → 遍历 MMAP 条目
      → ::write(fd, MMAP地址, body_len)   ← 直接从 MMAP 写文件
    → unlock
```

### 崩溃恢复

```
启动 → fix() 扫描 MMAP 条目 → flush_all() 写出 → 日志不丢
       ↑ 只恢复 fix() 找到的有效条目，部分写入的条目自动截断
```

### 三级写入（超限处理）

```
正常:   MMAP 有空间 → 写入 MMAP（有崩溃保护）
缓冲满: MMAP 末尾不足 → 强制刷盘 → 从头写
配置不当: bodySize > mmap 可用空间 → 直写 .xlog + logcat 警告含建议值
```

## 参数范围说明

| 参数 | 最小值 | 最大值 | 默认值 |
|------|--------|--------|--------|
| `bodySize` | 256 | 65,536 | 14,336 |
| `mmapSize` | 65,536 | 1,048,576 | 153,600 |
| `maxFileSize` | 0（不分割） | 无上限 | 52,428,800（50MB） |

超出范围的值自动 clamp，不会崩溃。`mmapSize` 建议至少比 `bodySize` 大 6 字节 + 8 字节 header 余量。

## 启动日志

每次 init 会通过 logcat 输出完整配置，便于确认实际生效参数：

```
I/XLog: [XLog] === init complete ===
I/XLog: [XLog]   logDir:      /data/data/.../log
I/XLog: [XLog]   cacheDir:    /data/data/.../cache/xlog
I/XLog: [XLog]   namePrefix:  WZCRM
I/XLog: [XLog]   fileLevel:   2 (VERBOSE)
I/XLog: [XLog]   consoleLevel:1 (SILENT)
I/XLog: [XLog]   bodySize:    14336 (range 256~65536, clamp)
I/XLog: [XLog]   mmapSize:    153600 (range 65536~1048576, clamp)
I/XLog: [XLog]   maxFileSize: 52428800 (0=不分割)
I/XLog: [XLog]   maxAliveSec: 604800 (7.0 天)
I/XLog: [XLog]   mmapPos:     8
I/XLog: [XLog] ===================
```

## 方法参考

| 方法 | 说明 |
|------|------|
| `init(context, ...)` | 初始化，应在 Application.onCreate 中调用一次 |
| `v/d/i/w/e/a(tag, msg)` | 日志输出（覆盖全部级别），`msg` 为 null 时不写入；超长内容自动分多条 |
| `flush()` | 强制刷盘 |
| `close()` | 关闭日志引擎（应用退出时可选） |

## 已知限制

- **无加密** — 日志文件为纯文本，敏感信息请自行脱敏后再写入
- **mmapSize 耗尽降级** — 配置不当时条目直写 .xlog 但无崩溃保护，详情看启动日志/警告
- **不跨平台** — 依赖 Android NDK + `__android_log_print`，只在 Android 可用

## 模块

| 目录 | 说明 |
|------|------|
| `library/` | 日志库核心（C++ + Kotlin） |
| `app/` | Demo 测试应用 |

## License

MIT

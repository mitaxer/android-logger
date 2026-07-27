# android-logger

基于 MMAP 的 Android Native 日志库，轻量、高性能、不加密、崩溃安全。

## 特性

- **MMAP 持久化** — 进程崩溃不丢日志
- **异步写入** — 调用端微秒级返回
- **自动分片** — 按天 + 按大小自动切割
- **自动清理** — 过期日志自动删除
- **零依赖** — 仅依赖 Android API，无第三方库
- **不加密** — 纯文本可读，无需解密工具
- **双级别控制** — 文件与 logcat 输出级别独立配置

## 详细文档

见 [`library/README.md`](library/README.md)。

## 快速开始

```kotlin
// Application.onCreate
XLog.init(this)

// 任意位置
XLog.i("TAG", "message")
XLog.e("TAG", "error: ${e.message}")

// 长日志（分多条完整写入）
XLog.full("API", responseBody)
```

## 引入

```kotlin
dependencies {
    implementation("com.github.mitaxer:android-logger:1.0.0")
}
```

或在 `settings.gradle.kts` 中引用本地 module。

## 模块

- `:library` — 日志库核心（C++ + Kotlin）↗ [文档](library/README.md)
- `:app` — Demo 测试应用

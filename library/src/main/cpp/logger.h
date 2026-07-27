#ifndef ANDROID_LOGGER_H
#define ANDROID_LOGGER_H

#include <cstdint>
#include <string>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <atomic>

namespace mitaxer {

// 清理过期日志文件
void clean_expired_files(const std::string& log_dir,
                         const std::string& prefix,
                         time_t max_alive);

// 魔数标记（用于崩溃恢复）
constexpr unsigned char kMagicSyncNoCrypt    = 0x08;
constexpr unsigned char kMagicAsyncNoCrypt   = 0x09;
constexpr unsigned char kMagicEnd            = 0x00;

// 日志级别
enum LogLevel : int32_t {
    LEVEL_SILENT  = 1,  // 关闭输出
    LEVEL_VERBOSE = 2,
    LEVEL_DEBUG   = 3,
    LEVEL_INFO    = 4,
    LEVEL_WARNING = 5,
    LEVEL_ERROR   = 6,
};

// 格式缓冲上下限
constexpr int32_t kMinBodySize = 256;
constexpr int32_t kMaxBodySize = 64 * 1024;
constexpr int32_t kDefaultBodySize = 14 * 1024;

// MMAP 大小上下限
constexpr int32_t kMinMmapSize = 64 * 1024;
constexpr int32_t kMaxMmapSize = 1024 * 1024;
constexpr int32_t kDefaultMmapSize = 150 * 1024;

// 日志配置
struct LoggerConfig {
    std::string log_dir;          // 日志文件目录
    std::string cache_dir;        // 缓存目录（MMAP 存放位置）
    std::string name_prefix;      // 文件名前缀
    int32_t max_file_size = 50 * 1024 * 1024;    // 单文件最大字节，0=不分片
    int64_t max_alive_sec = 7 * 24 * 3600; // 文件过期秒数
    LogLevel file_level = LEVEL_VERBOSE;    // 文件输出级别
    LogLevel console_level = LEVEL_SILENT;  // logcat 输出级别，默认关闭
    int32_t body_size = kDefaultBodySize;   // 格式缓冲大小，自动 clamp
    int32_t mmap_size = kDefaultMmapSize;   // MMAP 大小，自动 clamp
};

// 日志器
class Logger {
public:
    Logger();
    ~Logger();

    // 初始化
    bool init(const LoggerConfig& config);
    // 写入日志
    void write(LogLevel level, const char* tag, const char* msg);
    // 强制刷盘
    void flush();
    // 关闭
    void close();

private:
    void async_flush_loop();
    // MMAP 恢复：扫描有效日志，找到正确写入位置
    size_t fix();
    // 将 MMAP 所有条目解码写入文件
    void flush_all();
    // 写入纯文本到日志文件
    void write_text_to_file(const char* text, size_t len);

    LoggerConfig config_;
    void* mmap_addr_ = nullptr;       // MMAP 基址
    size_t mmap_size_ = 0;            // MMAP 缓冲大小（初始化时从 config 取）
    size_t mmap_pos_ = 0;             // 当前写入位置

    int fd_ = -1;                     // 当前日志文件描述符
    std::string current_date_;        // 当前日期 (YYYYMMDD)
    int current_index_ = 0;           // 当前分片索引

    std::mutex mutex_;
    LogLevel console_level_ = LEVEL_SILENT;
    std::mutex flush_mutex_;
    std::condition_variable flush_cv_;
    std::thread* flush_thread_ = nullptr;
    std::atomic<bool> running_{false};
    std::atomic<bool> need_flush_{false};
};

} // namespace mitaxer

#endif // ANDROID_LOGGER_H

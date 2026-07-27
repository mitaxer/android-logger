#include "logger.h"
#include <cstring>
#include <ctime>
#include <cstdio>
#include <memory>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <fcntl.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "NativeLogger"
#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace mitaxer {

Logger::Logger() = default;
Logger::~Logger() { close(); }

// ── MMAP 恢复 ─────────────────────────────────────────

size_t Logger::fix() {
    size_t header = sizeof(size_t);
    if (mmap_size_ <= header) return header;
    char* buf = static_cast<char*>(mmap_addr_);
    size_t pos = header;
    while (pos + 6 < mmap_size_) {
        unsigned char magic = static_cast<unsigned char>(buf[pos]);
        if (magic != kMagicSyncNoCrypt && magic != kMagicAsyncNoCrypt) break;
        uint32_t body_len;
        memcpy(&body_len, buf + pos + 1, sizeof(body_len));
        size_t entry_size = 1 + 4 + body_len + 1;
        if (body_len == 0 || body_len > (uint32_t)config_.body_size) break;
        if (pos + entry_size > mmap_size_) break;
        if (buf[pos + entry_size - 1] != kMagicEnd) break;
        pos += entry_size;
    }
    return pos;
}

// ── 初始化 ───────────────────────────────────────────

bool Logger::init(const LoggerConfig& config) {
    // clamp 配置值
    config_ = config;
    config_.body_size = std::max(kMinBodySize, std::min(kMaxBodySize, config_.body_size));
    config_.mmap_size = std::max(kMinMmapSize, std::min(kMaxMmapSize, config_.mmap_size));
    mmap_size_ = config_.mmap_size;

    mkdir(config_.log_dir.c_str(), 0755);
    mkdir(config_.cache_dir.c_str(), 0755);

    std::string mmap_path = config_.cache_dir + "/logger.mmap";
    int mmap_fd = open(mmap_path.c_str(), O_RDWR | O_CREAT, 0644);
    if (mmap_fd < 0) { ALOGE("Failed to open mmap"); return false; }
    if (ftruncate(mmap_fd, static_cast<off_t>(mmap_size_)) < 0) {
        ::close(mmap_fd); ALOGE("Failed to truncate"); return false;
    }
    mmap_addr_ = mmap(nullptr, mmap_size_, PROT_READ | PROT_WRITE, MAP_SHARED, mmap_fd, 0);
    ::close(mmap_fd);
    if (mmap_addr_ == MAP_FAILED) { mmap_addr_ = nullptr; ALOGE("Failed to mmap"); return false; }

    console_level_ = config_.console_level;

    mmap_pos_ = fix();

    // 立即刷出崩溃残留的日志
    flush_all();

    // 输出启动配置（实际 clamp 后的值）
    __android_log_print(ANDROID_LOG_INFO, "XLog",
        "[XLog] === init complete ===");
    __android_log_print(ANDROID_LOG_INFO, "XLog",
        "[XLog]   logDir:      %s", config_.log_dir.c_str());
    __android_log_print(ANDROID_LOG_INFO, "XLog",
        "[XLog]   cacheDir:    %s", config_.cache_dir.c_str());
    __android_log_print(ANDROID_LOG_INFO, "XLog",
        "[XLog]   namePrefix:  %s", config_.name_prefix.c_str());
    __android_log_print(ANDROID_LOG_INFO, "XLog",
        "[XLog]   fileLevel:   %d (%s)", config_.file_level,
        config_.file_level == LEVEL_VERBOSE ? "VERBOSE" :
        config_.file_level == LEVEL_DEBUG   ? "DEBUG" :
        config_.file_level == LEVEL_INFO    ? "INFO" :
        config_.file_level == LEVEL_WARNING ? "WARNING" :
        config_.file_level == LEVEL_ERROR   ? "ERROR" :
        config_.file_level == LEVEL_SILENT  ? "SILENT" : "UNKNOWN");
    __android_log_print(ANDROID_LOG_INFO, "XLog",
        "[XLog]   consoleLevel:%d (%s)", config_.console_level,
        config_.console_level == LEVEL_VERBOSE ? "VERBOSE" :
        config_.console_level == LEVEL_DEBUG   ? "DEBUG" :
        config_.console_level == LEVEL_INFO    ? "INFO" :
        config_.console_level == LEVEL_WARNING ? "WARNING" :
        config_.console_level == LEVEL_ERROR   ? "ERROR" :
        config_.console_level == LEVEL_SILENT  ? "SILENT" : "UNKNOWN");
    __android_log_print(ANDROID_LOG_INFO, "XLog",
        "[XLog]   bodySize:    %d (range %d~%d, clamp)", config_.body_size, kMinBodySize, kMaxBodySize);
    __android_log_print(ANDROID_LOG_INFO, "XLog",
        "[XLog]   mmapSize:    %d (range %d~%d, clamp)", config_.mmap_size, kMinMmapSize, kMaxMmapSize);
    __android_log_print(ANDROID_LOG_INFO, "XLog",
        "[XLog]   maxFileSize: %d (0=不分割)", config_.max_file_size);
    __android_log_print(ANDROID_LOG_INFO, "XLog",
        "[XLog]   maxAliveSec: %lld (%.1f 天)", (long long)config_.max_alive_sec,
        (double)config_.max_alive_sec / 86400.0);
    __android_log_print(ANDROID_LOG_INFO, "XLog",
        "[XLog]   mmapPos:     %zu", mmap_pos_);
    __android_log_print(ANDROID_LOG_INFO, "XLog",
        "[XLog] ===================");

    running_ = true;
    flush_thread_ = new std::thread(&Logger::async_flush_loop, this);
    return true;
}

// ── 写入（带魔数） ───────────────────────────────────

void Logger::write(LogLevel level, const char* tag, const char* msg) {
    if (!mmap_addr_ || !running_) return;

    // 输出到 logcat
    if (level >= console_level_ && console_level_ != LEVEL_SILENT) {
        int android_level;
        switch (level) {
            case LEVEL_VERBOSE: android_level = ANDROID_LOG_VERBOSE; break;
            case LEVEL_DEBUG:   android_level = ANDROID_LOG_DEBUG;   break;
            case LEVEL_INFO:    android_level = ANDROID_LOG_INFO;    break;
            case LEVEL_WARNING: android_level = ANDROID_LOG_WARN;    break;
            case LEVEL_ERROR:   android_level = ANDROID_LOG_ERROR;   break;
            default:            android_level = ANDROID_LOG_INFO;    break;
        }
        __android_log_print(android_level, tag, "%s", msg ? msg : "");
    }

    // 文件输出级别过滤
    if (level < config_.file_level) return;

    static thread_local std::unique_ptr<char[]> body;
    static thread_local int body_capacity = 0;

    // 首次调用或配置变化时分配固定大小的缓冲
    if (!body || body_capacity != config_.body_size) {
        body = std::make_unique<char[]>(config_.body_size + 1);
        body_capacity = config_.body_size;
    }

    struct timeval tv;
    gettimeofday(&tv, nullptr);
    time_t now = tv.tv_sec;
    struct tm tm_buf;
    localtime_r(&now, &tm_buf);
    int ms = tv.tv_usec / 1000;

    pid_t pid = getpid();
    pid_t tid = static_cast<pid_t>(syscall(SYS_gettid));

    static const char* level_str[] = {"", "", "V", "D", "I", "W", "E"};
    const char* lv = (level >= LEVEL_VERBOSE && level <= LEVEL_ERROR) ? level_str[level] : "U";

    int body_len = snprintf(body.get(), config_.body_size + 1,
        "%04d-%02d-%02d %02d:%02d:%02d.%03d  %d  %d  %s  %s  %s\n",
        tm_buf.tm_year + 1900, tm_buf.tm_mon + 1, tm_buf.tm_mday,
        tm_buf.tm_hour, tm_buf.tm_min, tm_buf.tm_sec, ms,
        pid, tid,
        lv, tag, msg ? msg : "");
    if (body_len <= 0 || body_len >= config_.body_size) return;

    uint32_t len32 = (uint32_t)body_len;
    size_t entry_size = 1 + 4 + len32 + 1;

    std::lock_guard<std::mutex> lock(mutex_);
    size_t header = sizeof(size_t);
    size_t write_pos = header + (mmap_pos_ - header) % (mmap_size_ - header);
    char* buf = static_cast<char*>(mmap_addr_);

    // 末尾放不下 → 封口 + 刷 MMAP + 从头开始
    if (write_pos + entry_size > mmap_size_) {
        buf[write_pos] = kMagicEnd;
        flush_all();
        write_pos = header;
    }

    // 刷完后还放不下（body_size 大于 MMAP 可用空间）→ 直写 .xlog，不经过 MMAP
    // 这种情况说明 bodySize 和 mmapSize 配置不匹配，日志中会给出警告
    if (write_pos + entry_size > mmap_size_) {
        int suggested_mmap = body_len + 6 + 8 + 4096;  // body + overhead + 4KB 余量
        int suggested_body = (mmap_size_ - header - 6) * 3 / 4;  // 可用空间的 75%
        __android_log_print(ANDROID_LOG_WARN, "XLog",
            "[XLog] body_size=%d 单条超出 MMAP 可用空间 %zu，该条已直写 .xlog。"
            "建议: 增大 mmapSize 至 >=%d，或减小 bodySize 至 <=%d。"
            "（当前 bodySize=%d, mmapSize=%d）",
            body_len, mmap_size_ - header,
            suggested_mmap, suggested_body,
            config_.body_size, (int)mmap_size_);
        write_text_to_file(body.get(), body_len);
        return;
    }

    buf[write_pos++] = kMagicAsyncNoCrypt;
    memcpy(buf + write_pos, &len32, sizeof(len32));
    write_pos += sizeof(len32);
    memcpy(buf + write_pos, body.get(), body_len);
    write_pos += body_len;
    buf[write_pos++] = kMagicEnd;

    mmap_pos_ = write_pos;
    *static_cast<size_t*>(mmap_addr_) = mmap_pos_;

    size_t used = mmap_pos_ - header;
    if (used > mmap_size_ / 3) { need_flush_.store(true); flush_cv_.notify_one(); }
}

// ── 将 MMAP 条目解码为纯文本写入文件 ────────────────

void Logger::flush_all() {
    if (!mmap_addr_) return;
    size_t header = sizeof(size_t);
    size_t end = mmap_pos_;
    if (end <= header) return;
    char* buf = static_cast<char*>(mmap_addr_) + header;
    size_t pos = 0;
    size_t available = end - header;
    while (pos + 6 < available) {
        unsigned char magic = (unsigned char)buf[pos];
        if (magic != kMagicSyncNoCrypt && magic != kMagicAsyncNoCrypt) break;
        uint32_t body_len;
        memcpy(&body_len, buf + pos + 1, sizeof(body_len));
        if (body_len == 0 || body_len > (uint32_t)config_.body_size) break;
        size_t entry_size = 1 + 4 + body_len + 1;
        if (pos + entry_size > available) break;
        if (buf[pos + entry_size - 1] != kMagicEnd) break;
        write_text_to_file(buf + pos + 5, body_len);
        pos += entry_size;
    }
    mmap_pos_ = header;
    *static_cast<size_t*>(mmap_addr_) = mmap_pos_;
    // 清掉第一个字节的魔数，fix() 下次不会误读已刷过的条目
    static_cast<char*>(mmap_addr_)[header] = kMagicEnd;
}

// ── 异步刷盘 ─────────────────────────────────────────

void Logger::async_flush_loop() {
    std::unique_lock<std::mutex> lock(flush_mutex_);
    while (running_) {
        flush_cv_.wait_for(lock, std::chrono::seconds(2));

        std::lock_guard<std::mutex> buf_lock(mutex_);
        // 无论是否有 notify，只要 MMAP 里有数据就刷
        if (mmap_pos_ > sizeof(size_t)) {
            flush_all();
        }
    }
}

void Logger::flush() {
    need_flush_.store(true);
    flush_cv_.notify_one();
}

void Logger::close() {
    running_.store(false);
    need_flush_.store(true);
    flush_cv_.notify_one();
    if (flush_thread_ && flush_thread_->joinable()) {
        flush_thread_->join();
        delete flush_thread_;
        flush_thread_ = nullptr;
    }
    flush_all();
    if (mmap_addr_) { munmap(mmap_addr_, mmap_size_); mmap_addr_ = nullptr; }
    if (fd_ >= 0) { ::close(fd_); fd_ = -1; }
}

// ── 纯文本写入文件 ──────────────────────────────────

void Logger::write_text_to_file(const char* text, size_t len) {
    time_t now = time(nullptr);
    struct tm tm_buf;
    localtime_r(&now, &tm_buf);
    char date_str[16];
    snprintf(date_str, sizeof(date_str), "%04d%02d%02d",
             tm_buf.tm_year + 1900, tm_buf.tm_mon + 1, tm_buf.tm_mday);

    if (current_date_ != date_str) {
        if (fd_ >= 0) ::close(fd_);
        current_date_ = date_str;
        current_index_ = 0;
        clean_expired_files(config_.log_dir, config_.name_prefix, config_.max_alive_sec);
        std::string path = config_.log_dir + "/" + config_.name_prefix + "_" + date_str + ".xlog";
        fd_ = open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644);
        if (fd_ < 0) return;
    }
    if (fd_ < 0) {
        std::string path = config_.log_dir + "/" + config_.name_prefix + "_" + date_str + ".xlog";
        fd_ = open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644);
        if (fd_ < 0) return;
    }
    if (config_.max_file_size > 0) {
        struct stat st;
        if (fstat(fd_, &st) == 0 && st.st_size + (off_t)len > config_.max_file_size) {
            ::close(fd_);
            current_index_++;
            std::string path = config_.log_dir + "/" + config_.name_prefix + "_"
                               + current_date_ + "_" + std::to_string(current_index_) + ".xlog";
            fd_ = open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644);
            if (fd_ < 0) return;
        }
    }
    ::write(fd_, text, len);
}

} // namespace mitaxer

#include "logger.h"
#include <cstdio>
#include <string>
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "FileCleaner"

namespace mitaxer {

/**
 * 删除过期日志文件
 *
 * @param log_dir   日志目录
 * @param prefix    文件名前缀
 * @param max_alive 存活秒数
 */
void clean_expired_files(const std::string& log_dir,
                         const std::string& prefix,
                         time_t max_alive) {
    DIR* dir = opendir(log_dir.c_str());
    if (!dir) return;

    time_t now = time(nullptr);

    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        std::string name(entry->d_name);

        // 只处理 .xlog 文件
        if (name.size() < 5 ||
            name.substr(name.size() - 5) != ".xlog") {
            continue;
        }

        // 跳过前缀不匹配的文件
        if (name.compare(0, prefix.size(), prefix) != 0) {
            continue;
        }

        std::string full_path = log_dir + "/" + name;
        struct stat st;
        if (stat(full_path.c_str(), &st) != 0) continue;

        if (now - st.st_mtime > max_alive) {
            unlink(full_path.c_str());
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG,
                               "Deleted expired log: %s", full_path.c_str());
        }
    }

    closedir(dir);
}

} // namespace mitaxer

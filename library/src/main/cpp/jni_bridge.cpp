#include <jni.h>
#include <string>
#include "logger.h"

static mitaxer::Logger g_logger;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mitaxer_logger_XLog_nativeInit(
        JNIEnv* env,
        jclass /*thiz*/,
        jstring log_dir,
        jstring cache_dir,
        jstring name_prefix,
        jint file_level,
        jint console_level,
        jint body_size,
        jint mmap_size,
        jint max_file_size) {

    const char* dir = env->GetStringUTFChars(log_dir, nullptr);
    const char* cache = env->GetStringUTFChars(cache_dir, nullptr);
    const char* prefix = env->GetStringUTFChars(name_prefix, nullptr);

    mitaxer::LoggerConfig config;
    config.log_dir = dir;
    config.cache_dir = cache;
    config.name_prefix = prefix;
    config.file_level = static_cast<mitaxer::LogLevel>(file_level);
    config.console_level = static_cast<mitaxer::LogLevel>(console_level);
    config.body_size = body_size;
    config.mmap_size = mmap_size;
    config.max_file_size = max_file_size;

    bool result = g_logger.init(config);

    env->ReleaseStringUTFChars(log_dir, dir);
    env->ReleaseStringUTFChars(cache_dir, cache);
    env->ReleaseStringUTFChars(name_prefix, prefix);

    return result ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mitaxer_logger_XLog_nativeWrite(
        JNIEnv* env,
        jclass /*thiz*/,
        jint level,
        jstring tag,
        jstring msg) {

    const char* tag_str = env->GetStringUTFChars(tag, nullptr);
    const char* msg_str = env->GetStringUTFChars(msg, nullptr);

    g_logger.write(static_cast<mitaxer::LogLevel>(level), tag_str, msg_str);

    env->ReleaseStringUTFChars(tag, tag_str);
    env->ReleaseStringUTFChars(msg, msg_str);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mitaxer_logger_XLog_nativeFlush(
        JNIEnv* /*env*/,
        jclass /*thiz*/) {
    g_logger.flush();
}

extern "C" JNIEXPORT void JNICALL
Java_com_mitaxer_logger_XLog_nativeClose(
        JNIEnv* /*env*/,
        jclass /*thiz*/) {
    g_logger.close();
}

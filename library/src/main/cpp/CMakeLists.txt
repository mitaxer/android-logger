cmake_minimum_required(VERSION 3.22.1)
project("android-logger")

add_library(
    android-logger
    SHARED
    logger.h
    logger.cpp
    file_writer.cpp
    jni_bridge.cpp
)

target_include_directories(android-logger PRIVATE .)

find_library(log-lib log)
target_link_libraries(
    android-logger
    ${log-lib}
    android
)

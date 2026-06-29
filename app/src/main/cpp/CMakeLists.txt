cmake_minimum_required(VERSION 3.22.1)
project(whisper-jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# whisper.cpp source is vendored locally by scripts/fetch-native.sh (not committed to git).
# We add_subdirectory it so the native build needs no network access.
set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp")
if(NOT EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "whisper.cpp source not found at ${WHISPER_DIR}. Run: bash scripts/fetch-native.sh")
endif()

set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)   # NDK has no system OpenMP by default

# CRITICAL: always build the native engine optimized, even in a debug APK. ggml/whisper are
# ~10x slower when compiled at -O0 (the default for the debug variant), which made Whisper take
# tens of seconds. Force -O3 + NDEBUG for everything compiled below (whisper, ggml, our shim).
add_compile_options(-O3 -DNDEBUG)

add_subdirectory(${WHISPER_DIR} ${CMAKE_BINARY_DIR}/whisper_cpp_build)

# Our thin JNI shim.
add_library(whisper-jni SHARED whisper_jni.cpp)

target_link_libraries(whisper-jni
    whisper
    android
    log
)

# On-device voice-message transcription (whisper.cpp). The whisper.cpp sources are
# fetched by the CI workflow into ./whisper.cpp (pinned tag) before the build.
cmake_minimum_required(VERSION 3.22.1)
project(rwhisper C CXX)

set(CMAKE_CXX_STANDARD 17)
# the app is shipped as a "debug" build: keep the speech engine fully optimized anyway
set(CMAKE_BUILD_TYPE Release)
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # every recent Galaxy phone has fp16 + dot-product instructions: much faster matrix math
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif()

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp whisper EXCLUDE_FROM_ALL)

add_library(rwhisper SHARED rwhisper.cpp)
target_compile_options(rwhisper PRIVATE -O3)
target_link_libraries(rwhisper PRIVATE whisper log)

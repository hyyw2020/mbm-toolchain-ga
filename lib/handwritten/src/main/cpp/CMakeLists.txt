cmake_minimum_required(VERSION 3.22.1)
project(hccr C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ncnn 预编译(各 ABI)已随模块提供于 cpp/ncnn/<abi>/
set(NCNN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${ANDROID_ABI})

# ANDROID_ABI -> NDK libomp 架构目录名
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(OMP_ARCH aarch64)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(OMP_ARCH arm)
elseif(ANDROID_ABI STREQUAL "x86")
    set(OMP_ARCH i386)
else()
    set(OMP_ARCH x86_64)
endif()
# NDK 内置 clang 的版本目录名随 NDK 版本变化（r28=clang/19、r30=clang/21），
# 原先硬编码 clang/19 在升级 NDK 后会指向不存在的路径。这里改为按架构直接 glob
# 真实存在的 libomp.a，完全不依赖 clang 版本号，后续再升 NDK 也不会失效。
set(_NDK_LLVM_DIR "${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64")
file(GLOB _LIBOMP_CANDIDATES "${_NDK_LLVM_DIR}/lib/clang/*/lib/linux/${OMP_ARCH}/libomp.a")
if(NOT _LIBOMP_CANDIDATES)
    message(FATAL_ERROR
        "libomp.a not found for arch=${OMP_ARCH} under ${_NDK_LLVM_DIR}/lib/clang/*")
endif()
list(SORT _LIBOMP_CANDIDATES)
list(REVERSE _LIBOMP_CANDIDATES)   # 取版本号最大的 clang 目录
list(GET _LIBOMP_CANDIDATES 0 LIBOMP)
message(STATUS "hccr: OpenMP runtime -> ${LIBOMP}")

add_library(hccr SHARED hccr_jni.cc preprocess.c)

target_include_directories(hccr PRIVATE
    ${NCNN_DIR}/include/ncnn
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# 超高精度：用满核心；-O3 保证推理吞吐
target_compile_options(hccr PRIVATE -fopenmp -O3)

# OpenMP 运行时通过 --whole-archive 静态链入 libhccr.so（推理并行用满核心）。
# 注意：链接期 clang 驱动会因 -fopenmp 自动追加共享库 -lomp，使 libhccr.so 带
# NEEDED: libomp.so。该 .so 不会被 Android Gradle 默认打包，会导致 System.loadLibrary("hccr")
# 在 dlopen 阶段因找不到 libomp.so 而失败（离线手写永远加载失败）。
# 已在 src/main/jniLibs/<abi>/libomp.so 随本模块打包，动态链接器据此满足 NEEDED，
# 运行期实际 OpenMP 运行时来自 libhccr.so 内静态链接的副本（单一运行时，无双初始化）。
target_link_libraries(hccr
    ${NCNN_DIR}/lib/libncnn.a
    -Wl,--whole-archive ${LIBOMP} -Wl,--no-whole-archive
    log
    android
)

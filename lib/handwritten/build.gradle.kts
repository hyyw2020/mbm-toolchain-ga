// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors

plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
}

android {
    namespace = "org.fcitx.fcitx5.android.lib.handwritten"

    // 沿用工程统一 NDK / CMake 版本
    ndkVersion = project.ndkVersion

    defaultConfig {
        // MBM 支持的全部 ABI
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
                cppFlags("-fopenmp", "-O3", "-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            version = project.cmakeVersion
            path("src/main/cpp/CMakeLists.txt")
        }
    }

    // 注意：不排除 *.so，libhccr.so 需随 AAR/APK 打包
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

open class NativeBaseConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val prebuiltDir = target.rootProject.projectDir.resolve("lib/fcitx5/src/main/cpp/prebuilt")
        val isBuildingBundle = target.rootProject.gradle.startParameter.taskNames.any {
            it.startsWith("${target.path}:bundle")
        }
        target.extensions.configure<CommonExtension> {
            ndkVersion = target.ndkVersion
            defaultConfig.apply {
                minSdk = Versions.minSdk
                @Suppress("UnstableApiUsage")
                externalNativeBuild {
                    cmake {
                        arguments(
                            "-DANDROID_STL=c++_shared",
                            "-DVERSION_NAME=${Versions.baseVersionName}",
                            "-DPREBUILT_DIR=${prebuiltDir.absolutePath}",
                            // P0-1（MBM v1.0.26）：16 KB 页对齐。
                            // NDK r28 起默认即按 16 KB 对齐生成；本工程用的正是 r28，
                            // 这两个 flag 属于「显式声明」——一旦有人把 NDK 降级回 r27/r26
                            // （老项目切分支常见），构建不会静默退回 4 KB 对齐。
                            // 同时强制 common-page-size，避免 LLD 生成 .relro_padding
                            // 造成段落在 16 KB 设备上错位。
                            "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
                        )
                    }
                }
            }
            externalNativeBuild.apply {
                cmake {
                    version = target.cmakeVersion
                    path("src/main/cpp/CMakeLists.txt")
                }
            }
            // split apks should be disabled when building bundle
            // https://issuetracker.google.com/402800800
            if (!isBuildingBundle) {
                splits.abi {
                    isEnable = true
                    // MBM v1.0.33（打包正确性）：Universal 包只在「全 ABI 构建」时产出。
                    // buildABI override（如 arm64-v8a 单架构快速构建）下各 ABI 分包只剩
                    // 一个，此时再打 universal 会得到一个「只有单 ABI 却名为通用包」的
                    // 产物——装到别的架构设备上必缺 .so 崩溃，属误导性交付物。
                    isUniversalApk = target.buildAbiOverride == null
                    reset()
                    (target.buildAbiOverride?.split(",") ?: Versions.supportedABIs).forEach {
                        include(it)
                    }
                }
            }
        }
        registerCleanCxxTask(target)
    }

    private fun registerCleanCxxTask(project: Project) {
        project.tasks.register<Delete>("cleanCxxIntermediates") {
            delete(project.file(".cxx"))
        }.also {
            project.cleanTask.dependsOn(it)
        }
    }

}

// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors

plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    // Room 的注解处理走 KSP（ksp.useKSP2=true，见 gradle.properties）
    alias(libs.plugins.ksp)
}

// 与 NativeBaseConventionPlugin 的 splits 判据同源（Utils.epn 的语义：
// env BUILD_ABI 优先，回退 property buildABI）。
val buildAbiOverride = System.getenv("BUILD_ABI")?.takeIf { it.isNotBlank() }
    ?: runCatching { property("buildABI")!!.toString() }.getOrNull()

android {
    namespace = "org.fcitx.fcitx5.android.plugin.recall"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.recall"
    }

    // MBM v1.0.28：本插件体积主要来自 ONNX Runtime 的 .so（4 个 ABI 各一份，
    // 合计约 140MB），universal 包达 185MB，超出 Gitee release 附件 100MB 上限、
    // 也无法作为附件分发。这里启用 ABI 分包：额外产出 arm64-v8a 单架构包
    // （绝大多数机型适用，约 1/3 体积可正常分发），universal 包仍保留供模拟器 / x86。
    //
    // MBM v1.0.33（打包正确性）：本模块不应用 native-base 约定插件
    // （app-convention 不含它），此前在此硬编码 isUniversalApk = true，
    // 与 NativeBaseConventionPlugin 的判据不同步。现已对齐：
    // 仅在「全 ABI 构建」（未设 buildABI override）时产出 universal 包；
    // buildABI override（如 arm64-v8a 单架构快速构建）下 universal 是
    // 约 198MB 的死产物——体积超 Gitee release 100MB 附件上限本就无法
    // 随 release 分发，还会在发布流程中被误选。判据与约定插件同源
    // （env BUILD_ABI → property buildABI，见 build-logic Utils.epn）。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = buildAbiOverride == null
        }
    }

    buildFeatures {
        resValues = true
    }

    buildTypes {
        release {
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }
}

// MBM v1.0.31：Room AutoMigration（1→2 加 blob 列）必须导出 schema，
// 与 app 模块同款配置（ksp.useKSP2=true，见 gradle.properties）。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Recall 独立可选 APK（MBM v1.0.26 起从主输入法拆出）。
    // 主输入法（org.fcitx.fcitx5.android）不再声明任何 AccessibilityService；
    // 只有安装了本 APK 并显式开启无障碍的用户，才会有屏幕捕获能力。
    implementation(libs.kotlinx.coroutines)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.timber)
    // 本地加密存储（SQLCipher）+ 加密偏好（Keystore MasterKey）
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.security.crypto)
    // 「查看与填充」的生物识别门
    implementation(libs.androidx.biometric)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // Recall 偏好（本地 DataStore）
    implementation(libs.androidx.datastore.preferences)
    // 离线 OCR 兜底
    implementation(libs.onnxruntime.android)
    // 隐私规则回归测试（JVM 单测，不依赖 Android 运行时）
    testImplementation(libs.junit)
}

// MBM v1.0.28：Recall 是独立分发的 APK，同样必须随附开源协议说明。
// 与主程序共用仓库根目录的单一事实来源（LICENSE / NOTICE / 上游清单），构建即同步。
tasks.register<Copy>("syncLicenseAssets") {
    val dest = layout.projectDirectory.dir("src/main/assets/licenses")
    from(rootProject.file("LICENSE")) { rename { "AGPL-3.0.txt" } }
    from(rootProject.file("NOTICE")) { rename { "NOTICE.txt" } }
    from(rootProject.file("docs/upstream.md")) { rename { "UPSTREAM.txt" } }
    // MBM v1.0.32：隐私政策随 APK 分发，RecallActivity 内离线可查（不依赖外网）
    from(rootProject.file("PRIVACY.txt")) { rename { "PRIVACY.txt" } }
    into(dest)
    inputs.files(
        rootProject.file("LICENSE"),
        rootProject.file("NOTICE"),
        rootProject.file("docs/upstream.md"),
        rootProject.file("PRIVACY.txt")
    )
    outputs.upToDateWhen {
        dest.file("AGPL-3.0.txt").asFile.exists() &&
            dest.file("NOTICE.txt").asFile.exists() &&
            dest.file("UPSTREAM.txt").asFile.exists() &&
            dest.file("PRIVACY.txt").asFile.exists()
    }
}
tasks.named("preBuild") { dependsOn("syncLicenseAssets") }

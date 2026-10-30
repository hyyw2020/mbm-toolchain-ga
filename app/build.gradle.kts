plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    id("org.fcitx.fcitx5.android.fcitx-component")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.fcitx.fcitx5.android"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets(
                    // jni
                    "native-lib",
                    // copy fcitx5 built-in addon libraries
                    "copy-fcitx5-modules",
                    // android specific modules
                    "androidfrontend",
                    "androidkeyboard",
                    "androidnotification"
                )
            }
        }
    }

    buildFeatures {
        viewBinding = true
        resValues = true
    }

    buildTypes {
        release {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round")
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher_debug")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round_debug")
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }
}

fcitxComponent {
    includeLibs = listOf(
        "fcitx5",
        "fcitx5-lua",
        "libime",
        "fcitx5-chinese-addons"
    )
    // exclude (delete immediately after install) tables that nobody would use
    excludeFiles = listOf("cangjie", "erbi", "qxm", "wanfeng").map {
        "usr/share/fcitx5/inputmethod/$it.conf"
    }
    installPrebuiltAssets = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    ksp(project(":codegen"))
    implementation(project(":lib:fcitx5"))
    implementation(project(":lib:fcitx5-lua"))
    implementation(project(":lib:libime"))
    implementation(project(":lib:fcitx5-chinese-addons"))
    implementation(project(":lib:common"))
    implementation(project(":lib:handwritten"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    // MBM v1.0.34（R3）：剪贴板数据库（clbdb）落入 SQLCipher 加密存储。
    // raw key 由 Keystore 信封加密（data/crypto/AppKeyManager.kt），
    // 与 :plugin:recall 的 RecallDatabase 同链路；v1.0.26 拆 Recall 时移除，
    // 本期为主输入法数据静态加密重新引入（arm64 .so 约 +4MB）。
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    implementation(libs.arrow.core)
    implementation(libs.arrow.functions)
    implementation(libs.imagecropper)
    implementation(libs.flexbox)
    implementation(libs.dependency)
    implementation(libs.timber)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.dimensions)
    implementation(libs.splitties.resources)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.appcompat)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    // ==== MBM 新增能力 ====
    // NOTE(MBM 冻结方案 / Recall 同进程内嵌)：
    // Recall 已从独立插件 :plugin:recall 改为内嵌 app 模块、与 IME 同进程运行，
    // 因此下列依赖须随 app 主模块一起打包（此前随插件拆出，现重新引入）：
    //  - onnxruntime-android：离线 PP-OCRv6 截图 OCR（RecallOcrController）；
    //  - androidx-datastore-preferences：Recall 偏好（总开关 / OCR / 拾词 / 剪贴板）；
    //  - androidx-security-crypto：Keystore 信封加密的凭证/密钥存储；
    //  - androidx-biometric：高级保护模式下的人机校验（可选解锁 Recall）。
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)
    // NOTE(v1.0.34 / R3)：SQLCipher 重新引入——主输入法 clbdb（剪贴板历史，
    // 含 ≤256KB 的内联敏感文本）改为 SQLCipher 加密存储；raw key 走
    // Keystore 信封加密（AppKeyManager），与 Recall 同链路。
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.lifecycle.testing)
    androidTestImplementation(libs.junit)
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
        // remove unwanted splitties libraries...
        exclude(group = "com.louiscad.splitties", module = "splitties-appctx")
        exclude(group = "com.louiscad.splitties", module = "splitties-systemservices")
    }
}

// MBM v1.0.28：把根目录的 LICENSE / NOTICE 同步进 APK assets，使「开源协议说明」
// 随安装包分发、可在应用内离线查看（合规硬要求：APK 内必须含许可声明）。
// 单一事实来源仍在仓库根目录，改 LICENSE/NOTICE 后构建即自动同步，不产生副本漂移。
tasks.register<Copy>("syncLicenseAssets") {
    val dest = layout.projectDirectory.dir("src/main/assets/licenses")
    from(rootProject.file("LICENSE")) { rename { "AGPL-3.0.txt" } }
    from(rootProject.file("NOTICE")) { rename { "NOTICE.txt" } }
    from(rootProject.file("docs/upstream.md")) { rename { "UPSTREAM.txt" } }
    // MBM v1.0.32：隐私政策随 APK 分发，应用内离线可查（不依赖外网）
    from(rootProject.file("PRIVACY.txt")) { rename { "PRIVACY.txt" } }
    into(dest)
    // 内容变化时才真正重写，避免每次构建无谓触发资源重打包
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

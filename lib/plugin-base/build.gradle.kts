// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors

plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
    `maven-publish`
    alias(libs.plugins.gitVersion)
    // MBM：plugin-base 的 AndroidManifest 引用 @raw/aboutlibraries（插件框架 METADATA），
    // 该资源原本由附加的未跟踪文件补足、随包被遗漏，构建会报 resource not found。
    // 改为在本模块应用 aboutlibraries 插件离线生成（与 app 模块一致，fetchRemoteLicense=false 不联网）。
    alias(libs.plugins.aboutlibraries)
}

android {
    namespace = "org.fcitx.fcitx5.android.lib.plugin_base"

    publishing {
        // :lib:plugin_base contains different AndroidManifest.xml for debug and release variant
        multipleVariants { allVariants() }
    }
}

dependencies {
    api(project(":lib:common"))
    implementation(libs.aboutlibraries.core)
}

aboutLibraries {
    collect {
        fetchRemoteLicense.set(false)
        fetchRemoteFunding.set(false)
        includePlatform.set(false)
    }
    export {
        excludeFields.set(
            setOf("generated", "developers", "organization", "scm", "funding", "content")
        )
    }
}

val gitVersion = extra["gitVersion"] as groovy.lang.Closure<*>
// v1.0.36：源码 zip/tar 快照无 .git 时 gitVersion() 会抛「Cannot find '.git' directory」
// （构建说明书 4.2 镜像方式亦命中），降级为 "0.0.0"；git clone 检出行为不变。
version = runCatching { gitVersion().toString() }.getOrElse { "0.0.0" }

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/fcitx5-android/fcitx5-android")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
    publications {
        register<MavenPublication>("default") {
            groupId = "org.fcitx.fcitx5.android.lib"
            artifactId = "plugin_base"
            pom {
                licenses {
                    name.set("LGPL-2.1")
                    url.set("https://spdx.org/licenses/LGPL-2.1.html")
                }
            }
            afterEvaluate {
                from(components["default"])
            }
        }
    }
}

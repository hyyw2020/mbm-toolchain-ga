// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors

plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
    `maven-publish`
    alias(libs.plugins.gitVersion)
}

android {
    namespace = "org.fcitx.fcitx5.android.lib.common"

    buildFeatures {
        aidl = true
    }
    publishing {
        singleVariant("release")
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
        register<MavenPublication>("release") {
            groupId = "org.fcitx.fcitx5.android.lib"
            artifactId = "common"
            pom {
                licenses {
                    name.set("LGPL-2.1")
                    url.set("https://spdx.org/licenses/LGPL-2.1.html")
                }
            }
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

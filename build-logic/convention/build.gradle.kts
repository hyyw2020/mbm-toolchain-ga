// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors

import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    `kotlin-dsl`
    kotlin("plugin.serialization") version embeddedKotlinVersion
    `maven-publish`
    alias(libs.plugins.gitVersion)
    `java-gradle-plugin`
}

group = "org.fcitx.fcitx5.android.build_logic"

val gitVersion = extra["gitVersion"] as groovy.lang.Closure<*>
// v1.0.36：源码 zip/tar 快照无 .git 时 gitVersion() 会抛「Cannot find '.git' directory」
// （构建说明书 4.2 镜像方式亦命中），降级为 "0.0.0"；git clone 检出行为不变。
version = runCatching { gitVersion().toString() }.getOrElse { "0.0.0" }

java {
    withSourcesJar()
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.aboutlibraries.plugin)
    implementation(libs.kotlinx.serialization.json)
    // A workaround to enable version catalog usage in the convention plugin,
    // see https://github.com/gradle/gradle/issues/15383#issuecomment-779893192
    implementation(files(LibrariesForLibs::class.java.protectionDomain.codeSource.location))
}

gradlePlugin {
    plugins {
        register("androidAppConvention") {
            id = "org.fcitx.fcitx5.android.app-convention"
            implementationClass = "AndroidAppConventionPlugin"
        }
        register("androidLibConvention") {
            id = "org.fcitx.fcitx5.android.lib-convention"
            implementationClass = "AndroidLibConventionPlugin"
        }
        register("androidPluginAppConvention") {
            id = "org.fcitx.fcitx5.android.plugin-app-convention"
            implementationClass = "AndroidPluginAppConventionPlugin"
        }
        register("buildMetadata") {
            id = "org.fcitx.fcitx5.android.build-metadata"
            implementationClass = "BuildMetadataPlugin"
        }
        register("dataDescriptor") {
            id = "org.fcitx.fcitx5.android.data-descriptor"
            implementationClass = "DataDescriptorPlugin"
        }
        register("fcitxComponent") {
            id = "org.fcitx.fcitx5.android.fcitx-component"
            implementationClass = "FcitxComponentPlugin"
        }
        register("fcitxHeaders") {
            id = "org.fcitx.fcitx5.android.fcitx-headers"
            implementationClass = "FcitxHeadersPlugin"
        }
        register("nativeAppConvention") {
            id = "org.fcitx.fcitx5.android.native-app-convention"
            implementationClass = "NativeAppConventionPlugin"
        }
        register("nativeLibConvention") {
            id = "org.fcitx.fcitx5.android.native-lib-convention"
            implementationClass = "NativeLibConventionPlugin"
        }
    }
}

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
}

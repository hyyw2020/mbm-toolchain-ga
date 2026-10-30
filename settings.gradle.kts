// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fcitx5-android"

include(":lib:common")
include(":lib:handwritten")
include(":lib:fcitx5")
include(":lib:fcitx5-lua")
include(":lib:libime")
include(":lib:fcitx5-chinese-addons")
include(":codegen")
include(":app")
include(":lib:plugin-base")
include(":plugin:anthy")
include(":plugin:clipboard-filter")
include(":plugin:unikey")
include(":plugin:rime")
include(":plugin:hangul")
include(":plugin:chewing")
include(":plugin:sayura")
include(":plugin:jyutping")
include(":plugin:thai")
// MBM 冻结方案：Recall 不再作为独立插件构建，已内嵌 app 模块（与 IME 同进程）。
// P3-1 修复：旧 plugin/recall 源码目录已删除（git 历史保留可回滚），避免双份实现混淆。

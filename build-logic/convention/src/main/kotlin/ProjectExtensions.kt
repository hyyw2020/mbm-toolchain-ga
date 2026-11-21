/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */

import com.android.build.api.dsl.ApkSigningConfig
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.kotlin.dsl.the
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

fun Project.runCmd(cmd: String, defaultValue: String = ""): String {
    val output = providers.exec {
        commandLine = cmd.split(" ")
        // v1.0.36：providers.exec 默认不忽略非零退出码，失败时 result.get() 直接抛
        // 「finished with non-zero exit value」，defaultValue 回退实为死代码——
        // 源码 zip/tar 快照无 .git 时 git 退出 128 即崩（构建说明书 4.2 镜像方式亦命中）。
        // 显式忽略退出码，令 fallback 真正生效；git 检出路径行为不变。
        isIgnoreExitValue = true
    }
    return if (output.result.get().exitValue == 0) {
        output.standardOutput.asText.get().trim()
    } else {
        defaultValue
    }
}

val Project.libs get() = the<LibrariesForLibs>()

val Project.assetsDir: File
    get() = file("src/main/assets").also { it.mkdirs() }

val Project.cleanTask: Task
    get() = tasks.getByName("clean")

val Project.cmakeVersion
    get() = ep("CMAKE_VERSION", "cmakeVersion") { Versions.defaultCMake }

val Project.ndkVersion
    get() = ep("NDK_VERSION", "ndkVersion") { Versions.defaultNDK }

val Project.buildToolsVersion
    get() = ep("BUILD_TOOLS_VERSION", "buildTools") { Versions.defaultBuildTools }

val Project.buildVersionName
    get() = ep("BUILD_VERSION_NAME", "buildVersionName") {
        // v1.0.87 工程化：版本名改用确定性语义版本，不再用 git describe。
        // git describe 依赖 tag 在构建时是否已存在/是否落在当前提交，会产生
        // v1.0.79-toolchain-ga-7-g… 这类陈旧且不可复现的名称；改由 releaseVersionName
        // 唯一确定。BUILD_VERSION_NAME 环境变量仍可覆盖（供 CI 校验用）。
        Versions.releaseVersionName
    }

val Project.buildCommitHash
    get() = ep("BUILD_COMMIT_HASH", "buildCommitHash") {
        runCmd("git rev-parse HEAD", "N/A")
    }

val Project.buildTimestamp
    get() = ep("BUILD_TIMESTAMP", "buildTimestamp") {
        System.currentTimeMillis().toString()
    }

val Project.buildAbiOverride: String?
    get() = epn("BUILD_ABI", "buildABI")

val Project.signKeyBase64: String?
    get() = epn("SIGN_KEY_BASE64", "signKeyBase64")

val Project.signKeyFile: String?
    get() = epn("SIGN_KEY_FILE", "signKeyFile")

private var signKeyTempFile: File? = null

val Project.signKey: File?
    get() {
        signKeyFile?.let {
            val file = File(it)
            if (file.exists()) return file
        }
        @OptIn(ExperimentalEncodingApi::class)
        signKeyBase64?.let {
            if (signKeyTempFile?.exists() == true) {
                return signKeyTempFile
            }
            val buildDir = layout.buildDirectory.asFile.get()
            buildDir.mkdirs()
            val file = File.createTempFile("sign-", ".ks", buildDir)
            try {
                file.writeBytes(Base64.decode(it))
                file.deleteOnExit()
                signKeyTempFile = file
                return file
            } catch (e: Exception) {
                println(e.localizedMessage ?: e.stackTraceToString())
                file.delete()
            }
        }
        return null
    }

val Project.signKeyPwd: String?
    get() = epn("SIGN_KEY_PWD", "signKeyPwd")

val Project.signKeyAlias: String?
    get() = epn("SIGN_KEY_ALIAS", "signKeyAlias")

fun NamedDomainObjectContainer<out ApkSigningConfig>.fromProjectEnv(project: Project): ApkSigningConfig? {
    val keyFile = project.signKey ?: return null
    val name = "release"
    return findByName(name) ?: create(name) {
        storeFile = keyFile
        storePassword = project.signKeyPwd
        keyAlias = project.signKeyAlias
        keyPassword = project.signKeyPwd
    }
}

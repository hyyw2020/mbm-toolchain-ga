/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */
import com.android.build.gradle.internal.cxx.configure.rewriteWithLocations
import com.android.build.gradle.internal.cxx.logging.PassThroughRecordingLoggingEnvironment
import com.android.build.gradle.internal.cxx.model.CxxAbiModel
import com.android.build.gradle.tasks.ExternalNativeBuildJsonTask
import com.android.build.gradle.tasks.ExternalNativeBuildTask
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.withType
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec
import java.io.File
import javax.inject.Inject

fun ExternalNativeBuildJsonTask.abiModel(): CxxAbiModel {
    val abi = ExternalNativeBuildJsonTask::class.java.declaredFields.find { it.name == "abi" }!!
    abi.isAccessible = true
    return abi.get(this) as CxxAbiModel
}

/**
 * MBM（AGP 9.4 / Gradle 9.8.2 工具链轮）：从 CxxAbiModel 里摊平出来的两个**可序列化**值。
 *
 * 原实现把整个 `CxxAbiModel` 作为 `Property<CxxAbiModel>` 塞进任务。它是 AGP internal
 * 类型且间接持有 `Project`，Gradle 9.8 的配置缓存无法序列化（`cannot serialize object of
 * type DefaultProject`），导致本工程 12 个 native install 任务全部不兼容配置缓存。
 * 任务实际只用到「cmake 可执行文件路径」和「C++ 构建目录」两项，这里摊平成 String，
 * 语义完全等价、可安全序列化。
 */
class CxxNativePaths(
    val cmakeExe: Property<String>,
    val cxxBuildFolder: Property<String>
)

/**
 * MBM：安装后对某个已安装资产做文本替换的描述（原来是 `(File) -> Unit` lambda）。
 * lambda 无法被配置缓存序列化，而这里唯一的真实用途就是 jyutping 的一个
 * `Library=libjyutping` → `Library=libIMEJyutping;libjyutping` 常量替换，
 * 数据化后语义完全等价且可安全序列化。
 */
data class TextReplacement(
    /** 相对 assetsDir 的路径 */
    val path: String,
    val from: String,
    val to: String
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * This function contains `configureEach` call, to avoid "task warming-up failure",
 * **DO NOT** call this during other tasks' configuration
 */
fun Project.getCxxNativePaths(): CxxNativePaths {
    val cmakeExe: Property<String> = project.objects.property()
    val cxxBuildFolder: Property<String> = project.objects.property()
    tasks.withType<ExternalNativeBuildJsonTask>().configureEach {
        doFirst {
            // `CxxAbiModel.rewriteWithLocations` requires a "Non-default logger"
            // https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:build-system/gradle-core/src/main/java/com/android/build/gradle/internal/cxx/configure/NativeLocationsBuildService.kt;drc=b5516899015633c99dc64b510d9729c4e001e89c;l=67
            // just supply a random LoggingEnvironment or whatever this is
            PassThroughRecordingLoggingEnvironment().use {
                val model = abiModel().rewriteWithLocations(nativeLocationsBuildService.get())
                cmakeExe.set(model.variant.module.cmake!!.cmakeExe!!.absolutePath)
                cxxBuildFolder.set(model.cxxBuildFolder.absolutePath)
            }
        }
    }
    return CxxNativePaths(cmakeExe, cxxBuildFolder)
}

abstract class CMakeBuildInstallTask : DefaultTask() {

    /**
     * MBM：原先在执行期调 `project.providers.exec(...)`，Gradle 9.8 报
     * "invocation of 'Task.project' at execution time is unsupported"。
     * 改为注入 [ExecOperations]（配置缓存兼容的标准做法）。
     */
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Input
    @get:Optional
    abstract val cmakeExe: Property<String>

    @get:Input
    @get:Optional
    abstract val cxxBuildFolder: Property<String>

    @get:Input
    @get:Optional
    abstract val buildTarget: Property<String>

    @get:Input
    @get:Optional
    abstract val installComponent: Property<String>

    @get:Input
    abstract val destDir: Property<File>

    /**
     * MBM：安装后要做的文本替换。原先这段逻辑写在 register 的 `doLast {}` 里，
     * 闭包捕获了 `project`（执行期访问 `Task.project` 不被配置缓存允许）；
     * 改成任务输入属性后，@TaskAction 内部只读属性，不再触碰 Project。
     */
    @get:Input
    abstract val textReplacements: ListProperty<TextReplacement>

    private fun exec(action: Action<ExecSpec>) {
        execOperations.exec(action).assertNormalExitValue()
    }

    @TaskAction
    fun execute() {
        val cmake = this.cmakeExe.get()
        val cxxBuildFolder = this.cxxBuildFolder.get()
        val buildTarget = this.buildTarget.getOrElse("")
        val installComponent = this.installComponent.getOrElse("")
        val destDir = this.destDir.get()
        if (buildTarget.isNotEmpty()) {
            exec {
                commandLine(
                    cmake,
                    "--build", cxxBuildFolder,
                    "--target", buildTarget
                )
            }
        }
        if (installComponent.isNotEmpty()) {
            exec {
                environment("DESTDIR", destDir.absolutePath)
                commandLine(
                    cmake,
                    "--install", cxxBuildFolder,
                    "--component", installComponent
                )
            }
        }
        textReplacements.get().forEach { (path, from, to) ->
            val file = destDir.resolve(path)
            if (file.exists()) {
                file.writeText(file.readText().replace(from, to))
            }
        }
    }
}

/**
 * Important: make sure that the task runs after than the native task
 * Since we can't declare the dependency relationship, a weaker running order constraint must be enforced
 */

fun Task.runAfterNativeBuild(project: Project) {
    project.tasks.withType<ExternalNativeBuildTask>().all externalNativeBuild@{
        this@runAfterNativeBuild.mustRunAfter(this@externalNativeBuild)
    }
}

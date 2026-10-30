/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
import com.android.build.gradle.tasks.ExternalNativeBuildJsonTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.Delete
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import java.io.File
import kotlin.io.path.isSymbolicLink

class FcitxComponentPlugin : Plugin<Project> {

    abstract class FcitxComponentExtension {
        var includeLibs: List<String> = emptyList()
        var excludeFiles: List<String> = emptyList()
        /**
         * MBM（Gradle 9.8.2 / 配置缓存）：原来是 `Map<String, (File) -> Unit>`，
         * lambda 不可被配置缓存序列化。改为数据描述 [TextReplacement]，语义等价。
         * 脚本侧请用 [textReplacement] 方法添加（[TextReplacement] 在 build-logic 的
         * 默认包里，模块脚本无法 import 它）。
         */
        var textReplacements: List<TextReplacement> = emptyList()
        var installPrebuiltAssets: Boolean = false

        /** 追加一条「安装后对资产文件做文本替换」的规则。 */
        fun textReplacement(path: String, from: String, to: String) {
            textReplacements = textReplacements + TextReplacement(path, from, to)
        }
    }

    companion object {
        const val INSTALL_TASK = "installFcitxComponent"
        const val DELETE_TASK = "deleteFcitxComponentExcludeFiles"
        const val CLEAN_TASK = "cleanFcitxComponents"
        const val EXTENSION = "fcitxComponent"

        val DEPENDENT_TASKS = arrayOf(INSTALL_TASK, DELETE_TASK)
    }

    override fun apply(target: Project) {
        val installTask = target.tasks.register(INSTALL_TASK)
        val deleteTask = target.tasks.register(DELETE_TASK)
        registerCleanTask(target)
        target.extensions.create<FcitxComponentExtension>(EXTENSION)
        target.afterEvaluate {
            val ext = extensions.getByName<FcitxComponentExtension>(EXTENSION)
            // MBM：注册统一挪到 afterEvaluate —— 只有此时 fcitxComponent {} 扩展才被
            // 模块脚本填好，textReplacements 才能作为**任务输入属性**塞进任务
            // （而不是在执行期回头找 project.extensions，那会被配置缓存判为非法）。
            val replacements = ext.textReplacements
            registerCMakeTask(target, "generate-desktop-file", "config", replacements = replacements)
            registerCMakeTask(target, "translation-file", "translation", replacements = replacements)
            ext.includeLibs.forEach {
                val project = rootProject.project(":lib:$it")
                registerCMakeTask(
                    target, "generate-desktop-file", "config", project, replacements
                )
                registerCMakeTask(
                    target, "translation-file", "translation", project, replacements
                )
            }
            if (ext.excludeFiles.isNotEmpty()) {
                // 提前把路径解析成 File 列表：doLast 闭包若捕获 project 会破坏配置缓存
                val assetsDir = target.assetsDir
                val toDelete = ext.excludeFiles.map { assetsDir.resolve(it) }
                deleteTask.get().apply {
                    dependsOn(installTask)
                    doLast {
                        toDelete.forEach { it.delete() }
                    }
                }
            }
            if (ext.installPrebuiltAssets) {
                registerCMakeTask(target, "", "prebuilt-assets", replacements = replacements)
            }
        }
    }

    /**
     * build [sourceProject]'s cmake [target], and install its [component] to [project]'s assets
     */
    private fun registerCMakeTask(
        project: Project,
        target: String,
        component: String,
        sourceProject: Project = project,
        replacements: List<TextReplacement> = emptyList()
    ) {
        val componentName = component.split('-').joinToString("") { it.capitalized() }
        val taskName = if (project === sourceProject) {
            "installProject$componentName"
        } else {
            "installLibrary$componentName[${sourceProject.name}]"
        }
        val paths = sourceProject.getCxxNativePaths()
        val task = project.tasks.register<CMakeBuildInstallTask>(taskName) {
            cmakeExe.set(paths.cmakeExe)
            cxxBuildFolder.set(paths.cxxBuildFolder)
            buildTarget.set(target)
            installComponent.set(component)
            destDir.set(project.assetsDir)
            textReplacements.set(replacements)
            mustRunAfter(sourceProject.tasks.withType<ExternalNativeBuildJsonTask>())
        }
        project.tasks.getByName(INSTALL_TASK).dependsOn(task)
    }

    private fun registerCleanTask(project: Project) {
        project.tasks.register<Delete>(CLEAN_TASK) {
            delete(project.assetsDir.resolve("usr/share/locale"))
            // delete all non symlink files
            // true -> delete
            val files = mutableMapOf<String, Boolean>()
            project.assetsDir.resolve("usr/share/fcitx5").walkBottomUp()
                .onEnter {
                    // Don't enter symlink dir and don't delete
                    (!it.toPath().isSymbolicLink()).also { x -> files[it.path] = x }
                }
                .onLeave {
                    // Delete dir if all of its children can be deleted
                    files[it.path] =
                        it.listFiles()?.mapNotNull { f -> files[f.path] }?.all { x -> x } ?: false
                }
                .forEach {
                    // Don't delete symlink
                    files[it.path] = !it.toPath().isSymbolicLink()
                }
            files.forEach {
                if (it.value) {
                    logger.log(LogLevel.DEBUG, "Delete ${it.key}")
                    delete(it.key)
                }
            }
        }.also {
            project.cleanTask.dependsOn(it)
        }
    }

}

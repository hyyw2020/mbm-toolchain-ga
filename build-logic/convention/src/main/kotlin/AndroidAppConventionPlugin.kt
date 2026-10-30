/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.ApkSigningConfig
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.BuildConfigField
import com.android.build.api.variant.Variant
import com.android.build.gradle.internal.tasks.CompileArtProfileTask
import com.android.build.gradle.internal.tasks.ExpandArtProfileWildcardsTask
import com.android.build.gradle.internal.tasks.MergeArtProfileTask
import com.android.build.gradle.tasks.PackageApplication
import com.mikepenz.aboutlibraries.plugin.AboutLibrariesExtension
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.internal.provider.AbstractProperty
import org.gradle.api.internal.provider.Providers
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * The prototype of an Android Application
 *
 * - Configure dependency for [DataDescriptorPlugin] task (If have)
 * - Provide default configuration for `android {...}`
 * - Add desugar JDK libs
 */
@Suppress("unused")
class AndroidAppConventionPlugin : AndroidBaseConventionPlugin() {

    override fun apply(target: Project) {
        target.pluginManager.apply(target.libs.plugins.android.application.get().pluginId)

        super.apply(target)

        target.extensions.configure<ApplicationExtension> {
            defaultConfig {
                targetSdk = Versions.targetSdk
                versionCode = Versions.calculateVersionCode()
                versionName = target.buildVersionName
            }
            buildFeatures {
                // MBM（AGP 9.4 工具链轮）：本插件在 onVariants 里注入 BuildConfig.SIGNER_SHA256
                // （P2-7 启动签名自校验，见 injectSignerSha256）。AGP 9 起 buildConfig 特性
                // 默认关闭，配置期会报「Accessing value buildConfigFields ... no effect」。
                // 实测字段其实仍会写入生成的 BuildConfig（不是静默失效），但依赖默认值不写
                // 进代码就是隐式契约：这里由「注入方」显式开启，既消掉警告，也保证将来
                // AGP 真的默认关闭时这条安全防线不会无声消失。
                buildConfig = true
            }
            buildTypes {
                release {
                    // MBM: 交付无混淆、无加固的 release 包
                    isMinifyEnabled = false
                    isShrinkResources = false
                    signingConfig = signingConfigs.fromProjectEnv(target)
                    proguardFile(getDefaultProguardFile("proguard-android-optimize.txt"))
                }
                debug {
                    applicationIdSuffix = ".debug"
                    // MBM v1.0.35：APK 一律不签名，由使用者自行签名后安装。
                    // debug 变体默认会挂 Android SDK 的 debug keystore，显式置空。
                    signingConfig = null
                }
                all {
                    // remove META-INF/version-control-info.textproto
                    @Suppress("UnstableApiUsage")
                    vcsInfo.include = false
                }
            }
            compileOptions {
                isCoreLibraryDesugaringEnabled = true
            }
        }

        target.extensions.configure<ApplicationExtension> {
            dependenciesInfo {
                includeInApk = false
                includeInBundle = false
            }
            packaging {
                resources {
                    excludes += setOf(
                        "/META-INF/*.version",
                        "/META-INF/*.kotlin_module",  // cannot be excluded actually
                        "/META-INF/androidx/**",
                        "/DebugProbesKt.bin",
                        "/kotlin-tooling-metadata.json"
                    )
                }
            }
        }

        // remove META-INF/com/android/build/gradle/app-metadata.properties
        target.tasks.withType<PackageApplication> {
            val valueField =
                AbstractProperty::class.java.declaredFields.find { it.name == "value" } ?: run {
                    println("class AbstractProperty field value not found, something could have gone wrong")
                    return@withType
                }
            valueField.isAccessible = true
            doFirst {
                valueField.set(appMetadata, Providers.notDefined<RegularFile>())
                allInputFilesWithNameOnlyPathSensitivity.removeAll { true }
            }
        }

        // try to remove <pkg_name>-<version_name>.kotlin_module, but it does not work ¯\_(ツ)_/¯
        target.tasks.withType<KotlinCompile> {
            doLast f@{
                val ktClass = outputs.files.files.filter { it.path.contains("kotlin-classes") }
                if (ktClass.isEmpty()) return@f
                val metaInf = ktClass.first().resolve("META-INF")
                if (!metaInf.exists() || !metaInf.isDirectory) return@f
                metaInf.listFiles()?.forEach {
                    if (it.name.endsWith(".kotlin_module")) {
                        it.delete()
                    }
                }
            }
        }

        // remove assets/dexopt/baseline.prof{,m} (baseline profile)
        target.tasks.withType<MergeArtProfileTask> { enabled = false }
        target.tasks.withType<ExpandArtProfileWildcardsTask> { enabled = false }
        target.tasks.withType<CompileArtProfileTask> { enabled = false }

        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            // Add dependency relationships for data descriptor task
            onVariants { v ->
                val variantName = v.name.capitalized()
                // Evaluation should be delayed as we need be able to see other tasks
                target.afterEvaluate {
                    tasks.findByName(DataDescriptorPlugin.TASK)?.also {
                        tasks.findByName("merge${variantName}Assets")?.dependsOn(it)
                        tasks.findByName("lintVitalAnalyzeRelease")?.dependsOn(it)
                        tasks.findByName("generateReleaseLintVitalReportModel")?.dependsOn(it)
                    }
                }
            }
            // MBM v1.0.26 / P2-7：把本变体实际签名证书的 SHA-256 注入 BuildConfig，
            // 供启动签名自校验对比（见 utils/AppSignatureVerifier.kt）。
            onVariants { v -> injectSignerSha256(target, v) }
            // Make data descriptor depend on fcitx component if have
            // Since we are using finalizeDsl, there is no need to do afterEvaluate
            finalizeDsl {
                target.tasks.findByName(DataDescriptorPlugin.TASK)?.also { dataDescriptorTask ->
                    FcitxComponentPlugin.DEPENDENT_TASKS
                        .mapNotNull { taskName -> target.tasks.findByName(taskName) }
                        .forEach { componentTask -> dataDescriptorTask.dependsOn(componentTask) }
                }
                // applicationId is not set upon apply
                it.defaultConfig {
                    // https://www.norio.be/blog/archivesBaseName-removed-from-gradle9.html
                    target.extensions.configure<BasePluginExtension> {
                        archivesName.set("$applicationId-$versionName")
                    }
                }
            }
        }

        target.pluginManager.apply(target.libs.plugins.aboutlibraries.get().pluginId)

        target.configure<AboutLibrariesExtension> {
            collect {
                configPath.set(target.file("licenses").takeIf { it.exists() })
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

        target.dependencies.add("coreLibraryDesugaring", target.libs.android.desugarJDKLibs)
    }

    /**
     * MBM v1.0.26 / P2-7（启动签名自校验）：
     * 读取该变体签名配置对应 keystore 的首个证书，算 SHA-256 大写无分隔 Hex，
     * 写进 `BuildConfig.SIGNER_SHA256`。运行时拿自己证书的指纹和它比对，
     * 不一致就说明「这个 APK 不是我们签的」——被重打包/换签名的第一步信号。
     *
     * 取不到（keystore 缺失、密码错、debug 默认 keystore 未创建）时注入空串，
     * 运行时跳过校验而不是误报：自校验是防线，不是新的崩溃源。
     */
    private fun injectSignerSha256(
        target: Project,
        variant: com.android.build.api.variant.Variant
    ) {
        // 注意：不能在 afterEvaluate 里改 variant.buildConfigFields ——
        // AGP 到那个时点已经锁死 variant DSL。这里同步读取 DSL（此时 buildTypes
        // 已在 apply() 阶段之后、onVariants 期间可见）。
        val buildType = variant.buildType ?: return
        val ext = target.extensions.findByType(ApplicationExtension::class.java) ?: return
        val signing: ApkSigningConfig? = ext.buildTypes.getByName(buildType).signingConfig
        val sha256: String = if (signing == null) "" else {
            runCatching { certSha256(signing) }.getOrDefault("")
        }
        variant.buildConfigFields?.put(
            "SIGNER_SHA256",
            com.android.build.api.variant.BuildConfigField(
                "String",
                "\"$sha256\"",
                "SHA-256 of the signing certificate of this variant (MBM P2-7)"
            )
        )
    }

    private fun certSha256(config: ApkSigningConfig): String {
        val store = config.storeFile ?: return ""
        if (!store.exists()) return ""
        val pwd = config.storePassword?.toCharArray() ?: return ""
        val ks = java.security.KeyStore.getInstance(
            if (store.extension.equals("p12", true) || store.extension.equals("pfx", true))
                "PKCS12"
            else
                "JKS"
        )
        store.inputStream().use { ks.load(it, pwd) }
        val alias = config.keyAlias ?: ks.aliases().toList().firstOrNull() ?: return ""
        val cert = ks.getCertificate(alias) ?: return ""
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { "%02X".format(it) }
    }

}

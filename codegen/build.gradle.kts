plugins {
    alias(libs.plugins.kotlin.jvm)
}

// JDK 27 下 Kotlin 2.4.21 最高只支持 JVM 26，会导致 compileJava(27) 与
// compileKotlin(26) 目标不一致并判定为错误。这里把 Java 与 Kotlin 的字节码
// 目标显式对齐到与工程其余模块一致的 11，消除该冲突。
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.kotlinpoet.ksp)
    implementation(libs.ksp)
}

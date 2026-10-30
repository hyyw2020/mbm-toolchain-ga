# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# disable obfuscation
-dontobfuscate

# Keep JNI interface
-keep class org.fcitx.fcitx5.android.core.* { *; }
-keep class org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase {
    public <init>(...);
}

# Keep dependency magic
-keep class ** extends org.mechdancer.dependency.Component {
    int hashCode();
    boolean equals(java.lang.Object);
}

# SQLCipher：保留加密数据库相关类与 JNI 方法。
# 防止 minify 开启后把 net.zetetic.database.** 或 native 方法剥掉，
# 否则运行时会 UnsatisfiedLinkError: No implementation found for ...SQLiteConnection.nativeOpen。
# （net.zetetic:sqlcipher-android 的 AAR 自带 consumer proguard 规则也会保留，
# 这里再显式声明一次，作为保险与意图记录，见 docs/pitfalls.md #2。）
-keep,includedescriptorclasses class net.zetetic.database.** { *; }
-keepclasseswithmembernames class net.zetetic.database.** {
    native <methods>;
}
-dontwarn net.zetetic.database.**

# remove kotlin null checks
-processkotlinnullchecks remove

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

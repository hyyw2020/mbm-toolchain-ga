/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

sealed interface PluginLoadFailed {
    data class PathConflict(
        val plugin: PluginDescriptor,
        val path: String,
        val existingSrc: FileSource
    ) : PluginLoadFailed

    data object MissingPluginDescriptor : PluginLoadFailed

    data object PluginDescriptorParseError : PluginLoadFailed

    data class MissingDataDescriptor(
        val plugin: PluginDescriptor
    ) : PluginLoadFailed

    data class DataDescriptorParseError(
        val plugin: PluginDescriptor
    ) : PluginLoadFailed

    data class PluginAPIIncompatible(
        val api: String
    ) : PluginLoadFailed

    /**
     * MBM v1.0.26（P0-3）：插件签名 / .so 校验未通过。
     * 「不加固」哲学下，加载前验证是唯一有效的加载门槛。
     */
    data class UntrustedPlugin(
        val plugin: PluginDescriptor,
        val reason: String
    ) : PluginLoadFailed
}
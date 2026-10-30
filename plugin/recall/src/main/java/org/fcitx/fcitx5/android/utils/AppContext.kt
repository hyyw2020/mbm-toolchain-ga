/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * Recall 插件自己的 applicationContext 句柄（与主输入法的同名工具类无任何关系，
 * 拆包后两个进程互相独立）。
 */
package org.fcitx.fcitx5.android.utils

import android.content.Context
import org.fcitx.fcitx5.android.plugin.recall.RecallApplication

val appContext: Context
    get() = RecallApplication.instance.applicationContext

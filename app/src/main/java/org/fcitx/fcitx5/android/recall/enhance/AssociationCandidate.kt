/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Cyletix
 *
 * 移植自 CyIME（https://github.com/Cyletix/CyIME），原路径
 * app/src/main/java/com/kingzcheung/xime/association/AssociationCandidate.kt。
 * 依 GPLv3 保留原版权与许可；来源说明见仓库 THIRD_PARTY_NOTICES.md 与 LICENSE 附录。
 */
package org.fcitx.fcitx5.android.recall.enhance

/** 联想候选项及其排序分数；分数越大优先级越高。 */
data class AssociationCandidate(
    val text: String,
    val score: Float,
)

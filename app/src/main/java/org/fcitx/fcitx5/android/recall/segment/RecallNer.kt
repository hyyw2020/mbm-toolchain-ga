/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 中文专名识别（NER）—— 双通道分词器的 B 通道。
 *
 * 设计（冻结方案 B3：不保证单次纯汉字专名 100% 识别，交付实测识别率）：
 *  - 首选：离线量化 ONNX NER 模型（10~25MB），从 assets/ner/ner.onnx 懒加载。
 *    该资产本期未随包分发，[loadModel] 检测不到即返回 null，自动回退到下方
 *    形态强规则 + 词典兜底（轻量、零额外体积）。
 *  - 兜底：基于「姓氏起首 + 停用字排除 + 词长 / 边界」的形态强规则，对游戏名、
 *    应用名、人名、地名等专名有较高召回；置信度由命中信号叠加得到（0.5~0.9）。
 *
 * 实测识别率目标（验收指标）≥ 70%，由 [RecallSegmenter] 在真实语料上回灌评估，
 * 不承诺 100%（信息论硬约束：纯汉字专名与通用词在字形上无必然区分）。
 */
package org.fcitx.fcitx5.android.recall.segment

import android.content.Context
import org.fcitx.fcitx5.android.recall.RecallConfig

data class RecallEntity(val text: String, val confidence: Float)

object RecallNer {

    /** 常见姓氏（专名起首信号，仅取高频单姓以降低误召回）。 */
    private val SURNAMES = setOf(
        '王', '李', '张', '刘', '陈', '杨', '黄', '赵', '吴', '周', '徐', '孙', '马', '朱',
        '胡', '林', '郭', '何', '高', '罗', '郑', '梁', '谢', '宋', '唐', '许', '韩', '冯',
        '邓', '曹', '彭', '曾', '萧', '田', '董', '潘', '袁', '蒋', '蔡', '余', '杜', '叶'
    )

    /** 强功能字 / 停用字：含这些字的基本是通用词而非专名，直接排除。 */
    private val STOP_CHARS = setOf(
        '的', '了', '是', '在', '我', '你', '他', '她', '它', '这', '那', '和', '与', '及',
        '等', '个', '们', '也', '都', '很', '就', '还', '要', '会', '能', '把', '被', '让',
        '给', '从', '向', '对', '于', '以', '为', '因', '而', '其', '之', '此', '该', '各',
        '某', '或', '如', '若', '但', '却', '已', '又', '再', '不', '没', '有', '上', '下',
        '中', '里', '内', '外', '前', '后', '左', '右', '大', '小', '多', '少', '新', '旧'
    )

    @Volatile
    private var modelLoaded = false

    /**
     * ONNX 模型加载钩子（预留）。本期无资产，返回 false 走启发式兜底。
     * 接入真实模型时：在此用 ai.onnxruntime 加载 assets/ner/ner.onnx，
     * 实现 [detectWithModel]，并把 [modelLoaded] 置 true。
     */
    fun loadModel(context: Context): Boolean {
        val asset = runCatching {
            context.assets.list("ner")?.contains("ner.onnx") == true
        }.getOrDefault(false)
        modelLoaded = asset
        return asset
    }

    fun isModelLoaded() = modelLoaded

    /** 主入口：对一段文本识别专名候选。 */
    fun detect(text: String): List<RecallEntity> {
        if (modelLoaded) return detectWithModel(text)
        return detectHeuristic(text)
    }

    /** 形态强规则兜底（无模型时启用）。 */
    private fun detectHeuristic(text: String): List<RecallEntity> {
        val out = LinkedHashSet<RecallEntity>()
        for (run in RecallTextUtils.hanRuns(text)) {
            if (run.length < 2 || run.length > 6) continue
            if (run.any { it in STOP_CHARS }) continue
            var score = 0.5f
            if (run.first() in SURNAMES) score += 0.25f            // 姓氏起首（人名信号）
            if (run.length >= 3) score += 0.1f                    // 较长词组更像专名
            if (run.length == 2 && run.first() !in SURNAMES) continue // 双字非姓氏起首判为通用词
            if (score < RecallConfig.NER_MIN_CONFIDENCE) continue
            out += RecallEntity(run, score.coerceAtMost(0.9f))
        }
        return out.toList()
    }

    /** 模型推理占位（接入 ONNX 后实现）。 */
    private fun detectWithModel(text: String): List<RecallEntity> {
        // TODO(MBM): 接入量化 ONNX NER 后，在此做分词 / 编码 / 推理 / 解码。
        return detectHeuristic(text)
    }
}

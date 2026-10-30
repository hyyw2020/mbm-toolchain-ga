/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * Recall 屏幕记忆 —— 同进程内嵌架构的全局调参中心（冻结方案 P0）。
 *
 * 所有阈值、队列上限、内存上限集中在此，便于验收与调优，也方便后续按实测
 * 识别率回灌（B3 专名全量识别的「交付实测值」即来自此处的可调边界）。
 *
 * 与推荐方案的关键差异（冻结版已记录，非推荐选项）：
 *  - D1/D2/D3：10ms 定频拾取，名义 10ms ≠ 实际 10ms，不承诺严格 100Hz；
 *  - E2：全局零 App 豁免（见 RecallPolicy，金融包名不再豁免）；
 *  - B3：不保证单次纯汉字专名 100% 识别，交付实测识别率而非承诺。
 */
package org.fcitx.fcitx5.android.recall

/**
 * 全局常量与阈值。开关类（总开关 / 拾词 / OCR）走 [RecallPreferences]，不在此处。
 */
object RecallConfig {

    // ---- 拾取层（D1/D2/D3：10ms 定频 + 事件驱动 + 静止退避）----

    /**
     * 活跃期定频采样间隔（ms）。P1-1 修复：由 10 提至 50——binder 排队下 10ms 名义值
     * 毫无收益（PICKER_MIN_FRAME_GAP_MS=120 已限流变化帧），纯耗电；50ms 已覆盖
     * 「尽可能快的连续采样」的交付语义，事件风暴由 dirty 标记合并，不再击穿退避。
     */
    const val PICKER_INTERVAL_MS = 50L

    /** 事件静止超过此值（ms）则退避：停止定频轮询，仅由新事件重新触发，避免空转耗电。 */
    const val PICKER_IDLE_BACKOFF_MS = 2000L

    /** 同一窗口两次快照的最小间隔，防止同帧重复处理。 */
    const val PICKER_MIN_FRAME_GAP_MS = 120L

    // ---- 生效层（C1/C2/C3：逐词写 + 批量 reload + ack 闭环）----

    /** 批量 reload 去抖窗口（ms）：落盘后在此窗口内合并多次请求。 */
    const val RELOAD_DEBOUNCE_MS = 400L

    /** 两次 reload 的最小间隔（ms），防止高频抖动刷 reload。 */
    const val RELOAD_MIN_INTERVAL_MS = 300L

    // ---- 分词层（B1/B3/B4：双通道 + 专名模型 + 三层过滤）----

    const val MIN_PHRASE_LEN = 2
    const val MAX_WHOLE_PHRASE_LEN = 6
    const val WINDOW_PHRASE_LEN = 4

    /** 单批次提交给词库的候选上限（有界队列：超出丢弃最旧）。 */
    const val CANDIDATE_BATCH_CAP = 200

    /** 新词发现语料滚动缓冲上限（字符数），防止内存无界增长。 */
    const val CORPUS_BUFFER_MAX = 4096

    /** NER 命中最低置信度（0~1）。低于此值不计入专名候选。 */
    const val NER_MIN_CONFIDENCE = 0.5f

    // ---- 词库层（B2：无上限 + 冷热分层 + auto + 衰减）----

    /** 同进程自定义词组托管条目硬上限（内存/落盘双控，超出按 LRU 淘汰最冷）。 */
    const val MANAGED_HARD_CAP = 5000

    /** 热词条顺序（最近活跃 → 候选靠前，输入拼音首字母即出现在第 2 位）。 */
    const val HOT_ORDER = 2

    /** 冷词条顺序（衰减后降级，不打扰常用词）。 */
    const val COLD_ORDER = 1

    /** 冷阈值：超过此时间（ms）未再出现即降为 cold。 */
    const val HOT_TTL_MS = 7L * 24 * 3600 * 1000

    // ---- OCR 层（区域 diff + 打字区裁剪 + 双开关）----

    const val OCR_MIN_INTERVAL_MS = 1200L

    // ---- 隐私与过滤（E1/E2/E3）----

    /**
     * 模式级正则（卡号 / 身份证 / 验证码）——硬编码、不可关闭。
     * 命中词条自动打标 `sensitive=true` 并降权（见 [RecallSegmenter] 第三层过滤），
     * 但不丢弃整屏：节点级（密码 / isDataSensitive）才整屏丢弃。
     */
    val SENSITIVE_PATTERNS: List<Regex> = listOf(
        Regex("""(?<!\d)\d{16,19}(?!\d)"""),                 // 银行卡 / 卡号
        Regex("""(?<!\d)\d{17}[\dxX](?!\d)"""),               // 身份证
        Regex("""(?i)(?:验证码|动态码|短信码|otp)\D{0,4}(\d{4,8})""") // 验证码
    )
}

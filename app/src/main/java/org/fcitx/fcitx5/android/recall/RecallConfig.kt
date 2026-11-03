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

    /**
     * 中等长度汉字段整词收录上限（MBM v1.0.60）。
     * 超过 [MAX_WHOLE_PHRASE_LEN] 但不超过本值的连续汉字段，直接整词作为候选收录，
     * 不再被定长 4 字窗口切碎（如「三国志王者天下」7 字、游戏名 / 标语）。
     * 超过本值（过长的句子片段）才走窗口切分，避免把整句当成一个长词组污染候选。
     */
    const val MAX_RUN_WHOLE_LEN = 10

    /**
     * 混合中英文专名（应用名 / 游戏名，含 · 分隔符或英文版本号）的最大长度。
     * 此类专名常超出普通短语的 6 字上限（如「云·战双帕弥什」7 字、带英文版本号的应用名），
     * 故在通道 D（混合专名抽取）里放宽到本上限后写库（见 [RecallSegmenter]）。
     */
    const val MAX_PROPERNOUN_LEN = 16

    /** 单批次提交给词库的候选上限（有界队列：超出丢弃最旧）。 */
    const val CANDIDATE_BATCH_CAP = 200

    /** 新词发现语料滚动缓冲上限（字符数），防止内存无界增长。 */
    const val CORPUS_BUFFER_MAX = 4096

    /** NER 命中最低置信度（0~1）。低于此值不计入专名候选。 */
    const val NER_MIN_CONFIDENCE = 0.5f

    // ---- 词库层（B2：无上限 + 冷热分层 + auto + 衰减）----

    /** 同进程自定义词组托管条目硬上限（内存/落盘双控，超出按 LRU 淘汰最冷）。 */
    const val MANAGED_HARD_CAP = 5000

    /**
     * 用户「置顶」最高优先级档（Order）。
     *
     * 用户把某词组置顶后，其 order 被强制设为 1，稳定压过所有自动导入词组、
     * 应用名快捷输入（order 2/8）与 Recall 热/冷词（order 3/9），输入对应拼音
     * 即出现在候选首位。tier 排布（order 越小越靠前）：
     *   置顶=1 > 应用名搜索场景=2 > Recall 热词=3 > 用户默认=5 > 应用名普通=8 > Recall 冷词=9。
     * 详见 PinyinCustomPhraseFragment 的置顶实现与 AppPhraseImporter / RecallWordStore 的 order 约定。
     */
    const val PIN_ORDER = 1

    /**
     * 热词条顺序（最近活跃 → 候选靠前，输入拼音首字母即出现在第 3 位）。
     *
     * native 语义：自定义词组 order=n 落在第 n 个候选位（order 越小越靠前）。
     * 注意：1 已预留给「用户置顶」档（PIN_ORDER），故热词从 2 之后取 3。
     */
    const val HOT_ORDER = 3

    /**
     * 冷词条顺序（衰减后降级，不打扰常用词）。
     *
     * v1.0.41 修复：旧值 1 会让「衰减后的冷词 / 敏感词」反而占据**第一候选位**，
     * 与本类注释（「不出候选首位」「不打扰常用词」）完全相反，也挤掉了真正该靠前的
     * 热词。冷词应显著靠后，故取 9（明显晚于键盘可见候选条）。
     *
     * 取值同时要避开应用名条目的 order（应用搜索 2 / 普通 8）与用户置顶档（1）、
     * Recall 热词（3）、用户默认（5），否则词库合并时无法按 order 区分条目归属
     * （见 RecallWordStore.isOwnEntry）。
     */
    const val COLD_ORDER = 9

    /** 冷阈值：超过此时间（ms）未再出现即降为 cold。 */
    const val HOT_TTL_MS = 7L * 24 * 3600 * 1000

    // ---- OCR 层（区域 diff + 打字区裁剪 + 双开关）----

    /**
     * OCR 最小触发间隔（ms）。MBM v1.0.58：由 1200 降至 100，满足「每 0.1 秒 OCR 一次屏幕」
     * 的交付要求。实际触发仍受三重门控约束（不互相叠加）：
     *  1) 区域哈希去重：静止屏只做一次轻量 24×24 哈希比对，未变化直接跳过，不启动模型推理；
     *  2) inFlight 互斥：同一时刻仅一笔 OCR 在飞，超时/失败自动复位；
     *  3) 本间隔：屏幕变化时 OCR 频率上限提升到 10Hz（原 0.83Hz）。
     * 性能护栏：静态界面几乎零开销（仅哈希），仅在内容变化帧触发全量 PaddleOCR 推理。
     */
    const val OCR_MIN_INTERVAL_MS = 100L

    /**
     * 屏幕记忆（Recall）捕获文字是否注入输入法候选栏 / 自定义词组词库。
     *
     * v1.0.59 改回 true：Recall 的核心价值就是「屏幕所见 → 自动拆词 → 导入词库 → 可一键输入」，
     * v1.0.58 为响应「训练文字不出现在输入法」而误把整条导入链路关死，导致 OCR 识别到的
     * 应用名 / 游戏名等专名「拆词失败、且完全没导入词库」。自动导入由既有开关
     * `autoAddEnabled`（设置页「自动添加词组」）独立控制，此处只做总闸门。
     *
     * 用户约束「所有训练内容和文字都不要出现在任何的代码里」= 不把训练语料 / 特定词硬编码进源码；
     * 本开关与动态捕获导入是两回事，动态捕获不违反该约束。如需「只进屏幕记忆库、不出候选首位」，
     * 由 [RecallWordStore] 的冷热分层（HOT_ORDER/COLD_ORDER）控制，不改此处。
     */
    const val INJECT_INTO_IME = true

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

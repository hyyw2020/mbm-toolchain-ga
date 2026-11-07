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

    /**
     * 批量 reload 去抖窗口（ms）：落盘后在此窗口内合并多次请求。
     * v1.0.62：400 → 100 —— 用户硬要求「捕获后立即可打出」（0.1 秒级识别语义）；
     * reload 是进程内 re-parse（千级条目 <10ms），100ms 去抖已足够合并同帧多次写入。
     */
    const val RELOAD_DEBOUNCE_MS = 100L

    /** 两次 reload 的最小间隔（ms），防止高频抖动刷 reload。 */
    const val RELOAD_MIN_INTERVAL_MS = 250L

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

    // ---- 用户选择驱动的个性化优先级（预判 / 联想增强层，UserChoiceStore）----

    /**
     * 用户「主动选择候选上屏」驱动的个性化优先级档位。
     *
     * v1.0.66 关键修正：fcitx5 自定义词组的 order **不是权重，而是绝对候选位**。
     * 见 fcitx5-chinese-addons/pinyin.cpp：
     *   `CandidateOrder{result.order() - 1, ...}` —— order=1 才是第一候选；
     *   `if (result.order() <= 0) continue;` —— <=0 的条目直接被丢弃；
     *   自定义候选按 sortOrder 归一化后插到拼音候选流的第 (order-1) 个位置。
     * 因此 v1.0.63~65 用的 [4,6,7] 会把「安全」永远钉在第 4 位以后，无论用户选多少次
     * 都压不过 aq 的原生第一候选「爱情」（用户反馈「输入好几遍还是没到第一」）。
     *
     * 现改为 order=1：选中的词下次输入该码即第一候选。
     * 为何可用 1：其他模块 isOwnEntry 覆盖 2/8（应用名）、3/9（Recall）、3..7（个人短语），
     * 靠 order 区间识别自己的条目；PhrasePinStore 的手动置顶虽也是 1，但按 key|value 判定、
     * 不看 order。本模块改用「持久化 managed key=value 集合」识别自己的条目（不靠 order 区间），
     * 且 flush 时主动跳过已置顶条目，保证「手动置顶 > 自动学习」不冲突。
     *
     * v1.0.67：置顶归属改为「last-commit-wins」——每个键只记最近一次主动选择上屏的词，
     * 选一次即无条件写 order=1，不再按分数排序取 Top-1（详见 UserChoiceStore）。
     * 用户反馈「v1.0.66 还是要输好几遍才提高」的根因即旧打分竞争：同键下反复被默认
     * 上屏的高频词 count 领先，主动选一次的词 score 太低永远抢不到 order=1。
     */
    val CHOICE_ORDER_TIERS = listOf(1)

    /** v1.0.63~65 写入的旧档位，升级时由 UserChoiceStore.migrateLegacy 精确清理。 */
    val CHOICE_LEGACY_ORDER_TIERS = setOf(4, 6, 7)

    /**
     * 选择计数时间衰减半衰期（ms）：约 30 天。期间不被选，权重按 exp 自然回落到 1/e，
     * 避免长期冷词永久占坑、压制合理候选（对应「优先级需要优化」里的时间维度）。
     */
    const val CHOICE_DECAY_HALFLIFE_MS = 30L * 24 * 3600 * 1000

    /**
     * 上下文命中权重倍数：上屏词的上文 == 该记录的 prevWord 时相乘（bigram 个性化）。
     * 仅当「相同上文」出现时才抬升，避免 (aq→安全) 在所有语境都无差别前置（对应「上下文」维度）。
     */
    const val CHOICE_CTX_BOOST = 1.5

    /**
     * v1.0.67 起**不再参与置顶决策**（last-commit-wins 无条件写 order=1）。
     * 保留供历史统计 / 兼容旧版读取：原语义为「低于此分数的 boost 词条移出自定义词组」。
     */
    const val CHOICE_MIN_SCORE = 0.6

    /**
     * v1.0.67 起**不再参与置顶决策**（每键恒有唯一 winner）。
     * 保留供兼容：v1.0.66 曾用于「只把该码下分数最高的词提到第一候选」。
     */
    const val CHOICE_TOP_N = 1

    /** 持久选择记录硬上限（内存/落盘双控，超出按最弱分数淘汰）。 */
    const val CHOICE_RECORD_CAP = 5000

    // ---- v1.0.69 分层模型参数（硬层有序 Top-K 栈 + 软层融合打分，见 rank/ChoiceRanker.kt）----
    //
    // 设计裁决（详见 /workspace/candidate-priority-design.md）：
    // 不采用「同拼音全候选 score 降序」——它与用户核心规则「选择 >= 1 次即提高、
    // 最近一次选择必为第 1」数学上冲突，且本仓库 v1.0.63~66 已用真机视频实测证伪
    //（用户反馈「输好几遍才提高」）。改为：硬层 last-commit-wins 锁定冠军(order=1)，
    // 软层打分只作用于次位排序 / 衰减退役 / 淘汰，永不推翻冠军。

    /** 每键硬插入位数：冠军=1，其余历史词=2..K。K>=2 才能表达「爱情第1、安全第2」。 */
    const val CHOICE_TOP_K = 3

    /** 时间衰减半衰期（5 天，取需求建议 3~7 天中值）。仅软层；冠军永不因分数退役。 */
    const val CHOICE_DECAY_HALFLIFE_MS_V2 = 5L * 24 * 3600 * 1000

    /** 次数对数平滑封顶点：ln(1+n)/ln(1+N_CAP) 后 clamp 到 1，防热门词垄断。 */
    const val CHOICE_N_CAP = 20

    /** 冷启动预热样本数：达到后 β=1，软层全力度介入；之前平滑过渡。 */
    const val CHOICE_WARMUP_SAMPLES = 3

    /** 连续选择加成上限 / 系数（有界，防连点刷分）。 */
    const val CHOICE_STREAK_CAP = 3
    const val CHOICE_STREAK_W = 0.15

    /** 上下文共现加成系数：仅当上一条上屏词命中记录 prevWord 时 +ctxW。 */
    const val CHOICE_CTX_W = 0.5

    /** 一次疑似误选的软层惩罚扣减。 */
    const val CHOICE_MISCLICK_PENALTY = 0.8

    /** 选词后在此窗口内改选别的词，视为疑似误选。 */
    const val CHOICE_MISCLICK_MS = 1500L

    /** 同一 key=>word 在此窗口内重复上屏只刷新时间、不累加次数（防连点/长按）。 */
    const val CHOICE_DEDUP_MS = 800L

    /** 软层退役下限：非冠军且 score 低于此值即从栈移除（时间衰减）。 */
    const val CHOICE_MIN_SCORE_V2 = 0.15

    /**
     * v1.0.80 隐式负反馈（拒绝信号）阻尼系数：damp(rejected)=1/(1+rejectW·rejected)。
     * 被反复跳过的次位候选软层分被温和压制、更快沉底/退役，使个性化 Top-K 更贴合真实选择。
     * 仅作用于软层次位排序与衰减退役，永不推翻硬层冠军(order=1)。
     */
    const val CHOICE_REJECT_W = 0.25

    /** 拒绝信号计数上限：防止长期被跳过的词被过度压制（damp 下限 = 1/(1+rejectW·cap)）。 */
    const val CHOICE_REJECT_CAP = 32

    /** 单次上屏最大长度 / 最多连续汉字段数：超长按粘贴处理，不学习。 */
    const val CHOICE_MAX_COMMIT_LEN = 16
    const val CHOICE_MAX_RUNS = 8

    // ---- v1.0.70：硬层覆盖扩展到多字词 / 多字母键 ----
    //
    // 背景：用户反馈「哔哩哔哩输入优先级顺序有问题，总是因为 OCR 而被排为顺序 2」。
    // v1.0.69 硬层只认「2 字词 + 2 字母键」，而「哔哩哔哩」是 4 字词、首字母键 blbl
    // 是 4 字母，被 wordLen=2 / keyLen=2 两道过滤彻底挡在门外 ⇒ 用户主动选它也不写
    // order=1，只能吃 AppPhraseImporter(2) / PhraseAutoWriter(2) / RecallWordStore(3,9) /
    // PersonalPhraseImporter(3..7) 给的 order>=2；native `CandidateOrder{order-1,...}`
    // 下 order=2 即第 2 候选位 ⇒ 永远第 2。放宽后用户选一次即锁定 order=1 压过 OCR。

    /** 单次上屏为单一连续汉字段时，允许整段作为完整词学习的最大字数（覆盖 4 字应用名）。 */
    const val CHOICE_PHRASE_LEN_MAX = 8

    /** 键长度下限：2 排除单字母键（防 a 下 emoji 污染）。 */
    const val CHOICE_KEY_LEN_MIN = 2

    /** 键长度上限：容纳多字词首字母缩写键（blbl / qmqz ...）。 */
    const val CHOICE_KEY_LEN_MAX = 12

    // ---- v1.0.72：预测增强（全拼键覆盖 + 上下文下一词预测） ----
    //
    // 根因：native `pinyin.cpp` 用 `customPhrase_.lookup(pyBeforeCursor)` 精确匹配
    // 用户实际敲入的原始拼音串，而 v1.0.71 及以前所有模块只写「首字母缩写键」
    // （安全=aq）。习惯全拼的用户输入 anquan 时词库无此键，v1.0.66~71 全部
    // 个性化排序与 N-gram 预测完全不生效。修法：写词组时同一 order 额外挂全拼键。
    // 同时新增「用户还没选过、但根据刚上屏的词可高置信猜到下一词」的预测冠军，
    // 严格让位于用户硬层历史（hasHistory / hasUserWord）。

    /** 全拼键（及预测键）最大长度：8 字词全拼可达 30+，超长词仍只走首字母键。 */
    const val PREDICT_KEY_LEN_MAX = 24

    /** 单次预测输出的最大词条数；一个词可挂 2 个键，行数另由 maxEntries 约束。 */
    const val PREDICT_MAX_WORDS = 12

    /** 单次预测写入词库的最大行数（键膨胀护栏）。 */
    const val PREDICT_MAX_ENTRIES = 48

    /** bigram/trigram 共现计数门槛，过滤一次性噪声。 */
    const val PREDICT_MIN_CONFIDENCE = 2

    /** 每个上下文种子最多取多少个后继词。 */
    const val PREDICT_PER_SEED = 3
}

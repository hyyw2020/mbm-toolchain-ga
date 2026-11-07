/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 *
 * 剪贴板容量边界统一策略 —— 纯数据层，零 Android 依赖，可在 JVM 单测中验证。
 */
package org.fcitx.fcitx5.android.data.clipboard

/**
 * MBM v1.0.77：把散落在 6 处的硬编码上限收口到一处，并厘清语义。
 *
 * 关键区分（用户要求「无上限字数复制或粘贴」的准确落点）：
 *  - **存储 / 上屏 / 导出 / 分享：无上限**。单条文本超过
 *    [ClipboardBlobStore.INLINE_THRESHOLD] 外置 blob 文件（流式写入，绕开
 *    CursorWindow 2MB）；粘贴走 [ClipboardPasteChunker] 分块 commit（绕开
 *    Binder 1MB）；分享走 FileProvider URI。这几条路径**不受本文件任何常量约束**。
 *  - **UI 单次装载 / 单条系统剪贴板事务：必须有边界**，但这是内存与平台事务
 *    的物理约束，不是容量上限。本策略只负责两件事：① 让边界可配置；
 *    ② 保证任何边界触发时都明确披露，绝不静默丢字。
 *
 * 关于「系统剪贴板写入」：Binder 事务按 **UTF-16 码元**计费（每码元 2 字节），
 * 不能用 UTF-8 字节数估算（纯 ASCII 会低估 2 倍）。Kotlin/String 的
 * [String.length] 本身就是 UTF-16 码元数，因此 `length * 2` 即事务字节数。
 * 超过安全阈值时不静默失败，改走文件 URI 通道（见 [SystemClipboardCopy]）。
 */
object ClipboardCapacityPolicy {

    enum class CopyPlan {
        /** 文本可直接放进 ClipData.newPlainText，事务大小安全。 */
        PLAIN,
        /** 文本过大，须以 FileProvider URI 形式放入剪贴板并如实告知用户。 */
        FILE_URI
    }

    /**
     * 系统剪贴板纯文本事务的安全字符上限。200_000 UTF-16 码元 ≈ 400KB，
     * 给 Binder ~1MB 事务留一半以上余量（ClipDescription / Parcel 还有额外开销）。
     */
    const val PLAIN_CLIP_SAFE_CHARS = 200_000

    /**
     * UI 单次装载软边界（仅影响「一次读多少进内存 / 渲染多少词块」，
     * 不影响能存多少、能粘贴多少、能导出多少）：
     *  - 查看全文、拆分选词、编辑框共用同一个可配置偏好；
     *  - 默认值较 v1.0.76 的 100_000 翻倍到 200_000，且用户可在设置里调大；
     *  - 触发时 UI 必须显示「仅显示前部分」类提示，不得静默截断。
     */
    const val DEFAULT_UI_LOAD_CHARS = 200_000

    /** 拆分选词单批渲染的词块数上限（防止一次性创建海量 TextView 拖垮 measure/layout）。 */
    const val DEFAULT_CHIPS_PER_PAGE = 1500

    /** 编辑框可承载的正文软边界（EditText 全量载入，过大时禁用破坏性保存）。 */
    const val DEFAULT_EDIT_CHARS = 1_000_000

    /** Binder 事务计费：UTF-16 码元 × 2 字节。 */
    fun parcelBytes(text: String): Long = text.length.toLong() * 2L

    /** 按 UTF-16 计费判断该文本能否安全走纯文本剪贴板事务。 */
    fun copyPlan(
        text: String,
        safeChars: Int = PLAIN_CLIP_SAFE_CHARS
    ): CopyPlan = if (text.length <= safeChars) CopyPlan.PLAIN else CopyPlan.FILE_URI

    /**
     * 解析用户配置的装载上限：`<= 0` 表示使用内置默认软边界（推荐，内存安全），
     * `> 0` 表示用户主动放宽/收紧到该字符数。返回 always >= 1。
     */
    fun resolveUiLoadChars(configured: Int, default: Int = DEFAULT_UI_LOAD_CHARS): Int =
        if (configured > 0) configured else default.coerceAtLeast(1)

    /** 是否需要披露截断（已装载量小于总量）。 */
    fun needsDisclosure(loaded: Int, total: Long): Boolean = loaded.toLong() < total
}

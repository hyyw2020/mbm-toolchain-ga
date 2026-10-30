/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 离线 OCR 引擎：PaddleOCR PP-OCRv6(small) 模型 + ONNX Runtime Mobile。
 *
 * MBM v1.0.27：识别模型 PP-OCRv4(mobile) → PP-OCRv6(small)（2026-06-11 随
 * PaddleOCR 3.7.0 发布的上游最新版，small 档 7.7M 参数为官方移动端档位）。
 * 升级前的兼容性核实（见 docs/pitfalls.md #62）：
 *  - 输入名仍为 "x"，det 输出仍为全分辨率概率图，rec 输出类数 = 字典+2 → 管线不变
 *  - det 归一化从 v4 的 ImageNet 统计量改为 0.5/0.5（v5 起官方即如此，实测验证）
 *  - rec 预处理（48 高/动态宽/0.5/BGR）与 CTC 字典映射与 v4 完全一致
 *  - cls 方向分类继续用 v4 cls 模型（v6 无独立 cls 资产，RapidOCR v3.10 亦如此）
 *
 * MBM v1.0.28：修复旋转文本识别全空 + 宽间隙拆框两个缺陷（用户实报：
 * 「识别不到云·战双帕弥什，拆分有问题」，倾斜 10-15° 的游戏 logo 必现）：
 *  - orderPoints 改极角排序：旧按 y/x 排序在旋转角 × 框宽 > 框高
 *    （宽扁文本约 > 6.5°）时把底左角误判为顶角，角点顺序错乱
 *  - warpCrop 宽/高改欧氏边长：旧用轴对齐 x/y 差值，8° 时 648px 的
 *    文本宽实算成 21px，裁剪出细条、rec 返回空
 *  - detect 输出后增加同行框合并：「·」与两侧间隙 ≥ 0.7× 字高时 det
 *    拆两框，词组被截断；保守判据（同行 + 间隙 < 0.6× 字高）不误合并双行
 * 三者均经真实 PP-OCRv6 模型 + 复现工程（test_fix_matrix/test_merge）验证：
 * 修复前 ±6° 以上识别结果恒为空；修复后 -20°~+15° 全部有结果，宽间隙
 * 拆框合并后完整命中。
 *
 * MBM v1.0.29：同源缺陷第二轮扫描修复（scan_bugs.py，26 场景）：
 *  - 竖排文本支持：cls 只纠正 180°，竖排（高 > 2× 宽）是识别盲区，
 *    原样识别为空；recognizeCrop 对竖排 crop 双向 rot90 取高分
 *    （实测竖排「以弥止争」1.00 精确命中）
 *  - 同行合并加固：新增垂直投影重叠率 > 0.8 条件——水平错开的紧凑
 *    双行（对话 UI 长短不一）中心连线接近水平且间隙小，旧判据会误
 *    合并成一行导致 rec 乱码（已构造复现并验证拦截）
 *  - 扫描确认的模型极限（非代码缺陷，记录不修）：低对比度/紧凑场景
 *    偶发「文字→文学」形近字（句子相关，换句即对）；超长词（32 字）
 *    末端形近字与丢字（宽度上限 960→2560 均同结果，分段识别同样错）；
 *    描边艺术字「·→・」（不影响拼音键匹配）
 *
 * MBM v1.0.30：同源缺陷第三轮（docs/ocr-regression/scan_bugs.py，30 场景，
 * 复用真实 PP-OCRv6 模型 + fix_pipeline.py v1.0.30）：
 *  - det 后 NMS 去重：DB 在文字密集处常对一行文本输出多个相互重叠的连通域框，
 *    不抑制会导致重复行 / 拼接乱码（「拆分」类问题的真源头之一）。IoU 阈值
 *    仅抑制高度重叠的重复框，相邻但不同的同行框 IoU 很低，不会被误删。
 *  - 置信门控的多旋转回退（泛化旋转处理）：旧逻辑仅 cls 纠 180° + 竖排特例；
 *    cls 偶发判错或文本呈 90°/270° 任意朝向时漏识。改为 base（含 cls 180°）
 *    + rot90 + rot270 四向取最高分——不做「base 高置信即采信」短路（模型会
 *    「自信地错」，实测某 90° 文本 base 给「原神」0.9998，正确朝向 rot270
 *    仅 1.000，必须靠 max 而非阈值选对）。
 *  - IME 闭环的术语词典后校正（本项目独有优势）：OCR 直接喂给 fcitx5 中文
 *    输入法，用字符级编辑距离对游戏/UI 术语做离线纠错（如「战双帕弥件」→
 *    「战双帕弥什」）。仅对中等置信结果、且与某术语长度差 ≤1 且字符距离很小
 *    时替换，避免误伤通用文本（防「方舟→明日方舟」「战斗→原神」）。词典见
 *    assets/ocr/ocr_corrections.txt，用户可自由增删游戏名。
 *  - 全部改动经 30 场景扫描验证：无回归（8 个残留失败均为已知的模型形近字极限）。
 *
 * 流程与 RapidOCR 一致，全部在本设备完成，不联网：
 *   检测 DBText(det) → 方向分类(cls，可选) → 识别 CRNN-like(rec) → CTC 解码
 *
 * 不依赖 OpenCV：连通域、凸包、最小旋转矩形、多边形外扩、四点透视采样
 * 全部在本文件内实现。
 */
package org.fcitx.fcitx5.android.recall.ocr

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

object OcrEngine {

    private const val ASSET_DIR = "ocr"
    private const val DET_MODEL = "ch_PP-OCRv6_det_small.onnx"
    private const val REC_MODEL = "ch_PP-OCRv6_rec_small.onnx"
    private const val CLS_MODEL = "ch_ppocr_mobile_v2.0_cls_infer.onnx"
    private const val KEYS_FILE = "ppocrv6_dict.txt"

    // 检测分辨率策略（v1.0.20 精度/速度修复的核心）
    // PP-OCR det 是在「短边约 736」尺度上训练/调优的（v6 官方 limit_side_len=736/min）；
    // 对 2800×1280 这类高清长截图直接按原生分辨率入网，概率图会严重退化
    // （实测关键词召回仅 2.6%），且推理极慢。这里改为：过大→降到长边 1280；
    // 过小→短边补到 736；否则保持原生。实测同一批样图召回从 2.6%(原生)
    // 提升到 41.1%(长边 1280)。
    private const val DET_MAX_SIDE = 1280
    private const val DET_MIN_SIDE = 736
    // 低对比度/花哨背景（游戏 UI）上的小字，默认 0.3/0.5 会大量漏检，下调以召回。
    private const val DET_THRESH = 0.15f
    private const val DET_BOX_THRESH = 0.2f
    private const val DET_UNCLIP_RATIO = 1.6f
    private const val DET_MAX_CANDIDATES = 1000
    private const val REC_HEIGHT = 48
    private const val REC_WIDTH = 320
    // rec 模型宽度轴是动态的：长句按真实宽高比给宽，避免硬压到 320 把字压扁导致 CTC 解码错。
    private const val REC_WIDTH_MAX = 960
    private const val CLS_THRESH = 0.9f
    /** v1.0.29：竖排判定——裁剪高 > 宽 × 此值即视为竖排文本，走 rot90 双向识别 */
    private const val VERTICAL_RATIO = 2f
    /** v1.0.30：IME 闭环术语词典（每行一个游戏/UI 术语，用户可编辑扩充） */
    private const val OCR_CORRECTIONS_FILE = "ocr_corrections.txt"
    /** v1.0.30：det 后 NMS 的 IoU 抑制阈值（仅合并高度重叠的重复框，不伤相邻同行框） */
    private const val NMS_IOU_THRESH = 0.5

    data class Line(val text: String, val score: Float, val box: FloatArray)

    @Volatile
    private var ready = false

    @Volatile
    private var failure: String? = null

    private lateinit var env: OrtEnvironment
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var clsSession: OrtSession? = null
    private var keys: List<String> = emptyList()
    private var lexicon: List<String> = emptyList()

    /**
     * MBM v1.0.31「词库大小无上限」：术语词典长度分桶索引。
     * [Pair.first] = 文件序（平票时保持旧版全扫「先者胜」的 tie-break）。
     * 校正接受条件含「长度差 ≤ 1」（见 [correctWithLexicon] 安全约束），
     * 分桶后每次只算 3 个桶——百万级用户词典也是常数级候选，不再 O(N·L²) 全扫。
     */
    private var lexiconByLen: Map<Int, List<Pair<Int, String>>> = emptyMap()

    private val mutex = Mutex()

    /** 释放模型到私有目录并加载会话；线程安全，可重复调用 */
    suspend fun ensureReady(context: Context): Boolean {
        if (ready) return true
        failure?.let { return false }
        mutex.withLock {
            if (ready) return true
            return runCatching {
                val dir = extractModels(context)
                env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                detSession = env.createSession(File(dir, DET_MODEL).absolutePath, opts)
                recSession = env.createSession(File(dir, REC_MODEL).absolutePath, opts)
                clsSession = runCatching {
                    env.createSession(File(dir, CLS_MODEL).absolutePath, opts)
                }.getOrNull()
                keys = File(dir, KEYS_FILE).readLines()
                lexicon = File(dir, OCR_CORRECTIONS_FILE).readLines()
                    .map { it.trim() }.filter { it.isNotEmpty() }
                // 长度分桶：withIndex 保留文件序，groupBy 后桶内仍是文件序
                lexiconByLen = lexicon.withIndex().groupBy(
                    keySelector = { it.value.length },
                    valueTransform = { it.index to it.value }
                )
                ready = true
                true
            }.onFailure {
                failure = it.message
            }.getOrDefault(false)
        }
    }

    fun isReady() = ready
    fun lastFailure() = failure

    private fun extractModels(context: Context): File {
        val dir = File(context.filesDir, ASSET_DIR).also { it.mkdirs() }
        val names = listOf(DET_MODEL, REC_MODEL, CLS_MODEL, KEYS_FILE, OCR_CORRECTIONS_FILE)
        for (name in names) {
            val target = File(dir, name)
            if (target.exists() && target.length() > 0) continue
            context.assets.open("$ASSET_DIR/$name").use { input ->
                input.copyTo(target.outputStream())
            }
        }
        // 升级清理：删除不再属于本版本的模型文件（如 v4→v6 更换时留在
        // filesDir/ocr/ 的旧 .onnx，单个 10MB+，不清会永久占用用户存储）
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name !in names && !f.name.endsWith(".tmp")) {
                runCatching { f.delete() }
                    .onFailure { Timber.w(it, "删除过期 OCR 资产失败: ${f.name}") }
            }
        }
        return dir
    }

    /**
     * 识别整图，返回按从上到下排序的文本行。
     */
    suspend fun recognize(context: Context, bitmap: Bitmap, useDetector: Boolean = true): List<Line> {
        if (!ensureReady(context)) return emptyList()
        return mutex.withLock {
            runCatching { recognizeInternal(bitmap, useDetector) }.getOrDefault(emptyList())
        }
    }

    private fun recognizeInternal(bitmap: Bitmap, useDetector: Boolean): List<Line> {
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)

        val lines = mutableListOf<Line>()
        if (useDetector) {
            // det 后处理顺序：先「同行合并」(mergeSameRowBoxes)，再「NMS 去重」(nmsBoxes)，
            // 最后按从上到下排序。
            //   - 合并把被 DB 拆成多块的同一文本行并回一行，避免同一行被识别成多段；
            //   - NMS 仅对合并后仍残留的高度重叠重复框按 IoU 抑制（相邻但不同的同行框
            //     IoU 很低，不会被误删）。与文件头注释历史版本相反，此处是「先合并后 NMS」。
            //   注：30 场景回归套件中 DB 未产生可见的重叠重复框，NMS 实际抑制 0 框
            //   （IoU 逻辑有单测覆盖，详见 docs/pitfalls.md #65）；真实密集游戏截图是否会
            //   触发 DB 重叠框仍需真机验证。
            val boxes = nmsBoxes(mergeSameRowBoxes(detect(src)))
            val sorted = boxes.sortedWith(compareBy({ it.centerY() }, { it.centerX() }))
            for (quad in sorted) {
                val crop = warpCrop(src, quad)
                val r = recognizeCrop(crop) ?: continue
                // v1.0.30：IME 闭环术语词典后校正（字符级编辑距离，离线、零模型增量）
                val text = correctWithLexicon(r.first, r.second)
                val score = if (text == r.first) r.second else max(r.second, 0.9f)
                lines += Line(text, score, quad.toFloatArray())
                if (crop != src) crop.recycle()
            }
        } else {
            recognizeCrop(src)?.let { lines += Line(it.first, it.second, floatArrayOf()) }
        }
        return lines
    }

    // ---------------- 检测 ----------------

    private data class Resized(val bitmap: Bitmap, val ratioW: Float, val ratioH: Float)

    /**
     * 检测分辨率归一：
     *  - 长边 > DET_MAX_SIDE：等比缩小到长边 1280（高清截图的精度与速度关键）
     *  - 短边 < DET_MIN_SIDE：等比放大到短边 736（小裁剪图仍能看清小字）
     *  - 其余：保持原生分辨率
     * 两个分支互斥；边长对齐 32 的倍数。
     */
    private fun detResize(src: Bitmap): Resized {
        val w = src.width
        val h = src.height
        val longSide = max(w, h)
        val shortSide = min(w, h)
        val ratio = when {
            longSide > DET_MAX_SIDE -> DET_MAX_SIDE.toFloat() / longSide
            shortSide < DET_MIN_SIDE -> DET_MIN_SIDE.toFloat() / shortSide
            else -> 1f
        }
        val rw = max(32, (round(w * ratio / 32) * 32).toInt())
        val rh = max(32, (round(h * ratio / 32) * 32).toInt())
        val scaled = Bitmap.createScaledBitmap(src, rw, rh, true)
        return Resized(scaled, w.toFloat() / rw, h.toFloat() / rh)
    }

    // 检测(det)用 PP-OCRv6 官方 0.5/0.5 归一化；识别(rec)/方向(cls)用 PP-OCR 默认的 mean=std=0.5。
    // ⚠️ MBM v1.0.27：PP-OCRv4 时代 det 用 ImageNet 统计量（mean=[0.485,0.456,0.406]），
    //   自 PP-OCRv5 起官方 det 预处理改为 0.5/0.5（RapidOCR v3.10 config.yaml 实证：
    //   Det.mean=Det.std=[0.5,0.5,0.5]）。直接套用 v4 的 ImageNet 参数会让 v6 概率图
    //   整体偏移、文字框找不到（OCR 静默失效，见 docs/pitfalls.md #62）。
    // 三者通道顺序均为 BGR（与 RapidOCR cv2.imread 一致，PP-OCR 官方在 BGR 下训练），
    // 张量布局 plane0=B plane1=G plane2=R。归一化参数与通道顺序必须同时正确：
    // 任一项写错都会让检测概率图整体偏移、文字框找不到（OCR 静默失效，见 docs/pitfalls.md #24/#25）。
    private val DET_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
    private val DET_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
    private val UNIT_MEAN_STD = floatArrayOf(0.5f, 0.5f, 0.5f)

    private fun chwBgrNormalized(
        src: Bitmap,
        mean: FloatArray = UNIT_MEAN_STD,
        std: FloatArray = UNIT_MEAN_STD
    ): FloatArray {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(3 * w * h)
        val plane = w * h
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                val idx = y * w + x
                // BGR 张量布局（与官方 cv2.imread 对齐）：plane0=图B plane1=图G plane2=图R；
                // mean/std 按 [B,G,R] 顺序绑定（det 的 0.485→B 即此）。v1.0.14 前误把 R 写入
                // plane0 并套 0.485，等于把 R 当 B 归一化，概率图整体偏移（见 pitfalls.md #25）。
                out[idx] = ((c and 0xFF) / 255f - mean[0]) / std[0]                  // B 平面 ← 图 B
                out[plane + idx] = (((c shr 8) and 0xFF) / 255f - mean[1]) / std[1]  // G 平面 ← 图 G
                out[2 * plane + idx] = (((c shr 16) and 0xFF) / 255f - mean[2]) / std[2] // R 平面 ← 图 R
            }
        }
        return out
    }

    private fun detect(src: Bitmap): List<Quad> {
        val (scaled, ratioW, ratioH) = detResize(src)
        // det 用 v6 官方 0.5/0.5 归一化（v4 时代是 ImageNet，换模型必须同步换，见 pitfalls.md #62）
        val input = chwBgrNormalized(scaled, DET_MEAN, DET_STD)
        val session = detSession ?: return emptyList()

        val prob = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(input),
            longArrayOf(1, 3, scaled.height.toLong(), scaled.width.toLong())
        ).use { tensor ->
            session.run(mapOf("x" to tensor)).use { result ->
                val out = result[0] as OnnxTensor
                val shape = out.info.shape
                val mapH = shape[2].toInt()
                val mapW = shape[3].toInt()
                val buf = out.floatBuffer
                FloatArray(mapW * mapH).also { buf.get(it) }
            }
        }
        // det 模型输出与输入同分辨率的全分辨率概率图（v4 实测如此，v6 small 实测同样如此：
        // 输入 736×736 → 输出 (1,1,736,736)，见 docs/pitfalls.md #62）。
        // 概率图坐标即缩放图像素坐标，下游再乘 ratioW/ratioH 即回原图。
        val mapH = scaled.height
        val mapW = scaled.width
        val map = FloatArray(mapW * mapH)
        System.arraycopy(prob, 0, map, 0, min(prob.size, map.size))
        scaled.recycle()

        return dbPostProcess(map, mapW, mapH, ratioW, ratioH)
    }

    private fun dbPostProcess(
        prob: FloatArray, mapW: Int, mapH: Int, ratioW: Float, ratioH: Float
    ): List<Quad> {
        // 1) 二值化
        val mask = BooleanArray(mapW * mapH) { prob[it] > DET_THRESH }
        // 2) 膨胀（2x2）
        val dilated = BooleanArray(mapW * mapH)
        for (y in 0 until mapH) {
            for (x in 0 until mapW) {
                if (!mask[y * mapW + x]) continue
                for (dy in 0..1) for (dx in 0..1) {
                    val ny = y + dy
                    val nx = x + dx
                    if (ny < mapH && nx < mapW) dilated[ny * mapW + nx] = true
                }
            }
        }
        // 3) 连通域 → 凸包 → 最小旋转矩形
        val visited = BooleanArray(mapW * mapH)
        val result = mutableListOf<Quad>()
        val stack = IntArray(mapW * mapH)
        for (y0 in 0 until mapH) {
            for (x0 in 0 until mapW) {
                val start = y0 * mapW + x0
                if (!dilated[start] || visited[start]) continue
                var top = 0
                stack[top++] = start
                visited[start] = true
                val points = mutableListOf<Int>()
                while (top > 0) {
                    val cur = stack[--top]
                    points += cur
                    val cx = cur % mapW
                    val cy = cur / mapW
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx
                        val ny = cy + dy
                        if (nx < 0 || ny < 0 || nx >= mapW || ny >= mapH) continue
                        val ni = ny * mapW + nx
                        if (dilated[ni] && !visited[ni]) {
                            visited[ni] = true
                            stack[top++] = ni
                        }
                    }
                }
                if (points.size < 8) continue
                if (result.size >= DET_MAX_CANDIDATES) break

                val hull = convexHull(points.map { (it % mapW).toDouble() to (it / mapW).toDouble() })
                if (hull.size < 3) continue
                val rect = minAreaRect(hull)
                if (rect.third < 3) continue // 最小边 < min_size

                val corners = rect.first
                val score = boxScoreFast(prob, mapW, mapH, corners)
                if (score < DET_BOX_THRESH) continue

                val expanded = unclip(corners, rect.second)
                val quad = Quad(
                    expanded.map { (it.first * ratioW).toFloat() to (it.second * ratioH).toFloat() }
                )
                result += quad
            }
        }
        return result
    }

    /**
     * v1.0.28：同行框合并。
     *
     * 宽间隙会让 det 把一个词拆成两框——典型如游戏 logo「云·战双帕弥什」，
     * 「·」与任一侧间隙 ≥ 0.7× 字高时概率图在「·」处断开，拆出「云·」+
     * 「战双帕弥什」两框。RecallService 按行 join("\n") 后词组被截成两段，
     * 自定义词组的全拼匹配随之失效（用户报「拆分有问题」即此场景）。
     *
     * 判据（欧氏几何，旋转安全）：
     *  1. 两框中心连线与水平轴夹角 < 45°（基本同一行）
     *  2. 中心距 - 半宽和 < 0.6 × max(字高)（水平间隙小于 0.6 个字高）
     * 正常行间距 ≥ 1 个字高，双行文字不会误合并（菜单场景实测 4 行保持 4 框）。
     * 合并后取 8 点并集重新求凸包 + 最小旋转矩形。
     * 附带收益：合并后框更宽，rec 上下文更足，实测 60/80px 间隙场景
     * 由「战双帕弥件」提升为完整正确的「云·战双帕弥什」。
     */
    private fun mergeSameRowBoxes(boxes: List<Quad>): List<Quad> {
        val merged = boxes.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            var i = 0
            while (i < merged.size - 1) {
                var j = i + 1
                while (j < merged.size) {
                    val union = tryMergeSameRow(merged[i], merged[j])
                    if (union != null) {
                        val rect = minAreaRect(convexHull(union))
                        merged[i] = Quad(
                            rect.first.map { it.first.toFloat() to it.second.toFloat() }
                        )
                        merged.removeAt(j)
                        changed = true
                    } else {
                        j++
                    }
                }
                i++
            }
        }
        return merged
    }

    /** 同一行且水平间隙足够小则返回 8 点并集（Double），否则 null */
    private fun tryMergeSameRow(a: Quad, b: Quad): List<Pair<Double, Double>>? {
        val pa = a.points
        val pb = b.points
        if (pa.size != 4 || pb.size != 4) return null
        val dx = b.centerX() - a.centerX()
        val dy = b.centerY() - a.centerY()
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < 1e-6) return null
        // 条件 1：中心连线接近水平（夹角 < 45°）
        if (abs(dy) / dist > 0.707) return null
        // 条件 2（v1.0.29）：垂直投影重叠率 > 0.8（以 A 的文字方向为基准轴）。
        // 同一行拆开的框（词内宽间隙）重叠率 ~1.0；而水平错开的紧凑双行
        // （对话 UI 长短不一 / 缩进）中心连线可能接近水平、间隙也小，
        // 重叠率却 < 0.35——不拦下就会把两行并成一行，rec 输出乱码。
        val ax = (pa[1].first - pa[0].first).toDouble()
        val ay = (pa[1].second - pa[0].second).toDouble()
        val alen = sqrt(ax * ax + ay * ay)
        if (alen < 1e-6) return null
        val vx = -ay / alen
        val vy = ax / alen
        fun proj(p: Pair<Float, Float>) = p.first * vx + p.second * vy
        val aMin = pa.minOf { proj(it) }
        val aMax = pa.maxOf { proj(it) }
        val bMin = pb.minOf { proj(it) }
        val bMax = pb.maxOf { proj(it) }
        val overlap = min(aMax, bMax) - max(aMin, bMin)
        val hMin = min(aMax - aMin, bMax - bMin)
        if (hMin < 1e-6 || overlap / hMin < 0.8) return null
        fun edgeLen(p: List<Pair<Float, Float>>, i: Int, j: Int): Double {
            val ddx = (p[i].first - p[j].first).toDouble()
            val ddy = (p[i].second - p[j].second).toDouble()
            return sqrt(ddx * ddx + ddy * ddy)
        }
        val wa = (edgeLen(pa, 0, 1) + edgeLen(pa, 3, 2)) / 2
        val ha = (edgeLen(pa, 0, 3) + edgeLen(pa, 1, 2)) / 2
        val wb = (edgeLen(pb, 0, 1) + edgeLen(pb, 3, 2)) / 2
        val hb = (edgeLen(pb, 0, 3) + edgeLen(pb, 1, 2)) / 2
        // 条件 3：水平间隙 < 0.6 × 较高框的字高
        val gap = dist - (wa + wb) / 2.0
        if (gap < 0.6 * max(ha, hb)) {
            return pa.map { it.first.toDouble() to it.second.toDouble() } +
                pb.map { it.first.toDouble() to it.second.toDouble() }
        }
        return null
    }

    // ---------------- det 后 NMS 去重（v1.0.30） ----------------
    // 凸四边形可用 Sutherland-Hodgman 精确求交（无需外部几何库）。

    private fun polyArea(poly: List<Pair<Double, Double>>): Double {
        var s = 0.0
        val n = poly.size
        for (i in 0 until n) {
            val (x1, y1) = poly[i]
            val (x2, y2) = poly[(i + 1) % n]
            s += x1 * y2 - x2 * y1
        }
        return abs(s) / 2.0
    }

    private fun clipInside(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Boolean =
        (b.first - a.first) * (p.second - a.second) - (b.second - a.second) * (p.first - a.first) >= 0.0

    private fun clipIntersect(
        p1: Pair<Double, Double>, p2: Pair<Double, Double>,
        a: Pair<Double, Double>, b: Pair<Double, Double>
    ): Pair<Double, Double> {
        val dcx = a.first - b.first
        val dcy = a.second - b.second
        val dpx = p1.first - p2.first
        val dpy = p1.second - p2.second
        val n1 = a.first * b.second - a.second * b.first
        val n2 = p1.first * p2.second - p1.second * p2.first
        val den = dcx * dpy - dcy * dpx
        if (abs(den) < 1e-9) return p1
        return ((n1 * dpx - n2 * dcx) / den) to ((n1 * dpy - n2 * dcy) / den)
    }

    private fun clipPolygon(
        subject: List<Pair<Double, Double>>, clip: List<Pair<Double, Double>>
    ): List<Pair<Double, Double>> {
        var out = subject.toMutableList()
        val n = clip.size
        for (i in 0 until n) {
            val a = clip[i]
            val b = clip[(i + 1) % n]
            val inp = out
            out = mutableListOf()
            if (inp.isEmpty()) break
            var s = inp.last()
            for (p in inp) {
                if (clipInside(p, a, b)) {
                    if (!clipInside(s, a, b)) out.add(clipIntersect(s, p, a, b))
                    out.add(p)
                } else if (clipInside(s, a, b)) {
                    out.add(clipIntersect(s, p, a, b))
                }
                s = p
            }
        }
        return out
    }

    private fun polyIou(a: Quad, b: Quad): Double {
        val pa = a.points.map { it.first.toDouble() to it.second.toDouble() }
        val pb = b.points.map { it.first.toDouble() to it.second.toDouble() }
        val inter = clipPolygon(pa, pb)
        if (inter.size < 3) return 0.0
        val ai = polyArea(inter)
        if (ai <= 0.0) return 0.0
        val union = polyArea(pa) + polyArea(pb) - ai
        return if (union > 0.0) ai / union else 0.0
    }

    /** IoU-NMS：按面积降序，重叠度过高的低优先框被抑制。返回保留的框。 */
    private fun nmsBoxes(boxes: List<Quad>, iouThresh: Double = NMS_IOU_THRESH): List<Quad> {
        val order = boxes.indices.sortedByDescending { polyArea(boxes[it].points.map { p -> p.first.toDouble() to p.second.toDouble() }) }
        val keep = mutableListOf<Quad>()
        val suppressed = BooleanArray(boxes.size)
        for (i in order) {
            if (suppressed[i]) continue
            keep.add(boxes[i])
            for (j in order) {
                if (j == i || suppressed[j]) continue
                if (polyIou(boxes[i], boxes[j]) > iouThresh) suppressed[j] = true
            }
        }
        return keep
    }

    /** 四个角点表示的多边形（顺序：左上、右上、右下、左下） */
    class Quad(val points: List<Pair<Float, Float>>) {
        fun toFloatArray(): FloatArray {
            val arr = FloatArray(8)
            points.forEachIndexed { i, p ->
                arr[i * 2] = p.first
                arr[i * 2 + 1] = p.second
            }
            return arr
        }

        fun centerX() = points.map { it.first }.average().toFloat()
        fun centerY() = points.map { it.second }.average().toFloat()
    }

    /** 平均概率（ rápido 模式：多边形内像素均值） */
    private fun boxScoreFast(
        prob: FloatArray, w: Int, h: Int, corners: List<Pair<Double, Double>>
    ): Float {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        corners.forEach { (x, y) ->
            minX = min(minX, x.toInt()); maxX = max(maxX, x.toInt())
            minY = min(minY, y.toInt()); maxY = max(maxY, y.toInt())
        }
        minX = max(0, minX); maxX = min(w - 1, maxX)
        minY = max(0, minY); maxY = min(h - 1, maxY)
        if (minX > maxX || minY > maxY) return 0f
        var sum = 0f
        var count = 0
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val px = x.toDouble()
                val py = y.toDouble()
                if (pointInPolygon(px, py, corners)) {
                    sum += prob[y * w + x]
                    count++
                }
            }
        }
        return if (count == 0) 0f else sum / count
    }

    private fun pointInPolygon(x: Double, y: Double, poly: List<Pair<Double, Double>>): Boolean {
        var inside = false
        var i = 0
        var j = poly.size - 1
        while (i < poly.size) {
            val (xi, yi) = poly[i]
            val (xj, yj) = poly[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi) {
                inside = !inside
            }
            j = i++
        }
        return inside
    }

    /** Andrew monotone chain */
    private fun convexHull(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val pts = points.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (pts.size < 3) return pts
        fun cross(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>) =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)

        val lower = mutableListOf<Pair<Double, Double>>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) {
                lower.removeAt(lower.lastIndex)
            }
            lower += p
        }
        val upper = mutableListOf<Pair<Double, Double>>()
        for (i in pts.size - 1 downTo 0) {
            val p = pts[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) {
                upper.removeAt(upper.lastIndex)
            }
            upper += p
        }
        return (lower.dropLast(1) + upper.dropLast(1))
    }

    /**
     * 旋转卡壳求最小外接矩形
     * @return Triple(四角点, 面积, 短边长度)
     */
    private fun minAreaRect(hull: List<Pair<Double, Double>>): Triple<List<Pair<Double, Double>>, Double, Double> {
        var bestAngle = 0.0
        var bestArea = Double.MAX_VALUE
        var bestBox: DoubleArray? = null
        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            val dx = b.first - a.first
            val dy = b.second - a.second
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-9) continue
            val angle = kotlin.math.atan2(dy, dx)
            val cosA = cos(-angle)
            val sinA = sin(-angle)
            var minX = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for (p in hull) {
                val rx = p.first * cosA - p.second * sinA
                val ry = p.first * sinA + p.second * cosA
                minX = min(minX, rx); maxX = max(maxX, rx)
                minY = min(minY, ry); maxY = max(maxY, ry)
            }
            val area = (maxX - minX) * (maxY - minY)
            if (area < bestArea) {
                bestArea = area
                bestAngle = angle
                bestBox = doubleArrayOf(minX, maxX, minY, maxY)
            }
        }
        val box = bestBox ?: return Triple(hull, 0.0, 0.0)
        val cosA = cos(bestAngle)
        val sinA = sin(bestAngle)
        fun rotateBack(x: Double, y: Double) = (x * cosA - y * sinA) to (x * sinA + y * cosA)
        val (minX, maxX, minY, maxY) = listOf(box[0], box[1], box[2], box[3])
        val corners = listOf(
            rotateBack(minX, minY), rotateBack(maxX, minY),
            rotateBack(maxX, maxY), rotateBack(minX, maxY)
        )
        val shortSide = min(maxX - minX, maxY - minY)
        return Triple(orderPoints(corners), bestArea, shortSide)
    }

    /**
     * 按 左上、右上、右下、左下 排序。
     *
     * v1.0.28：极角排序（绕质心 atan2 + 从 x+y 最小的角起顺时针），替代旧的
     * 按 y/x 排序。旧法在倾角较大时会把底左角误判为顶角——旋转角 × 框宽
     * 超过框高（宽扁文本约 > 6.5°）时底左角的 y 可能小于顶右角，导致
     * 角点顺序错乱，后续 warpCrop 裁剪尺寸算错，识别结果恒为空。
     * 典型受害场景：倾斜 10-15° 的游戏 logo（如「云·战双帕弥什」）。
     */
    private fun orderPoints(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val cx = points.sumOf { it.first } / points.size
        val cy = points.sumOf { it.second } / points.size
        val angular = points.sortedBy { atan2(it.second - cy, it.first - cx) }
        val start = angular.indices.minByOrNull { angular[it].first + angular[it].second } ?: 0
        return (0 until angular.size).map { angular[(start + it) % angular.size] }
    }

    /** 沿矩形自身轴向外扩张（等价于 RapidOCR 的 unclip） */
    private fun unclip(
        corners: List<Pair<Double, Double>>, area: Double
    ): List<Pair<Double, Double>> {
        fun len(a: Pair<Double, Double>, b: Pair<Double, Double>) =
            sqrt((a.first - b.first).let { it * it } + (a.second - b.second).let { it * it })

        val perimeter = (0 until 4).sumOf { len(corners[it], corners[(it + 1) % 4]) }
        if (perimeter < 1e-6) return corners
        val distance = area * DET_UNCLIP_RATIO / perimeter / 2.0
        val cx = corners.sumOf { it.first } / 4.0
        val cy = corners.sumOf { it.second } / 4.0
        return corners.map { (x, y) ->
            val vx = x - cx
            val vy = y - cy
            val norm = sqrt(vx * vx + vy * vy)
            if (norm < 1e-6) x to y
            else x + vx / norm * distance to y + vy / norm * distance
        }
    }

    // ---------------- 方向分类 ----------------

    private fun shouldRotate(crop: Bitmap): Boolean {
        val session = clsSession ?: return false
        val resized = Bitmap.createScaledBitmap(crop, 192, 48, true)
        val input = chwBgrNormalized(resized)
        resized.recycle()
        return OnnxTensor.createTensor(
            env, FloatBuffer.wrap(input), longArrayOf(1, 3, 48, 192)
        ).use { tensor ->
            session.run(mapOf("x" to tensor)).use {
                val out = (it[0] as OnnxTensor).floatBuffer
                val probs = FloatArray(2).also { arr -> out.get(arr) }
                probs[1] > CLS_THRESH && probs[1] > probs[0]
            }
        }
    }

    // ---------------- 识别 ----------------

    private fun recognizeCrop(crop: Bitmap): Pair<String, Float>? {
        // v1.0.29：竖排支持——cls 方向分类只纠正 180°，竖排文本（游戏古风
        // UI / 竖排标题）高 > 2× 宽时双向 rot90 识别，取高分。实测竖排
        // 「以弥止争」原样识别为空，rot90 后 1.00 精确命中。单字（正方）
        // 不触发此分支。
        if (crop.height > crop.width * VERTICAL_RATIO) {
            var best: Pair<String, Float>? = null
            for (clockwise in booleanArrayOf(true, false)) {
                val rotated = rotate90(crop, clockwise)
                val r = recognizeOriented(rotated)
                if (rotated !== crop) rotated.recycle()
                if (r != null && (best == null || r.second > best.second)) best = r
            }
            return best
        }
        // v1.0.30：置信门控的多旋转回退（泛化旋转处理）。
        // base 已含 cls 纠 180°；cls 无法纠正 90°/270°，故补上这两向，四向取最高分。
        // 不做「base 高置信即采信」短路——模型会「自信地错」（实测某 90° 文本
        // base 给「原神」0.9998，正确朝向 rot270 仅 1.000，必须靠 max 而非阈值选对）。
        val cands = mutableListOf<Pair<String, Float>>()
        recognizeOriented(crop)?.let { cands += it }
        for (clockwise in booleanArrayOf(true, false)) {
            val rotated = rotate90(crop, clockwise)
            recognizeOriented(rotated)?.let { cands += it }
            if (rotated !== crop) rotated.recycle()
        }
        return cands.maxByOrNull { it.second }
    }

    /** 单朝向识别：cls 纠 180° + rec CTC（不含旋转/竖排分支，由 recognizeCrop 调度） */
    private fun recognizeOriented(crop: Bitmap): Pair<String, Float>? {
        val session = recSession ?: return null
        val oriented = if (shouldRotate(crop)) rotate180(crop) else crop

        val ratio = oriented.width.toFloat() / oriented.height.toFloat()
        // v1.0.20：宽度按真实宽高比取，上限 960（rec 模型宽度轴是动态的）。
        // 旧实现一律压到 320，长句会被横向压扁数倍，CTC 解码必错。
        val targetW = min(REC_WIDTH_MAX, max(8, ceil(REC_HEIGHT * ratio).toInt()))
        val resized = Bitmap.createScaledBitmap(oriented, targetW, REC_HEIGHT, true)

        // 归一化（宽度即 targetW，无需补零）
        // ⚠️ v1.0.21 修复（代码可证实的真 bug，见 docs/pitfalls.md #36）：
        //   张量行宽实际是动态 targetW，行步长必须用 targetW；旧实现误用常量 REC_WIDTH(320)，
        //   当 targetW≠320（长句动态宽度可达 960）时 B/G/R 三平面写入错位，长行识别整段乱码，
        //   直接抵消 v1.0.20 的「识别动态宽度」修复。此处 idx 步长改回 y*targetW+x。
        val input = FloatArray(3 * REC_HEIGHT * targetW)
        val pixels = IntArray(targetW * REC_HEIGHT)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, REC_HEIGHT)
        val plane = REC_HEIGHT * targetW
        for (y in 0 until REC_HEIGHT) {
            for (x in 0 until targetW) {
                val c = pixels[y * targetW + x]
                val idx = y * targetW + x
                // BGR 张量布局（rec 模型同样在 BGR 下训练）：plane0=图B plane1=图G plane2=图R
                input[idx] = ((c and 0xFF) / 255f - 0.5f) / 0.5f
                input[plane + idx] = (((c shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
                input[2 * plane + idx] = (((c shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
            }
        }
        resized.recycle()
        if (oriented !== crop) oriented.recycle()

        return OnnxTensor.createTensor(
            env, FloatBuffer.wrap(input), longArrayOf(1, 3, REC_HEIGHT.toLong(), targetW.toLong())
        ).use { tensor ->
            session.run(mapOf("x" to tensor)).use { result ->
                val out = result[0] as OnnxTensor
                val shape = out.info.shape
                val time = shape[1].toInt()
                val classes = shape[2].toInt()
                val buf = out.floatBuffer
                ctcDecode(buf, time, classes)
            }
        }
    }

    // ---------------- IME 闭环术语词典后校正（v1.0.30） ----------------

    /** 字符级编辑距离（Levenshtein） */
    private fun charEditDistance(a: String, b: String): Int {
        if (a == b) return 0
        val la = a.length
        val lb = b.length
        var dp = IntArray(lb + 1) { it }
        for (i in 1..la) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..lb) {
                val cur = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = cur
            }
        }
        return dp[lb]
    }

    /**
     * 利用 fcitx5 输入法的闭环优势做离线术语校正：OCR 错多为形近（如
     * 「战双帕弥件」→「战双帕弥什」仅 1 字之差），故以字符级编辑距离度量。
     * 触发条件：rec 至少输出了连贯内容（score>=0.4，不做「高置信跳过」——
     * 模型「自信地错」恰是最该校正的情况）。安全约束防误伤通用文本：
     *  - 错误数随长度缩放（每 ~3 字容 1 错）
     *  - 至少半数字符一致
     *  - 长度差 <= 1（防「方舟」误扩为「明日方舟」、防「战斗」被近形「原神」吞掉）
     * 词典见 assets/ocr/ocr_corrections.txt，用户可自由增删游戏/UI 术语（大小无上限）。
     *
     * MBM v1.0.31：改长度分桶扫描（[lexiconByLen]），只算长度差 ≤1 的三个桶。
     * 语义细化：旧版全扫中，远长度词唯一的作用是「以更小距离抢占最佳位、再被
     * 长度条件拒绝」（制造漏校正）；分桶后 = 「最近的近长度词足够近才校正」，
     * 与上面防误伤约束的意图一致。平票仍按文件序（旧版先者胜）。
     */
    private fun correctWithLexicon(text: String, score: Float): String {
        if (text.isEmpty() || score < 0.4f) return text
        var bestTerm: String? = null
        var bestD = Int.MAX_VALUE
        var bestIdx = Int.MAX_VALUE
        for (l in text.length - 1..text.length + 1) {
            val bucket = lexiconByLen[l] ?: continue
            for ((idx, term) in bucket) {
                val d = charEditDistance(text, term)
                if (d < bestD || (d == bestD && idx < bestIdx)) {
                    bestD = d
                    bestTerm = term
                    bestIdx = idx
                }
            }
        }
        val bt = bestTerm
        if (bt != null && bt != text
            && bestD <= max(1, text.length / 3)
            && bestD * 2 <= text.length
            && abs(text.length - bt.length) <= 1
        ) {
            return bt
        }
        return text
    }

    /** CTC greedy 解码 + 置信度 */
    private fun ctcDecode(buffer: FloatBuffer, time: Int, classes: Int): Pair<String, Float> {
        val sb = StringBuilder()
        var prev = -1
        var scoreSum = 0f
        var count = 0
        for (t in 0 until time) {
            var bestIdx = 0
            var bestVal = Float.NEGATIVE_INFINITY
            for (c in 0 until classes) {
                val v = buffer[t * classes + c]
                if (v > bestVal) {
                    bestVal = v
                    bestIdx = c
                }
            }
            if (bestIdx != 0 && bestIdx != prev) {
                sb.append(charOf(bestIdx))
                scoreSum += bestVal
                count++
            }
            prev = bestIdx
        }
        val score = if (count == 0) 0f else scoreSum / count
        return sb.toString() to score
    }

    /**
     * 字典索引：0 = blank，1..N = ppocrv6_dict.txt 各行，N+1 = 空格
     * （与 PaddleOCR/RapidOCR 的 CTCLabelDecode 一致：dict + " " + blank 前插）
     */
    private fun charOf(index: Int): String {
        if (keys.isEmpty()) return ""
        if (index - 1 >= 0 && index - 1 < keys.size) return keys[index - 1]
        return if (index == keys.size + 1) " " else ""
    }

    // ---------------- 图像工具 ----------------

    /**
     * 四点采样形变到矩形（双线性四边形映射）。
     * v1.0.20：不再把裁剪分辨率砍半，也不再用最近邻 —— 游戏 UI 上约 20px 高的小字
     * 会被砍到 10px 再放大到 48，必然糊成一团（识别成乱码的主因之一）。
     * v1.0.28：裁剪宽/高改用四边形的欧氏边长之和（上边+下边、左边+右边），
     * 不再用轴对齐的 x/y 差值——旋转角 > ~6.5° 的宽扁框，差值法会把宽算成
     * 接近 0（8° 时 648px 的文本实测只剩 21px），裁剪出细条导致识别为空。
     * pts 顺序为 左上、右上、右下、左下（与 orderPoints 一致）。
     */
    private fun warpCrop(src: Bitmap, quad: Quad): Bitmap {
        val pts = quad.points
        if (pts.size != 4) return src
        fun edgeLen(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
            val dx = a.first - b.first
            val dy = a.second - b.second
            return sqrt(dx * dx + dy * dy)
        }
        val width0 = edgeLen(pts[0], pts[1]) + edgeLen(pts[3], pts[2])
        val height0 = edgeLen(pts[0], pts[3]) + edgeLen(pts[1], pts[2])
        val w = max(8, width0.toInt().coerceAtMost(src.width))
        val h = max(8, height0.toInt().coerceAtMost(src.height))

        val srcW = src.width
        val srcH = src.height
        val srcPixels = IntArray(srcW * srcH)
        src.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
        val out = IntArray(w * h)
        for (v in 0 until h) {
            val tv = v.toFloat() / max(1, h - 1)
            val leftX = pts[0].first + (pts[3].first - pts[0].first) * tv
            val leftY = pts[0].second + (pts[3].second - pts[0].second) * tv
            val rightX = pts[1].first + (pts[2].first - pts[1].first) * tv
            val rightY = pts[1].second + (pts[2].second - pts[1].second) * tv
            for (u in 0 until w) {
                val tu = u.toFloat() / max(1, w - 1)
                val fx = leftX + (rightX - leftX) * tu
                val fy = leftY + (rightY - leftY) * tu
                val x0 = fx.toInt().coerceIn(0, srcW - 1)
                val y0 = fy.toInt().coerceIn(0, srcH - 1)
                val x1 = min(x0 + 1, srcW - 1)
                val y1 = min(y0 + 1, srcH - 1)
                val ax = (fx - x0).coerceIn(0f, 1f)
                val ay = (fy - y0).coerceIn(0f, 1f)
                out[v * w + u] = bilinearARGB(
                    srcPixels[y0 * srcW + x0], srcPixels[y0 * srcW + x1],
                    srcPixels[y1 * srcW + x0], srcPixels[y1 * srcW + x1], ax, ay
                )
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 四像素双线性插值（ARGB 各通道分别插值） */
    private fun bilinearARGB(c00: Int, c10: Int, c01: Int, c11: Int, ax: Float, ay: Float): Int {
        val a = (c00 ushr 24) and 0xFF
        val r = (c00 shr 16) and 0xFF
        val g = (c00 shr 8) and 0xFF
        val b = c00 and 0xFF
        val r1 = (c10 shr 16) and 0xFF
        val g1 = (c10 shr 8) and 0xFF
        val b1 = c10 and 0xFF
        val r2 = (c01 shr 16) and 0xFF
        val g2 = (c01 shr 8) and 0xFF
        val b2 = c01 and 0xFF
        val r3 = (c11 shr 16) and 0xFF
        val g3 = (c11 shr 8) and 0xFF
        val b3 = c11 and 0xFF
        val w00 = (1f - ax) * (1f - ay)
        val w10 = ax * (1f - ay)
        val w01 = (1f - ax) * ay
        val w11 = ax * ay
        val rr = (r * w00 + r1 * w10 + r2 * w01 + r3 * w11).toInt().coerceIn(0, 255)
        val gg = (g * w00 + g1 * w10 + g2 * w01 + g3 * w11).toInt().coerceIn(0, 255)
        val bb = (b * w00 + b1 * w10 + b2 * w01 + b3 * w11).toInt().coerceIn(0, 255)
        return (a shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    private fun rotate180(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            out[(h - 1 - y) * w + (w - 1 - x)] = pixels[y * w + x]
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** v1.0.29：竖排 rot90（手写像素旋转，同 rotate180 理由避开 Matrix） */
    private fun rotate90(src: Bitmap, clockwise: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            // 新图尺寸为 h × w（宽高互换）
            val nx = if (clockwise) h - 1 - y else y
            val ny = if (clockwise) x else w - 1 - x
            out[ny * h + nx] = pixels[y * w + x]
        }
        return Bitmap.createBitmap(out, h, w, Bitmap.Config.ARGB_8888)
    }

    fun release() {
        runCatching { detSession?.close() }
        runCatching { recSession?.close() }
        runCatching { clsSession?.close() }
        detSession = null
        recSession = null
        clsSession = null
        ready = false
    }
}

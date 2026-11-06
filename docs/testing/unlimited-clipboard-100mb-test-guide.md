# 「无上限剪贴板」100MB 实测指引

> 版本：MBM v1.0.31（对应 commit 见文末）
> 设计文档：`docs/design/unlimited-clipboard-design.md`
> 核心目标：**复制/导入 100MB 文字 → 粘贴出完整 100MB 原文，一字不差**；
> 历史记录、词库、拆分字数同样无上限。

---

## 1. 安装物

| APK | 路径 | 说明 |
| --- | --- | --- |
| 主输入法 | `app/build/outputs/apk/debug/` 下 `...-arm64-v8a-debug.apk` | 剪贴板 blob 存储 + 分块粘贴 + 文件导入 |
| Recall 插件 | `plugin/recall/build/outputs/apk/debug/` 下 `...-arm64-v8a-debug.apk` | 屏幕捕获历史（分页浏览 + 导出全文） |

安装后：

1. 主输入法：设置 → 语言和输入法 → 启用 Fcitx → 设为默认；
2. Recall 插件（可选，测历史无上限）：安装后开启无障碍服务；
3. 两个 APK 都是 debug 签名，可直接覆盖安装。

## 2. 生成 100MB 测试文件

在电脑或手机 Termux 里任选一种：

```bash
# 方法 A：纯 ASCII 可打印字符（最快，~1 秒）
python3 -c "
import sys
pat = bytes((32 + i % 95) for i in range(95))
with open('test100mb.txt','wb') as f:
    written = 0
    block = pat * 1092            # ~103KB/块
    while written < 100*1024*1024:
        f.write(block); written += len(block)
"

# 方法 B：中文 UTF-8（更接近真实场景，验证多字节字符边界）
python3 -c "
unit = '中文测试abc😀混合XYZ🎉结尾'   # 含 3 字节汉字 + 4 字节 emoji
with open('test100mb_cn.txt','w',encoding='utf-8') as f:
    written = 0
    while written < 100*1024*1024:
        s = unit * 2600            # ~99KB/轮
        f.write(s); written += len(s.encode())
"

# 记录原始哈希（后面一字不差校验用）
sha256sum test100mb.txt test100mb_cn.txt
```

> 建议两种都测：A 验证吞吐与字节级一致，B 专门砸 UTF-8 跨块边界。

## 3. 路径 A：主输入法「文件导入 → 分块粘贴」（100MB 主通道）

为什么走文件导入：Android 系统剪贴板单次事务约 1MB（Binder 限制），
从别处「复制」100MB 在系统层就过不来；导入通道流式读取，是完整方案。

1. 把 `test100mb.txt` 拷到手机存储；
2. 任意输入框唤起主输入法 → 顶部工具栏进入**剪贴板面板**；
3. 点面板上的**导入按钮**（↩ 样式图标）→ 系统文件选择器选 `test100mb.txt`；
4. 预期 toast：`已导入 N 字节`（100MB ≈ 104857600 字节）；
5. 剪贴板列表出现新条目，摘要显示前 256 字符；
6. 点条目粘贴，预期 toast（按耗时 10 秒 ~ 1 分钟）：
   - 成功：`已粘贴 100 MB，校验通过`
   - 进度：每 32MB 弹一次 `正在粘贴… X/100 MB`

### 3.1 「一字不差」校验

1. 手机装一个能吃大文件的纯文本编辑器（如 Markor、QuickEdit）；
2. 粘贴后**全选 → 保存为文件**；
3. 比对哈希（Termux）：

```bash
sha256sum /sdcard/Download/粘贴结果.txt   # 应等于第 2 步记录的哈希
```

> 注意：部分第三方编辑器输入框有长度上限，粘进去被截断是**目标应用**的限制，
> 不是本输入法丢内容——此时 toast 会提示「目标输入框可能截断了内容」。
> 一字不差校验请在无长度限制的编辑器里做。

## 4. 路径 B：Recall 历史无上限

1. 打开 Recall 插件 → 开启无障碍捕获；
2. 在支持分享的应用里分享文本到 Recall，或用导入入口收录大文本；
3. 历史列表**keyset 分页**浏览：上滑距底一屏自动加载下一页（每页 300 条），
   不整表进内存；`keepDays` 默认 0 = 永久保留（不再有 30 天硬编码清理）；
4. 勾选记录 →「导出为文本」：流式写出全文到
   `Android/data/org.fcitx.fcitx5.android.plugin.recall/files/Download/`；
5. 勾选 →「导入到输入法」：选中总量 ≤ ~900KB 走系统剪贴板；
   超过则**不截断**，改为写缓存文件 + 系统分享面板交出完整内容；
6. 勾选 →「导出为词组」：大文本逐行流式提炼，无条数上限。

## 5. 预期 toast / 行为对照表

| 场景 | 预期 |
| --- | --- |
| 导入 100MB 文件 | `已导入 104857600 字节` |
| 粘贴 100MB 完成 | `已粘贴 100 MB，校验通过` |
| 目标输入框有 maxLength | `已粘贴 X MB，但目标输入框可能截断了内容（长度限制？）` |
| blob 文件丢失 | `条目文件丢失，无法粘贴` |
| 某块 commit 失败 | `粘贴中断：已粘贴 X/Y MB（请重试）` |
| 小文本（<256KB） | 行为与旧版完全一致（原 commitText 快路径） |
| 相同内容再导入 | 内容寻址去重，不产生第二份文件 |

## 6. JVM 单测覆盖（51 个，新增 24 个）

```
./gradlew :app:testDebugUnitTest :plugin:recall:testDebugUnitTest
```

| 测试类 | 覆盖 |
| --- | --- |
| `ClipboardPasteChunkerTest`（10） | UTF-8 跨块边界（1/2/3/4/5/7/13/64 字节块）、拼接==原文、失败即停、读流容错、**100MB 规模逐字节校验（400 块）**、尾部校验 |
| `ClipboardBlobStoreTest`（7） | sha256 内容寻址、preview 截断、.tmp 清理、孤儿回收、**100MB 流式往返零差异** |
| `RecallBlobStoreTest`（7） | 同构覆盖（写入/读回/去重/清理/GC/100MB） |

## 7. 「词库大小无上限」实测

1. **自定义词组（用户词典）**：设置 → 用户词库 → 自定义词组；
   - 落盘**不再有 100MB 裁剪**（旧版超限会从尾部静默丢词，pitfalls #71）；
   - 估算超过 100MB 时保存后提示「词库约 X MB / N 条，已全量保存」；
   - 保存失败会精确报错（N 条 / 约 X MB），内容不丢、可重试；
   - 长按候选 →「从自定义词组中删除」走进程内快照，大词库不再每次全量 JNI load。
2. **OCR 术语词典**：Recall 插件 `assets/ocr/ocr_corrections.txt` 可自由增删；
   校正改长度分桶扫描（只算长度差 ≤1 的三桶），百万级词典也是常数级候选。
3. **词组提炼**：捕获自动成词与手动「导出为词组」均无条数上限
   （旧 `PHRASE_HARD_LIMIT=200` / `MAX_LEN=200` / `MAX_PER_CAPTURE=3` /
   `MAX_TEXT_LENGTH=4000` 已全部移除）。

## 8. 已知平台边界（绕行，不是逃避）

| 平台限制 | 现象 | 本方案绕行 |
| --- | --- | --- |
| CursorWindow 2MB | 单条文本 >~1MB 查询即崩 | 大文本外置 blob 文件，DB 行只存元数据 |
| Binder ~1MB | `setPrimaryClip`/`commitText` 大文本抛 TransactionTooLarge | 分块 commitText（256KB/块）+ 文件导入/分享通道 |
| 目标应用 maxLength | 粘贴被静默截断 | 尾部 64 字节比对，启发式告警（不误报） |

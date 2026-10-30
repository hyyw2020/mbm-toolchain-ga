# 剪贴板 / 历史记录 / 词库 / 拆分「无上限」重新设计方案

> 版本：v1（2026-10-25）· 状态：**设计已定稿，待实施**
> 触发：用户要求「剪贴板支持无上限复制粘贴（如 100MB 文字）、历史记录无上限、词库大小无上限、拆分字数无上限」，
> 并明确「不允许以实现困难为由逃避目标」。
> 本文件是重新设计（redesign），不是补丁。所有结论基于源码审计，行号对应 `main` @ `6523649`。

---

## 0. 「无上限」的工程定义

用户口中的「无上限」在工程上定义为四条硬约束，**任何一条不满足即视为未实现**：

| # | 约束 | 含义 |
|---|---|---|
| C1 | **无硬编码容量上限** | 代码中不存在写死的条数/字节数/字数阈值；容量边界只由设备剩余存储决定 |
| C2 | **全路径流式** | 从写入、存储、读取到 UI 渲染，任何环节不得把大文本整体拉进内存（禁 `String` 全量、禁 `readLines()`、禁 `Flow<List<全表>>`） |
| C3 | **限制一律可配置 + 可观测** | 若因工程原因必须设边界（如单次粘贴的事务大小），必须是用户偏好且默认关闭/放宽，且**禁止静默丢弃**——每次丢弃/清理都写审计日志 |
| C4 | **系统边界给绕行，不给否决** | Android 平台硬边界（Binder ~1MB、CursorWindow 2MB）必须用架构绕行（分块/外置/文件通道），不得以「系统不允许」为由缩减目标 |

**反面清单（现状违反项）**：`MAX_TEXT_LENGTH=4000`、`PHRASE_HARD_LIMIT=200`、`MAX_LEN=200`、`clipboardHistoryLimit` 默认 10、`trim(…, 30)` 硬编码 30 天——全部是 C1/C3 违反项，本设计逐一拔除。

---

## 1. 现状审计：瓶颈定位（全部有行号）

### 1.1 主输入法剪贴板（`app/` 模块）

| # | 位置 | 现状 | 障碍类型 |
|---|---|---|---|
| A1 | `data/prefs/AppPrefs.kt:337-340` | `clipboardHistoryLimit` 默认 **10** | C1 违反：条数上限 |
| A2 | `data/clipboard/ClipboardManager.kt:261-272` `removeOutdated()` | 每次插入都 `getAllUnpinned()` **全量加载所有未置顶条目到内存**再比较；`limit <= 0` 语义是「**删全部未置顶**」而非无上限 | 性能炸弹 + **语义陷阱** |
| A3 | `ClipboardDao.kt:44` `find(text, sensitive)` | 去重 = `text = :text` **全表扫描**，text 列无索引；大文本参数绑定随表膨胀线性劣化 | 性能 |
| A4 | `ClipboardEntry.kt` `text: String` 单列 | Android **CursorWindow 2MB 硬限制**：单条文本超过约 1~2MB，任何查询返回该行即 `Row too big to fit into CursorWindow` 崩溃 | **平台硬边界，100MB 单条的直接死因** |
| A5 | `ClipboardManager.kt:218-259` `onPrimaryClipChanged` | 系统剪贴板写入/读取走 Binder，事务上限约 1MB；`setPrimaryClip` 100MB 必然 `TransactionTooLargeException` | 平台硬边界（读取路径） |
| A6 | `ClipboardDao.kt:37` `allEntries()` | 已是 `PagingSource`（好）；但 `getAllUnpinned()`/`findUnpinnedIds()` 非分页 | 部分违反 C2 |
| A7 | `data/clipboard/db/ClipboardDatabase.kt` | version=4，Room **无 SQLCipher**（与 Recall 的加密库不一致） | 一致性 |

### 1.2 Recall 插件（`plugin/recall/`，SQLCipher 加密）

| # | 位置 | 现状 | 障碍类型 |
|---|---|---|---|
| B1 | `RecallService.kt:93` `MAX_TEXT_LENGTH=4000`；`:212`（NODE）与 `:317`（OCR）两处 `text.take(4000)` | **静默截断**，超出直接丢弃、无日志 | C1+C3 违反，字数无上限的直接死因 |
| B2 | `RecallService.kt:136-139` | `trim(this, 30)` **硬编码 30 天**清理；`RecallPreferences.keepDays` 默认 30 存在但 service 从未读取 | C1 违反：时间上限 |
| B3 | `data/RecallDatabase.kt:54-55` `all(): Flow<List<RecallRecord>>` | **无分页全量表**：Room Flow 一发射就把整表拉进内存，历史无上限时必 OOM（`RecallActivity` 的分块渲染救不了数据层全量） | C2 违反，历史无上限的直接死因 |
| B4 | `RecallDatabase.kt:57-61` `search(q)` | `LIKE '%' \|\| :q \|\| '%'` 全表扫描，无 FTS | 性能 |
| B5 | `RecallDatabase.kt:76-80` `duplicates(pkg, text, since)` | `text = :text` 全表扫 + 大文本绑定 | 性能 |
| B6 | `RecallRecord.text: String` 单列 | 同 A4：CursorWindow 2MB | 平台硬边界 |
| B7 | `RecallClipboardWriter.kt:33-35` | `MAX_LEN=200` / `MAX_PER_CAPTURE=3` / `MIN_INTERVAL_MS=2000`；`:77` `queueFile.readLines()` **全量读文件去重** | C1+C2 违反 |
| B8 | `PhraseAutoWriter.kt:35,39` | `PHRASE_HARD_LIMIT=200` / `BATCH_LIMIT=120`；`:99` `readLines()` 全量去重 | C1+C2 违反 |
| B9 | `ocr/OcrEngine.kt:153` | `lexicon = File(...).readLines()` **词典全量进内存**；`:864-880` `correctWithLexicon` 对每行 × 每词做 Levenshtein | C2 违反，词库无上限的直接死因 |
| B10 | `ocr/OcrEngine.kt:794` | `REC_WIDTH_MAX=960` → 超宽行**压缩**而非拆分，长行（表格/URL/代码）精度受损 | 拆分无上限的死因 |

### 1.3 用户词库（主输入法 ⇄ libime）

| # | 位置 | 现状 | 障碍类型 |
|---|---|---|---|
| D1 | `data/pinyin/CustomPhraseManager.kt` | `load()/save()` 通过 JNI **全量 `Array<PinyinCustomPhrase>`** 传递，百万级词组时两端同时 OOM | C2 违反 |
| D2 | `data/pinyin/customphrase/AppPhraseImporter`（.txt 导入路径） | 需按流式重写（导入期全量缓冲） | C2 |

**结论**：四个「无上限」各有真实死因——100MB 单条死于 **CursorWindow（A4/B6）**，历史无上限死于 **全量 Flow（B3）+ 全量清理（A2）**，词库无上限死于 **全量内存词典（B9）+ JNI 全量数组（D1）**，拆分无上限死于 **静默 take（B1）+ 压缩不拆分（B10）**。以下设计逐一给出架构级解。

---

## 2. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│ UI 层（只碰摘要，永不碰全量）                                  │
│  · RecallActivity：keyset 分页列表 + preview(前 256 字)        │
│  · 剪贴板候选栏：excerpt 预览 + 「粘贴全文」动作                 │
├─────────────────────────────────────────────────────────────┤
│ 服务层（流式 API，禁用 String 全量）                            │
│  · CaptureService：OCR/NODE → 流式写入（无 take）               │
│  · ImportService：.txt 导入 = 流式解析入库                      │
│  · PasteService：大文本上屏 = 分块 commit（绕 Binder）           │
├─────────────────────────────────────────────────────────────┤
│ 索引层（一切检索走索引，禁全表扫）                               │
│  · recall / clipboard 表：idx_timestamp, idx_sha256, idx_pkg   │
│  · FTS5 虚表（shadow content）+ 触发器同步 → 替代 LIKE          │
│  · OCR 词典：长度分桶 + 前缀索引 + mmap                          │
├─────────────────────────────────────────────────────────────┤
│ 存储层（大文本外置，DB 只存元数据）                              │
│  · 小文本 < 64KB：内联 text 列（快路径）                         │
│  · 大文本 ≥ 64KB：外置 blob 分片文件（压缩 + 流式 + sha256）      │
│    filesDir/blobs/<sha[0:2]>/<sha>.bin.zst                     │
│  · DB 行：blob_ref / sha256 / byte_len / preview / codec       │
│  · SQLCipher 加密（recall）；blob 用派生 key AES-GCM 流式加密    │
└─────────────────────────────────────────────────────────────┘
```

三条铁律贯穿全层：

1. **DB 行不放大文本**：超过内联阈值的文本一律外置 blob，DB 行恒定小尺寸——CursorWindow 2MB 问题从架构上消失（大文本从来不出现在查询结果行里）。
2. **一切列表分页**：`WHERE timestamp < :cursor ORDER BY timestamp DESC LIMIT :n`（keyset pagination），禁用 `Flow<List<全表>>`。
3. **一切清理可观测**：删除/淘汰/截断全部写 `RecallAuditLog` + UI 可见的存储统计。

---

## 3. 分主题设计

### 3.1 单条文本 100MB+（目标：复制粘贴 100MB 文字）

#### 3.1.1 写入路径（流式 + 分片 + 压缩）

```
输入（捕获/导入/系统剪贴板读取）
  → TextSplitter：按 4MB 分片，边读边算 SHA-256
  → 每片：zstd 压缩 → AES-GCM 流式加密 → append 到 .tmp
  → 全部完成：.tmp 原子 rename 为 blobs/<sha[0:2]>/<sha>.bin.zst
  → DB insert(sha256, byte_len, codec, preview=前 256 字, inline_text=NULL)
```

- **去重靠 sha256**：`duplicates()` 改为 `SELECT … WHERE sha256 = ? AND packageName = ?`（有索引，O(log n)），替代 B5 的 `text = :text` 全表扫——同时解决大文本绑定的性能问题。
- **崩溃恢复**：启动时扫描 `blobs/*.tmp` 全部删除（未完成写），孤儿 blob（无 DB 行引用）由低频 `PRAGMA` 巡检任务回收，回收记审计日志。
- **压缩**：默认 zstd（text 场景典型 5~10×）；不可压缩时退化为 stored。压缩是存储优化，**不是正确性依赖**——解压路径对两种 codec 都实现。
- **SQLite 兜底**：`SQLITE_MAX_LENGTH` 默认 1GB，blob 外置后彻底不触这条线。

#### 3.1.2 读取路径（流式，绕开 CursorWindow）

- `openBlob(sha): InputStream`——按需解密 + 解压 + 分片输出，调用方自己控制消费节奏。
- UI 只取 `preview` 列（DB 行恒定小）与 `byte_len`。
- 「查看全文」= 流式读入虚拟化文本视图（分段加载，禁 WebView 整载）。

#### 3.1.3 粘贴路径（绕开 Binder ~1MB）

系统剪贴板 `setPrimaryClip` 与 IME `commitText` 都走 Binder，100MB 直接 `TransactionTooLargeException`。绕行设计（不是缩减目标）：

| 场景 | 通道 | 设计 |
|---|---|---|
| 剪贴板历史 → 当前输入框 | `InputConnection` | **分块 commit**：按 256KB 一块 `commitText`，块间 yield；目标应用侧表现为连续输入，带进度回调；失败块可重试 |
| 剪贴板历史 → 系统剪贴板（给别的应用用） | `ClipboardManager` | ≤1MB 走 `setPrimaryClip` 原路；>1MB 写 `cache/share/bigtext_<sha>.txt` + `ClipData.newUri(contentResolver, …)`，经 FileProvider 分享——接收方按 URI 流式读 |
| 剪贴板历史 → 文件/其他应用 | `Intent.ACTION_SEND` | FileProvider URI，接收方流式读 |
| 系统剪贴板 → 历史库（监听路径） | `onPrimaryClipChanged` | >1MB 的 clip **不读全文**，只取前 64KB 内联 + 记 `truncated=true` + 审计日志；要全量走显式「从文件导入」 |

> 说明：平台对「一次事务塞 100MB」的限制是物理的，但用户目标「100MB 文字能存、能找、能粘贴」由分块 commit + 文件通道完整覆盖。这是绕行，不是逃避。

### 3.2 历史记录无上限

#### 3.2.1 主输入法（`app/`）

1. **`clipboardHistoryLimit` 语义重定义**（A1/A2）：
   - 默认值 `10` → **`0` 表示无上限**（彻底删「删全部」的旧语义，旧值 0 用户迁移时按新语义解释并提示）。
   - `removeOutdated()`（A2）重写为**纯 SQL**，不再拉内存：
     ```sql
     DELETE FROM clipboard
     WHERE pinned = 0 AND deleted = 0
       AND id NOT IN (
         SELECT id FROM clipboard WHERE pinned = 0 AND deleted = 0
         ORDER BY timestamp DESC LIMIT :limit)
     ```
     `limit = 0` 时整段跳过（无上限 = 不清理）。
   - `getAllUnpinned()`/`findUnpinnedIds()` 保留仅供「清空」一次性操作，且改分页批处理。
2. **索引**（A3）：`CREATE UNIQUE INDEX idx_clip_sha ON clipboard(sha256)`（新增 sha256 列）；去重查询走索引。
3. **FTS5**（A4 检索）：`clipboard_fts`（content=clipboard 外部内容表）+ INSERT/UPDATE/DELETE 触发器同步；搜索从 LIKE 全表扫改为 `MATCH`。
4. **schema**：version 4→5 AutoMigration（加 `sha256 TEXT`、`byte_len INTEGER`、`blob_ref TEXT NULL`、`codec INTEGER`、`preview TEXT`），旧行 `sha256` 由一次性回填任务计算（分页遍历算哈希，禁全量）。

#### 3.2.2 Recall 插件（`plugin/recall/`）

1. **`all()` 改 keyset 分页**（B3）：
   ```kotlin
   @Query("SELECT * FROM recall WHERE timestamp < :cursor ORDER BY timestamp DESC LIMIT :n")
   suspend fun page(cursor: Long, n: Int): List<RecallRecord>
   ```
   Activity 无限滚动加载；`count()` 用 `SELECT COUNT(*)` 单独查。**`Flow<List<RecallRecord>>` 删除**。
2. **`search()` 接 FTS5**（B4）：`recall_fts` shadow 表 + 触发器。
3. **`duplicates()` 走 sha256 索引**（B5）。
4. **`trim(…, 30)` 硬编码删除**（B2）：改为读 `RecallPreferences.keepDays`，**默认 0 = 永不自动清理**；用户设了天数才按天清理，且清理写审计日志。UI 展示「保留策略：永久 / N 天」。
5. **存储统计可观测**：Activity 顶部常驻「共 N 条 · 占用 X MB」，数据来自 `COUNT(*)` + blob 目录 `du`；提供用户主动的「按条数/按大小/按时间」清理（删除必留审计日志，遵循既有 `RecallAuditLog` 约定）。

### 3.3 词库大小无上限

#### 3.3.1 OCR 校正词典（B9）

现状 `readLines()` 全量内存 + 每行×每词 Levenshtein。重设计：

```
词典文件（用户可无限增删，无条数上限）
  → 构建期：按「词长度」分桶（len2, len3, … lenN）+ 每桶内按首字符二级哈希
  → 索引文件（.idx）缓存，词典变更（mtime/size 变）才重建
  → 运行期：RandomAccessFile/mmap 按桶流式扫，绝不整体载入
```

`correctWithLexicon(text, score)` 的匹配剪枝（保证大词典下的速度）：

1. **长度差剪枝**：Levenshtein 距离 > 阈值时必然 `|len(词) - len(候选)| > 阈值`，桶外不碰；
2. **首字剪枝**：距离 ≤ 2 时首字相同或为常见形近字集合，二级哈希先筛；
3. **早退**：逐字符 DP 超阈值即 `break`；
4. **候选串长度分桶**：rec 输出串通常 ≤ 20 字，只扫相邻桶。

效果：百万词规模单行校正亚秒级；词典 100MB 也只在磁盘上。**用户增删词条无任何条数限制**（文件追加即生效，索引惰性重建）。

#### 3.3.2 用户词库 / 自定义词组（D1/D2）

1. `CustomPhraseManager` JNI 全量 `Array` → 改为**分页/游标**接口（`loadPage(offset, limit)` + `count()`），Android 侧 UI 分页消费；libime 侧 userdict 本为 MARISA trie，容量本就不是瓶颈，瓶颈只在 Android 桥的全量拷贝——桥改流式即可。
2. `.txt` 导入（`AppPhraseImporter`/`RuleImporter` 同类路径）：`BufferedReader` 逐行流式解析 + 批量事务提交（每 1000 行一 commit），导入进度可见、可取消。
3. Recall 的 `recall_phrase_queue.txt`（B8）：`readLines()` 全量去重 → 迁入 **SQLite 队列表**（`phrase_queue`，sha1 去重索引 + `pending` 状态列），`PhraseAutoWriter.PHRASE_HARD_LIMIT=200` 删除（提炼量由用户导出的偏好控制，不再有硬上限）。
4. `recall_clipboard_queue.txt`（B7）：同上迁 SQLite；`MAX_LEN=200`/`MAX_PER_CAPTURE=3` 从「跳过」改为「**大文本整条入队**」（既然历史库已支持 100MB，剪贴板候选没有理由只收 200 字）；节流 `MIN_INTERVAL_MS` 保留但改为偏好。

### 3.4 拆分字数无上限

1. **删除 `MAX_TEXT_LENGTH`**（B1）：`:212`/`:317` 两处 `take(4000)` 去掉。捕获文本全量入库（大文本自动走 blob 外置路径）。可选偏好 `maxCaptureBytes`（**默认 0 = 无上限**）；一旦用户设置且触发，**记审计日志 + UI 提示**，绝不静默。
2. **`REC_WIDTH_MAX` 从「压缩」改「二次切分」**（B10）：det 检出超宽框（宽/高 > 阈值）后，先按字符密度估算子框数，**水平切分为多个子框分别送 rec**，结果按顺序拼接。长行（表格/URL/693 字代码行）不再被压掉精度——这才是「拆分无上限」的字面落实。
3. **多屏累积**：长文本靠多次截屏自然累积，已无上限（见 `longtext_sim_report.md` 结论 3）；本设计移除 4000 天花板后，累积总量也不再有上限。

---

## 4. 数据模型（目标 schema）

### 4.1 Recall（version 1 → 2，SQLCipher）

```sql
CREATE TABLE recall (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  packageName TEXT NOT NULL,
  appLabel    TEXT NOT NULL,
  source      TEXT NOT NULL,           -- NODE / OCR / IMPORT
  timestamp   INTEGER NOT NULL,
  -- 文本存取：小文本内联，大文本外置（二选一，由 codec=INLINE 区分）
  text        TEXT,                    -- 仅 < 64KB 时非空（快路径）
  sha256      TEXT NOT NULL,           -- 内容哈希：去重 + blob 定位 + 完整性校验
  byte_len    INTEGER NOT NULL,        -- 原始字节数（UTF-8）
  blob_ref    TEXT,                    -- blobs/<sha[0:2]>/<sha>.bin.zst；NULL=内联
  codec       INTEGER NOT NULL,        -- 0=INLINE 1=ZSTD 2=STORED
  preview     TEXT NOT NULL            -- 前 256 字符（列表/搜索摘要，永不空）
);
CREATE INDEX idx_recall_ts    ON recall(timestamp DESC);
CREATE INDEX idx_recall_sha   ON recall(sha256);
CREATE INDEX idx_recall_pkg   ON recall(packageName);
CREATE INDEX idx_recall_ts_pkg ON recall(packageName, timestamp DESC);

-- FTS5 外部内容表 + 触发器（与 recall 同步）
CREATE VIRTUAL TABLE recall_fts USING fts5(preview, content='recall', content_rowid='id');
CREATE TRIGGER recall_ai AFTER INSERT ON recall BEGIN
  INSERT INTO recall_fts(rowid, preview) VALUES (new.id, new.preview); END;
CREATE TRIGGER recall_ad AFTER DELETE ON recall BEGIN
  INSERT INTO recall_fts(recall_fts, rowid, preview) VALUES('delete', old.id, old.preview); END;
```

> **FTS 索引对象是 preview 而非全文**：100MB 级条目的全文检索由「导出后本地 grep / 查看全文页内查找」覆盖；FTS 保 preview 级（256 字）足够列表搜索场景，且索引体积可控。大文本全文检索列为后续增强，不阻塞本设计。

### 4.2 主输入法剪贴板（version 4 → 5）

`clipboard` 表加同样五列（`sha256` 唯一索引、`byte_len`、`blob_ref`、`codec`、`preview`），`text` 列改为可空（大文本为 NULL）；FTS5 `clipboard_fts` 同上。Room `AutoMigration 4→5`。

### 4.3 Blob 文件布局与加密

```
filesDir/blobs/<sha[0:2]>/<sha>.bin.zst     # 分片压缩 + AES-GCM 流式加密
filesDir/blobs/<sha[0:2]>/<sha>.bin.zst.tmp # 写中；启动时清理
```

- 加密 key：由 SQLCipher raw key（`RecallKeyManager.rawKey`，Keystore 信封）经 HKDF 派生独立子 key，**不复用 DB key**。
- 流式加解密：每 4MB 片一个 GCM frame（frame 头含 nonce + length），单帧失败不影响其他帧定位。
- 完整性：`sha256` 文件名 = 明文内容哈希；读取时流式校验，损坏即报「条目损坏」并允许删除（记审计日志）。

---

## 5. 横切关注点

### 5.1 可观测性（C3 的落实）

| 事件 | 记录 |
|---|---|
| 捕获超 `maxCaptureBytes`（用户设了才可能） | `RecallAuditLog` + 通知栏一次 |
| 自动清理（按天数/按用户主动清理） | `RecallAuditLog` ACTION_TRIM（已存在） |
| blob 孤儿回收 / .tmp 清理 | `RecallAuditLog` + Timber |
| 分块粘贴进度/失败 | Timber + 输入框内 toast |
| 存储统计 | UI 常驻（条数 + MB） |

**原则延续既有约定：禁止静默丢弃；一切删除留痕。**

### 5.2 内存安全（C2 的落实）

- 新增 lint/评审规则：`recall`/`clipboard` 包内禁 `readLines()`、禁 `Flow<List<RecallRecord>>` 全表、禁把 `record.text`/`entry.text` 直接交给 `String` 变量持有超 64KB。
- 大文本 API 一律返回 `InputStream`/`CharSequence` 分页视图。
- UI 预览恒走 `preview` 列。

### 5.3 迁移策略

- DB 迁移只加列不删列（Room AutoMigration）；旧行 `sha256`/`preview` 由后台回填任务分页补算（`WHERE sha256 IS NULL` + keyset 游标）。
- 大文本内联→外置的搬迁是**惰性**的：读到的旧大文本行当场搬（读时迁），不要求一次性全量迁移。
- `clipboardHistoryLimit` 旧值 0（=删全部）用户的迁移：首次启动检测到 limit==0 时弹一次说明，按新语义（无上限）执行。

### 5.4 测试计划

| 用例 | 验证点 |
|---|---|
| 1MB / 10MB / 100MB 文本导入 → 读取 → sha256 校验 | 流式正确性、内存峰值 < 50MB |
| 100MB 文本分块粘贴到测试输入框 | 分块 commit 完整率、乱序/丢块检测 |
| 10 万条历史 keyset 分页滚动 | 无 OOM、无 ANR、页间无重复无遗漏 |
| 10 万条 FTS 搜索 | 延迟 < 200ms |
| 百万词 OCR 词典校正单行 | 延迟 < 1s；词典增删后索引惰性重建 |
| 写入进程 kill（模拟崩溃） | .tmp 清理、无半成品条目、DB 无孤儿引用 |
| limit=0 语义 | 不再触发「删全部」 |

---

## 6. 实施分期

| 期 | 内容 | 交付物 |
|---|---|---|
| **P0** | 拆雷：删 `MAX_TEXT_LENGTH` 静默 take（改可观测）+ `trim(30)` 改偏好 + `clipboardHistoryLimit` 默认 0 + `removeOutdated` 改 SQL | 4 处小改，行为变化可解释 |
| **P1** | 历史无上限：keyset 分页 + 索引 + 存储统计 UI + FTS5 | 大历史可用 |
| **P2** | 100MB 单条：blob 外置 + 流式读写 + 分块粘贴 + FileProvider 通道 + schema v2/v5 | 100MB 落地 |
| **P3** | 词库无上限：OCR 词典分桶索引 + 剪枝 Levenshtein + JNI 分页 + 队列 SQLite 化 | 词库无上限 |
| **P4** | 拆分无上限：`REC_WIDTH_MAX` 压缩→二次切分 | 长行保精度 |

P0 可独立交付（纯删雷，风险最低）；P1/P2/P3/P4 按序依赖 P0。

---

## 7. 明确不做（诚实清单）

1. **不修改 Android 平台限制**：Binder 事务大小、CursorWindow 大小是系统常量，本设计只做绕行（分块/外置/文件通道）。
2. **不做 100MB 全文的 FTS 实时检索**：FTS 只覆盖 preview（256 字/条）；全文检索走「查看全文页内查找」。若未来需要，在 blob 侧建独立全文索引，不阻塞本设计。
3. **不自动清理任何数据**：所有清理必须由用户偏好触发或用户手动执行；默认策略是永久保留（C3）。
4. **不为「无上限」牺牲加密**：blob 与 DB 同等级加密，key 派生不复用。

---

## 8. 与既有决议的关系

- 承接 `docs/ocr-regression/longtext_sim_report.md` §6 待确认 ①②（`MAX_TEXT_LENGTH` 目标值、超长行二次切分）——本设计给出答案：**默认无上限 + 可观测**、**切分替代压缩**。
- 承接用户 v1.0.23 已做的「DAO 移除 LIMIT 300」与 Activity 分块渲染——本设计把该思路补完到数据层（分页查询）与清理层（SQL 化）。
- 不涉及 v1.0.30 release 范围（纯设计文档，无代码改动）。

# PATCHES v1.0.34 —— R3 第一期：主输入法数据静态加密

> 记录时间：2026-10-25
> 对应提交：v1.0.34（基于 v1.0.33 `0c36697`）
> 上游基线：fcitx5-android；minSdk=29 / targetSdk=37 / compileSdk=37

本文件是 v1.0.33 审计报告残留事项 **R3（主输入法剪贴板数据明文存储）**
第一期的完整整改记录：把 Keystore 信封加密 + 分块流式 AES-256-GCM +
SQLCipher 引入主输入法，并与 recall 插件对齐。

---

## 一、R3 背景与范围裁决

v1.0.33 审计报告 §4.5 残留事项 R3：主输入法剪贴板历史数据库此前为
**明文 Room**，>256KB 的大文本外置 blob 文件同样明文落盘——只依赖
Android 沙箱 + 文件级加密（FBE）。设备被 root / 换机迁移提取时无第二道
防线。Recall 插件自 v1.0.26 起数据库已是 SQLCipher + Keystore 信封加密，
但两个 APK 的 **blob 文件都还是明文**。

本轮范围（第一期）：

| 数据 | v1.0.33 及以前 | v1.0.34 起 |
|------|---------------|-----------|
| 剪贴板历史数据库（clbdb） | 明文 Room | SQLCipher（raw key 经 Keystore 信封加密） |
| 剪贴板大文本 blob 文件 | 明文 | AES-256-GCM 分块静态加密 |
| Recall 大文本 blob 文件 | 明文 | AES-256-GCM 分块静态加密（同构镜像） |
| Recall 数据库 | SQLCipher | 不变（复用同一 raw key 加密 blob） |
| 用户词库 / 自定义词组 | 明文 JSON 文件 | **本期不做**（见下） |

**用户词库透明加密不可行性论证**：Rime yaml / 拼音 / 码表词典由 native
引擎（libime / fcitx5 JNI）**直接按文件路径**加载——透明加密需要改
native 侧的读文件路径（或整个改走内存镜像），涉及 4 个 ABI 的原生重编
与上游行为变更，风险/收益不成比例。词库内容为「用户主动确认的词条」，
敏感度低于剪贴板全文（可能是密码/令牌/证件），记录为 R3 第二期候选，
待有 native 侧改动需求时一并做。

## 二、密码层：分块流式 AES-256-GCM（StreamingFileCipher）

新增 `app/src/main/java/org/fcitx/fcitx5/android/data/crypto/`
StreamingFileCipher.kt，recall 侧同构镜像于
`plugin/recall/.../recall/data/StreamingFileCipher.kt`
（两个 APK 独立分发、不能共用代码，沿用既有 BlobStore 同构模式）。

- **封装格式**：文件头 16B（magic "MBMC" + version + chunkShift +
  reserved + 8B 随机 noncePrefix）；正文按 256KB 切块，每块独立
  GCM 封装（nonce = prefix(8B) || BE32(块序号)，tag 16B）。
- **块序号进 AAD**：块被重排/对调即 tag 校验失败（有单测覆盖）。
- **流式不缓存全文**：100MB 文件加密/解密内存占用 O(块大小)。
- **遗留明文自动识别**：文件头无 magic 一律按明文透读——v1.0.33 及
  以前的 blob 升级不丢数据；dedup 命中遗留明文时顺手改写为密文
  （惰性迁移）。
- **sha256 仍按明文计算**：内容寻址语义不变，文件长度 = 密文长度
  （16B 头 + 16B/256KB 块，约 0.006% 开销）。

**性能实测**（2026-10-25，沙箱 x86 JDK20 未走 AES intrinsics 的软件
加密最差情况）：100MB 加密 ~1.7s（57MB/s）、解密 ~1.2s（80MB/s）。
Android arm64 设备（API 29+ 普遍带 ARMv8 Crypto Extensions）硬件加速下
快 10–30 倍，且 100MB 粘贴本身耗时 10s–1min，加密不是瓶颈。

## 三、密钥链路

- **app 侧新增 AppKeyManager**（alias `mbm_clip_data_key`）：raw key
  = 32B SecureRandom，由 Keystore AES-256-GCM（StrongBox 优先、回退
  TEE）信封加密；wrapped key + IV 以 Base64 存**普通**
  SharedPreferences——存放值本身已信封加密，不叠加已弃用的
  EncryptedSharedPreferences（R6 弃用不扩散到新代码）。
- **recall 侧复用 RecallKeyManager**（v1.0.34 起 `private` → `internal`）：
  blob 静态加密与 SQLCipher 用同一 raw key（同一信任域，避免第二条
  密钥链路）。NOTICE/PRIVACY 的「主输入法不持有 Recall 数据库任何密钥」
  表述不受影响——那是跨 APK 边界，此处是模块内复用。
- **解封失败时轮换 key 并记日志**（Keystore 被清等场景），旧密文
  blob 将读不出并按 DB 行元数据处理，不会静默错数据。

## 四、接入点

1. **ClipboardBlobStore**（重写 writeTo/openIn）：单循环流式读入，
   digest 按明文累计、落盘经 EncryptingSink；dedup 判据改用
   `StreamingFileCipher.encodedLength(total)`（精确值，见第六节
   bug #1）。`open()` 走 `AppKeyManager.rawKey(appContext)`。
2. **RecallBlobStore**：同构接入（`RecallKeyManager.rawKey`）。
3. **ClipboardManager**：剪贴板数据库改 SQLCipher，含一次性后台迁移
   （见第五节）。
4. **ClipboardPasteChunker.expectedTailText**：签名从
   `(File, Long, Int)` 改为 `(InputStream, Int)`——blob 加密后不能
   RandomAccessFile seek，改流式一遍过 + 尾部环缓冲（内存 O(n)）；
   `FcitxInputMethodService.verifyPasteTail` 改走解密流。

## 五、一次性数据库迁移（clbdb 明文 → SQLCipher）

`ClipboardManager.init()` 检测到「库文件存在但无加密标记」时：

1. 旧明文 clbdb（含 -wal/-shm）改名 `clbdb.legacy.bak`；
2. 明文 Room 打开 .bak（触发既有 autoMigration 到 v5）；
3. 按 id 升序分批（50 行/批）`dumpRange` 读入 → `clbDao.insertAll`
   写入密文库（走 Room 事务机制，失效追踪正常触发，UI 自动刷新；
   内存有界）；
4. 删备份、写加密标记。
5. **任一步失败**：关密文库、删半成品、还原 .bak、退回明文 DB
   （不写标记，下次启动重试）——迁移失败绝不丢用户历史。

新增 DAO：`insertAll(List<ClipboardEntry>)` 与
`dumpRange(lastId, limit)`（纯新增，不动既有查询）。

## 六、测试：80 个单测全绿，过程中抓出 3 个真 bug

两模块合计 **80 个测试**（app 55 + recall 25）全部通过，其中本轮新增：

- `StreamingFileCipherTest`（15 个）：0/1/chunk-1/chunk/chunk+1/
  2chunk+1/100MB 规模往返逐字节、文件头格式、encodedLength 精确性
  （16 组边界尺寸）、篡改/错密钥/块重排 → AEADBadTagException、
  截断 → EOFException、遗留明文透读、decryptedLength。
- `ClipboardBlobStoreTest` / `RecallBlobStoreTest` 各 +7 个加密用例：
  加密往返、落盘无明文泄漏、遗留明文可读、dedup 命中惰性迁移、
  同内容去重、错密钥认证失败、空输入、100MB 加密往返。

**bug #1（实现期自查）**：`encodedLength` 初版按「满块」估算总长
（`chunks * (CHUNK_SIZE + 16)`），尾块短于 256KB 时高估——1 字节
明文算出 262176。后果：dedup 长度判据永不命中，同内容反复重写
（性能退化）。已改为「满块数 ×(块+16) + 尾块(尾长+16)」。

**bug #2（编译期）**：`Cipher.doFinal(buf, 0, len)` 用了 3 参重载
——它返回**新 ByteArray** 而非 int，不能直接赋给 Int。改 5 参重载
写输出缓冲。

**bug #3（测试期，最值得记住）**：解密端 `fillChunk` 假设每块都是
满块（`chunkSize + 16`），尾块短时误抛 `EOFException`。三个叠加原因
让它活过实现期：

1. **性能基准的数据偏差**：基准用 100MB（= 400 × 256KB 整块），
   尾块路径从未被执行——「跑过一遍」不等于「覆盖了边界」；
2. **同构镜像不防笔误**：recall 侧 `cipherBuf = ByteArray((1 shl 24) + 16)`
   带了括号，app 侧写成 `1 shl 24 + 16`——Kotlin 中缀函数 `shl` 优先级
   **低于**算术 `+`，实际解析为 `1 shl 40` = `1 shl 8` = **256**，
   缓冲只有 256 字节，大块读取时 BufferedInputStream 抛
   IndexOutOfBoundsException。「结构相同」的两份代码行为相反；
3. **构建顺序掩盖**：recall 测试先失败 → 构建中断，`:app:testDebugUnitTest`
   根本没执行（test-results 里的 XML 是上一轮的旧文件，差点被误读为
   app 也过了）。

修复：补齐括号并加注释；`fillChunk` 改为「EOF 时已读 ≥16B（至少一个
tag）按合法尾块解密，GCM 校验放行真尾块、拒绝截断块；不足 16B 才抛
EOFException」；重跑加 `--continue` 确保两模块测试都真实执行。
教训已沉淀为 pitfalls #76。

## 七、工程沉淀

- `docs/pitfalls.md` 新增 #76（Kotlin 中缀优先级 + 尾块短读 +
  「同构镜像≠行为一致，测试必须两侧都真跑」）。
- `docs/sbom/license_ledger.txt` 与 `sbom.cdx.json` 重新生成：
  实测第一方 LGPL-2.1-or-later × 360 / AGPL-3.0-or-later × 91
  （上一版台账停留在 v1.0.31 时点的 358/77，三轮未更新）。
- NOTICE / PRIVACY.txt 同步更新（crypto 包归属、SQLCipher 小节、
  Recall 特别说明、SPDX 计数），经 `syncLicenseAssets` 进两 APK
  的 assets 随包分发。

## 八、仍未完成

| # | 事项 | 状态 |
|---|------|------|
| R3-2 | 用户词库/自定义词组透明加密 | 需改 native 侧读文件路径，论证见第一节 |
| R5 | 手写设备侧失败根因 | 待真机日志（v1.0.33 起有分级日志） |
| R6 | `androidx.security:security-crypto` 弃用 | 待官方迁移路径；app 侧新代码已改用普通 SP + Keystore 信封 |

## 九、复现与验证命令

```bash
# 单元测试（两模块 80 个）
./gradlew :app:testDebugUnitTest :plugin:recall:testDebugUnitTest --continue

# 许可台账重生（纯本地，不联网）
python3 docs/license_audit.py

# blob 加密行为（设备侧）：写入 >256KB 剪贴板条目后
# filesDir/clipblobs/<sha>.bin 前 4 字节应为 "MBMC"（0x4D424D43）
```

# libncnn.a 已打补丁：AAsset_getBuffer → hccr_compat_AAsset_getBuffer

本目录下 4 个 ABI 的 `lib/libncnn.a` 均经过符号重命名修补（llvm-objcopy
--redefine-sym，ar 替换成员 + ranlib），**不要用上游原版覆盖**。

## 为什么

ncnn 的 `datareader.cpp.o` 里 `DataReaderFromAndroidAsset::scan` 引用
`AAsset_getBuffer`，该函数是 **API 33（Android 13）新增**符号。本工程
minSdk=29，Android 10-12 设备上没有这个符号；`libhccr.so` 采用 BIND_NOW
（NDK 默认 -z now），dlopen 在符号绑定阶段直接失败，导致手写引擎永远
初始化不了（真机实测报「手写引擎初始化失败（模型或字表加载失败）」）。

`datareader.cpp.o` 必须参与链接：本工程用 `DataReaderFromMemory` 从
assets 字节加载模型，其实现（含析构函数）就在该成员里。

## 补丁内容

- 把 `datareader.cpp.o` 内对 `AAsset_getBuffer` 的引用改名为
  `hccr_compat_AAsset_getBuffer`；
- 该垫片在 `../hccr_jni.cc` 中定义（返回 nullptr）；
- `AAsset_seek`（API ≤ 25）与 `AAsset_read`（远古 API）未动；
- 语义影响为零：`scan` 只在 `Net::load_param_bin(AAsset*)` 路径被调用，
  本工程只走 `DataReaderFromMemory` + `AAsset_read` 路径。

若未来需要 `Net::load_param(AAssetManager*)` / `load_param_bin(AAsset*)`
路径，必须先把 `hccr_compat_AAsset_getBuffer` 换成真实实现
（AAsset_getLength64 + AAsset_seek + AAsset_read 读全量并返回缓冲指针，
注意释放时机），或换用 API 33+ 的 libncnn 构建。

修补脚本留存于会话记录（docs/pitfalls.md #72 有完整记录）。

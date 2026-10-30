// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright Ismantic/Handwritten Contributors
// SPDX-FileContributor: MBM (Mixed Bug Method) — 13 路 TTA 集成与线程策略修改
//
// Shared offline HCCR JNI runtime. Model weights remain valid for Session's life.
// Vendored from Ismantic/Handwritten (Apache-2.0). Modified for MBM:
//   1. 线程数用满全部核心(耗电不敏感);
//   2. 超高精度 TTA 集成(test-time augmentation):把 64x64 输入生成 13 路仿射变体
//      (平移 / 缩放 / 微旋转),逐路前向推理后对 softmax 概率取平均,再取 top-k。
//      MBM 场景是"精度优先、耗电不敏感",故默认全程开启。
#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <exception>
#include <memory>
#include <mutex>
#include <numeric>
#include <thread>
#include <vector>

#include "net.h"
#include "datareader.h"
#include "preprocess.h"

namespace {

constexpr int kCanvas = 64;
constexpr int kPixels = kCanvas * kCanvas;
constexpr float kCenter = (kCanvas - 1) * 0.5f;  // 31.5
// TTA 变体路数，与 build_variants() 的返回值大小一致。
// N121：外层 TTA 会占满 min(cores, kVariantCount) 条线程，nativeInit 里据此把每路
// 前向内部的 OpenMP 线程数夹到合理范围，避免 13 路 × cores 的线程爆炸。
constexpr int kVariantCount = 13;

// 诊断日志（MBM v1.0.33）：加载失败必须能区分「asset 缺失 / 读取短读 /
// load_param 失败 / load_model 失败」四种成因。此前 nativeInit 失败只返回 0，
// 真机排障无从下手。tag 与 Kotlin 侧 HandwritingWindow 的诊断日志保持一致。
#define HW_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MBM-HW", __VA_ARGS__)
#define HW_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "MBM-HW", __VA_ARGS__)

// 加载稳健性垫片（MBM v1.0.31a）。
// 背景：libncnn.a 的 datareader.cpp.o（DataReaderFromAndroidAsset::scan）
// 引用 AAsset_getBuffer。本工程读取模型只走 DataReaderFromMemory +
// AAsset_read 路径，scan 所属的 load_param_bin(AAsset*) 路径永不执行，
// 但该引用仍会作为未定义符号进入 libhccr.so 的动态符号表。
// 处理：构建期在 4 个 ABI 的 libncnn.a 上以 llvm-objcopy --redefine-sym
// 把该引用改名为本符号，由本工程自行提供定义，使 libhccr.so 的加载不
// 依赖个别系统镜像对单一 NDK 符号的解析策略（本工程以 BIND_NOW 链接，
// 未定义符号解析失败将直接导致 dlopen 失败）。
// 实证说明：经 NDK 逐版本桩库核对，AAsset_getBuffer 自 API 29
// （本工程 minSdk）起即由 libandroid.so 导出；故本垫片定位为
// 「加载稳健性加固」而非版本兼容修复，对功能语义无影响（返回 nullptr
// 的分支永不执行）。
// 若未来启用 Net::load_param(AAssetManager*)/load_param_bin(AAsset*) 路径，
// 必须把本垫片换成真实实现（AAsset_getLength64 + AAsset_seek + AAsset_read
// 读全量到缓冲并返回指针，注意释放时机）。
// AAsset_read / AAsset_seek / AAsset_getLength64 等其余 NDK 符号在
// API 29 以前即已存在，保持原样。
extern "C" const void* hccr_compat_AAsset_getBuffer(AAsset*) {
    return nullptr;
}

struct Session {
    ncnn::Net net;
    std::vector<unsigned char> model_bytes;
    std::mutex extractor_mutex;
};

/** output(x, y) = src(u, v); u = a*x + b*y + c, v = d*x + e*y + f */
struct Affine {
    float a, b, c, d, e, f;
};

std::vector<unsigned char> read_asset(AAssetManager* manager, const char* name) {
    AAsset* asset = AAssetManager_open(manager, name, AASSET_MODE_BUFFER);
    if (!asset) {
        HW_LOGE("asset open failed: %s", name);
        return {};
    }
    const auto length = static_cast<size_t>(AAsset_getLength64(asset));
    std::vector<unsigned char> bytes(length);
    // 短读循环（MBM v1.0.33 加固）：单次 AAsset_read 不保证读满请求长度——
    // 压缩存储的 asset 受内部解压缓冲块大小限制会短读。旧代码一次读不满即判
    // 「模型或字表加载失败」，是间歇性失败的可疑诱因之一；现循环读满为止。
    size_t done = 0;
    while (done < length) {
        const int got = AAsset_read(asset, bytes.data() + done, length - done);
        if (got <= 0) break;  // 0 = EOF，<0 = 读取错误
        done += static_cast<size_t>(got);
    }
    AAsset_close(asset);
    if (done != length) {
        HW_LOGE("asset short read: %s (got %zu / %zu)", name, done, length);
        return {};
    }
    return bytes;
}

inline float sample_or_white(const float* src, int x, int y) {
    // 画布外按白底处理(归一化后 0 = 白)
    if (x < 0 || y < 0 || x >= kCanvas || y >= kCanvas) return 0.f;
    return src[y * kCanvas + x];
}

/** 双线性仿射采样 */
void affine_warp(const float* src, float* dst, const Affine& m) {
    for (int y = 0; y < kCanvas; ++y) {
        for (int x = 0; x < kCanvas; ++x) {
            const float u = m.a * x + m.b * y + m.c;
            const float v = m.d * x + m.e * y + m.f;
            const float fx0 = std::floor(u), fy0 = std::floor(v);
            const int ix = static_cast<int>(fx0), iy = static_cast<int>(fy0);
            const float tx = u - fx0, ty = v - fy0;
            const float p00 = sample_or_white(src, ix, iy);
            const float p10 = sample_or_white(src, ix + 1, iy);
            const float p01 = sample_or_white(src, ix, iy + 1);
            const float p11 = sample_or_white(src, ix + 1, iy + 1);
            dst[y * kCanvas + x] =
                (1.f - ty) * ((1.f - tx) * p00 + tx * p10) +
                (     ty) * ((1.f - tx) * p01 + tx * p11);
        }
    }
}

Affine identity() { return {1.f, 0.f, 0.f, 0.f, 1.f, 0.f}; }

Affine translate(float dx, float dy) { return {1.f, 0.f, -dx, 0.f, 1.f, -dy}; }

/** 以画布中心缩放:视觉上放大 s 倍 == 采样坐标收缩 1/s */
Affine scale_about(float s) {
    const float inv = 1.f / s;
    const float t = kCenter * (1.f - inv);
    return {inv, 0.f, t, 0.f, inv, t};
}

Affine rotate_about(float rad) {
    const float cs = std::cos(rad), sn = std::sin(rad);
    return {cs, -sn, kCenter * (1.f - cs + sn),
            sn,  cs, kCenter * (1.f - sn - cs)};
}

/** TTA 变体:1 原图 + 4 轴向平移 + 4 对角平移 + 2 缩放 + 2 微旋转 = 13 路 */
std::vector<Affine> build_variants() {
    std::vector<Affine> v;
    v.push_back(identity());
    for (float d : {-2.f, 2.f}) {
        v.push_back(translate(d, 0.f));
        v.push_back(translate(0.f, d));
    }
    for (float dx : {-2.f, 2.f}) {
        for (float dy : {-2.f, 2.f}) v.push_back(translate(dx, dy));
    }
    v.push_back(scale_about(0.92f));
    v.push_back(scale_about(1.08f));
    constexpr float kPi = 3.14159265358979323846f;
    v.push_back(rotate_about( 4.f * kPi / 180.f));
    v.push_back(rotate_about(-4.f * kPi / 180.f));
    return v;
}

/** 单路前向:64x64 float → softmax 概率向量 */
bool forward(Session* session, const float* patch, std::vector<float>& prob) {
    ncnn::Mat in(kCanvas, kCanvas, 1);
    std::memcpy(in.channel(0).data, patch, sizeof(float) * kPixels);
    ncnn::Extractor extractor = [session] {
        std::lock_guard<std::mutex> guard(session->extractor_mutex);
        return session->net.create_extractor();
    }();
    if (extractor.input("in0", in) != 0) return false;
    ncnn::Mat out;
    if (extractor.extract("out0", out) != 0 || out.w <= 0) return false;
    const int count = out.w;
    const float* logits = out;
    float maximum = logits[0];
    for (int i = 1; i < count; ++i) maximum = std::max(maximum, logits[i]);
    prob.resize(static_cast<size_t>(count));
    float total = 0.f;
    for (int i = 0; i < count; ++i) {
        const float e = std::exp(logits[i] - maximum);
        prob[static_cast<size_t>(i)] = e;
        total += e;
    }
    if (total <= 0.f) return false;
    for (float& p : prob) p /= total;
    return true;
}

/**
 * N121：RAII 线程回收器。
 * joinable 的 std::thread 被析构会直接 std::terminate，而线程的创建/启动路径上
 * 任何一步失败（emplace_back 抛 system_error / bad_alloc）都会让已建线程滞留在
 * joinable 状态。用本对象兜住所有退出路径（含异常），保证它们一定被 join。
 */
class ThreadGuard {
public:
    explicit ThreadGuard(std::vector<std::thread>& threads) : threads_(threads) {}
    ~ThreadGuard() {
        for (auto& t : threads_) {
            if (t.joinable()) t.join();
        }
    }
    ThreadGuard(const ThreadGuard&) = delete;
    ThreadGuard& operator=(const ThreadGuard&) = delete;
private:
    std::vector<std::thread>& threads_;
};

/**
 * TTA 集成推理:变体分摊到多线程,各线程独立累加概率,最后按成功路数取平均。
 */
bool predict_ensemble(Session* session, const float* base, std::vector<float>& prob) {
    const std::vector<Affine> variants = build_variants();
    const int n = static_cast<int>(variants.size());
    std::vector<std::vector<float>> patches(static_cast<size_t>(n));
    for (int i = 0; i < n; ++i) {
        patches[static_cast<size_t>(i)].resize(kPixels);
        affine_warp(base, patches[static_cast<size_t>(i)].data(), variants[static_cast<size_t>(i)]);
    }

    const int cores = std::max(1, static_cast<int>(std::thread::hardware_concurrency()));
    const int workers = std::min(cores, n);
    std::vector<std::vector<float>> sums(static_cast<size_t>(workers));
    std::atomic<int> ok_total{0};

    if (workers <= 1) {
        std::vector<float> tmp;
        for (int i = 0; i < n; ++i) {
            if (!forward(session, patches[static_cast<size_t>(i)].data(), tmp)) continue;
            if (sums[0].empty()) sums[0].assign(tmp.size(), 0.f);
            if (sums[0].size() != tmp.size()) continue;
            for (size_t j = 0; j < sums[0].size(); ++j) sums[0][j] += tmp[j];
            ++ok_total;
        }
    } else {
        std::vector<std::thread> threads;
        threads.reserve(static_cast<size_t>(workers));
        // N121：guard 声明在 threads 之后 → 析构早于 threads，可安全 join；它兜住所有退出路径
        ThreadGuard guard(threads);
        // N121：起跑门——先全部创建成功再统一放行。任一路创建失败时撤销整批，
        // 不让「部分线程已在跑、部分创建失败」的半成品状态出现。
        std::mutex gate_mutex;
        std::condition_variable gate_cv;
        bool go = false;       // 全部创建成功 → 放行
        bool aborted = false;  // 有线程创建失败 → 让已建的线程立即退出
        bool created_all = false;
        try {
            for (int w = 0; w < workers; ++w) {
                threads.emplace_back([session, &patches, &sums, &ok_total,
                                      &gate_mutex, &gate_cv, &go, &aborted, w, workers, n] {
                    std::unique_lock<std::mutex> lock(gate_mutex);
                    gate_cv.wait(lock, [&go, &aborted] { return go || aborted; });
                    const bool run = go;
                    lock.unlock();
                    if (!run) return;
                    std::vector<float> tmp;
                    int ok = 0;
                    for (int i = w; i < n; i += workers) {
                        if (!forward(session, patches[static_cast<size_t>(i)].data(), tmp)) continue;
                        auto& sum = sums[static_cast<size_t>(w)];
                        if (sum.empty()) sum.assign(tmp.size(), 0.f);
                        if (sum.size() != tmp.size()) continue;
                        for (size_t j = 0; j < sum.size(); ++j) sum[j] += tmp[j];
                        ++ok;
                    }
                    ok_total.fetch_add(ok, std::memory_order_relaxed);
                });
            }
            created_all = true;
        } catch (const std::exception& e) {
            // N121：创建失败不能让异常穿出 extern "C" 边界，也不能留下 joinable 的线程
            HW_LOGE("tta thread create failed: %s", e.what());
        }
        {
            std::lock_guard<std::mutex> lock(gate_mutex);
            if (created_all) {
                go = true;
            } else {
                aborted = true;
            }
        }
        gate_cv.notify_all();
        for (auto& t : threads) {
            if (t.joinable()) t.join();
        }
        if (!created_all) return false;
    }

    const int ok = ok_total.load();
    if (ok <= 0) return false;
    for (auto& sum : sums) {
        if (sum.empty()) continue;
        if (prob.empty()) prob.assign(sum.size(), 0.f);
        if (prob.size() != sum.size()) continue;
        for (size_t j = 0; j < prob.size(); ++j) prob[j] += sum[j];
    }
    if (prob.empty()) return false;
    const float inv = 1.f / static_cast<float>(ok);
    for (float& p : prob) p *= inv;
    return true;
}

}  // namespace

extern "C" {
JNIEXPORT jlong JNICALL
Java_com_shiyu_handwritten_runtime_HCCRRecognizer_nativeInit(
        JNIEnv* env, jclass, jobject assets, jstring param, jstring bin) {
    AAssetManager* manager = AAssetManager_fromJava(env, assets);
    if (!manager) {
        HW_LOGE("stage=asset_manager_null");
        return 0;
    }
    auto session = std::make_unique<Session>();
    // 超高精度:禁用所有可能掉精度的加速（线程数见下方 N121 的 clamp 说明）
    session->net.opt.use_vulkan_compute = false;
    session->net.opt.use_fp16_arithmetic = false;
    session->net.opt.use_fp16_storage = false;
    session->net.opt.use_fp16_packed = false;
    session->net.opt.use_bf16_storage = false;
    session->net.opt.use_winograd_convolution = false;
    session->net.opt.use_sgemm_convolution = true;
    // TTA 用多线程并发跑 13 路前向；lightmode 下权重会在最后一次使用后释放，
    // 并发 extractor 之间会互相踩权重（结果错乱 / 崩溃）。关掉 lightmode 让权重常驻内存，
    // 并发只读即可安全共享（模型仅 4.3MB，内存代价可忽略）。
    session->net.opt.lightmode = false;
    // N121：过去是 max(4, cores)，而 TTA 会把 13 路前向摊到 ensemble_workers 条线程上并行，
    // 每路前向内部再各自开 max(4, cores) 条 OpenMP 线程 → 13×cores 线程爆炸（低端机直接卡死）。
    // 外层已占住 ensemble_workers 条，这里只给每路前向 cores/ensemble_workers 条并夹到 [1,4]，
    // 使总线程数与核心数保持同量级。
    const int cores = std::max(1, static_cast<int>(std::thread::hardware_concurrency()));
    const int ensemble_workers = std::min(cores, kVariantCount);
    session->net.opt.num_threads = std::clamp(cores / ensemble_workers, 1, 4);
    const char* p = env->GetStringUTFChars(param, nullptr);
    if (!p) {
        HW_LOGE("stage=param_name_null");
        return 0;
    }
    std::vector<unsigned char> param_bytes = read_asset(manager, p);
    if (param_bytes.empty()) {
        HW_LOGE("stage=param_asset name=%s", p);
        env->ReleaseStringUTFChars(param, p);
        return 0;
    }
    env->ReleaseStringUTFChars(param, p);
    const unsigned char* param_memory = param_bytes.data();
    ncnn::DataReaderFromMemory param_reader(param_memory);
    const int param_rc = session->net.load_param(param_reader);
    if (param_rc != 0) {
        HW_LOGE("stage=load_param rc=%d bytes=%zu", param_rc, param_bytes.size());
        return 0;
    }
    const char* b = env->GetStringUTFChars(bin, nullptr);
    if (!b) {
        HW_LOGE("stage=model_name_null");
        return 0;
    }
    session->model_bytes = read_asset(manager, b);
    if (session->model_bytes.empty()) {
        HW_LOGE("stage=model_asset name=%s", b);
        env->ReleaseStringUTFChars(bin, b);
        return 0;
    }
    env->ReleaseStringUTFChars(bin, b);
    const unsigned char* bin_memory = session->model_bytes.data();
    ncnn::DataReaderFromMemory bin_reader(bin_memory);
    const int model_rc = session->net.load_model(bin_reader);
    if (model_rc != 0) {
        HW_LOGE("stage=load_model rc=%d bytes=%zu", model_rc, session->model_bytes.size());
        return 0;
    }
    HW_LOGI("init ok: param=%zu bin=%zu threads=%d",
            param_bytes.size(), session->model_bytes.size(),
            static_cast<int>(session->net.opt.num_threads));
    return reinterpret_cast<jlong>(session.release());
}

JNIEXPORT jint JNICALL
Java_com_shiyu_handwritten_runtime_HCCRRecognizer_nativePredict(
        JNIEnv* env, jclass, jlong handle, jfloatArray input, jintArray out_indices,
        jfloatArray out_probabilities, jint requested) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session || requested <= 0 || env->GetArrayLength(input) != kPixels) return 0;
    std::vector<float> base(static_cast<size_t>(kPixels));
    jfloat* source = env->GetFloatArrayElements(input, nullptr);
    // N124：GetFloatArrayElements 在 OOM 时返回 NULL，必须判空再 memcpy，否则解引用空指针崩溃。
    if (!source) return 0;
    std::memcpy(base.data(), source, sizeof(float) * kPixels);
    env->ReleaseFloatArrayElements(input, source, JNI_ABORT);

    std::vector<float> probabilities;
    if (!predict_ensemble(session, base.data(), probabilities)) return 0;

    const int count = static_cast<int>(probabilities.size());
    const int k = std::min(requested, count);
    std::vector<int> indices(static_cast<size_t>(count));
    std::iota(indices.begin(), indices.end(), 0);
    std::partial_sort(indices.begin(), indices.begin() + k, indices.end(),
            [&probabilities](int a, int b) {
                return probabilities[static_cast<size_t>(a)] > probabilities[static_cast<size_t>(b)];
            });
    std::vector<jint> result_indices(static_cast<size_t>(k));
    std::vector<jfloat> result_probabilities(static_cast<size_t>(k));
    for (int i = 0; i < k; ++i) {
        result_indices[static_cast<size_t>(i)] = indices[static_cast<size_t>(i)];
        result_probabilities[static_cast<size_t>(i)] =
            probabilities[static_cast<size_t>(indices[static_cast<size_t>(i)])];
    }
    env->SetIntArrayRegion(out_indices, 0, k, result_indices.data());
    env->SetFloatArrayRegion(out_probabilities, 0, k, result_probabilities.data());
    return k;
}

JNIEXPORT void JNICALL
Java_com_shiyu_handwritten_runtime_HCCRRecognizer_nativeRelease(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_shiyu_handwritten_runtime_HCCRRecognizer_nativePreprocess(
        JNIEnv* env, jclass, jbyteArray gray, jint width, jint height, jfloatArray output) {
    // N59：用 int64_t 做乘法，避免 width*height 在 32 位有符号整数下回绕（如 65536*65536 归零）
    // 导致越界长度校验被绕过。
    const int64_t expectedPixels = static_cast<int64_t>(width) * height;
    if (width <= 0 || height <= 0 || expectedPixels != env->GetArrayLength(gray)
            || env->GetArrayLength(output) != kPixels) return JNI_FALSE;
    jbyte* pixels = env->GetByteArrayElements(gray, nullptr);
    jfloat* result = env->GetFloatArrayElements(output, nullptr);
    // N59：Get*ArrayElements 在 OOM 时返回 NULL，未判空即传入 preprocess 会解引用空指针。
    if (!pixels || !result) {
        if (pixels) env->ReleaseByteArrayElements(gray, pixels, JNI_ABORT);
        if (result) env->ReleaseFloatArrayElements(output, result, JNI_ABORT);
        return JNI_FALSE;
    }
    int ok = hccr_preprocess(reinterpret_cast<const uint8_t*>(pixels), width, height, result);
    env->ReleaseByteArrayElements(gray, pixels, JNI_ABORT);
    env->ReleaseFloatArrayElements(output, result, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}
}  // extern "C"

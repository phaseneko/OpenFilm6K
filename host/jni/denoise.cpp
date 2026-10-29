// On-device denoise: ncnn U-Net, tiled inference with Hann-window blending.
#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <cmath>
#include <mutex>
#include <chrono>
#include <thread>
#include <atomic>
#include <utility>
#include <string>
#include <vector>

#include "ncnn/net.h"
#include "ncnn/pipelinecache.h"
#include "turbo/turbojpeg.h"


// ncnn's prebuilt lib references libc++'s __hash_memory, which newer NDK libc++_shared
// no longer exports. Provide a consistent implementation (only ncnn calls it, so a custom
// hash is safe: insert and lookup both go through this symbol).
extern "C" size_t _ZNSt6__ndk113__hash_memoryEPKvm(const void* p, size_t n) __attribute__((visibility("default")));
extern "C" size_t _ZNSt6__ndk113__hash_memoryEPKvm(const void* p, size_t n) {
    const unsigned char* s = (const unsigned char*) p;
    size_t h = 1469598103934665603ULL;
    for (size_t i = 0; i < n; i++) { h ^= (size_t) s[i]; h *= 1099511628211ULL; }
    return h;
}

#define DN_TAG "of6k-dn"
#define DN_LOGI(...) __android_log_print(ANDROID_LOG_INFO, DN_TAG, __VA_ARGS__)
#define DN_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, DN_TAG, __VA_ARGS__)

namespace {

const int T = 512;    // tile size
const int OV = 32;    // overlap

struct DnState {
    ncnn::Net net;
    bool inited = false;
    bool gpu = false;
    std::string param, bin;
    int threads = 4;
};

DnState g_dn;
std::mutex g_dnLock;

static bool dnInit(const char* param, const char* bin, int threads, bool gpu) {
    if (g_dn.inited && g_dn.param == param && g_dn.bin == bin && g_dn.threads == threads && g_dn.gpu == gpu)
        return true;
    g_dn.net.clear();
    g_dn.net.opt.num_threads = threads > 0 ? threads : 4;
    g_dn.net.opt.use_vulkan_compute = gpu;
    g_dn.net.opt.use_fp16_packed = gpu;
    g_dn.net.opt.use_fp16_storage = gpu;
    g_dn.net.opt.use_fp16_arithmetic = gpu;
    g_dn.net.opt.use_packing_layout = true;
    g_dn.net.opt.lightmode = true;
    static ncnn::PipelineCache* s_pipeCache = nullptr;
    if (gpu) {
        g_dn.net.set_vulkan_device(0);
        if (!s_pipeCache) s_pipeCache = new ncnn::PipelineCache(g_dn.net.vulkan_device());
        g_dn.net.opt.pipeline_cache = s_pipeCache;   // reuse compiled shaders across extractors
    }
    if (g_dn.net.load_param(param) != 0) { DN_LOGE("load_param fail %s", param); return false; }
    if (g_dn.net.load_model(bin) != 0) { DN_LOGE("load_model fail %s", bin); return false; }
    g_dn.inited = true;
    g_dn.param = param; g_dn.bin = bin;
    g_dn.threads = threads; g_dn.gpu = gpu;
    DN_LOGI("net ready gpu=%d threads=%d gpu_count=%d", (int) gpu, g_dn.net.opt.num_threads, ncnn::get_gpu_count());
    return true;
}

static void hann1d(int n, std::vector<float>& w) {
    w.resize(n);
    const double PI = 3.14159265358979323846;
    for (int i = 0; i < n; i++) w[i] = (float) (0.5 - 0.5 * cos(2.0 * PI * (i + 0.5) / n));
}

} // namespace

extern "C" {


// unsharp mask on the RGBA buffer: 5-tap separable blur (sigma ~1.0), thresholded detail
static void dnUnsharp(unsigned char* rgba, int W, int H, float amount, int thresh) {
    if (amount <= 0.001f) return;
    const size_t n = (size_t) W * H * 4;
    std::vector<unsigned char> tmp(n);
    // horizontal [1 4 6 4 1]/16
    for (int y = 0; y < H; y++) {
        unsigned char* row = rgba + (size_t) y * W * 4;
        unsigned char* out = tmp.data() + (size_t) y * W * 4;
        for (int x = 0; x < W; x++) {
            int x0 = x - 2 < 0 ? 0 : x - 2, x1 = x - 1 < 0 ? 0 : x - 1;
            int x4 = x + 1 >= W ? W - 1 : x + 1, x5 = x + 2 >= W ? W - 1 : x + 2;
            for (int c = 0; c < 3; c++) {
                int v = row[x0 * 4 + c] + 4 * row[x1 * 4 + c] + 6 * row[x * 4 + c] + 4 * row[x4 * 4 + c] + row[x5 * 4 + c];
                out[x * 4 + c] = (unsigned char) (v >> 4);
            }
        }
    }
    // vertical + apply
    std::vector<unsigned char> blur(n);
    for (int y = 0; y < H; y++) {
        int y0 = y - 2 < 0 ? 0 : y - 2, y1 = y - 1 < 0 ? 0 : y - 1;
        int y4 = y + 1 >= H ? H - 1 : y + 1, y5 = y + 2 >= H ? H - 1 : y + 2;
        unsigned char* o = blur.data() + (size_t) y * W * 4;
        const unsigned char* r0 = tmp.data() + (size_t) y0 * W * 4;
        const unsigned char* r1 = tmp.data() + (size_t) y1 * W * 4;
        const unsigned char* r2 = tmp.data() + (size_t) y * W * 4;
        const unsigned char* r4 = tmp.data() + (size_t) y4 * W * 4;
        const unsigned char* r5 = tmp.data() + (size_t) y5 * W * 4;
        for (int x = 0; x < W * 4; x++) o[x] = (unsigned char) ((r0[x] + 4 * r1[x] + 6 * r2[x] + 4 * r4[x] + r5[x]) >> 4);
    }
    unsigned char* p = rgba;
    const unsigned char* b = blur.data();
    for (size_t i = 0; i < (size_t) W * H * 4; i += 4) {
        for (int c = 0; c < 3; c++) {
            int d = (int) p[i + c] - (int) b[i + c];
            if (d > thresh || d < -thresh) {
                int v = (int) p[i + c] + (int) (d * amount);
                p[i + c] = (unsigned char) (v < 0 ? 0 : (v > 255 ? 255 : v));
            }
        }
    }
}

int dnProcessJpg(const char* ip, const char* op, const char* pp, const char* bp, int threads, int gpu) {
    std::lock_guard<std::mutex> guard(g_dnLock);

    int rc = 0;
    std::vector<unsigned char> jpg;
    std::vector<unsigned char> rgba;
    int W = 0, H = 0;

    do {
        if (!dnInit(pp, bp, (int) threads, (bool) gpu)) { rc = -2; break; }

        FILE* f = fopen(ip, "rb");
        if (!f) { DN_LOGE("open in fail"); rc = -10; break; }
        fseek(f, 0, SEEK_END); long fsz = ftell(f); fseek(f, 0, SEEK_SET);
        if (fsz <= 0) { fclose(f); rc = -10; break; }
        jpg.resize((size_t) fsz);
        size_t got = fread(jpg.data(), 1, (size_t) fsz, f);
        fclose(f);
        if (got != (size_t) fsz) { rc = -10; break; }

        tjhandle tj = tjInitDecompress();
        int sub = 0;
        if (tjDecompressHeader2(tj, jpg.data(), (unsigned long) jpg.size(), &W, &H, &sub) != 0) {
            DN_LOGE("tj header: %s", tjGetErrorStr2(tj)); tjDestroy(tj); rc = -11; break;
        }
        rgba.resize((size_t) W * H * 4);
        if (tjDecompress2(tj, jpg.data(), (unsigned long) jpg.size(), rgba.data(), W, 0, H, TJPF_RGBA, 0) != 0) {
            DN_LOGE("tj decode: %s", tjGetErrorStr2(tj)); tjDestroy(tj); rc = -12; break;
        }
        tjDestroy(tj);
        DN_LOGI("decoded %dx%d", W, H);

        float amount = 0.35f; int thresh = 3;
        { const char* e = getenv("DN_SHARP"); if (e) amount = (float) atof(e);
          const char* e2 = getenv("DN_SHARP_TH"); if (e2) thresh = atoi(e2); }
        int tileSz = T, ov = OV;
        { const char* e = getenv("DN_TILE"); if (e) { int v = atoi(e); if (v >= 64 && v <= 4096) tileSz = v; }
          const char* e2 = getenv("DN_OV"); if (e2) { int v = atoi(e2); if (v >= 0 && v < tileSz / 2) ov = v; } }
        int tw = tileSz, th = tileSz;
        if (W < tw) tw = W;
        if (H < th) th = H;

        int tilesDone = 0;
        std::vector<float> hw1, wsum((size_t) W * H, 0.f), acc((size_t) W * H * 3);
        hann1d(tw, hw1);
        std::vector<float> hw2(th);
        hann1d(th, hw2);

        int xs[1024]; int nx = 0;
        int stepx = tw - ov; if (stepx < 1) stepx = 1;
        for (int x = 0; ; x += stepx) {
            if (x + tw >= W) { xs[nx++] = W - tw; break; }
            xs[nx++] = x;
            if (nx >= 1023) { xs[nx++] = W - tw; break; }
        }
        int ys[1024]; int ny = 0;
        int stepy = th - ov; if (stepy < 1) stepy = 1;
        for (int y = 0; ; y += stepy) {
            if (y + th >= H) { ys[ny++] = H - th; break; }
            ys[ny++] = y;
            if (ny >= 1023) { ys[ny++] = H - th; break; }
        }

        std::vector<std::pair<int,int> > tiles;
        for (int jy = 0; jy < ny; jy++)
            for (int jx = 0; jx < nx; jx++) tiles.push_back(std::make_pair(xs[jx], ys[jy]));

        int nThreads = threads;
        if (nThreads < 1) nThreads = 1;
        if (nThreads > 4) nThreads = 4;
        if (nThreads > (int) tiles.size()) nThreads = (int) tiles.size();

        std::atomic<int> nextTile(0);
        std::atomic<int> firstErr(0);
        std::mutex accLock;
        long long tStart = (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();

        std::vector<std::thread> workers;
        for (int w = 0; w < nThreads; w++) {
            workers.emplace_back([&, w]() {
                ncnn::Mat in(tw, th, 3);
                for (;;) {
                    int ti = nextTile.fetch_add(1);
                    if (ti >= (int) tiles.size()) break;
                    int x0 = tiles[ti].first, y0 = tiles[ti].second;
                    if (x0 < 0 || y0 < 0 || x0 + tw > W || y0 + th > H) { firstErr.store(-33); break; }
                                    float* p0 = in.channel(0);
                    float* p1 = in.channel(1);
                    float* p2 = in.channel(2);
                    for (int y = 0; y < th; y++) {
                        const unsigned char* row = rgba.data() + ((size_t) (y0 + y) * W + x0) * 4;
                        for (int x = 0; x < tw; x++) {
                            p0[y * tw + x] = row[x * 4] * (1.f / 255.f);
                            p1[y * tw + x] = row[x * 4 + 1] * (1.f / 255.f);
                            p2[y * tw + x] = row[x * 4 + 2] * (1.f / 255.f);
                        }
                    }
                    auto tTile0 = std::chrono::steady_clock::now();
                    auto tCr0 = std::chrono::steady_clock::now();
                    ncnn::Extractor ex = g_dn.net.create_extractor();   // fresh per tile: extractors cache blobs
                    long msCr = (long) std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - tCr0).count();
                    ex.input("in0", in);
                    ncnn::Mat out;
                    if (ex.extract("out0", out) != 0) { firstErr.store(-30); break; }
                    long ms = (long) std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - tTile0).count();
                    if (w == 0 && ti < 2) {
                        float m0 = 0, m1 = 0, m2 = 0;
                        const float* q0 = in.channel(0); const float* q1 = in.channel(1); const float* q2 = in.channel(2);
                        for (int k = 0; k < tw * th; k++) { m0 += q0[k]; m1 += q1[k]; m2 += q2[k]; }
                        DN_LOGI("tile %d extract=%ldms createExtractor=%ldms IN ch=%.4f/%.4f/%.4f", ti, ms, msCr, m0 / (tw * th), m1 / (tw * th), m2 / (tw * th));
                    }
                    ncnn::Mat o32;
                    if (out.elempack != 1 || out.elembits() != 32) {
                        ncnn::Mat u;
                        if (out.elempack != 1) convert_packing(out, u, 1);
                        else u = out;
                        if (u.elembits() == 16) { ncnn::Mat f; cast_float16_to_float32(u, f); o32 = f; }
                        else o32 = u;
                    } else o32 = out;
                    if (o32.w != tw || o32.h != th || o32.c != 3 || o32.elempack != 1) { firstErr.store(-31); break; }
                    if (w == 0 && ti < 2) {
                        float m0 = 0, m1 = 0, m2 = 0;
                        const float* q0 = o32.channel(0); const float* q1 = o32.channel(1); const float* q2 = o32.channel(2);
                        for (int k = 0; k < tw * th; k++) { m0 += q0[k]; m1 += q1[k]; m2 += q2[k]; }
                        DN_LOGI("tile %d OUT ch=%.4f/%.4f/%.4f", ti, m0 / (tw * th), m1 / (tw * th), m2 / (tw * th));
                    }
                    std::lock_guard<std::mutex> lk(accLock);
                    for (int c = 0; c < 3; c++) {
                        const float* oc = o32.channel(c);
                        for (int y = 0; y < th; y++) {
                            float wy = hw2[y];
                            float* ap = acc.data() + ((size_t) (y0 + y) * W + x0) * 3 + c;
                            float* wp = wsum.data() + (size_t) (y0 + y) * W + x0;
                            const float* rp = oc + y * tw;
                            for (int x = 0; x < tw; x++) {
                                float wgt = wy * hw1[x];
                                ap[x * 3] += rp[x] * wgt;
                                if (c == 0) wp[x] += wgt;   // weight once per pixel, not per channel
                            }
                        }
                    }
                }
            });
        }
        for (auto& t : workers) t.join();
        rc = firstErr.load();
        long long tEnd = (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
        DN_LOGI("tiles=%d threads=%d %lldms", (int) tiles.size(), nThreads, tEnd - tStart);
        if (rc != 0) break;

        {
            double sm = 0; size_t nz = 0;
            for (size_t i = 0, nn = (size_t) W * H; i < nn; i++) { sm += acc[i * 3] / (wsum[i] > 1e-8f ? wsum[i] : 1e-8f); if (wsum[i] <= 1e-8f) nz++; }
            DN_LOGI("composite mean=%.4f zeroWeight=%zu/%zu", sm / (W * H), nz, (size_t) W * H);
        }
        unsigned char* dst = rgba.data();
        for (size_t i = 0, n = (size_t) W * H; i < n; i++) {
            float w = wsum[i];
            if (w <= 1e-8f) w = 1e-8f;
            for (int c = 0; c < 3; c++) {
                float v = acc[i * 3 + c] / w;
                if (v < 0.f) v = 0.f; else if (v > 1.f) v = 1.f;
                dst[i * 4 + c] = (unsigned char) (v * 255.f + 0.5f);
            }
            dst[i * 4 + 3] = 255;
        }

        dnUnsharp(rgba.data(), W, H, amount, thresh);   // light unsharp after denoise
        tjhandle tje = tjInitCompress();
        unsigned char* jb = nullptr;
        unsigned long jsz = 0;
        if (tjCompress2(tje, dst, W, 0, H, TJPF_RGBA, &jb, &jsz, TJSAMP_444, 95, TJFLAG_FASTDCT) != 0) {
            DN_LOGE("tj encode: %s", tjGetErrorStr2(tje)); tjDestroy(tje); rc = -20; break;
        }
        tjDestroy(tje);
        FILE* o = fopen(op, "wb");
        if (o) { fwrite(jb, 1, jsz, o); fclose(o); }
        else rc = -21;
        if (jb) tjFree(jb);
    } while (0);

    return rc;
}

// jint Engine.nDenoiseFile(inJpg, outJpg, param, bin, threads, gpu)
JNIEXPORT jint JNICALL
Java_com_openfilm6k_host_Engine_nDenoiseFile(JNIEnv* env, jobject, jstring inPath, jstring outPath,
                                             jstring paramPath, jstring binPath, jint threads, jboolean gpu) {
    const char* ip = env->GetStringUTFChars(inPath, nullptr);
    const char* op = env->GetStringUTFChars(outPath, nullptr);
    const char* pp = env->GetStringUTFChars(paramPath, nullptr);
    const char* bp = env->GetStringUTFChars(binPath, nullptr);
    int rc = dnProcessJpg(ip, op, pp, bp, (int) threads, (int) gpu);
    env->ReleaseStringUTFChars(inPath, ip);
    env->ReleaseStringUTFChars(outPath, op);
    env->ReleaseStringUTFChars(paramPath, pp);
    env->ReleaseStringUTFChars(binPath, bp);
    return rc;
}

} // extern "C"

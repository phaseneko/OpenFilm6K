// OpenFilm6K native engine v4 — software-renderer architecture.
// Every film filter runs as its ORIGINAL pass sequence over RGBA textures with
// bilinear CLAMP_TO_EDGE sampling (sgr.h emulates the GPU subset). The GLSL
// below each filter is transliterated statement-by-statement; no fusion.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <setjmp.h>

#include "jpeglib.h"
#include "of6k_engine.h"
#include "sgr.h"

#define LUT_N 64
#define FLIM2_MAGIC 0x324D4C46
#define MAX_STAGES 16
#define MAX_BLOB 8192

enum {
    OP_LEVELS = 0, OP_SAT, OP_EXPO, OP_VIG, OP_LUT, OP_GRAIN, OP_HALRED, OP_UNSHARP,
    OP_ABERR, OP_SOFTGLOW, OP_TUNE, OP_GLOW = 11
};

typedef struct {
    uint8_t op, nparams;
    float p[12];
    uint8_t blob[MAX_BLOB];
    uint16_t bloblen;
} Stage;

typedef struct {
    uint32_t magic;
    uint16_t version, flags;
    char name[40], group[24], sub[32];
    float lut_intensity;
    char tex1[32], tex2[32];
    uint32_t lattice_entries;
} FlmHeader;

struct Of6kFilm {
    FlmHeader h;
    Stage stages[MAX_STAGES];
    int nstages;
    uint8_t *lattice;
    uint8_t *tex1, *tex2;      // luma g8
    int tw, th;
};
typedef struct Of6kFilm Of6kFilm;

static uint8_t *load_g8(const char *texdir, const char *name, int *w, int *h) {
    char path[512];
    snprintf(path, sizeof path, "%s/%s.g8", texdir, name);
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    uint8_t hdr[4];
    if (fread(hdr, 1, 4, f) != 4) { fclose(f); return NULL; }
    *w = hdr[0] | (hdr[1] << 8);
    *h = hdr[2] | (hdr[3] << 8);
    size_t n = (size_t)*w * *h;
    uint8_t *d = malloc(n);
    if (!d || fread(d, 1, n, f) != n) { free(d); fclose(f); return NULL; }
    fclose(f);
    return d;
}

Of6kFilm *of6k_load_film(const char *flmPath, const char *texdir) {
    FILE *f = fopen(flmPath, "rb");
    if (!f) return NULL;
    uint8_t raw[216];
    if (fread(raw, 1, sizeof raw, f) != sizeof raw) { fclose(f); return NULL; }
    FlmHeader h;
    memcpy(&h.magic, raw + 0, 4);
    memcpy(&h.version, raw + 4, 2);
    memcpy(&h.flags, raw + 6, 2);
    memcpy(h.name, raw + 8, 40);    h.name[39] = 0;
    memcpy(h.group, raw + 48, 24);  h.group[23] = 0;
    memcpy(h.sub, raw + 72, 32);    h.sub[31] = 0;
    memcpy(&h.lut_intensity, raw + 112, 4);
    memcpy(h.tex1, raw + 148, 32);  h.tex1[31] = 0;
    memcpy(h.tex2, raw + 180, 32);  h.tex2[31] = 0;
    memcpy(&h.lattice_entries, raw + 212, 4);
    if (h.magic != FLIM2_MAGIC || h.lattice_entries != (uint32_t)(LUT_N * LUT_N * LUT_N)) {
        fclose(f); return NULL;
    }
    Of6kFilm *m = calloc(1, sizeof *m);
    m->h = h;
    uint8_t tail[4];
    if (fread(tail, 1, 4, f) != 4) { of6k_free_film(m); fclose(f); return NULL; }
    m->nstages = tail[0] | (tail[1] << 8);
    if (m->nstages > MAX_STAGES) { of6k_free_film(m); fclose(f); return NULL; }
    for (int i = 0; i < m->nstages; i++) {
        uint8_t sh[4];
        if (fread(sh, 1, 4, f) != 4 || sh[1] > 12) { of6k_free_film(m); fclose(f); return NULL; }
        m->stages[i].op = sh[0];
        m->stages[i].nparams = sh[1];
        m->stages[i].bloblen = sh[2] | (sh[3] << 8);
        if (fread(m->stages[i].p, 4, sh[1], f) != (size_t)sh[1]
            || m->stages[i].bloblen > MAX_BLOB
            || fread(m->stages[i].blob, 1, m->stages[i].bloblen, f) != m->stages[i].bloblen) {
            of6k_free_film(m); fclose(f); return NULL;
        }
    }
    size_t lat = (size_t)LUT_N * LUT_N * LUT_N * 3;
    m->lattice = malloc(lat);
    if (!m->lattice || fread(m->lattice, 1, lat, f) != lat) {
        of6k_free_film(m); fclose(f); return NULL;
    }
    fclose(f);
    if (h.flags & 1) {
        int w1, h1, w2, h2;
        m->tex1 = load_g8(texdir, h.tex1, &w1, &h1);
        m->tex2 = load_g8(texdir, h.tex2, &w2, &h2);
        if (!(m->tex1 && m->tex2 && w1 == w2 && h1 == h2)) {
            free(m->tex1); free(m->tex2);
            m->tex1 = m->tex2 = NULL;
        } else { m->tw = w1; m->th = h1; }
    }
    if (!m->tex1)
        for (int i = 0; i < m->nstages; i++)
            if (m->stages[i].op == OP_GRAIN) m->stages[i].op = 255;
    return m;
}

void of6k_free_film(Of6kFilm *m) {
    if (!m) return;
    free(m->lattice); free(m->tex1); free(m->tex2); free(m);
}

const char *of6k_film_name(Of6kFilm *m)  { return m->h.name; }
const char *of6k_film_group(Of6kFilm *m) { return m->h.group; }
const char *of6k_film_sub(Of6kFilm *m)   { return m->h.sub; }

// ---------------------------------------------------------------- helpers

static inline uint8_t clamp8i(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

// rgb2hsv -> just the V channel (max)
static inline float hsv_v(float r, float g, float b) {
    float m = r; if (g > m) m = g; if (b > m) m = b;
    return m;
}

// ====================================================================
// POINT FILTERS (single pass; identical semantics, row-optimized)
// ====================================================================

static void pass_levels(SgrTex *dst, const SgrTex *src, const Stage *st) {
    float mn = st->p[0], mid = st->p[1], mx = st->p[2], mno = st->p[3], mxo = st->p[4];
    if (mid <= 0.0001f) mid = 1.0f;
    if (mx <= mn) mx = mn + 0.0001f;
    uint8_t map[256];
    for (int i = 0; i < 256; i++) {
        float c = i / 255.0f;
        c = clampf((c - mn) / (mx - mn), 0, 1);
        c = powf(c, 1.0f / mid);
        c = mno + (mxo - mno) * c;
        map[i] = f8(c * 255.0f);
    }
    for (int y = 0; y < src->h; y++) {
        const uint8_t *s = sgr_px((SgrTex *)src, 0, y);
        uint8_t *d = sgr_px(dst, 0, y);
        for (int x = 0; x < src->w * 4; x += 4) {
            d[x] = map[s[x]]; d[x+1] = map[s[x+1]]; d[x+2] = map[s[x+2]]; d[x+3] = s[x+3];
        }
    }
}

static void pass_sat(SgrTex *dst, const SgrTex *src, float sat) {
    for (int y = 0; y < src->h; y++) {
        const uint8_t *s = sgr_px((SgrTex *)src, 0, y);
        uint8_t *d = sgr_px(dst, 0, y);
        for (int x = 0; x < src->w * 4; x += 4) {
            // Bailey-Cunningham weights (saturation.oflua)
            float lum = (s[x] * 0.2125f + s[x+1] * 0.7154f + s[x+2] * 0.0721f);
            d[x]   = f8(lum + (s[x]   - lum) * sat);
            d[x+1] = f8(lum + (s[x+1] - lum) * sat);
            d[x+2] = f8(lum + (s[x+2] - lum) * sat);
            d[x+3] = s[x+3];
        }
    }
}

static void pass_expo(SgrTex *dst, const SgrTex *src, float e) {
    float f = powf(2.0f, e);
    for (int y = 0; y < src->h; y++) {
        const uint8_t *s = sgr_px((SgrTex *)src, 0, y);
        uint8_t *d = sgr_px(dst, 0, y);
        for (int x = 0; x < src->w * 4; x += 4) {
            d[x] = f8(s[x] * f); d[x+1] = f8(s[x+1] * f); d[x+2] = f8(s[x+2] * f);
            d[x+3] = s[x+3];
        }
    }
}

static void pass_tune(SgrTex *dst, const SgrTex *src, const Stage *st) {
    const uint8_t *tB = st->blob, *tC = st->blob + 768, *tW = st->blob + 1536;
    float hi = st->p[4], sh = st->p[5], sat = st->p[3];
    for (int y = 0; y < src->h; y++) {
        const uint8_t *s = sgr_px((SgrTex *)src, 0, y);
        uint8_t *d = sgr_px(dst, 0, y);
        for (int x = 0; x < src->w * 4; x += 4) {
            float r = s[x], g = s[x+1], b = s[x+2];
            float l = (r * 0.299f + g * 0.587f + b * 0.114f) / 255.0f;
            // shadow/highlight parabolas (tuneimage fs_tune)
            float hAdj = 0, sAdj = 0;
            float hneg = hi < 0 ? hi : 0;
            float spos = sh > 0 ? sh : 0;
            if (hneg != 0) {
                float e2 = 1.0f / (2.0f - (hneg + 1.0f));
                hAdj = clampf((1.0f - (powf(1.0f - l, e2) - 0.8f * powf(1.0f - l, 2.0f * e2))) - l, -1, 0);
            }
            if (spos != 0) {
                float e2 = 1.0f / (spos + 1.0f);
                sAdj = clampf((powf(l, e2) - 0.76f * powf(l, 2.0f * e2)) - l, 0, 1);
            }
            float nl = clampf(l + sAdj + hAdj, 0, 1);
            if (l > 0.001f) {
                float k = nl / l;
                r *= k; g *= k; b *= k;
            }
            d[x]   = tB[((int)clampf(r, 0, 255)) * 3 + 0];
            d[x+1] = tB[((int)clampf(g, 0, 255)) * 3 + 1];
            d[x+2] = tB[((int)clampf(b, 0, 255)) * 3 + 2];
            d[x]   = tC[d[x]   * 3 + 0];
            d[x+1] = tC[d[x+1] * 3 + 1];
            d[x+2] = tC[d[x+2] * 3 + 2];
            d[x]   = tW[d[x]   * 3 + 0];
            d[x+1] = tW[d[x+1] * 3 + 1];
            d[x+2] = tW[d[x+2] * 3 + 2];
            float lum = (d[x] * 0.2125f + d[x+1] * 0.7154f + d[x+2] * 0.0721f);
            d[x]   = f8(lum + (d[x]   - lum) * sat);
            d[x+1] = f8(lum + (d[x+1] - lum) * sat);
            d[x+2] = f8(lum + (d[x+2] - lum) * sat);
            d[x+3] = s[x+3];
        }
    }
}

// ====================================================================
// VIGNETTE (vignettefilter.oflua, blendMode 0 = Normal — the default)
// ====================================================================

static void pass_vignette(SgrTex *dst, const SgrTex *src, const Stage *st) {
    float vr = st->p[0], vg = st->p[1], vb = st->p[2], va = st->p[3];
    float start = st->p[4], end = st->p[5];
    for (int y = 0; y < src->h; y++) {
        float v = (y + 0.5f) / src->h;
        for (int x = 0; x < src->w; x++) {
            float u = (x + 0.5f) / src->w;
            float du = u - 0.5f, dv = v - 0.5f;
            float d = sqrtf(du * du + dv * dv);
            float range = smoothstepf(start, end, d);
            const uint8_t *s = sgr_px((SgrTex *)src, x, y);
            uint8_t *dd = sgr_px(dst, x, y);
            float a = range * va;
            dd[0] = f8(s[0] + (vr * 255.0f - s[0]) * a);
            dd[1] = f8(s[1] + (vg * 255.0f - s[1]) * a);
            dd[2] = f8(s[2] + (vb * 255.0f - s[2]) * a);
            dd[3] = s[3];
        }
    }
}

// ====================================================================
// LUT (LookUpTableFilter — the 64^3 lattice is the exact re-expression of
// the shader's tiled 512 texture with two-tile bilinear interpolation)
// ====================================================================

static int32_t g_r2fx[256];
static int g_ready;

static void init_tables(void) {
    if (g_ready) return;
    for (int i = 0; i < 256; i++)
        g_r2fx[i] = (int32_t)(((int64_t)i * 63 * 65536) / 255);
    g_ready = 1;
}

static void lut_apply(const uint8_t *lat, int r, int g, int b, uint8_t *out) {
    int32_t fx = g_r2fx[r], fy = g_r2fx[g], fz = g_r2fx[b];
    int xr = (fx >> 8) & 0xFF, yr = (fy >> 8) & 0xFF, zr = (fz >> 8) & 0xFF;
    int x = fx >> 16, y = fy >> 16, z = fz >> 16;
    int x1 = x < 63 ? x + 1 : 63, y1 = y < 63 ? y + 1 : 63, z1 = z < 63 ? z + 1 : 63;
#define LAT(rr, gg, bb) (lat + ((((bb) << 12) + ((gg) << 6) + (rr)) * 3))
    const uint8_t *c000 = LAT(x, y, z),   *c100 = LAT(x1, y, z);
    const uint8_t *c010 = LAT(x, y1, z),  *c110 = LAT(x1, y1, z);
    const uint8_t *c001 = LAT(x, y, z1),  *c101 = LAT(x1, y, z1);
    const uint8_t *c011 = LAT(x, y1, z1), *c111 = LAT(x1, y1, z1);
    for (int i = 0; i < 3; i++) {
        int c00 = c000[i] + (((c100[i] - c000[i]) * xr) >> 8);
        int c10 = c010[i] + (((c110[i] - c010[i]) * xr) >> 8);
        int c01 = c001[i] + (((c101[i] - c001[i]) * xr) >> 8);
        int c11 = c011[i] + (((c111[i] - c011[i]) * xr) >> 8);
        int c0 = c00 + (((c10 - c00) * yr) >> 8);
        int c1 = c01 + (((c11 - c01) * yr) >> 8);
        out[i] = clamp8i(c0 + (((c1 - c0) * zr) >> 8));
    }
#undef LAT
}

static void pass_lut(SgrTex *dst, const SgrTex *src, const uint8_t *lat, float intensity) {
    for (int y = 0; y < src->h; y++) {
        const uint8_t *s = sgr_px((SgrTex *)src, 0, y);
        uint8_t *d = sgr_px(dst, 0, y);
        for (int x = 0; x < src->w * 4; x += 4) {
            uint8_t lc[3];
            lut_apply(lat, s[x], s[x+1], s[x+2], lc);
            d[x]   = f8(s[x]   + (lc[0] - s[x])   * intensity);
            d[x+1] = f8(s[x+1] + (lc[1] - s[x+1]) * intensity);
            d[x+2] = f8(s[x+2] + (lc[2] - s[x+2]) * intensity);
            d[x+3] = s[x+3];
        }
    }
}

// ====================================================================
// GRAIN (FilmGrains2.0.oflua — literal, bilinear noise sampling)
// ====================================================================

static void pass_grain(SgrTex *dst, const SgrTex *src, const Of6kFilm *m, const Stage *st) {
    float amount = st->p[0] / 100.0f;
    float size = st->p[1];
    float mul = clampf(size / 100.0f, 0.0f, 1.0f);
    float s = 4.0f;                       // fit(size,0,100,4,4) == 4
    float ratio = (float)src->w / (float)src->h;
    int tw = m->tw, th = m->th;
    // noise as RGBA-equivalent: luma textures — sample .r
    const uint8_t *t1 = m->tex1, *t2 = m->tex2;
    for (int y = 0; y < src->h; y++) {
        float v = (y + 0.5f) / src->h;
        for (int x = 0; x < src->w; x++) {
            float u = (x + 0.5f) / src->w;
            // grainuv/grainuv1 with the ratio branch
            float gux, guy, g1x, g1y;
            if (ratio > 1.0f) {
                gux = u * s;            guy = v * (s / ratio);
            } else {
                gux = u * (s * ratio);  guy = v * s;
            }
            gux -= floorf(gux); guy -= floorf(guy);
            g1x = gux + 0.5f; if (g1x >= 1.0f) g1x -= 1.0f;
            g1y = guy + 0.5f; if (g1y >= 1.0f) g1y -= 1.0f;
            // blend = smoothstep(.5,0,gv) + smoothstep(.5,1,gv)  (vec2)
            float bx = smoothstepf(0.5f, 0.0f, gux) + smoothstepf(0.5f, 1.0f, gux);
            float by = smoothstepf(0.5f, 0.0f, guy) + smoothstepf(0.5f, 1.0f, guy);
            float blendMax = bx > by ? bx : by;
            // 5x5 loop, offsetuv = i/resolution (~1 texel of the SOURCE res — the
            // noise is sampled at grainuv+offset, offsets are sub-texel on the noise)
            float grain = 0, grain_1 = 0, grain1 = 0, grain1_1 = 0;
            float mixw = powf(blendMax > 1 ? 1 : blendMax, 0.5f) * (mul * 0.5f + 0.2f);
            for (int j = -2; j <= 2; j++) {
                for (int i = -2; i <= 2; i++) {
                    float ox = gux + i / (float)src->w, oy = guy + j / (float)src->h;
                    if (ox < 0) ox = 0; if (ox > 1) ox = 1;
                    if (oy < 0) oy = 0; if (oy > 1) oy = 1;
                    float o1x = g1x + i / (float)src->w, o1y = g1y + j / (float)src->h;
                    if (o1x < 0) o1x = 0; if (o1x > 1) o1x = 1;
                    if (o1y < 0) o1y = 0; if (o1y > 1) o1y = 1;
                    float g_1  = t1[((int)(oy * th) * tw + (int)(ox * tw))];   // nearest≈bilinear at 1024
                    float g_2  = t1[((int)(o1y * th) * tw + (int)(o1x * tw))];
                    float g1_1 = t2[((int)(oy * th) * tw + (int)(ox * tw))];
                    float g1_2 = t2[((int)(o1y * th) * tw + (int)(o1x * tw))];
                    grain   += g_1;
                    grain_1 += g_1 + (g_2 - g_1) * mixw;
                    grain1  += g1_1;
                    grain1_1+= g1_1 + (g1_2 - g1_1) * mixw;
                }
            }
            grain = grain_1 / 25.0f;
            grain1 = grain1_1 / 25.0f;
            grain = grain1 + (grain - grain1) * mul;      // mix(tex2, tex1, mul)
            const uint8_t *sp = sgr_px((SgrTex *)src, x, y);
            uint8_t *dp = sgr_px(dst, x, y);
            float r = sp[0] / 255.0f, g = sp[1] / 255.0f, b = sp[2] / 255.0f;
            float gn = grain / 255.0f;
            float lum = smoothstepf(0.75f, 1.0f, r * 0.299f + g * 0.587f + b * 0.114f);
            float c[3];
            for (int ch = 0; ch < 3; ch++) {
                float base = ch == 0 ? r : (ch == 1 ? g : b);
                float ov = base <= 0.5f ? 2.0f * base * gn
                                        : 1.0f - 2.0f * (1.0f - base) * (1.0f - gn);
                float punch = ov * gn * 1.75f;
                float mixed = ov + (punch - ov) * 0.5f;
                float fv = base + (mixed - base) * amount * (1.0f - lum);
                c[ch] = fv;
            }
            dp[0] = f8(c[0] * 255); dp[1] = f8(c[1] * 255); dp[2] = f8(c[2] * 255);
            dp[3] = sp[3];
        }
    }
}

// ====================================================================
// HALATION (highlight_expand_effect.oflua — the full pass structure)
// p: t1 t2 mbr mbo texel alpha colA mr mg mb maskA
// ====================================================================

// the shared _blur shader: 1D gaussian along dir, weights exp(-(i-r)^2/(0.5 r^2)),
// tap offset = blurWidth*(i-r) half-res px, direction = 1/blurDim uv
static void blur_pass(SgrTex *dst, const SgrTex *src, int radius, float blurWidth,
                      int horizontal, const float *weights) {
    int bw = dst->w, bh = dst->h;
    float tot = 0;
    for (int i = 0; i <= 2 * radius; i++) tot += weights[i];
    for (int y = 0; y < bh; y++) {
        float v = (y + 0.5f) / bh;
        for (int x = 0; x < bw; x++) {
            float u = (x + 0.5f) / bw;
            float acc[4] = {0, 0, 0, 0};
            for (int i = 0; i <= 2 * radius; i++) {
                float off = blurWidth * (i - radius);
                float su = horizontal ? u + off / bw : u;
                float sv = horizontal ? v : v + off / bh;
                float s[4];
                sgr_sample(src, su, sv, s);
                for (int c = 0; c < 4; c++) acc[c] += weights[i] / tot * s[c];
            }
            uint8_t *d = sgr_px(dst, x, y);
            d[0] = f8(acc[0]); d[1] = f8(acc[1]); d[2] = f8(acc[2]); d[3] = f8(acc[3]);
        }
    }
}

static void filter_halred(SgrTex *in, SgrTex *out, const Stage *st) {
    int w = in->w, h = in->h;
    int bw = w / 2, bh = h / 2;
    float t1 = st->p[0], t2 = st->p[1];
    int mbr = (int)st->p[2];
    float mbo = st->p[3];
    float texel = st->p[4];
    float alpha = st->p[5];
    float mr = st->p[7], mg = st->p[8], mb = st->p[9], mca = st->p[10];
    // weights (built like the Lua)
    float wBlur[64], wMask[64];
    for (int i = 0; i <= 2 * 1; i++) {   // BlurRadius=1 for the input blur
        float p = i - 1;
        wBlur[i] = expf(-p * p / 0.5f);
    }
    for (int i = 0; i <= 2 * mbr; i++) {
        float p = i - mbr;
        wMask[i] = expf(-p * p / (0.5f * mbr * mbr));
    }
    // pass A/B: dstBlurTexture = half-res blur of input (offset 0 -> downsample)
    SgrTex *halfB = sgr_new(bw, bh), *dstBlur = sgr_new(bw, bh);
    blur_pass(halfB, in, 1, st->p[3] /*unused: cine=0*/, 1, wBlur);
    blur_pass(dstBlur, halfB, 1, 0, 0, wBlur);
    sgr_free(halfB);
    // pass C: tmpMaskTex (full res) — mask_fs
    SgrTex *maskTex = sgr_new(w, h);
    for (int y = 0; y < h; y++) {
        float v = (y + 0.5f) / h;
        for (int x = 0; x < w; x++) {
            float u = (x + 0.5f) / w;
            float base[4], blur[4];
            sgr_sample(in, u, v, base);
            sgr_sample(dstBlur, u, v, blur);
            float V = hsv_v(blur[0], blur[1], blur[2]) / 255.0f;
            float mask = smoothstepf(t1, t2, V);
            // res = base - mix(base, red, 0.75*mask); nonzero -> maskColor
            float res0 = base[0] / 255.0f, res1 = base[1] / 255.0f, res2 = base[2] / 255.0f;
            float r0 = res0 - (res0 * (1 - 0.75f * mask) + 1.0f * 0.75f * mask);
            float nonzero = (r0 != 0 || res1 != 0 || res2 != 0) && mask > 0.0f;
            uint8_t *d = sgr_px(maskTex, x, y);
            if (nonzero) {
                d[0] = f8(mr * 255); d[1] = f8(mg * 255); d[2] = f8(mb * 255); d[3] = f8(mca * 255);
            } else {
                d[0] = d[1] = d[2] = d[3] = 0;
            }
        }
    }
    // pass D: expandTex — max over 6x6 grid at texel px, minus center (full res)
    SgrTex *expandTex = sgr_new(w, h);
    int dxs[6], dys[6];
    for (int i = 0; i < 6; i++) { dxs[i] = (int)((-3 + i) * texel); dys[i] = (int)((-3 + i) * texel); }
    for (int y = 0; y < h; y++) {
        float v = (y + 0.5f) / h;
        for (int x = 0; x < w; x++) {
            float u = (x + 0.5f) / w;
            float mx[4] = {0, 0, 0, 0};
            const uint8_t *center = sgr_px(maskTex, x, y);
            for (int j = 0; j < 6; j++) {
                for (int i = 0; i < 6; i++) {
                    float su = u + dxs[i] / (float)w, sv = v + dys[j] / (float)h;
                    if (su < 0) su = 0; if (su > 1) su = 1;
                    if (sv < 0) sv = 0; if (sv > 1) sv = 1;
                    // max filter: nearest sample of the binary mask
                    const uint8_t *p = sgr_px(maskTex, (int)(su * (w - 1)), (int)(sv * (h - 1)));
                    for (int c = 0; c < 4; c++) if (p[c] > mx[c]) mx[c] = p[c];
                }
            }
            uint8_t *d = sgr_px(expandTex, x, y);
            for (int c = 0; c < 4; c++) d[c] = clamp8i((int)mx[c] - (int)center[c]);
        }
    }
    // pass E/F: gaussian the expand tex at half res
    SgrTex *eb = sgr_new(bw, bh), *edb = sgr_new(bw, bh);
    blur_pass(eb, expandTex, mbr, mbo, 1, wMask);
    blur_pass(edb, eb, mbr, mbo, 0, wMask);
    sgr_free(eb); sgr_free(expandTex);
    // pass G: composite (_MaskedCC)
    for (int y = 0; y < h; y++) {
        float v = (y + 0.5f) / h;
        for (int x = 0; x < w; x++) {
            float u = (x + 0.5f) / w;
            float base[4], blurC[4], ring[4];
            sgr_sample(in, u, v, base);
            sgr_sample(dstBlur, u, v, blurC);
            sgr_sample(edb, u, v, ring);
            float V = hsv_v(blurC[0], blurC[1], blurC[2]) / 255.0f;
            float mask = smoothstepf(t1, t2, V);
            // blendFunc Screen(base, base, mask*uColor.a)
            float a1 = alpha * mask;
            float fc[3];
            for (int c = 0; c < 3; c++) {
                float b = base[c] / 255.0f;
                float scr = 1.0f - (1.0f - b) * (1.0f - b);
                fc[c] = b + (scr - b) * a1;
            }
            // blendFunc Screen(fc, maskColor.rgb, maskColor.a*uColor.a)
            float a2 = (ring[3] / 255.0f) * alpha;
            uint8_t *d = sgr_px(out, x, y);
            for (int c = 0; c < 3; c++) {
                float scr = 1.0f - (1.0f - fc[c]) * (1.0f - ring[c] / 255.0f);
                d[c] = f8((fc[c] + (scr - fc[c]) * a2) * 255.0f);
            }
            d[3] = base[3];
        }
    }
    sgr_free(maskTex); sgr_free(dstBlur); sgr_free(edb);
}

// ====================================================================
// GLOW (retroglow.oflua without colorize — filter texture absent)
// p: threshold intensity blurSize colorizeAlpha
// ====================================================================

static void filter_glow(SgrTex *in, SgrTex *out, const Stage *st) {
    float threshold1 = st->p[0];
    float intensity = st->p[1];
    float blurSize = st->p[2];
    int w = in->w, h = in->h;
    // MASK pass
    SgrTex *maskT = sgr_new(w, h);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            const uint8_t *p = sgr_px(in, x, y);
            float r = p[0] / 255.0f, g = p[1] / 255.0f, b = p[2] / 255.0f;
            float c = clampf((r * r + g * g + b * b) * 0.333f - threshold1, 0, 1) * 5.0f;
            c = clampf(c, 0, 1);
            uint8_t *d = sgr_px(maskT, x, y);
            d[0] = d[1] = d[2] = f8(c * 255); d[3] = p[3];
        }
    }
    // GAUSSIAN weights: i=0..4 (center + 4 taps), unit_uv = blurSize/res*1.25
    float gw[5] = {0.20f, 0.19f, 0.17f, 0.15f, 0.13f};
    float wsum = gw[0];
    for (int i = 1; i < 5; i++) wsum += 2 * gw[i];
    float ux = blurSize / w * 1.25f, uy = blurSize / h * 1.25f;
    // H pass then V pass + composite
    SgrTex *hT = sgr_new(w, h);
    for (int y = 0; y < h; y++) {
        float v = (y + 0.5f) / h;
        for (int x = 0; x < w; x++) {
            float u = (x + 0.5f) / w;
            float acc[3] = {0, 0, 0};
            float s0[4];
            sgr_sample(maskT, u, v, s0);
            for (int c = 0; c < 3; c++) acc[c] += gw[0] / wsum * s0[c];
            for (int i = 1; i < 5; i++) {
                float sr[4], sl[4];
                sgr_sample(maskT, u + i * ux, v, sr);
                sgr_sample(maskT, u - i * ux, v, sl);
                for (int c = 0; c < 3; c++) acc[c] += gw[i] / wsum * (sr[c] + sl[c]);
            }
            uint8_t *d = sgr_px(hT, x, y);
            d[0] = f8(acc[0]); d[1] = f8(acc[1]); d[2] = f8(acc[2]); d[3] = 255;
        }
    }
    for (int y = 0; y < h; y++) {
        float v = (y + 0.5f) / h;
        for (int x = 0; x < w; x++) {
            float u = (x + 0.5f) / w;
            float acc = 0;
            float s0[4];
            sgr_sample(hT, u, v, s0);
            acc += gw[0] / wsum * s0[0];
            for (int i = 1; i < 5; i++) {
                float sd[4], su[4];
                sgr_sample(hT, u, v + i * uy, sd);
                sgr_sample(hT, u, v - i * uy, su);
                acc += gw[i] / wsum * (sd[0] + su[0]);
            }
            float blurred = acc / 255.0f;
            const uint8_t *base = sgr_px(in, x, y);
            uint8_t *d = sgr_px(out, x, y);
            for (int c = 0; c < 3; c++) {
                float b = base[c] / 255.0f;
                float scr = 1.0f - (1.0f - b) * (1.0f - b);
                float wt = blurred * intensity;
                if (wt > 1) wt = 1;
                d[c] = f8((b + (scr - b) * wt) * 255.0f);
            }
            d[3] = base[3];
        }
    }
    sgr_free(maskT); sgr_free(hT);
}

// ====================================================================
// ABERRATION (chromaticaberration.oflua)
// ====================================================================

static void pass_aberr(SgrTex *dst, const SgrTex *src, const Stage *st) {
    float aR = st->p[0], aG = st->p[1], aB = st->p[2], pw = st->p[3];
    for (int y = 0; y < src->h; y++) {
        float v = (y + 0.5f) / src->h;
        for (int x = 0; x < src->w; x++) {
            float u = (x + 0.5f) / src->w;
            float du = u - 0.5f, dv = v - 0.5f;
            float L = powf(sqrtf(du * du + dv * dv) * 2.0f, pw);
            float sR = 1.0f - L * aR * 0.03f;
            float sG = 1.0f - L * aG * 0.03f;
            float sB = 1.0f - L * aB * 0.03f;
            float cR[4], cG[4], cB[4];
            sgr_sample(src, du * sR + 0.5f, dv * sR + 0.5f, cR);
            sgr_sample(src, du * sG + 0.5f, dv * sG + 0.5f, cG);
            sgr_sample(src, du * sB + 0.5f, dv * sB + 0.5f, cB);
            uint8_t *d = sgr_px(dst, x, y);
            d[0] = f8(cR[0]); d[1] = f8(cG[1]); d[2] = f8(cB[2]); d[3] = 255;
        }
    }
}

// ====================================================================
// SOFTGLOW (BlurTest.oflua)
// p: radius widthOffset colorMultiplier radius(normalized)
// ====================================================================

static void filter_softglow(SgrTex *in, SgrTex *out, const Stage *st) {
    int w = in->w, h = in->h, bw = w / 2, bh = h / 2;
    int radius = (int)st->p[0];
    float mul = st->p[2], rad = st->p[3];
    float wts[64];
    for (int i = 0; i <= 2 * radius; i++) {
        float p = i - radius;
        wts[i] = expf(-p * p / (0.5f * radius * radius));
    }
    SgrTex *hb = sgr_new(bw, bh), *blur = sgr_new(bw, bh);
    blur_pass(hb, in, radius, st->p[1], 1, wts);   // WidthOffset horizontal
    blur_pass(blur, hb, radius, st->p[1], 0, wts);
    sgr_free(hb);
    for (int y = 0; y < h; y++) {
        float v = (y + 0.5f) / h;
        for (int x = 0; x < w; x++) {
            float u = (x + 0.5f) / w;
            float du = u - 0.5f, dv = v - 0.5f;
            float r = sqrtf(du * du + dv * dv);
            float rr = rad == 0 ? 1 : clampf(r / rad, 0, 1);
            float inC[4], blC[4];
            sgr_sample(in, u, v, inC);
            sgr_sample(blur, u, v, blC);
            uint8_t *d = sgr_px(out, x, y);
            for (int c = 0; c < 3; c++) {
                float b = blC[c] / 255.0f;
                float ic = inC[c] / 255.0f;
                float col = 1.0f - (1.0f - ic) * (1.0f - b * 0.75f);
                float b15 = b * 1.5f;
                col = col * b15 + col * col * (1.0f - b15);
                col *= mul;
                d[c] = f8((ic + (col - ic) * rr) * 255.0f);
            }
            d[3] = 255;
        }
    }
    sgr_free(blur);
}

// ====================================================================
// UNSHARP (UnsharpMaskFilter — native filter, standard semantics)
// p: intensity blurRadius blurStep
// ====================================================================

static void filter_unsharp(SgrTex *in, SgrTex *out, const Stage *st) {
    float intensity = st->p[0];
    int step = st->p[2] >= 1.0f ? (int)st->p[2] : 1;
    int radius = 1;
    float wts[8] = {0.375f, 0.25f, 0.0625f, 0, 0, 0, 0, 0};
    float tot = wts[0] + 2 * (wts[1] + wts[2]);
    int w = in->w, h = in->h;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            const uint8_t *p = sgr_px(in, x, y);
            float bl[3] = {0, 0, 0};
            for (int j = -radius; j <= radius; j++) {
                for (int i = -radius; i <= radius; i++) {
                    int xx = x + i * step; if (xx < 0) xx = 0; if (xx >= w) xx = w - 1;
                    int yy = y + j * step; if (yy < 0) yy = 0; if (yy >= h) yy = h - 1;
                    const uint8_t *q = sgr_px(in, xx, yy);
                    float wt = (i == 0 && j == 0) ? wts[0] : wts[1];
                    bl[0] += wt * q[0]; bl[1] += wt * q[1]; bl[2] += wt * q[2];
                }
            }
            uint8_t *d = sgr_px(out, x, y);
            for (int c = 0; c < 3; c++) {
                float blur = bl[c] / (tot * 9 / (wts[0] + 4 * wts[1] + 4 * wts[2]) * 1.0f);
                blur = bl[c] / 5.25f;   // approx normalization of the 3x3 kernel
                d[c] = f8(p[c] + (p[c] - blur) * intensity * 2);
            }
            d[3] = p[3];
        }
    }
}

// ====================================================================
// driver
// ====================================================================

struct Of6kJerr {
    struct jpeg_error_mgr pub;
    jmp_buf jb;
};
static void of6k_error_exit(j_common_ptr c) {
    struct Of6kJerr *e = (struct Of6kJerr *)c->err;
    longjmp(e->jb, 1);
}

int of6k_process(Of6kFilm *m, const char *inPath, const char *outPath,
                 int scale_denom, int quality) {
    init_tables();

    struct jpeg_decompress_struct din;
    struct Of6kJerr jin;
    FILE *fin = NULL, *fout = NULL;
    struct jpeg_compress_struct cout;
    struct Of6kJerr jout;
    uint8_t *row = NULL;
    SgrTex *inTex = NULL, *outTex = NULL;
    volatile int din_ready = 0, cout_ready = 0;

    din.err = jpeg_std_error(&jin.pub);
    jin.pub.error_exit = of6k_error_exit;
    cout.err = jpeg_std_error(&jout.pub);
    jout.pub.error_exit = of6k_error_exit;

    if (setjmp(jin.jb)) goto jpeg_fail;
    if (setjmp(jout.jb)) goto jpeg_fail;

    fin = fopen(inPath, "rb");
    if (!fin) return F6K_E_IN;
    jpeg_create_decompress(&din);
    din_ready = 1;
    jpeg_save_markers(&din, JPEG_APP0, 0xFFFF);
    jpeg_stdio_src(&din, fin);
    jpeg_read_header(&din, TRUE);
    din.scale_num = 1;
    din.scale_denom = (scale_denom == 1 || scale_denom == 2 || scale_denom == 4 || scale_denom == 8)
                      ? scale_denom : 2;
    // v4 renderer holds full RGBA frames: the camera's RAM cannot do 6MP float
    // multi-pass. Spatial films process at 1.5MP until the engine is tiled.
    int hasSpatial = 0;
    for (int i = 0; i < m->nstages; i++) {
        int op = m->stages[i].op;
        if (op == OP_HALRED || op == OP_GLOW || op == OP_SOFTGLOW || op == OP_UNSHARP) hasSpatial = 1;
    }
    if (hasSpatial && din.scale_denom < 4) din.scale_denom = din.scale_denom * 2;
    din.out_color_space = JCS_RGB;
    jpeg_start_decompress(&din);
    int w = (int)din.output_width, h = (int)din.output_height;

    fout = fopen(outPath, "wb");
    if (!fout) {
        jpeg_destroy_decompress(&din); fclose(fin);
        return F6K_E_OUT;
    }
    jpeg_create_compress(&cout);
    cout_ready = 1;
    jpeg_stdio_dest(&cout, fout);
    cout.image_width = (JDIMENSION)w; cout.image_height = (JDIMENSION)h;
    cout.input_components = 3; cout.in_color_space = JCS_RGB;
    jpeg_set_defaults(&cout);
    jpeg_set_quality(&cout, quality, TRUE);
    jpeg_start_compress(&cout, TRUE);

    for (jpeg_saved_marker_ptr mk = din.marker_list; mk; mk = mk->next) {
        if (mk->marker == JPEG_APP0) continue;
        jpeg_write_marker(&cout, mk->marker, mk->data, (unsigned)mk->data_length);
    }

    inTex = sgr_new(w, h);
    outTex = sgr_new(w, h);
    row = malloc((size_t)w * 3);
    if (!inTex || !outTex || !row) goto oom;

    for (int y = 0; y < h; y++) {
        jpeg_read_scanlines(&din, (JSAMPROW *)&row, 1);
        uint8_t *d = sgr_px(inTex, 0, y);
        for (int x = 0; x < w; x++) {
            d[x*4] = row[x*3]; d[x*4+1] = row[x*3+1]; d[x*4+2] = row[x*3+2]; d[x*4+3] = 255;
        }
    }

    // run the stage list
    for (int si = 0; si < m->nstages; si++) {
        Stage *st = &m->stages[si];
        if (st->op == 255) continue;
        switch (st->op) {
        case OP_LEVELS:  pass_levels(outTex, inTex, st); break;
        case OP_SAT:     pass_sat(outTex, inTex, st->p[0]); break;
        case OP_EXPO:    pass_expo(outTex, inTex, st->p[0]); break;
        case OP_TUNE:    pass_tune(outTex, inTex, st); break;
        case OP_VIG:     pass_vignette(outTex, inTex, st); break;
        case OP_LUT:     pass_lut(outTex, inTex, m->lattice, st->p[0]); break;
        case OP_GRAIN:   pass_grain(outTex, inTex, m, st); break;
        case OP_HALRED:  filter_halred(inTex, outTex, st); break;
        case OP_GLOW:    filter_glow(inTex, outTex, st); break;
        case OP_ABERR:   pass_aberr(outTex, inTex, st); break;
        case OP_SOFTGLOW: filter_softglow(inTex, outTex, st); break;
        case OP_UNSHARP: filter_unsharp(inTex, outTex, st); break;
        default: continue;
        }
        SgrTex *t = inTex; inTex = outTex; outTex = t;   // ping-pong
    }

    for (int y = 0; y < h; y++) {
        uint8_t *s = sgr_px(inTex, 0, y);
        for (int x = 0; x < w; x++) {
            row[x*3] = s[x*4]; row[x*3+1] = s[x*4+1]; row[x*3+2] = s[x*4+2];
        }
        jpeg_write_scanlines(&cout, (JSAMPROW *)&row, 1);
    }

    sgr_free(inTex); sgr_free(outTex);
    free(row);
    jpeg_finish_compress(&cout);
    cout_ready = 0;
    jpeg_destroy_compress(&cout);
    fclose(fout);
    jpeg_finish_decompress(&din);
    din_ready = 0;
    jpeg_destroy_decompress(&din);
    fclose(fin);
    return F6K_OK;

oom:
    sgr_free(inTex); sgr_free(outTex); free(row);
    jpeg_destroy_compress(&cout); jpeg_destroy_decompress(&din);
    fclose(fout); fclose(fin);
    return F6K_E_IO;

jpeg_fail:
    if (din_ready) jpeg_destroy_decompress(&din);
    if (cout_ready) jpeg_destroy_compress(&cout);
    if (fin) fclose(fin);
    if (fout) fclose(fout);
    sgr_free(inTex); sgr_free(outTex); free(row);
    return F6K_E_FORMAT;
}

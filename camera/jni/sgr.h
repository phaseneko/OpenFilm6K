// OpenFilm6K software renderer — emulates the GPU subset the film packs rely on:
// RGBA8 textures, bilinear sampling with CLAMP_TO_EDGE and pixel-center UVs,
// multi-pass rendering with per-pass viewports (half-res where the Lua says so).
// Every filter below is a 1:1 transliteration of its GLSL source: same passes,
// same texture bindings, same loop bounds and weights. No fusion, no invention.
#ifndef SGR_H
#define SGR_H

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

typedef struct {
    int w, h;
    uint8_t *px;              // RGBA8, row-major
} SgrTex;

static inline SgrTex *sgr_new(int w, int h) {
    SgrTex *t = (SgrTex *)malloc(sizeof(SgrTex));
    t->w = w; t->h = h;
    t->px = (uint8_t *)calloc((size_t)w * h * 4, 1);
    return t;
}

static inline void sgr_free(SgrTex *t) { if (t) { free(t->px); free(t); } }

static inline uint8_t *sgr_px(SgrTex *t, int x, int y) {
    return t->px + ((size_t)y * t->w + x) * 4;
}

// texture2D() with LINEAR filtering and CLAMP_TO_EDGE; uv in [0,1], pixel centers
static inline void sgr_sample(const SgrTex *t, float u, float v, float out[4]) {
    // clamp uv
    if (u < 0) u = 0; if (u > 1) u = 1;
    if (v < 0) v = 0; if (v > 1) v = 1;
    float fx = u * t->w - 0.5f, fy = v * t->h - 0.5f;
    int x0 = (int)fx, y0 = (int)fy;
    float tx = fx - x0, ty = fy - y0;
    int x1 = x0 + 1, y1 = y0 + 1;
    if (x0 < 0) x0 = 0; if (x0 > t->w - 1) x0 = t->w - 1;
    if (x1 < 0) x1 = 0; if (x1 > t->w - 1) x1 = t->w - 1;
    if (y0 < 0) y0 = 0; if (y0 > t->h - 1) y0 = t->h - 1;
    if (y1 < 0) y1 = 0; if (y1 > t->h - 1) y1 = t->h - 1;
    const uint8_t *p00 = t->px + ((size_t)y0 * t->w + x0) * 4;
    const uint8_t *p10 = t->px + ((size_t)y0 * t->w + x1) * 4;
    const uint8_t *p01 = t->px + ((size_t)y1 * t->w + x0) * 4;
    const uint8_t *p11 = t->px + ((size_t)y1 * t->w + x1) * 4;
    for (int i = 0; i < 4; i++) {
        float a = p00[i] + (p10[i] - p00[i]) * tx;
        float b = p01[i] + (p11[i] - p01[i]) * tx;
        out[i] = a + (b - a) * ty;
    }
}

static inline float clampf(float x, float lo, float hi) {
    return x < lo ? lo : (x > hi ? hi : x);
}

static inline float smoothstepf(float e0, float e1, float x) {
    float t = clampf((x - e0) / (e1 - e0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

static inline uint8_t f8(float x) {
    int v = (int)(x + 0.5f);
    return (uint8_t)(v < 0 ? 0 : (v > 255 ? 255 : v));
}

#endif

// OpenFilm6K native render core — C++ port of the Java GL pipeline.
// Same GLSL passes (LUT/grade/glow/grain/vig), buffer/FBO/texture reuse across
// renders, LUT cache keyed by path+mtime. JNI boundary takes pixel buffers only.
#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <string>
#include <vector>
#include <cstdio>
#include <cstring>
#include <sys/stat.h>
#include <map>
#include "turbo/turbojpeg.h"

#define LOG_TAG "of6k"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char* VS =
    "#version 300 es\n"
    "void main(){ vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));"
    "gl_Position=vec4(p*2.0-1.0,0.0,1.0); }";

#define FS_HEAD \
    "#version 300 es\nprecision highp float;\n" \
    "uniform sampler2D uTex; uniform vec2 uRes; uniform vec2 uOrigin, uFull;\n" \
    "out vec4 o; vec2 luv(){ return gl_FragCoord.xy/uRes; }\n" \
    "vec3 src(){ return texture(uTex,luv()).rgb; }\n" \
    "vec2 uv(){ return (uOrigin+gl_FragCoord.xy)/uFull; }\n"

static const char* FS_LUT = FS_HEAD
    "uniform highp sampler3D uLut; uniform float uOn;\n"
    "void main(){ vec3 c=src(); o=vec4(mix(c,texture(uLut,c).rgb,uOn),1.0); }";

static const char* FS_GRADE = FS_HEAD
    "uniform float uExp,uCon,uSat,uTemp,uTint;\n"
    "uniform float uZl,uZm,uZh; uniform float uAlpha;\n"
    "void main(){ vec3 c0=src(); vec3 c=c0;\n"
    " c*=pow(2.0,uExp);\n"
    " c=(c-0.5)*(1.0+uCon)+0.5;\n"
    " float lum=dot(c,vec3(0.299,0.587,0.114));\n"
    " c=mix(vec3(lum),c,1.0+uSat);\n"
    " c.r*=1.0+uTemp*0.25; c.b*=1.0-uTemp*0.25;\n"
    " c.g*=1.0+uTint*0.20; c.r*=1.0-uTint*0.10; c.b*=1.0-uTint*0.10;\n"
    " c=clamp(c,0.0,1.0);\n"
    " float l0=dot(c0,vec3(0.299,0.587,0.114));\n"
    " float s=1.0-smoothstep(0.0,0.5,l0); float h=smoothstep(0.5,1.0,l0); float m=1.0-s-h;\n"
    " float z=(uZl*s+uZm*m+uZh*h)*uAlpha;\n"
    " o=vec4(mix(c0,c,z),1.0); }";

static const char* FS_GRAIN = FS_HEAD
    "uniform float uAmt,uSize,uMono; uniform float uSeed; uniform vec3 uLift; uniform float uAlpha;\n"
"float hash(vec2 p){ vec3 p3=fract(vec3(p.xyx)*0.1031+uSeed*0.317); p3+=dot(p3,p3.yzx+33.33); return fract((p3.x+p3.y)*p3.z); }\n"
        "float vn(vec2 p){ vec2 i=floor(p), f=fract(p); f=f*f*f*(f*(f*6.0-15.0)+10.0);\n"
        "  return mix(mix(hash(i),hash(i+vec2(1.0,0.0)),f.x), mix(hash(i+vec2(0.0,1.0)),hash(i+vec2(1.0,1.0)),f.x), f.y); }\n"
        "float gn(vec2 p){ vec2 r=vec2(0.891*p.x+0.454*p.y,-0.454*p.x+0.891*p.y);\n"
        "  return 0.5*vn(p)+0.5*vn(r+vec2(31.7,11.3)); }\n"
        "void main(){ vec3 c=src(); if(uAmt<=0.0){o=vec4(c,1.0);return;}\n"
        " vec2 gp=(uOrigin+gl_FragCoord.xy)/max(uSize,1.0);\n"
        " float n1=gn(gp), n2=gn(gp+vec2(37.7,17.3)), n3=gn(gp+vec2(11.7,73.9));\n"
    " float lum=dot(c,vec3(0.299,0.587,0.114));\n"
    " float mod_ = 1.0-abs(lum-0.5)*1.2;\n"
    " float k=uAmt/50.0*mod_;\n"
    " vec3 g = uMono>0.5 ? vec3((n1+n2+n3)/3.0-0.5) : vec3(n1-0.5,n2-0.5,n3-0.5);\n"
    " o=vec4(mix(c,clamp(c+g*k*0.35+uLift,0.0,1.0),uAlpha),1.0); }";

static const char* FS_VIG = FS_HEAD
    "uniform float uA,uS,uE,uR,uG,uB; uniform float uAlpha;\n"
    "void main(){ vec3 c=src(); if(uA<=0.0){o=vec4(c,1.0);return;}\n"
    " vec2 asp=vec2(uRes.x/uRes.y,1.0); float d=length((uv()-0.5)*asp)/(0.5*length(asp));\n"
    " float t=smoothstep(uS,uE,d);\n"
    " o=vec4(mix(c,vec3(uR,uG,uB),t*uA*uAlpha),1.0); }";

static const char* FS_COPY = FS_HEAD
    "void main(){ o=texture(uTex,luv()); }\n";

// glow mask pre-pass (peak mode): lowpass-antialiased threshold mask,
// tiny dilation for sub-sample robustness on small point lights
static const char* FS_GLOW_MASK = FS_HEAD
    "uniform float uThresh,uThresh2,uMode;\n"
    "float vv(vec3 c){ return uMode<0.5?dot(c,vec3(0.299,0.587,0.114)):max(c.r,max(c.g,c.b)); }\n"
    "float lp(vec2 uv){ vec2 px=1.0/uRes;\n"
    " vec3 s=vec3(0.0);\n"
    " for(int dy=-2;dy<=2;dy++) for(int dx=-2;dx<=2;dx++)\n"
    "  s+=texture(uTex,uv+vec2(float(dx),float(dy))*px).rgb;\n"
    " return vv(s/25.0); }\n"
    "void main(){\n"
    " float v=lp(luv());\n"
    " float m=smoothstep(uThresh,max(uThresh2,uThresh+0.001),v);\n"
    " o=vec4(m,m,m,1.0); }\n";

// glow peak mode: separable sparse max-dilate (7 taps, spacing uOff px)
static const char* FS_GLOW_DIL = FS_HEAD
    "uniform vec2 uDir; // axis*spacing(px)\n"
    "void main(){ float m=0.0;\n"
    " for(int i=-3;i<=3;i++) m=max(m,texture(uTex,luv()+float(i)*uDir/uRes).r);\n"
    " o=vec4(m,m,m,1.0); }\n";

// glow peak mode: ring = clamp(dilated - mask, 0, 1)
static const char* FS_GLOW_RING = FS_HEAD
    "uniform sampler2D uMask;\n"
    "void main(){ float d=texture(uTex,luv()).r;\n"
    " float m=texture(uMask,luv()).r;\n"
    " float r=clamp(d-m,0.0,1.0);\n"
    " o=vec4(r,r,r,1.0); }\n";

static const char* FS_GLOW_H = FS_HEAD
    "uniform sampler2D uOrig; uniform float uPeak;\n"
    "uniform float uRadius,uThresh,uThresh2,uMode;\n"
    "float hm(vec3 c){ float v=uMode<0.5?dot(c,vec3(0.299,0.587,0.114)):max(c.r,max(c.g,c.b)); return smoothstep(uThresh,max(uThresh2,uThresh+0.001),v); }\n"
    "float dth(vec2 p){ vec3 p3=fract(vec3(p.xyx)*0.1031); p3+=dot(p3,p3.yzx+33.33); return fract((p3.x+p3.y)*p3.z); }\n"
    "void main(){ vec3 acc=vec3(0.0); float ws=0.0;\n"
    " for(int i=-12;i<=12;i++){\n"
    "  float w=exp(-float(i*i)/72.0);\n"
    "  vec2 to=luv()+vec2(float(i)*uRadius/12.0/uRes.x,0.0);\n"
    "  if(uPeak>0.5){ acc+=texture(uOrig,to).rgb*w; ws+=w; }\n"
    "  else { vec3 s=texture(uOrig,to).rgb; acc+=s*hm(s)*w; ws+=w; } }\n"
    " o=vec4(acc/ws + vec3((dth(gl_FragCoord.xy)-0.5)/255.0),1.0); }\n";

static const char* FS_GLOW_V = FS_HEAD
    "uniform sampler2D uSrc;\n"
    "uniform float uRadius,uAmt,uColorMix; uniform vec3 uGlowColor; uniform float uAlpha;\n"
    "float dth(vec2 p){ vec3 p3=fract(vec3(p.xyx)*0.1031); p3+=dot(p3,p3.yzx+33.33); return fract((p3.x+p3.y)*p3.z); }\n"
    "void main(){ vec3 blur=vec3(0.0); float ws=0.0;\n"
    " for(int j=-12;j<=12;j++){\n"
    "  float w=exp(-float(j*j)/72.0);\n"
    "  blur+=texture(uTex,luv()+vec2(0.0,float(j)*uRadius/12.0/uRes.y)).rgb*w; ws+=w; }\n"
    " blur/=ws;\n"
    " blur=mix(blur,blur*uGlowColor,uColorMix);\n"
    " vec3 c=texture(uSrc,luv()).rgb;\n"
    " vec3 g=clamp(blur*uAmt,0.0,1.0);\n"
    " vec3 scr=vec3(1.0)-(vec3(1.0)-c)*(vec3(1.0)-g);\n"
    " o=vec4(mix(c,scr,uAlpha) + vec3((dth(gl_FragCoord.xy)-0.5)/255.0),1.0); }\n";

// sharp/soft: unsharp mask — positive amount sharpens, negative softens (halo blends)
static const char* FS_SHARP = FS_HEAD
    "uniform float uAmt, uRadius, uAlpha;\n"
    "void main(){ vec3 c=src(); if(uAlpha<=0.0){o=vec4(c,1.0);return;}\n"
    " vec2 px=uRadius/uRes;\n"
    " vec3 b=vec3(0.0); float wsum=0.0;\n"
    " for(int dy=-2;dy<=2;dy++) for(int dx=-2;dx<=2;dx++){\n"
    "  float w=exp(-float(dx*dx+dy*dy)/2.5);\n"
    "  b+=texture(uTex,luv()+vec2(float(dx),float(dy))*px).rgb*w; wsum+=w; }\n"
    " b/=wsum;\n"
    " o=vec4(clamp(mix(c,c+uAmt*(c-b),uAlpha),0.0,1.0),1.0); }\n";

// overlay node: full-frame texture composited in image coordinates (tile-safe),
// cover-fit + user scale, selectable blend mode, JPEG source decoded on demand
static const char* FS_IMAGE = FS_HEAD
    "uniform sampler2D uOv; uniform float uAmt, uScale; uniform vec2 uOvRes; uniform int uBlend;\n"
    "void main(){ vec3 c=src();\n"
    " vec2 ar=vec2((uFull.x/uFull.y)/(uOvRes.x/uOvRes.y),1.0);\n"
    " float cv=max(ar.x,ar.y);\n"
    " vec2 ouv=(uv()-0.5)*cv*2.0/uScale*0.5*2.0+0.5;   // cover-fit, centered, scaled\n"
    " ouv=(uv()-0.5)*cv/uScale+0.5;\n"
    " if(ouv.x<0.0||ouv.x>1.0||ouv.y<0.0||ouv.y>1.0){ o=vec4(c,1.0); return; }\n"
    " vec3 b=texture(uOv,ouv).rgb; vec3 r=c;\n"
    " if(uBlend==0) r=b;\n"
    " else if(uBlend==1) r=vec3(1.0)-(vec3(1.0)-c)*(vec3(1.0)-b);\n"
    " else if(uBlend==2) r=c*b;\n"
    " else if(uBlend==3) r=mix(2.0*c*b, vec3(1.0)-2.0*(vec3(1.0)-c)*(vec3(1.0)-b), step(0.5,c));\n"
    " else r=(1.0-2.0*b)*c*c+2.0*b*c;\n"
    " o=vec4(clamp(mix(c,r,uAmt),0.0,1.0),1.0); }\n";

// hue shift: rotate hues within a wheel range around a target hue,
// weight falls off inside the range; gated by tonal zones (low/mid/high)
static const char* FS_HUE = FS_HEAD
    "uniform float uHue, uRange, uShift, uOn, uExp, uSat; uniform vec3 uZone;\n"
    "vec3 rgb2hsv(vec3 c){ vec4 K=vec4(0.0,-1.0/3.0,2.0/3.0,-1.0);\n"
    " vec4 p=mix(vec4(c.bg,K.wz),vec4(c.gb,K.xy),step(c.b,c.g));\n"
    " vec4 q=mix(vec4(p.xyw,c.r),vec4(c.r,p.yzx),step(p.x,c.r));\n"
    " float d=q.x-min(q.w,q.y); float e=1.0e-10;\n"
    " return vec3(abs(q.z+(q.w-q.y)/(6.0*d+e)),d/(q.x+e),q.x); }\n"
    "vec3 hsv2rgb(vec3 c){ vec3 p=abs(fract(c.xxx+vec3(1.0,0.666666666,0.333333333))*6.0-3.0);\n"
    " return c.z*mix(vec3(1.0),clamp(p-1.0,0.0,1.0),c.y); }\n"
    "void main(){ vec3 c=src();\n"
    " float l=dot(c,vec3(0.299,0.587,0.114));\n"
    " float zone=uZone.x*smoothstep(0.33,0.0,l)+uZone.y*(1.0-abs(l-0.5)*2.0)+uZone.z*smoothstep(0.33,0.66,l);\n"
    " zone=clamp(zone,0.0,1.0);\n"
    " if(uOn<=0.001||zone<=0.001){ o=vec4(c,1.0); return; }\n"
    " vec3 hsv=rgb2hsv(c);\n"
    " float d=abs(hsv.x-uHue); d=min(d,1.0-d);\n"
    " float w=1.0-smoothstep(uRange*0.55,uRange,d);\n"
    " if(w<=0.001){ o=vec4(c,1.0); return; }\n"
    " float g=w*zone;\n"
    " hsv.x=fract(hsv.x+uShift*g);\n"
    " vec3 r=hsv2rgb(hsv);\n"
    " r*=pow(2.0,uExp*g);\n"
    " float lum2=dot(r,vec3(0.299,0.587,0.114));\n"
    " r=mix(vec3(lum2),r,1.0+uSat*g);\n"
    " o=vec4(clamp(mix(c,r,uOn),0.0,1.0),1.0); }\n";

// ---- GL objects (created once, reused across renders) ----
static EGLDisplay g_dpy = EGL_NO_DISPLAY;
static EGLContext g_ctx = EGL_NO_CONTEXT;
static EGLSurface g_surf = EGL_NO_SURFACE;
static bool g_inited = false;
static GLuint g_progLut, g_progGrade, g_progGlowH, g_progGlowV, g_progGrain, g_progVig, g_progGlowMask, g_progGlowDil, g_progGlowRing, g_progCopy, g_progSharp, g_progImage, g_progHue;
static GLuint g_fbo[6] = {0, 0, 0, 0, 0, 0}, g_tex[6] = {0, 0, 0, 0, 0, 0};
static GLuint g_curTex = 0;
static std::vector<std::string> g_ovPaths;   // overlay file per image node (chain order)
static int g_ovIdx = 0;
struct OvCache { GLuint tex; long mt; int w, h; };
static std::map<std::string, OvCache> g_ovCache;
static GLuint g_holdTex = 0;      // day/night blend: holds the day result while night renders
static int s_holdW = 0, s_holdH = 0;
static void ensureHold(int w, int h) {
    if (g_holdTex && s_holdW == w && s_holdH == h) return;
    if (g_holdTex) glDeleteTextures(1, &g_holdTex);
    glGenTextures(1, &g_holdTex);
    glBindTexture(GL_TEXTURE_2D, g_holdTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    s_holdW = w; s_holdH = h;
}    // driver-copied composite base (coherent)
static int g_fboW = 0, g_fboH = 0;
static GLuint g_inTex = 0;
static int g_inW = 0, g_inH = 0;
static float g_ox = 0, g_oy = 0, g_fw = 0, g_fh = 0;

// ---- LUT cache ----
struct Lut { std::string path; long mtime; long size; int n; GLuint tex; };
static Lut g_lut;
static bool g_lutValid = false;

#define CHECK_GL() do { GLenum e = glGetError(); if (e) { LOGE("GL err 0x%x line %d", e, __LINE__); } } while (0)

// decode a JPEG overlay into a cached GL texture (keyed by path+mtime)
static GLuint loadOverlay(const std::string& path, int& ow, int& oh) {
    struct stat st;
    if (stat(path.c_str(), &st) != 0) return 0;
    auto it = g_ovCache.find(path);
    if (it != g_ovCache.end() && it->second.mt == st.st_mtime && it->second.tex) {
        ow = it->second.w; oh = it->second.h; return it->second.tex;
    }
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return 0;
    fseek(f, 0, SEEK_END); long fsz = ftell(f); fseek(f, 0, SEEK_SET);
    std::vector<unsigned char> jpg(fsz);
    fread(jpg.data(), 1, fsz, f); fclose(f);
    tjhandle tj = tjInitDecompress();
    int W = 0, H = 0, sub;
    if (tjDecompressHeader2(tj, jpg.data(), (unsigned long) fsz, &W, &H, &sub) != 0) { tjDestroy(tj); return 0; }
    std::vector<unsigned char> rgba((size_t) W * H * 4);
    if (tjDecompress2(tj, jpg.data(), (unsigned long) fsz, rgba.data(), W, 0, H, TJPF_RGBA, 0) != 0) { tjDestroy(tj); return 0; }
    tjDestroy(tj);
    GLuint tex;
    if (it != g_ovCache.end() && it->second.tex) tex = it->second.tex;
    else glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
    g_ovCache[path] = { tex, st.st_mtime, W, H };
    ow = W; oh = H;
    return tex;
}

static GLuint mkProg(const char* fs) {
    const char* srcs[2] = { VS, fs };
    GLenum types[2] = { GL_VERTEX_SHADER, GL_FRAGMENT_SHADER };
    GLuint p = glCreateProgram();
    for (int i = 0; i < 2; i++) {
        GLuint s = glCreateShader(types[i]);
        glShaderSource(s, 1, &srcs[i], nullptr);
        glCompileShader(s);
        GLint ok = 0; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
        if (!ok) {
            char log[512]; GLsizei l; glGetShaderInfoLog(s, 511, &l, log);
            LOGE("shader compile: %s", log);
        }
        glAttachShader(p, s); glDeleteShader(s);
    }
    glLinkProgram(p);
    GLint ok = 0; glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) LOGE("program link failed");
    return p;
}

static void ensureSize(int w, int h) {
    if (g_fboW == w && g_fboH == h && g_fbo[0]) return;
    if (g_fbo[0]) { glDeleteFramebuffers(6, g_fbo); glDeleteTextures(6, g_tex); for (int q = 0; q < 6; q++) { g_fbo[q] = 0; } }
    glGenFramebuffers(6, g_fbo);
    glGenTextures(6, g_tex);
    for (int i = 0; i < 6; i++) {
        glBindTexture(GL_TEXTURE_2D, g_tex[i]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindFramebuffer(GL_FRAMEBUFFER, g_fbo[i]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_tex[i], 0);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    g_fboW = w; g_fboH = h;
}

static void ensureInTex(int w, int h) {
    if (g_inTex && g_inW == w && g_inH == h) return;
    if (g_inTex) glDeleteTextures(1, &g_inTex);
    glGenTextures(1, &g_inTex);
    glBindTexture(GL_TEXTURE_2D, g_inTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    g_inW = w; g_inH = h;
}

static void bindRun(GLuint prog, GLuint srcTex, GLuint dstFbo, int w, int h,
                    float ox, float oy, float fw, float fh) {
    glBindFramebuffer(GL_FRAMEBUFFER, dstFbo);
    glViewport(0, 0, w, h);
    glUseProgram(prog);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, srcTex);
    glUniform1i(glGetUniformLocation(prog, "uTex"), 0);
    glUniform2f(glGetUniformLocation(prog, "uRes"), (float) w, (float) h);
    glUniform2f(glGetUniformLocation(prog, "uOrigin"), ox, oy);
    glUniform2f(glGetUniformLocation(prog, "uFull"), fw, fh);
    glDrawArrays(GL_TRIANGLES, 0, 3);
}

// parse a .cube into linear RGB triplets
static bool parseCube(const char* path, std::vector<float>& data, int& n) {
    FILE* f = fopen(path, "r");
    if (!f) return false;
    n = 0;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, "LUT_3D_SIZE")) { sscanf(line, "%*s %d", &n); continue; }
        if (line[0] == '#' || line[0] == 'T' || line[0] == 'D') continue;
        float r, g, b;
        if (sscanf(line, "%f %f %f", &r, &g, &b) == 3) { data.push_back(r); data.push_back(g); data.push_back(b); }
    }
    fclose(f);
    if (n <= 0 || (int) data.size() / 3 != n * n * n) { LOGE("LUT parse bad: %s n=%d vals=%zu", path, n, data.size()); return false; }
    return true;
}

// upload RGB float triplets as the 3D lut texture
static void uploadLutTex(const std::vector<float>& d1, const std::vector<float>& d2, float s) {
    int n = (int) std::cbrt((float) (d1.size() / 3));
    int nnn = n * n * n;
    std::vector<unsigned char> bytes(nnn * 4, 255);
    for (int i = 0; i < nnn; i++) for (int ch = 0; ch < 3; ch++) {
        float v = (d2.empty() ? d1[i * 3 + ch] : d1[i * 3 + ch] * (1.0f - s) + d2[i * 3 + ch] * s) * 255.0f;
        int iv = (int) (v + (v >= 0 ? 0.5f : -0.5f));
        bytes[i * 4 + ch] = (unsigned char) (iv < 0 ? 0 : (iv > 255 ? 255 : iv));
    }
    if (!g_lutValid) { glGenTextures(1, &g_lut.tex); g_lutValid = true; }
    glBindTexture(GL_TEXTURE_3D, g_lut.tex);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
    glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA, n, n, n, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes.data());
    g_lut.n = n;
}

static bool loadLut(const char* path) {
    struct stat st;
    if (stat(path, &st) != 0) return false;
    if (g_lutValid && g_lut.path == path && g_lut.mtime == st.st_mtime && g_lut.size == st.st_size) return true;
    std::vector<float> d; int n;
    if (!parseCube(path, d, n)) return false;
    uploadLutTex(d, std::vector<float>(), 0.0f);
    g_lut.path = path; g_lut.mtime = st.st_mtime; g_lut.size = st.st_size;
    LOGI("LUT loaded %s n=%d", path, n);
    return true;
}

// day/night blend: quantize score for cache reuse
static bool loadLutBlend(const char* p1, const char* p2, float s) {
    struct stat st1, st2;
    if (stat(p1, &st1) != 0 || stat(p2, &st2) != 0) return loadLut(p1);
    int q = (int) (s * 64.0f + 0.5f);
    static std::string bk1, bk2; static long m1, m2, sz1, sz2; static int bq = -1;
    if (g_lutValid && bk1 == p1 && bk2 == p2 && m1 == st1.st_mtime && m2 == st2.st_mtime && sz1 == st1.st_size && sz2 == st2.st_size && bq == q) return true;
    std::vector<float> d1, d2; int n1, n2;
    if (!parseCube(p1, d1, n1)) return false;
    if (!parseCube(p2, d2, n2)) return loadLut(p1);
    if (n1 != n2) return loadLut(p1);
    uploadLutTex(d1, d2, q / 64.0f);
    bk1 = p1; bk2 = p2; m1 = st1.st_mtime; m2 = st2.st_mtime; sz1 = st1.st_size; sz2 = st2.st_size; bq = q;
    g_lut.path = std::string(p1) + "|" + p2;
    LOGI("LUT blend %s + %s s=%.3f n=%d", p1, p2, q / 64.0f, n1);
    return true;
}

// node param row layout (Java packs): [0]=type 0..4, then type-specific floats (see Engine.java)
enum { N_LUT = 0, N_GRADE = 1, N_GLOW = 2, N_GRAIN = 3, N_VIG = 4, N_SHARP = 5, N_IMAGE = 6, N_HUE = 7 };

static void setU(GLuint p, const char* n, float v) { glUniform1f(glGetUniformLocation(p, n), v); }

static void runNode(const float* P, int w, int h, GLuint& cur, GLuint& curFbo) {
    int type = (int) P[0];
    GLuint dT = (cur == g_tex[0]) ? g_tex[1] : g_tex[0];
    GLuint dF = (cur == g_tex[0]) ? g_fbo[1] : g_fbo[0];
    switch (type) {
        case N_LUT: {
            if (P[1] <= 0) break;
            glUseProgram(g_progLut);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_3D, g_lut.tex);
            glUniform1i(glGetUniformLocation(g_progLut, "uLut"), 1);
            glUniform1f(glGetUniformLocation(g_progLut, "uOn"), P[1]);
            glActiveTexture(GL_TEXTURE0);
            bindRun(g_progLut, cur, dF, w, h, g_ox, g_oy, g_fw, g_fh);
            cur = dT; curFbo = dF;
            break;
        }
        case N_GRADE: {
            if (P[10] <= 0) break;  // alpha
            if (P[1] == 0 && P[2] == 0 && P[3] == 0 && P[4] == 0 && P[5] == 0) break;
            glUseProgram(g_progGrade);
            setU(g_progGrade, "uExp", P[1]); setU(g_progGrade, "uCon", P[2]); setU(g_progGrade, "uSat", P[3]);
            setU(g_progGrade, "uTemp", P[4]); setU(g_progGrade, "uTint", P[5]);
            setU(g_progGrade, "uZl", P[6]); setU(g_progGrade, "uZm", P[7]); setU(g_progGrade, "uZh", P[8]);
            setU(g_progGrade, "uAlpha", P[10]);
            bindRun(g_progGrade, cur, dF, w, h, g_ox, g_oy, g_fw, g_fh);
            cur = dT; curFbo = dF;
            break;
        }
        case N_GLOW: {
            if (P[4] <= 0) break;   // strength = alpha
            // radius is defined in A6000 pixels (6000x4000): scale by photo height
            // so the visual size is identical at every resolution
            float rEff = P[2] * (g_fh / 4000.0f);
            // peak mode: mask -> dilate -> ring, then blur the ring (dilate-then-subtract:
            // ring amplitude independent of source size, matches observed FIMO behavior)
            if (P[11] > 0.0f) {
                // m -> tex[2]
                glBindFramebuffer(GL_FRAMEBUFFER, g_fbo[2]);
                glViewport(0, 0, w, h);
                glUseProgram(g_progGlowMask);
                setU(g_progGlowMask, "uThresh", P[3]); setU(g_progGlowMask, "uThresh2", P[5]);
                setU(g_progGlowMask, "uMode", P[6]);
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, g_tex[3]);
                glUniform1i(glGetUniformLocation(g_progGlowMask, "uTex"), 0);
                glUniform2f(glGetUniformLocation(g_progGlowMask, "uRes"), (float) w, (float) h);
                glUniform2f(glGetUniformLocation(g_progGlowMask, "uOrigin"), g_ox, g_oy);
                glUniform2f(glGetUniformLocation(g_progGlowMask, "uFull"), g_fw, g_fh);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                // dilate H: tex[2] -> tex[4]  (spacing radius*0.2, 7 taps => reach ±0.6*radius)
                float dilSpc = rEff * 0.2f;
                glUseProgram(g_progGlowDil);
                glUniform2f(glGetUniformLocation(g_progGlowDil, "uDir"), dilSpc, 0.0f);
                bindRun(g_progGlowDil, g_tex[2], g_fbo[4], w, h, g_ox, g_oy, g_fw, g_fh);
                // dilate V: tex[4] -> tex[5] (dedicated scratch)
                glUniform2f(glGetUniformLocation(g_progGlowDil, "uDir"), 0.0f, dilSpc);
                bindRun(g_progGlowDil, g_tex[4], g_fbo[5], w, h, g_ox, g_oy, g_fw, g_fh);
                // ring = dilate - mask: (tex[5], tex[2]) -> tex[4]
                glUseProgram(g_progGlowRing);
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_2D, g_tex[2]);
                glUniform1i(glGetUniformLocation(g_progGlowRing, "uMask"), 1);
                glActiveTexture(GL_TEXTURE0);
                bindRun(g_progGlowRing, g_tex[5], g_fbo[4], w, h, g_ox, g_oy, g_fw, g_fh);
            }
            // pass A: horizontal gaussian -> flip buffer (tex[2]/tex[5] alternate;
            // tex[5] is free after the ring pass consumed dilV's output)
            static bool s_glowFlip = false;
            s_glowFlip = !s_glowFlip;
            int hIdx = s_glowFlip ? 2 : 5;
            glBindFramebuffer(GL_FRAMEBUFFER, g_fbo[hIdx]);
            glViewport(0, 0, w, h);
            glUseProgram(g_progGlowH);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, cur);
            glUniform1i(glGetUniformLocation(g_progGlowH, "uTex"), 0);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, P[11] > 0.0f ? g_tex[4] : g_tex[3]);
            glUniform1i(glGetUniformLocation(g_progGlowH, "uOrig"), 1);
            glUniform1f(glGetUniformLocation(g_progGlowH, "uPeak"), P[11]);
            glUniform2f(glGetUniformLocation(g_progGlowH, "uRes"), (float) w, (float) h);
            glUniform2f(glGetUniformLocation(g_progGlowH, "uOrigin"), g_ox, g_oy);
            glUniform2f(glGetUniformLocation(g_progGlowH, "uFull"), g_fw, g_fh);
            glUniform1f(glGetUniformLocation(g_progGlowH, "uRadius"), rEff);
            glUniform1f(glGetUniformLocation(g_progGlowH, "uThresh"), P[3]);
                        glUniform1f(glGetUniformLocation(g_progGlowH, "uThresh2"), P[5]);
            glUniform1f(glGetUniformLocation(g_progGlowH, "uMode"), P[6]);
            glActiveTexture(GL_TEXTURE0);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            // V pass samples the H output (tex[hIdx]) directly
            // driver-copy cur as well: V sampling the just-rendered LUT output
            // hits the same incoherent-read; copy both inputs
            static int s_curW = 0, s_curH = 0;
            if (g_curTex == 0 || s_curW != w || s_curH != h) {
                if (g_curTex) glDeleteTextures(1, &g_curTex);
                glGenTextures(1, &g_curTex);
                glBindTexture(GL_TEXTURE_2D, g_curTex);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
                s_curW = w; s_curH = h;
            }
            glBindFramebuffer(GL_FRAMEBUFFER, curFbo == 0 ? g_fbo[0] : curFbo);
            glBindTexture(GL_TEXTURE_2D, g_curTex);
            glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
            // pass B: vertical gaussian + tint + composite (sample scratch + original)
            glBindFramebuffer(GL_FRAMEBUFFER, dF);
            glViewport(0, 0, w, h);
            glUseProgram(g_progGlowV);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, g_tex[hIdx]);
            glUniform1i(glGetUniformLocation(g_progGlowV, "uTex"), 0);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, g_curTex);
            glUniform1i(glGetUniformLocation(g_progGlowV, "uSrc"), 1);
            glUniform2f(glGetUniformLocation(g_progGlowV, "uRes"), (float) w, (float) h);
            glUniform2f(glGetUniformLocation(g_progGlowV, "uOrigin"), g_ox, g_oy);
            glUniform2f(glGetUniformLocation(g_progGlowV, "uFull"), g_fw, g_fh);
            setU(g_progGlowV, "uRadius", rEff); setU(g_progGlowV, "uAmt", P[1]); setU(g_progGlowV, "uAlpha", P[4]);
            glUniform3f(glGetUniformLocation(g_progGlowV, "uGlowColor"), P[7], P[8], P[9]);
            setU(g_progGlowV, "uColorMix", P[10]);
            glActiveTexture(GL_TEXTURE0);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            cur = dT; curFbo = dF;
            break;
        }
        case N_IMAGE: {
            if (P[1] <= 0 || g_ovIdx >= (int) g_ovPaths.size()) break;
            std::string p = g_ovPaths[g_ovIdx++];
            LOGI("overlay pick: %s", p.c_str());
            int ow = 1, oh = 1;
            GLuint ov = loadOverlay(p, ow, oh);
            if (!ov) break;
            glUseProgram(g_progImage);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, ov);
            glUniform1i(glGetUniformLocation(g_progImage, "uOv"), 1);
            glUniform1f(glGetUniformLocation(g_progImage, "uAmt"), P[1]);
            glUniform1f(glGetUniformLocation(g_progImage, "uScale"), P[3] <= 0.01f ? 1.0f : P[3]);
            glUniform2f(glGetUniformLocation(g_progImage, "uOvRes"), (float) ow, (float) oh);
            glUniform1i(glGetUniformLocation(g_progImage, "uBlend"), (int) P[2]);
            glActiveTexture(GL_TEXTURE0);
            bindRun(g_progImage, cur, dF, w, h, g_ox, g_oy, g_fw, g_fh);
            cur = dT; curFbo = dF;
            break;
        }
        case N_HUE: {
            if (P[4] <= 0) break;
            glUseProgram(g_progHue);
            setU(g_progHue, "uHue", P[1]); setU(g_progHue, "uRange", P[2]); setU(g_progHue, "uShift", P[3]);
            setU(g_progHue, "uOn", P[4]);
            glUniform3f(glGetUniformLocation(g_progHue, "uZone"), P[6], P[7], P[8]);
            setU(g_progHue, "uExp", P[9]); setU(g_progHue, "uSat", P[10]);
            bindRun(g_progHue, cur, dF, w, h, g_ox, g_oy, g_fw, g_fh);
            cur = dT; curFbo = dF;
            break;
        }
        case N_SHARP: {
            if (P[1] == 0 || P[3] <= 0) break;
            glUseProgram(g_progSharp);
            setU(g_progSharp, "uAmt", P[1]); setU(g_progSharp, "uRadius", P[2] * (g_fh / 4000.0f));
            setU(g_progSharp, "uAlpha", P[3]);
            bindRun(g_progSharp, cur, dF, w, h, g_ox, g_oy, g_fw, g_fh);
            cur = dT; curFbo = dF;
            break;
        }
        case N_GRAIN: {
            if (P[7] <= 0) break;   // strength = alpha
            glUseProgram(g_progGrain);
            setU(g_progGrain, "uAmt", P[1]); setU(g_progGrain, "uSize", P[2] * (g_fh / 4000.0f)); setU(g_progGrain, "uMono", P[3]);
            setU(g_progGrain, "uSeed", 1.0f);
            glUniform3f(glGetUniformLocation(g_progGrain, "uLift"), P[4] / 255.0f, P[5] / 255.0f, P[6] / 255.0f);
            setU(g_progGrain, "uAlpha", P[7]);
            bindRun(g_progGrain, cur, dF, w, h, g_ox, g_oy, g_fw, g_fh);
            cur = dT; curFbo = dF;
            break;
        }
        case N_VIG: {
            if (P[7] <= 0) break;   // strength = alpha
            glUseProgram(g_progVig);
            setU(g_progVig, "uA", P[1]); setU(g_progVig, "uS", P[2]); setU(g_progVig, "uE", P[3]);
            setU(g_progVig, "uR", P[4]); setU(g_progVig, "uG", P[5]); setU(g_progVig, "uB", P[6]);
            setU(g_progVig, "uAlpha", P[7]);
            bindRun(g_progVig, cur, dF, w, h, g_ox, g_oy, g_fw, g_fh);
            cur = dT; curFbo = dF;
            break;
        }
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_openfilm6k_host_Engine_nInit(JNIEnv*, jobject) {
    if (g_inited) return JNI_TRUE;
    g_dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(g_dpy, nullptr, nullptr)) { LOGE("eglInitialize"); return JNI_FALSE; }
    EGLConfig cfg;
    EGLint num = 0;
    const EGLint attr[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_NONE };
    eglChooseConfig(g_dpy, attr, &cfg, 1, &num);
    const EGLint pbattrib[] = { EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE };
    g_surf = eglCreatePbufferSurface(g_dpy, cfg, pbattrib);
    const EGLint ctxattr[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    g_ctx = eglCreateContext(g_dpy, cfg, EGL_NO_CONTEXT, ctxattr);
    if (g_ctx == EGL_NO_CONTEXT) { LOGE("no ctx"); return JNI_FALSE; }
    eglMakeCurrent(g_dpy, g_surf, g_surf, g_ctx);
    g_progLut = mkProg(FS_LUT);
    g_progImage = mkProg(FS_IMAGE);
    g_progHue = mkProg(FS_HUE);
    g_progSharp = mkProg(FS_SHARP);
    g_progGrade = mkProg(FS_GRADE);
    g_progGlowH = mkProg(FS_GLOW_H);
    g_progGlowMask = mkProg(FS_GLOW_MASK);
    g_progGlowDil = mkProg(FS_GLOW_DIL);
    g_progGlowRing = mkProg(FS_GLOW_RING);
    g_progCopy = mkProg(FS_COPY);
    g_progGlowV = mkProg(FS_GLOW_V);
    g_progGrain = mkProg(FS_GRAIN);
    g_progVig = mkProg(FS_VIG);
    eglMakeCurrent(g_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    g_inited = true;
    LOGI("native engine init OK");
    return JNI_TRUE;
}

// nRender(w, h, directBufRGBA, lutPath, nodes[]) — renders the chain into buf in place.
// node row: [type, 9 floats..., ] stride 11
JNIEXPORT jint JNICALL
Java_com_openfilm6k_host_Engine_nRender(JNIEnv* env, jobject, jint w, jint h, jobject buf,
                                        jstring lutPath, jfloatArray nodes, jobjectArray overlays,
                                        jstring lutPath2, jfloat score) {
    if (!g_inited) return -1;
    g_ovPaths.clear(); g_ovIdx = 0;
    unsigned char* px = (unsigned char*) env->GetDirectBufferAddress(buf);
    if (!px) { LOGE("not a direct buffer"); return -2; }
    jsize nNodes = env->GetArrayLength(nodes) / 12;
    jfloat* nd = env->GetFloatArrayElements(nodes, nullptr);
    const char* lp = lutPath ? env->GetStringUTFChars(lutPath, nullptr) : nullptr;

    if (!eglMakeCurrent(g_dpy, g_surf, g_surf, g_ctx)) { LOGE("makeCurrent fail"); return -3; }
    int rc = 0;
    do {
        // LUT needed?
        bool needLut = false;
        for (int i = 0; i < nNodes; i++) if ((int) nd[i * 12] == N_LUT && nd[i * 12 + 1] > 0) { needLut = true; break; }
        if (needLut) {
            if (!lp || !loadLut(lp)) {
                // fall back to identity LUT
                if (!g_lutValid) {
                    static unsigned char ident4[64 * 64 * 64 * 4];
                    int k = 0;
                    for (int b = 0; b < 64; b++) for (int g = 0; g < 64; g++) for (int r = 0; r < 64; r++) {
                        ident4[k * 4] = (unsigned char) (r * 255 / 63);
                        ident4[k * 4 + 1] = (unsigned char) (g * 255 / 63);
                        ident4[k * 4 + 2] = (unsigned char) (b * 255 / 63);
                        ident4[k * 4 + 3] = 255;
                        k++;
                    }
                    glGenTextures(1, &g_lut.tex);
                    glBindTexture(GL_TEXTURE_3D, g_lut.tex);
                    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
                    glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA, 64, 64, 64, 0, GL_RGBA, GL_UNSIGNED_BYTE, ident4);
                    g_lutValid = true; g_lut.n = 64;
                }
            }
        }
        ensureSize(w, h);
        glGenTextures(1, &g_inTex);
        glBindTexture(GL_TEXTURE_2D, g_inTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, px);

        bool hasGlowNR = false;
        for (int i = 0; i < nNodes; i++) if ((int) nd[i * 12] == N_GLOW && nd[i * 12 + 4] > 0) { hasGlowNR = true; break; }
        bool hasGlow = hasGlowNR;
        if (hasGlow) {   // snapshot the raw input for glow's pre-LUT threshold
            glUseProgram(g_progLut);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_3D, g_lut.tex);
            glUniform1i(glGetUniformLocation(g_progLut, "uLut"), 1);
            glUniform1f(glGetUniformLocation(g_progLut, "uOn"), 0.0f);
            glActiveTexture(GL_TEXTURE0);
            bindRun(g_progLut, g_inTex, g_fbo[3], w, h, 0, 0, w, h);
        }
        GLuint cur = g_inTex, curFbo = 0;
        for (int i = 0; i < nNodes; i++) runNode(nd + i * 12, w, h, cur, curFbo);
        if (curFbo == 0) {   // all nodes disabled: passthrough copy via identity lut pass
            glUseProgram(g_progLut);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_3D, g_lut.tex);
            glUniform1i(glGetUniformLocation(g_progLut, "uLut"), 1);
            glUniform1f(glGetUniformLocation(g_progLut, "uOn"), 0.0f);
            glActiveTexture(GL_TEXTURE0);
            bindRun(g_progLut, cur, g_fbo[0], w, h, 0, 0, w, h);
            curFbo = g_fbo[0];
        }
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px);
        glDeleteTextures(1, &g_inTex); g_inTex = 0;
        {   // GL rows are bottom-up; Bitmap expects top-down — flip in place
            std::vector<unsigned char> tmp(w * 4);
            for (int y = 0; y < h / 2; y++) {
                unsigned char* a = px + (size_t) y * w * 4;
                unsigned char* b = px + (size_t) (h - 1 - y) * w * 4;
                memcpy(tmp.data(), a, w * 4); memcpy(a, b, w * 4); memcpy(b, tmp.data(), w * 4);
            }
        }
    } while (0);
    eglMakeCurrent(g_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (lp) env->ReleaseStringUTFChars(lutPath, lp);
    env->ReleaseFloatArrayElements(nodes, nd, JNI_ABORT);
    return rc;
}

// full native path: jpeg file -> chain -> jpeg file. Tiled GL for >4096. Zero Java bitmaps.
JNIEXPORT jint JNICALL
Java_com_openfilm6k_host_Engine_nProcessFile(JNIEnv* env, jobject, jstring inPath, jstring outPath,
                                             jstring lutPath, jfloatArray nodes, jobjectArray overlays,
                                             jstring lutPath2, jfloatArray nodesNight, jfloat score) {
    if (!g_inited) return -1;
    g_ovPaths.clear();
    if (overlays) {
        jsize n = env->GetArrayLength(overlays);
        for (int i = 0; i < n; i++) {
            jstring s = (jstring) env->GetObjectArrayElement(overlays, i);
            if (!s) { g_ovPaths.push_back(""); continue; }
            const char* cs = env->GetStringUTFChars(s, nullptr);
            g_ovPaths.push_back(cs);
            env->ReleaseStringUTFChars(s, cs);
        }
    }
    g_ovIdx = 0;
   
    const char* lp2 = nullptr;
    if (lutPath2) lp2 = env->GetStringUTFChars(lutPath2, nullptr); const char* ip = env->GetStringUTFChars(inPath, nullptr);
    const char* op = env->GetStringUTFChars(outPath, nullptr);
    const char* lp = lutPath ? env->GetStringUTFChars(lutPath, nullptr) : nullptr;
    jsize nNodes = env->GetArrayLength(nodes) / 12;
    jfloat* nd = env->GetFloatArrayElements(nodes, nullptr);
    jsize nNight = nodesNight ? env->GetArrayLength(nodesNight) / 12 : 0;
    jfloat* ndN = nNight > 0 ? env->GetFloatArrayElements(nodesNight, nullptr) : nullptr;
    const bool outBlend = (lp2 != nullptr) && nNight > 0 && score > 0.001f;   // chains may differ freely
    LOGI("day/night output blend=%d score=%.3f", (int) outBlend, score);

    // decode (turbojpeg, full resolution)
    FILE* f = fopen(ip, "rb");
    if (!f) { LOGE("open in fail"); return -10; }
    fseek(f, 0, SEEK_END); long fsz = ftell(f); fseek(f, 0, SEEK_SET);
    std::vector<unsigned char> jpg(fsz);
    fread(jpg.data(), 1, fsz, f); fclose(f);
    tjhandle tj = tjInitDecompress();
    int W = 0, H = 0, sub;
    if (tjDecompressHeader2(tj, jpg.data(), (unsigned long) fsz, &W, &H, &sub) != 0) { LOGE("tj header: %s", tjGetErrorStr2(tj)); return -11; }
    size_t bufSz = (size_t) W * H * 4;
    std::vector<unsigned char> img(bufSz);
    if (tjDecompress2(tj, jpg.data(), (unsigned long) fsz, img.data(), W, 0, H, TJPF_RGBA, 0) != 0) { LOGE("tj decode: %s", tjGetErrorStr2(tj)); return -12; }
    tjDestroy(tj);
    LOGI("tj decoded %dx%d", W, H);

    if (!eglMakeCurrent(g_dpy, g_surf, g_surf, g_ctx)) return -3;
    int rc = 0;
    do {
        bool needLut = false, needGlow = false;
        for (int i = 0; i < nNodes; i++) {
            if ((int) nd[i * 12] == N_LUT && nd[i * 12 + 1] > 0) needLut = true;
            if ((int) nd[i * 12] == N_GLOW && nd[i * 12 + 4] > 0) needGlow = true;
        }
        bool hasGlow = needGlow;
        if (needLut && lp) loadLut(lp);
        int LIMIT = 4096 - 8;
        // glow apron must cover the widest fetch: blur taps reach +-radius px, dilate +-(0.6*radius)
        float maxGR = 0.0f;
        for (int i = 0; i < nNodes; i++)
            if ((int) nd[i * 12] == N_GLOW && nd[i * 12 + 4] > 0 && nd[i * 12 + 2] * (H / 4000.0f) > maxGR)
                maxGR = nd[i * 12 + 2] * (H / 4000.0f);
        int O = 0;
        if (needGlow) {
            O = (int) ceilf(maxGR * 1.3f) + 8;
            if (O < 72) O = 72;
            if (O > 240) O = 240;
        }
        if (W <= LIMIT && H <= LIMIT) {
            g_fw = W; g_fh = H; g_ox = 0; g_oy = 0;
            ensureSize(W, H);
            glGenTextures(1, &g_inTex);
            glBindTexture(GL_TEXTURE_2D, g_inTex);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, img.data());
            if (hasGlow) {   // snapshot raw input for glow pre-LUT threshold (single-tile)
                glUseProgram(g_progLut);
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_3D, g_lut.tex);
                glUniform1i(glGetUniformLocation(g_progLut, "uLut"), 1);
                glUniform1f(glGetUniformLocation(g_progLut, "uOn"), 0.0f);
                glActiveTexture(GL_TEXTURE0);
                bindRun(g_progLut, g_inTex, g_fbo[3], W, H, 0, 0, W, H);
            }
            GLuint cur = g_inTex, curFbo = 0;
            for (int i = 0; i < nNodes; i++) runNode(nd + i * 12, W, H, cur, curFbo);
            if (curFbo == 0) {
                glUseProgram(g_progLut);
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_3D, g_lut.tex);
                glUniform1i(glGetUniformLocation(g_progLut, "uLut"), 1);
                glUniform1f(glGetUniformLocation(g_progLut, "uOn"), 0.0f);
                glActiveTexture(GL_TEXTURE0);
                bindRun(g_progLut, cur, g_fbo[0], W, H, 0, 0, W, H);
                curFbo = g_fbo[0];
            }
            if (outBlend) {   // parallel-style: save day via driver copy, render night, ONE sync read
                ensureHold(W, H);
                glBindFramebuffer(GL_FRAMEBUFFER, curFbo);
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, g_holdTex);
                glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, W, H);
                if (needLut && lp2) loadLut(lp2);
                g_ovIdx = 0;
                GLuint cur2 = g_inTex, curFbo2 = 0;
                for (int i = 0; i < nNight; i++) runNode(ndN + i * 12, W, H, cur2, curFbo2);
                if (curFbo2 == 0) {
                    glUseProgram(g_progLut);
                    glActiveTexture(GL_TEXTURE1);
                    glBindTexture(GL_TEXTURE_3D, g_lut.tex);
                    glUniform1i(glGetUniformLocation(g_progLut, "uLut"), 1);
                    glUniform1f(glGetUniformLocation(g_progLut, "uOn"), 0.0f);
                    glActiveTexture(GL_TEXTURE0);
                    bindRun(g_progLut, cur2, g_fbo[0], W, H, 0, 0, W, H);
                    curFbo2 = g_fbo[0];
                }
                glBindFramebuffer(GL_FRAMEBUFFER, curFbo2);
                glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, img.data());   // night -> img
                static std::vector<unsigned char> db;
                db.resize(bufSz);
                glBindFramebuffer(GL_FRAMEBUFFER, g_fbo[4]);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_holdTex, 0);
                glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, db.data());    // day from hold
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_tex[4], 0);
                for (size_t i = 0; i < bufSz; i++)
                    img[i] = (unsigned char) (db[i] * (1.0f - score) + img[i] * score + 0.5f);
            } else {
                glBindFramebuffer(GL_FRAMEBUFFER, curFbo);
                glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, img.data());
            }
            glDeleteTextures(1, &g_inTex); g_inTex = 0;
        } else {
            // tiled: per-pixel passes are tile-safe; glow gets an apron
            int tx = (W + LIMIT - 1) / LIMIT, ty = (H + LIMIT - 1) / LIMIT;
            int tw = (W + tx - 1) / tx, th = (H + ty - 1) / ty;
            // RAW-APRON GUARD: earlier tiles' readbacks overwrite img in place, so a
            // later tile's input upload would read PROCESSED pixels in its apron —
            // post-LUT values there can cross the glow mask threshold and light a
            // phantom glow skirt ~46px into this tile's content (the seam ghost).
            // Save the raw cross-seam strips now (img still pristine), restore per tile.
            std::vector<std::vector<unsigned char>> vStrips(tx), hStrips(ty);
            for (int i = 1; i < tx; i++) {
                int x0 = i * tw - O;
                vStrips[i].resize((size_t) H * O * 4);
                for (int y = 0; y < H; y++)
                    memcpy(vStrips[i].data() + (size_t) y * O * 4,
                           img.data() + ((size_t) y * W + x0) * 4, (size_t) O * 4);
            }
            for (int j = 1; j < ty; j++) {
                int y0 = j * th - O;
                hStrips[j].resize((size_t) O * W * 4);
                memcpy(hStrips[j].data(), img.data() + (size_t) y0 * W * 4, (size_t) O * W * 4);
            }
            for (int j = 0; j < ty; j++) {
                for (int i = 0; i < tx; i++) {
                    int cx = i * tw, cy = j * th;
                    int cw = (cx + tw <= W) ? tw : W - cx;
                    int ch = (cy + th <= H) ? th : H - cy;
                    int ax = cx - O; if (ax < 0) ax = 0;
                    int ay = cy - O; if (ay < 0) ay = 0;
                    int bx = cx + cw + O; if (bx > W) bx = W;
                    int by = cy + ch + O; if (by > H) by = H;
                    int aw = bx - ax, ah = by - ay;
                    g_fw = W; g_fh = H; g_ox = ax; g_oy = ay;
                    ensureSize(aw, ah);
                    glGenTextures(1, &g_inTex);
                    glBindTexture(GL_TEXTURE_2D, g_inTex);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, aw, ah, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
                    // assemble the upload for interior tiles: img rows already hold
                    // PROCESSED output from earlier readbacks in the apron area — patch
                    // the apron with the saved RAW strips inside a staging buffer
                    // (img itself must NOT be touched: it accumulates final output).
                    const unsigned char* upSrc = img.data() + (size_t) ay * W * 4 + ax * 4;
                    int upRowLen = W;
                    std::vector<unsigned char> upBuf;
                    if (i > 0 || j > 0) {
                        upBuf.resize((size_t) aw * ah * 4);
                        for (int r = 0; r < ah; r++)   // strided rect -> contiguous rows
                            memcpy(upBuf.data() + (size_t) r * aw * 4,
                                   img.data() + ((size_t) (ay + r) * W + ax) * 4, (size_t) aw * 4);
                        upRowLen = aw;
                        if (i > 0) {   // left apron cols [ax,cx): raw from vStrips
                            int lw = cx - ax;
                            for (int y = ay; y < by; y++)
                                memcpy(upBuf.data() + (size_t) (y - ay) * aw * 4,
                                       vStrips[i].data() + (size_t) y * O * 4, (size_t) lw * 4);
                        }
                        if (j > 0) {   // top apron rows [ay,cy): raw from hStrips
                            int thh = cy - ay;
                            for (int r = 0; r < thh; r++)
                                memcpy(upBuf.data() + (size_t) r * aw * 4,
                                       hStrips[j].data() + ((size_t) r * W + ax) * 4, (size_t) aw * 4);
                        }
                        upSrc = upBuf.data();
                    }
                    glPixelStorei(GL_UNPACK_ROW_LENGTH, upRowLen);
                    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, aw, ah, GL_RGBA, GL_UNSIGNED_BYTE, upSrc);
                    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
                    if (hasGlow) {
                        glUseProgram(g_progLut);
                        glActiveTexture(GL_TEXTURE1);
                        glBindTexture(GL_TEXTURE_3D, g_lut.tex);
                        glUniform1i(glGetUniformLocation(g_progLut, "uLut"), 1);
                        glUniform1f(glGetUniformLocation(g_progLut, "uOn"), 0.0f);
                        glActiveTexture(GL_TEXTURE0);
                        bindRun(g_progLut, g_inTex, g_fbo[3], aw, ah, ax, ay, W, H);
                        // MTK: sampling a texture right after writing/uploading it can
                        // return stale memory. Force a pipeline barrier before the node chain.
                        glFinish();
                    }
                                        if (outBlend && needLut && lp) loadLut(lp);   // night chain of the previous tile overwrote the shared LUT texture
                    GLuint cur = g_inTex, curFbo = 0;
                    for (int k = 0; k < nNodes; k++) runNode(nd + k * 12, aw, ah, cur, curFbo);
                    if (curFbo == 0) {
                        glUseProgram(g_progLut);
                        glActiveTexture(GL_TEXTURE1);
                        glBindTexture(GL_TEXTURE_3D, g_lut.tex);
                        glUniform1i(glGetUniformLocation(g_progLut, "uLut"), 1);
                        glUniform1f(glGetUniformLocation(g_progLut, "uOn"), 0.0f);
                        glActiveTexture(GL_TEXTURE0);
                        bindRun(g_progLut, cur, g_fbo[0], aw, ah, ax, ay, W, H);
                        curFbo = g_fbo[0];
                    }
                    glBindFramebuffer(GL_FRAMEBUFFER, curFbo);
                    {
                        // read the FULL tile then copy the content subrect on CPU —
                        // dodges a driver quirk where glReadPixels with a nonzero
                        // sub-rect x-start smears the first columns
                        static std::vector<unsigned char> tileBuf;
                        tileBuf.resize((size_t) aw * ah * 4);
                        if (outBlend) {   // parallel-style: save day via driver copy, night renders, one sync read
                            ensureHold(aw, ah);
                            glBindFramebuffer(GL_FRAMEBUFFER, curFbo);
                            glActiveTexture(GL_TEXTURE0);
                            glBindTexture(GL_TEXTURE_2D, g_holdTex);
                            glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, aw, ah);
                            if (needLut && lp2) loadLut(lp2);
                            g_ovIdx = 0;   // overlay picks re-aligned for the second chain
                            GLuint cur2 = g_inTex, curFbo2 = 0;
                            for (int k2 = 0; k2 < nNight; k2++) runNode(ndN + k2 * 12, aw, ah, cur2, curFbo2);
                            if (curFbo2 == 0) {
                                glUseProgram(g_progLut);
                                glActiveTexture(GL_TEXTURE1);
                                glBindTexture(GL_TEXTURE_3D, g_lut.tex);
                                glUniform1i(glGetUniformLocation(g_progLut, "uLut"), 1);
                                glUniform1f(glGetUniformLocation(g_progLut, "uOn"), 0.0f);
                                glActiveTexture(GL_TEXTURE0);
                                bindRun(g_progLut, cur2, g_fbo[0], aw, ah, ax, ay, W, H);
                                curFbo2 = g_fbo[0];
                            }
                            glBindFramebuffer(GL_FRAMEBUFFER, curFbo2);
                            glPixelStorei(GL_PACK_ROW_LENGTH, aw);
                            glReadPixels(0, 0, aw, ah, GL_RGBA, GL_UNSIGNED_BYTE, tileBuf.data());   // night
                            static std::vector<unsigned char> tileBufD;
                            tileBufD.resize((size_t) aw * ah * 4);
                            glBindFramebuffer(GL_FRAMEBUFFER, g_fbo[4]);
                            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_holdTex, 0);
                            glReadPixels(0, 0, aw, ah, GL_RGBA, GL_UNSIGNED_BYTE, tileBufD.data());  // day from hold
                            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_tex[4], 0);
                            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
                            for (size_t q = 0; q < tileBuf.size(); q++)
                                tileBuf[q] = (unsigned char) (tileBufD[q] * (1.0f - score) + tileBuf[q] * score + 0.5f);
                        } else {
                            glBindFramebuffer(GL_FRAMEBUFFER, curFbo);
                            glPixelStorei(GL_PACK_ROW_LENGTH, aw);
                            glReadPixels(0, 0, aw, ah, GL_RGBA, GL_UNSIGNED_BYTE, tileBuf.data());
                            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
                        }
                        int ox = cx - ax, oy = cy - ay;
                        for (int row = 0; row < ch; row++) {
                            memcpy(img.data() + (size_t) (cy + row) * W * 4 + cx * 4,
                                   tileBuf.data() + (size_t) (oy + row) * aw * 4 + ox * 4,
                                   (size_t) cw * 4);
                        }
                    }
                    glDeleteTextures(1, &g_inTex); g_inTex = 0;
                    LOGI("tile %d/%d %dx%d+%d+%d ok", j * tx + i + 1, tx * ty, cw, ch, cx, cy);
                }
            }
        }
    } while (0);
    eglMakeCurrent(g_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    // raw lossless dump (diagnostics)
    if (strlen(op) > 4 && strcmp(op + strlen(op) - 4, ".raw") == 0) {
        FILE* o = fopen(op, "wb");
        if (o) { fwrite(img.data(), 1, (size_t) W * H * 4, o); fclose(o); LOGI("raw dumped %dx%d", W, H); }
        tjDestroy(tjInitCompress());
        if (lp) env->ReleaseStringUTFChars(lutPath, lp);
        env->ReleaseStringUTFChars(outPath, op);
        env->ReleaseStringUTFChars(inPath, ip);
        env->ReleaseFloatArrayElements(nodes, nd, JNI_ABORT);
        return 0;
    }
    // encode (turbojpeg q92)
    tjhandle tje = tjInitCompress();
    unsigned char* jpegBuf = nullptr;
    unsigned long jpegSz = 0;
    if (tjCompress2(tje, img.data(), W, 0, H, TJPF_RGBA, &jpegBuf, &jpegSz, TJSAMP_444, 95,
                    TJFLAG_FASTDCT) != 0) { LOGE("tj encode: %s", tjGetErrorStr2(tje)); rc = -20; }
    if (rc == 0) {
        FILE* o = fopen(op, "wb");
        if (o) { fwrite(jpegBuf, 1, jpegSz, o); fclose(o); }
        else rc = -21;
    }
    if (jpegBuf) tjFree(jpegBuf);
    tjDestroy(tje);
    if (lp) env->ReleaseStringUTFChars(lutPath, lp);
    if (lp2) env->ReleaseStringUTFChars(lutPath2, lp2);
    env->ReleaseStringUTFChars(outPath, op);
    env->ReleaseStringUTFChars(inPath, ip);
    env->ReleaseFloatArrayElements(nodes, nd, JNI_ABORT);
    if (ndN) env->ReleaseFloatArrayElements(nodesNight, ndN, JNI_ABORT);
    return rc;
}

} // extern "C"

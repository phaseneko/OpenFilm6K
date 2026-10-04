package com.openfilm6k.host;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.opengl.GLUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** OpenFilm6K GLES3 engine. Color = 3D LUT; spatial = grain / vignette / glow passes. */
public class Engine {
    private static final Engine I = new Engine();
    public static Engine get() { return I; }

    private EGLDisplay dpy;
    private EGLContext ctx;
    private EGLSurface surf;
    private volatile boolean inited = false;
    private int progLut, progGrain, progVig, progGlowH, progGlowV, progGrade;
    private int lutTex = 0; private String lutFor = ""; private int lutN = 64;
    private int fboA, texA, fboB, texB, fboW, fboH;
    private int fboScratch = 0, texScratch = 0;
    private final Object lock = new Object();

    private static final String VS =
        "#version 300 es\n" +
        "void main(){ vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));" +
        "gl_Position=vec4(p*2.0-1.0,0.0,1.0); }";

    private static final String FS_HEAD =
        "#version 300 es\nprecision highp float;\n" +
        "uniform sampler2D uTex; uniform vec2 uRes;\n" +
        "out vec4 o; vec2 uv(){ return gl_FragCoord.xy/uRes; } vec3 src(){ return texture(uTex,uv()).rgb; }\n";

    private static final String FS_LUT = FS_HEAD +
        "uniform highp sampler3D uLut; uniform float uOn; uniform float uAlpha;\n" +
        "void main(){ vec3 c=src(); o=vec4(mix(c,texture(uLut,c).rgb,uOn*uAlpha),1.0); }";

    private static final String FS_GRADE = FS_HEAD +
        "uniform float uExp,uCon,uSat,uTemp,uTint;\n" +
        "uniform float uZl,uZm,uZh; uniform float uAlpha;\n" +   // tonal-range qualifiers: low/mid/high (default 1,1,1 = full range)
        "void main(){ vec3 c0=src(); vec3 c=c0;\n" +
        " c*=pow(2.0,uExp);\n" +
        " c=(c-0.5)*(1.0+uCon)+0.5;\n" +
        " float lum=dot(c,vec3(0.299,0.587,0.114));\n" +
        " c=mix(vec3(lum),c,1.0+uSat);\n" +
        " c.r*=1.0+uTemp*0.25; c.b*=1.0-uTemp*0.25;\n" +
        " c.g*=1.0+uTint*0.20; c.r*=1.0-uTint*0.10; c.b*=1.0-uTint*0.10;\n" +
        " c=clamp(c,0.0,1.0);\n" +
        " float l0=dot(c0,vec3(0.299,0.587,0.114));\n" +
        " float s=1.0-smoothstep(0.0,0.5,l0); float h=smoothstep(0.5,1.0,l0); float m=1.0-s-h;\n" +
        " float z=(uZl*s+uZm*m+uZh*h)*uAlpha;\n" +
        " o=vec4(mix(c0,c,z),1.0); }";

    private static final String FS_GRAIN = FS_HEAD +
        "uniform float uAmt,uSize,uMono; uniform float uSeed; uniform vec3 uLift; uniform float uAlpha;\n" +
"float hash(vec2 p){ vec3 p3=fract(vec3(p.xyx)*0.1031+uSeed*0.317); p3+=dot(p3,p3.yzx+33.33); return fract((p3.x+p3.y)*p3.z); }\n" +
        "float vn(vec2 p){ vec2 i=floor(p), f=fract(p); f=f*f*f*(f*(f*6.0-15.0)+10.0);\n" +
        "  return mix(mix(hash(i),hash(i+vec2(1.0,0.0)),f.x), mix(hash(i+vec2(0.0,1.0)),hash(i+vec2(1.0,1.0)),f.x), f.y); }\n" +
        "float gn(vec2 p){ vec2 r=vec2(0.891*p.x+0.454*p.y,-0.454*p.x+0.891*p.y);\n" +
        "  return 0.5*vn(p)+0.5*vn(r+vec2(31.7,11.3)); }\n" +
        "void main(){ vec3 c=src(); if(uAmt<=0.0){o=vec4(c,1.0);return;}\n" +
        " vec2 gp=gl_FragCoord.xy/max(uSize,1.0);\n" +
        " float n1=gn(gp), n2=gn(gp+vec2(37.7,17.3)), n3=gn(gp+vec2(11.7,73.9));\n" +
        " float lum=dot(c,vec3(0.299,0.587,0.114));\n" +
        " float mod_ = 1.0-abs(lum-0.5)*1.2;\n" +
        " float k=uAmt/50.0*mod_;\n" +
        " vec3 g = uMono>0.5 ? vec3((n1+n2+n3)/3.0-0.5) : vec3(n1-0.5,n2-0.5,n3-0.5);\n" +
        " o=vec4(mix(c,clamp(c+g*k*0.35+uLift,0.0,1.0),uAlpha),1.0); }";


    private static final String FS_VIG = FS_HEAD +
        "uniform float uA,uS,uE,uR,uG,uB; uniform float uAlpha;\n" +
        "void main(){ vec3 c=src(); if(uA<=0.0){o=vec4(c,1.0);return;}\n" +
        " vec2 asp=vec2(uRes.x/uRes.y,1.0); float d=length((uv()-0.5)*asp)/(0.5*length(asp));\n" +
        " float t=smoothstep(uS,uE,d);\n" +
        " o=vec4(mix(c,vec3(uR,uG,uB),t*uA*uAlpha),1.0); }";

    private static final String FS_GLOW_H = FS_HEAD +
        "uniform float uRadius,uThresh,uThresh2,uMode;\n" +
        "float hm(vec3 c){ float v=uMode<0.5?dot(c,vec3(0.299,0.587,0.114)):max(c.r,max(c.g,c.b)); return smoothstep(uThresh,max(uThresh2,uThresh+0.001),v); }\n" +
        "float dth(vec2 p){ vec3 p3=fract(vec3(p.xyx)*0.1031); p3+=dot(p3,p3.yzx+33.33); return fract((p3.x+p3.y)*p3.z); }\n" +
        "void main(){ vec3 acc=vec3(0.0); float ws=0.0;\n" +
        " for(int i=-12;i<=12;i++){\n" +
        "  float w=exp(-float(i*i)/72.0);\n" +
        "  vec3 s=texture(uTex,uv()+vec2(float(i)*uRadius/12.0/uRes.x,0.0)).rgb;\n" +
        "  acc+=s*hm(s)*w; ws+=w; }\n" +
        " o=vec4(acc/ws + vec3((dth(gl_FragCoord.xy)-0.5)/255.0),1.0); }";

    private static final String FS_GLOW_V = FS_HEAD +
        "uniform sampler2D uSrc; uniform float uRadius,uAmt,uColorMix; uniform vec3 uGlowColor; uniform float uAlpha;\n" +
        "float dth(vec2 p){ vec3 p3=fract(vec3(p.xyx)*0.1031); p3+=dot(p3,p3.yzx+33.33); return fract((p3.x+p3.y)*p3.z); }\n" +
        "void main(){ vec3 blur=vec3(0.0); float ws=0.0;\n" +
        " for(int j=-12;j<=12;j++){\n" +
        "  float w=exp(-float(j*j)/72.0);\n" +
        "  blur+=texture(uTex,uv()+vec2(0.0,float(j)*uRadius/12.0/uRes.y)).rgb*w; ws+=w; }\n" +
        " blur/=ws; blur=mix(blur,blur*uGlowColor,uColorMix);\n" +
        " vec3 c=texture(uSrc,uv()).rgb;\n" +
        " o=vec4(mix(c,c+blur*uAmt,uAlpha) + vec3((dth(gl_FragCoord.xy)-0.5)/255.0),1.0); }";

    static boolean NATIVE = false;
    static { try { System.loadLibrary("of6k"); NATIVE = true; dbg("libof6k loaded"); } catch (Throwable t) { NATIVE = false; dbg("libof6k load FAIL: " + t); } }
    private native boolean nInit();
    /** full native: jpeg file -> chain -> jpeg file (turbojpeg decode/encode, tiled GL). 0 = ok */
    private native int nProcessFile(String inPath, String outPath, String lutPath, float[] nodes, String[] overlays, String lutPath2, float[] nodesNight, float score);
    /** renders chain into direct buf (RGBA, w*h*4) in place. node rows: 11 floats each */
    private native int nRender(int w, int h, java.nio.ByteBuffer buf, String lutPath, float[] nodes, String[] overlays, String lutPath2, float score);
    private static boolean nInited = false;
    private static java.nio.ByteBuffer nBuf = null;

    /** pack film chain into native node rows: [type, 9 params, alpha] (layout per C++ runNode) */
    private static float parseRat(String v) {
        try {
            if (v.contains("/")) { String[] p2 = v.split("/"); return Float.parseFloat(p2[0]) / Math.max(1e-6f, Float.parseFloat(p2[1])); }
            return Float.parseFloat(v);
        } catch (Throwable e) { return 0f; }
    }

    /** scene day/night score from EXIF exposure: EV12+ -> 0 (day), EV6- -> 1 (night) */
    static float dayNightScore(String imgPath) {
        try {
            android.media.ExifInterface ex = new android.media.ExifInterface(imgPath);
            String et = ex.getAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME);
            String fn = ex.getAttribute(android.media.ExifInterface.TAG_F_NUMBER);
            String iso = ex.getAttribute(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS);
            if (et == null || fn == null) return 0f;
            float t = parseRat(et);
            float f = parseRat(fn);
            float s = parseRat(iso != null ? iso : "100");
            if (t <= 0 || f <= 0) return 0f;
            double ev = Math.log(f * f / t) / Math.log(2) - Math.log(Math.max(100f, s) / 100f) / Math.log(2);
            // remapped window: EV 12 -> 0 (day), EV 8 -> 1 (night); raw score 0.667 (=EV8) clamps to 1
            float sc = (float) ((12.0 - ev) / 4.0);
            return Math.max(0f, Math.min(1f, sc));
        } catch (Throwable e) { return 0f; }
    }

    /** camera-style exposure string from EXIF: "1/60 F4 ISO100" (parts without EXIF are skipped) */
    static String exposureText(String imgPath) {
        try {
            android.media.ExifInterface ex = new android.media.ExifInterface(imgPath);
            StringBuilder sb = new StringBuilder();
            String et = ex.getAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME);
            float t = et != null ? parseRat(et) : 0f;
            if (t > 0f) {
                if (t >= 1f) sb.append(Math.round(t)).append("s");
                else sb.append("1/").append(Math.max(1, Math.round(1f / t)));
            }
            String fn = ex.getAttribute(android.media.ExifInterface.TAG_F_NUMBER);
            float f = fn != null ? parseRat(fn) : 0f;
            if (f > 0f) { if (sb.length() > 0) sb.append(' '); sb.append('F').append(f == Math.round(f) ? String.valueOf(Math.round(f)) : String.format(java.util.Locale.US, "%.1f", f)); }
            String iso = ex.getAttribute(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS);
            if (iso != null && parseRat(iso) > 0f) { if (sb.length() > 0) sb.append(' '); sb.append("ISO").append(Math.round(parseRat(iso))); }
            return sb.toString();
        } catch (Throwable e) { return ""; }
    }

    /** one random overlay file per enabled image node, in chain order */
    private String[] buildOverlays(String film) {
        if (Films.isSplit(film)) film = film + ".day";
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        for (String step : Films.s(film, "chain", "").split(",")) {
            step = step.trim();
            if (step.isEmpty()) continue;
            String[] pr = step.split(":", 2);
            String ty = pr[0], nid = pr.length > 1 ? pr[1] : "";
            if (!ty.equals("overlay")) continue;
            if (np(film, ty, nid, "enable", 1.0f) <= 0) continue;
            String list = Films.s(film, ty + "@" + nid + ".list", "");
            java.util.ArrayList<String> fs = new java.util.ArrayList<String>();
            for (String s2 : list.split(",")) {
                s2 = s2.trim();
                if (s2.length() > 0) fs.add("/sdcard/OpenFilm6K/overlays/" + s2);
            }
            if (fs.isEmpty()) continue;
            String pick = fs.get((int) (Math.random() * fs.size()));
            dbg("overlay pick " + pick);
            out.add(pick);
        }
        return out.toArray(new String[0]);
    }

    private float[] buildNodes(String film) {
        java.util.List<Float> out = new java.util.ArrayList<Float>();
        for (String step : Films.s(film, "chain", "lut,grade,glow,grain,vig").split(",")) {
            step = step.trim();
            if (step.isEmpty()) continue;
            String[] pr = step.split(":", 2);
            String ty = pr[0], nid = pr.length > 1 ? pr[1] : "";
            float al;
            if (ty.equals("lut")) al = alF(film, ty, nid, "on", 1f);
            else if (ty.equals("glow")) al = alF(film, ty, nid, "amount", 1f);
            else if (ty.equals("grain")) al = alF(film, ty, nid, "amount", 1f / 50f);
            else if (ty.equals("vig")) al = alF(film, ty, nid, "amount", 1f);
            else if (ty.equals("overlay")) al = alF(film, ty, nid, "amount", 1f);
            else al = np(film, ty, nid, "alpha", 1.0f);
            if (np(film, ty, nid, "enable", 1.0f) <= 0) continue;
            float[] P = new float[12];
            if (ty.equals("lut")) {
                P[0] = 0; P[1] = al;   // strength folded into alpha
            } else if (ty.equals("grade")) {
                P[0] = 1;
                P[1] = np(film, ty, nid, "exposure", 0); P[2] = np(film, ty, nid, "contrast", 0);
                P[3] = np(film, ty, nid, "saturation", 0); P[4] = np(film, ty, nid, "temp", 0); P[5] = np(film, ty, nid, "tint", 0);
                P[6] = np(film, ty, nid, "low", 1); P[7] = np(film, ty, nid, "mid", 1); P[8] = np(film, ty, nid, "high", 1);
                P[10] = al;
            } else if (ty.equals("glow")) {
                P[0] = 2; P[1] = 1.0f; P[2] = np(film, ty, nid, "radius", 8);
                P[3] = np(film, ty, nid, "threshold", 0.6f); P[4] = al;   // strength folded into alpha
                P[5] = np(film, ty, nid, "thresh2", 1.0f); P[6] = np(film, ty, nid, "mode", 0.0f);
                P[7] = np(film, ty, nid, "r", 1.0f); P[8] = np(film, ty, nid, "g", 1.0f); P[9] = np(film, ty, nid, "b", 1.0f);
                P[10] = np(film, ty, nid, "cmix", 0.0f);
                P[11] = np(film, ty, nid, "peak", 0.0f);
            } else if (ty.equals("grain")) {
                P[0] = 3; P[1] = 50.0f; P[2] = np(film, ty, nid, "size", 1);   // amount folded into alpha
                P[3] = np(film, ty, nid, "mono", 1); P[4] = np(film, ty, nid, "liftR", 0);
                P[5] = np(film, ty, nid, "liftG", 0); P[6] = np(film, ty, nid, "liftB", 0); P[7] = al;
            } else if (ty.equals("overlay")) {
                P[0] = 6; P[1] = al;   // strength folded into alpha
                P[2] = np(film, ty, nid, "blend", 0.0f); P[3] = np(film, ty, nid, "scale", 1.0f);
            } else if (ty.equals("hue")) {
                P[0] = 7; P[1] = np(film, ty, nid, "hue", 0.58f); P[2] = np(film, ty, nid, "range", 0.15f);
                P[3] = np(film, ty, nid, "shift", 0.0f); P[4] = al;
                P[6] = np(film, ty, nid, "low", 1.0f); P[7] = np(film, ty, nid, "mid", 1.0f); P[8] = np(film, ty, nid, "high", 1.0f);
                P[9] = np(film, ty, nid, "exposure", 0.0f); P[10] = np(film, ty, nid, "saturation", 0.0f);
            } else if (ty.equals("sharp")) {
                P[0] = 5; P[1] = np(film, ty, nid, "amount", 0); P[2] = np(film, ty, nid, "radius", 1.5f);
                P[3] = al;
            } else if (ty.equals("vig")) {
                P[0] = 4; P[1] = 1.0f; P[2] = np(film, ty, nid, "start", 0);   // amount folded into alpha
                P[3] = np(film, ty, nid, "end", 1); P[4] = np(film, ty, nid, "r", 0);
                P[5] = np(film, ty, nid, "g", 0); P[6] = np(film, ty, nid, "b", 0); P[7] = al;
            } else continue;
            for (float v : P) out.add(v);
        }
        float[] r = new float[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** native fast path: returns true if rendered into out jpeg */
    private boolean nativeProcess(android.graphics.Bitmap src, String film, String outPath) {
        try {
            if (!NATIVE) return false;
            if (!nInited) nInited = nInit();
            if (!nInited) { dbg("nInit FAIL"); return false; }
            int w = src.getWidth(), h = src.getHeight();
            int cap = w * h * 4;
            if (nBuf == null || nBuf.capacity() < cap) nBuf = java.nio.ByteBuffer.allocateDirect(cap).order(java.nio.ByteOrder.nativeOrder());
            nBuf.position(0);
            src.copyPixelsToBuffer(nBuf);
            if (nBuf.position() != cap) return false;
            float[] nodes = buildNodes(film);
            java.io.File lf = Films.lutFile(film);
            String lp = lf != null ? lf.getAbsolutePath() : null;
            nBuf.position(0);
            int rc = nRender(w, h, nBuf, lp, nodes, new String[0], null, 0f);
            if (rc != 0) return false;
            nBuf.position(0);
            android.graphics.Bitmap outBm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            outBm.copyPixelsFromBuffer(nBuf);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(outPath);
            outBm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, fo);
            fo.close();
            return true;
        } catch (Throwable t) {
            dbg("nativeProcess fallback: " + t);
            return false;
        }
    }

    void start() {
        Thread t = new Thread(new Runnable() { public void run() { glInit(); } }, "gl");
        t.start();
    }

    /** strength alpha with legacy-amount inheritance: a saved legacy amount/on key was the operative
     *  strength (the old alpha row was a hidden default for these nodes), so legacy wins when present;
     *  alpha applies only for new-style saves (no amount key). */
    private static float alF(String film, String ty, String nid, String legacyKey, float legacyScale) {
        float v = Films.f(film, ty + "@" + nid + "." + legacyKey, Float.NaN);
        if (Float.isNaN(v)) v = Films.f(film, ty + "." + legacyKey, Float.NaN);
        if (!Float.isNaN(v)) return Math.max(0f, Math.min(1f, v * legacyScale));
        float a = Films.f(film, ty + "@" + nid + ".alpha", Float.NaN);
        if (Float.isNaN(a)) a = Films.f(film, ty + ".alpha", Float.NaN);
        return Float.isNaN(a) ? 1.0f : a;
    }

    /** per-node param lookup: chain step "grade:2" -> key "grade:2.exposure", fallback shared "grade.exposure" */
    private static float np(String film, String ty, String nid, String param, float def) {
        if (nid != null && !nid.isEmpty()) {
            float v = Films.f(film, ty + "@" + nid + "." + param, Float.NaN);
            if (!Float.isNaN(v)) return v;
        }
        return Films.f(film, ty + "." + param, def);
    }

    static void dbg(String s) {
        try { java.io.FileWriter fw = new java.io.FileWriter("/sdcard/OpenFilm6K/debug.txt", true); fw.write(s + "\n"); fw.close(); } catch (Throwable ig) {}
    }

    private void glInit() {
        dbg("glInit enter");
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            EGL14.eglInitialize(dpy, ver, 0, ver, 1);
            int[] cfgA = { EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, 4, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE };
            int[] num = new int[1];
            EGLConfig[] cfg = new EGLConfig[1];
            EGL14.eglChooseConfig(dpy, cfgA, 0, cfg, 0, 1, num, 0);
            int[] ctxA = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE };
            ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, ctxA, 0);
            int[] pbA = { EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE };
            surf = EGL14.eglCreatePbufferSurface(dpy, cfg[0], pbA, 0);
            EGL14.eglMakeCurrent(dpy, surf, surf, ctx);
            progLut = prog(VS, FS_LUT);
            progGrain = prog(VS, FS_GRAIN);
            progVig = prog(VS, FS_VIG);
            progGlowH = prog(VS, FS_GLOW_H);
            progGlowV = prog(VS, FS_GLOW_V);
            progGrade = prog(VS, FS_GRADE);
            inited = true;
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            dbg("glInit done (released)");
        } catch (Throwable t) { dbg("glInit EX " + t); MainActivity.say("engine init EX " + t); }
    }

    private static int prog(String vs, String fs) {
        int v = sh(GLES30.GL_VERTEX_SHADER, vs), f = sh(GLES30.GL_FRAGMENT_SHADER, fs);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] st = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, st, 0);
        if (st[0] == 0) throw new RuntimeException("link: " + GLES30.glGetProgramInfoLog(p));
        return p;
    }

    private static int sh(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s);
        int[] st = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, st, 0);
        if (st[0] == 0) throw new RuntimeException("compile: " + GLES30.glGetShaderInfoLog(s));
        return s;
    }

    private int lut3D(CubeLib.Lut lut) {
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE);
        int nnn = lut.n * lut.n * lut.n;
        ByteBuffer b = ByteBuffer.allocateDirect(nnn * 4);
        for (int i = 0; i < nnn; i++) {
            for (int c = 0; c < 3; c++) {
                float v = lut.data[i * 3 + c];
                b.put((byte) Math.max(0, Math.min(255, Math.round(v * 255f))));
            }
            b.put((byte) 255);
        }
        b.rewind();
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, lut.n, lut.n, lut.n, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, b);
        return tex[0];
    }

    private long lutMtime = 0;

    private int lutFor(String film) {
        try {                                           // invalidate cache when the lut file changes
            java.io.File lf = Films.lutFile(film);
            long mt = lf != null ? lf.lastModified() : 0;
            if (mt != lutMtime && lutTex != 0) {
                GLES30.glDeleteTextures(1, new int[]{lutTex}, 0);
                lutTex = 0; lutFor = "";
            }
        } catch (Throwable ig) {}
        if (lutFor.equals(film) && lutTex != 0) return lutTex;
        if (lutTex != 0) GLES30.glDeleteTextures(1, new int[]{lutTex}, 0);
        CubeLib.Lut lut;
        try {
            File f = Films.lutFile(film);
            if (f != null && f.getName().endsWith(".cube")) lut = CubeLib.parseCube(f);
            else if (f != null && f.getName().endsWith(".png")) {
                Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
                lut = CubeLib.identity(64);
                int n = 64, i = 0;
                for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
                    int px = (g % 8) * n + r, py = (b / 8) * n + g;
                    int c = bm.getPixel(px, py);
                    lut.data[i++] = ((c >> 16) & 255) / 255f;
                    lut.data[i++] = ((c >> 8) & 255) / 255f;
                    lut.data[i++] = (c & 255) / 255f;
                }
                lut.n = n;
            } else lut = CubeLib.identity(64);
        } catch (Throwable t) { MainActivity.say("lut " + film + " EX " + t); lut = CubeLib.identity(64); }
        lutN = lut.n; lutTex = lut3D(lut); lutFor = film;
        try { java.io.File lf = Films.lutFile(film); lutMtime = lf != null ? lf.lastModified() : 0; } catch (Throwable ig) {}
        return lutTex;
    }

    private void ensureFbo(int w, int h) {
        if (w == fboW && h == fboH) return;
        for (int[] pair : new int[][]{{fboA, texA}, {fboB, texB}}) {
            if (pair[0] != 0) { GLES30.glDeleteFramebuffers(1, pair, 0); GLES30.glDeleteTextures(1, new int[]{pair[1]}, 0); }
        }
        int[] t = new int[2], f = new int[2];
        for (int i = 0; i < 2; i++) {
            GLES30.glGenTextures(1, t, i); GLES30.glGenFramebuffers(1, f, i);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[i]);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[i]);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t[i], 0);
        }
        fboA = f[0]; texA = t[0]; fboB = f[1]; texB = t[1];
        {   int[] ts = new int[1], fs = new int[1];
            GLES30.glGenTextures(1, ts, 0); GLES30.glGenFramebuffers(1, fs, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ts[0]);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fs[0]);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, ts[0], 0);
            texScratch = ts[0]; fboScratch = fs[0];
        }
        fboW = w; fboH = h;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    private void run(int prog, int srcTex, int dstFbo, int w, int h, Uniforms u) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dstFbo);
        GLES30.glViewport(0, 0, w, h);
        GLES30.glUseProgram(prog);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uTex"), 0);
        GLES30.glUniform2f(GLES30.glGetUniformLocation(prog, "uRes"), w, h);
        u.set(prog);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
    }

    interface Uniforms { void set(int prog); }

    /** editor time-preview override for the day/night score (null = auto EXIF) */
    static volatile Float scoreOverride = null;

    /** render img through film pipeline, write jpeg to out. returns out path or null */
    public String process(String imgPath, String film, String outPath) {
        synchronized (lock) {
            try {
                if (!inited) { MainActivity.say("engine not ready"); return "ERR engine not ready"; }
                BitmapFactory.Options _o = new BitmapFactory.Options();
                _o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                long _d0 = android.os.SystemClock.elapsedRealtime();
                // full-native fast path: jpeg -> chain -> jpeg, zero Java bitmaps
                if (NATIVE) {
                    if (!nInited) nInited = nInit();
                    String low = imgPath.toLowerCase(java.util.Locale.US);
                    if (nInited && (low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".raw"))) {
                        String dayKey = Films.isSplit(film) ? film + ".day" : film;   // day half reads the .day variant when split
                        float[] nds = buildNodes(dayKey);
                        java.io.File lf = Films.lutFile(dayKey);
                        long _n0 = android.os.SystemClock.elapsedRealtime();
                        String[] ovs = buildOverlays(film);
                        // day/night split: score 0=day 1=night — TWO full renders, outputs blended
                        Float ov = scoreOverride; scoreOverride = null;   // consume-once: a leaked editor value can pin at most one render
                        float score = ov != null ? ov : dayNightScore(imgPath);
                        String lut2 = null;
                        float[] nn = null;
                        if (Films.isSplit(film) && score > 0.001f) {
                            nn = buildNodes(film + ".night");
                            java.io.File lf2 = Films.lutFile(film + ".night");
                            if (lf2 != null && nn.length > 0) {
                                if (score >= 0.999f) {   // short-circuit: pure night, single render
                                    nds = nn; lf = lf2; nn = null;
                                } else lut2 = lf2.getAbsolutePath();   // output blend: chains may differ freely
                            } else { nn = null; dbg("daynight: night lut missing, day only"); }
                        }
                        dbg("daynight score=" + score + " blend=" + (lut2 != null));
                        int rc = nProcessFile(imgPath, outPath, lf != null ? lf.getAbsolutePath() : null, nds, ovs, lut2, nn, lut2 != null ? score : 0f);
                        long _n1 = android.os.SystemClock.elapsedRealtime();
                        dbg("TIMING nativeFile=" + (_n1 - _n0) + "ms rc=" + rc);
                        if (rc == 0) { Exif.carry(new java.io.File(imgPath), new java.io.File(outPath)); return outPath; }
                        dbg("nProcessFile rc=" + rc + " -> fallback");
                    }
                }
                Bitmap src = BitmapFactory.decodeFile(imgPath, _o);
                long _d1 = android.os.SystemClock.elapsedRealtime();
                dbg("TIMING decode=" + (_d1 - _d0) + "ms");
                if (src == null) { MainActivity.say("decode fail " + imgPath); return "ERR decode fail " + imgPath; }
                long _t0 = android.os.SystemClock.elapsedRealtime();
                dbg("native try " + imgPath);
                boolean _ok = nativeProcess(src, film, outPath);
                long _t1 = android.os.SystemClock.elapsedRealtime();
                long _td = _t0;
                try { _td = Long.parseLong(Films.props(film).getProperty("_decodeMs", "0")); } catch (Throwable ig) {}
                dbg(String.format(java.util.Locale.US,
                    "TIMING total=%dms (decode+scale=%d nativeRest=%d) %dx%d",
                    _t1 - _t0, _td, (_t1 - _t0) - _td, src.getWidth(), src.getHeight()));
                if (_ok) { dbg("native OK"); Exif.carry(new java.io.File(imgPath), new java.io.File(outPath)); return outPath; }
                dbg("native miss -> java path");
                int w = src.getWidth(), h = src.getHeight();
                int[] mts = new int[1];
                GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, mts, 0);
                int lim = Math.max(512, (mts[0] == 0 ? 4096 : mts[0]) - 8);
                if (w > lim || h > lim) {                       // GPU texture limit: scale to fit, keep aspect
                    float s = Math.min(lim / (float) w, lim / (float) h);
                    int nw = Math.max(1, Math.round(w * s)), nh = Math.max(1, Math.round(h * s));
                    android.graphics.Bitmap sc = Bitmap.createScaledBitmap(src, nw, nh, true);
                    if (sc != src) { src.recycle(); src = sc; }
                    dbg("input scaled " + w + "x" + h + " -> " + nw + "x" + nh + " (maxTex " + mts[0] + ")");
                    w = nw; h = nh;
                }
                if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) {
                    dbg("makeCurrent FAILED on " + Thread.currentThread().getName());
                    return "ERR GL context busy";
                }
                ensureFbo(w, h);
                int[] inTex = new int[1];
                GLES30.glGenTextures(1, inTex, 0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inTex[0]);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, src, 0);
                src.recycle();

                int cur = inTex[0];
                int curFbo = 0;   // input texture is not a render target
                // per-film pipeline: chain=lut,grade,glow,grain,vig (comma list, any order,
                // repeats allowed, unknown passes skipped — forward compatible. FIMO chains
                // differ per film (CS-800T runs vignette+levels BEFORE its LUT), so each
                // film.properties can declare its own order, e.g. chain=vig,grade,lut,grade)
                String chain = Films.s(film, "chain", "lut,grade,glow,grain,vig");
                for (String step : chain.split(",")) {
                    step = step.trim();
                    if (step.isEmpty()) continue;
                    String[] _p = step.split(":", 2);
                    final String ty = _p[0], nid = _p.length > 1 ? _p[1] : "";
                    step = ty;
                    final float nAl = np(film, ty, nid, "alpha", 1.0f);
                    if (np(film, ty, nid, "enable", 1.0f) <= 0) { dbg("chain: node " + step + " disabled"); continue; }
                    int dT = (cur == texA) ? texB : texA, dF = (cur == texA) ? fboB : fboA;
                    if ("lut".equals(step)) {
                        final float fon = np(film, ty, nid, "on", 1.0f) * nAl; final int lt = lutFor(film);
                        run(progLut, cur, dF, w, h, new Uniforms() { public void set(int p) {
                            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
                            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lt);
                            GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "uLut"), 1);
                            GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uOn"), fon);
                            GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uAlpha"), 1.0f);
                            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                        }});
                        cur = dT; curFbo = dF;
                    } else if ("grade".equals(step)) {
                        final float ge = np(film, ty, nid, "exposure", 0), gc = np(film, ty, nid, "contrast", 0),
                            gs = np(film, ty, nid, "saturation", 0), gt = np(film, ty, nid, "temp", 0),
                            gi = np(film, ty, nid, "tint", 0);
                        if (ge != 0 || gc != 0 || gs != 0 || gt != 0 || gi != 0) {
                            final float zl = np(film, ty, nid, "low", 1), zm = np(film, ty, nid, "mid", 1), zh = np(film, ty, nid, "high", 1);
                            run(progGrade, cur, dF, w, h, new Uniforms() { public void set(int p) {
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uExp"), ge);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uCon"), gc);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uSat"), gs);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uTemp"), gt);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uTint"), gi);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uZl"), zl);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uZm"), zm);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uZh"), zh);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uAlpha"), nAl);
                            }});
                            cur = dT; curFbo = dF;
                        }
                    } else if ("glow".equals(step)) {
                        if (np(film, ty, nid, "amount", 0) > 0) {
                            final float ga = np(film, ty, nid, "amount", 0), gr = np(film, ty, nid, "radius", 8), gt = np(film, ty, nid, "threshold", 0.6f);
                            run(progGlowH, cur, fboScratch, w, h, new Uniforms() { public void set(int p) {
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uRadius"), gr);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uThresh"), gt);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uThresh2"), np(film, ty, nid, "thresh2", 1.0f));
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uMode"), np(film, ty, nid, "mode", 0.0f));
                            }});
                            final int curF = cur;
                            run(progGlowV, texScratch, dF, w, h, new Uniforms() { public void set(int p) {
                                GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
                                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curF);
                                GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "uSrc"), 1);
                                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uRadius"), gr);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uAmt"), ga);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uAlpha"), nAl);
                                android.opengl.GLES30.glUniform3f(GLES30.glGetUniformLocation(p, "uGlowColor"),
                                    np(film, ty, nid, "r", 1.0f), np(film, ty, nid, "g", 1.0f), np(film, ty, nid, "b", 1.0f));
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uColorMix"), np(film, ty, nid, "cmix", 0.0f));
                            }});
                            cur = dT; curFbo = dF;
                        }
                    } else if ("grain".equals(step)) {
                        if (np(film, ty, nid, "amount", 0) > 0) {
                            final float ga = np(film, ty, nid, "amount", 0), gs = np(film, ty, nid, "size", 1), gm = np(film, ty, nid, "mono", 1);
                            final float lr = np(film, ty, nid, "liftR", 0), lg = np(film, ty, nid, "liftG", 0), lb = np(film, ty, nid, "liftB", 0);
                            run(progGrain, cur, dF, w, h, new Uniforms() { public void set(int p) {
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uAmt"), ga);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uSize"), gs);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uMono"), gm);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uSeed"), 1.0f);
                                GLES30.glUniform3f(GLES30.glGetUniformLocation(p, "uLift"), lr / 255f, lg / 255f, lb / 255f);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uAlpha"), nAl);
                            }});
                            cur = dT; curFbo = dF;
                        }
                    } else if ("vig".equals(step)) {
                        if (np(film, ty, nid, "alpha", 0) > 0) {
                            final float a = np(film, ty, nid, "amount", np(film, ty, nid, "alpha", 0)), s0 = np(film, ty, nid, "start", 0),
                                e0 = np(film, ty, nid, "end", 1), r0 = np(film, ty, nid, "r", 0),
                                g0 = np(film, ty, nid, "g", 0), b0 = np(film, ty, nid, "b", 0);
                            run(progVig, cur, dF, w, h, new Uniforms() { public void set(int p) {
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uA"), a);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uS"), s0);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uE"), e0);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uR"), r0);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uG"), g0);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uB"), b0);
                                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uAlpha"), nAl);
                            }});
                            cur = dT; curFbo = dF;
                        }
                    } else {
                        dbg("chain: unknown pass '" + step + "' skipped");
                    }
                }
                if (curFbo == 0) {   // every node disabled: passthrough copy via identity LUT pass
                    final int lt0 = lutFor(film);
                    run(progLut, cur, fboA, w, h, new Uniforms() { public void set(int p) {
                        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lt0);
                        GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "uLut"), 1);
                        GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uOn"), 0.0f);
                        GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uAlpha"), 1.0f);
                        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                    }});
                    curFbo = fboA;
                }

                ByteBuffer bb = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, curFbo);
                GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb);
                GLES30.glDeleteTextures(1, inTex, 0);
                Bitmap outBm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bb.rewind();
                outBm.copyPixelsFromBuffer(bb);
                Bitmap rotated = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(rotated);
                android.graphics.Matrix m = new android.graphics.Matrix();
                // GL readback rows already match GLUtils upload order: no flip needed
                cv.drawBitmap(outBm, m, null);
                File of = new File(outPath);
                of.getParentFile().mkdirs();
                FileOutputStream fo = new FileOutputStream(of);
                rotated.compress(Bitmap.CompressFormat.JPEG, 92, fo);
                fo.close();
                Exif.carry(new File(imgPath), of);   // keep the capture's EXIF on the Java path too
                return outPath;
            } catch (Throwable t) {
                MainActivity.say("process EX " + t);
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                MainActivity.say(sw.toString());
                return "ERR process EX " + t;
            } finally {
                try { EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT); } catch (Throwable ig) {}
            }
        }
    }
}

package com.openfilm6k.camera;

import android.hardware.Camera;

import java.lang.reflect.Method;
import java.util.List;

/**
 * CameraEx via reflection (no stubs dependency, Recipe Lab's proven approach).
 * All Sony scalar APIs: com.sony.scalar.hardware.CameraEx(.ParametersModifier).
 */
final class CameraRig {
    private Object cameraEx;          // com.sony.scalar.hardware.CameraEx
    private Camera camera;            // android.hardware.Camera
    private Class<?> cxClass, modClass;

    boolean open() {
        try {
            cxClass = Class.forName("com.sony.scalar.hardware.CameraEx");
            modClass = Class.forName("com.sony.scalar.hardware.CameraEx$ParametersModifier");
            Method open = cxClass.getMethod("open", int.class,
                    Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions"));
            cameraEx = open.invoke(null, 0, null);
            camera = (Camera) cxClass.getMethod("getNormalCamera").invoke(cameraEx);
            return true;
        } catch (Throwable t) {
            Logger.log("CameraRig.open: " + t);
            return false;
        }
    }

    void release() {
        try {
            if (camera != null) camera.stopPreview();
            if (cameraEx != null) cxClass.getMethod("release").invoke(cameraEx);
        } catch (Throwable t) {
            Logger.log("CameraRig.release: " + t);
        }
        cameraEx = null;
        camera = null;
    }

    Camera camera() { return camera; }
    boolean ready() { return camera != null; }

    /** empty parameters — only what we set gets applied (Sony semantics) */
    Camera.Parameters emptyParams() {
        // full current param set: modifying only the target key preserves everything else
        // (the old createEmptyParameters path stomped unset vendor params like AF area mode)
        return camera.getParameters();
    }

    private Object modifier(Camera.Parameters p) throws Exception {
        return cxClass.getMethod("createParametersModifier", Camera.Parameters.class)
                .invoke(cameraEx, p);
    }

    // ---- parameter reads (through the live parameter set) ----

    @SuppressWarnings("rawtypes")
    List supportedIsos() {
        try {
            Object m = modifier(camera.getParameters());
            return (List) modClass.getMethod("getSupportedISOSensitivities").invoke(m);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("rawtypes")
    Object shutterSpeed() {   // android.util Pair(n, d)
        try {
            Object m = modifier(camera.getParameters());
            return modClass.getMethod("getShutterSpeed").invoke(m);
        } catch (Throwable t) {
            return null;
        }
    }

    int iso() {
        try {
            Object m = modifier(camera.getParameters());
            return (Integer) modClass.getMethod("getISOSensitivity").invoke(m);
        } catch (Throwable t) { return 0; }
    }

    int aperture() {
        try {
            Object m = modifier(camera.getParameters());
            return (Integer) modClass.getMethod("getAperture").invoke(m);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---- parameter writes (each on an empty param set, applied atomically) ----

    private void applyModifier(String setter, Class<?> type, Object value, String tag) {
        try {
            Camera.Parameters p = emptyParams();
            Object m = modifier(p);
            modClass.getMethod(setter, type).invoke(m, value);
            camera.setParameters(p);
        } catch (Throwable t) {
            Logger.log(tag + ": " + t);
        }
    }

    void setIso(int iso) {
        applyModifier("setISOSensitivity", int.class, iso, "setIso");
    }

    void setSceneMode(String mode) {
        try {
            Camera.Parameters p = emptyParams();
            p.setSceneMode(mode);
            camera.setParameters(p);
        } catch (Throwable t) {
            Logger.log("setSceneMode: " + t);
        }
    }

    void setDriveSingle() {
        applyModifier("setDriveMode", String.class, "single", "setDriveMode");
    }

    void setAspectRatio32() {
        applyModifier("setImageAspectRatio", String.class, "3:2", "setAspectRatio");
    }

    void setEv(int ev) {
        try {
            Camera.Parameters p = emptyParams();
            p.setExposureCompensation(ev);
            camera.setParameters(p);
        } catch (Throwable t) {
            Logger.log("setEv: " + t);
        }
    }

    // ---- stepping (Sony-native relative adjustments) ----

    void adjustShutter(int steps) {
        try {
            cxClass.getMethod("adjustShutterSpeed", int.class).invoke(cameraEx, steps);
        } catch (Throwable t) {
            Logger.log("adjustShutter: " + t);
        }
    }

    void adjustAperture(int steps) {
        try {
            cxClass.getMethod("adjustAperture", int.class).invoke(cameraEx, steps);
        } catch (Throwable t) {
            Logger.log("adjustAperture: " + t);
        }
    }

    // ---- capture ----

    void shoot() {
        if (camera == null) { Logger.log("shoot: camera not open"); return; }
        try {
            Logger.log("takePicture: enter");
            camera.takePicture(null, null, null);
            Logger.log("takePicture: returned");
        } catch (Throwable t) {
            Logger.log("takePicture: " + t);
        }
    }

    void cancelShot() {
        try {
            Logger.log("cancelTakePicture: enter");
            cxClass.getMethod("cancelTakePicture").invoke(cameraEx);
            Logger.log("cancelTakePicture: returned");
        } catch (Throwable t) {
            Logger.log("cancelTakePicture: " + t);
        }
    }

    void startPreview() {
        try {
            camera.startPreview();
            spotDirty = spotTouched || userSetFam;   // re-apply after preview restart, but never before first user touch
        } catch (Throwable t) {
            Logger.log("startPreview: " + t);
        }
    }

    private int spotX = 0, spotY = 0;   // flexible spot AF, -1000..1000
    private boolean spotDirty = false;   // only push params after the user touched focus
    private boolean spotTouched = false;
    static final String[] FOCUS_MODES = {"local", "center", "flex-spot"};   // app concept: area/center/flex-spot (no wide)
    static final String[] FOCUS_NAMES = {"Area", "Center", "Flex Spot"};
    static final String[] FOCUS_DISP = {"WIDE", "NARROW", "POINT"};   // app-facing names
    private int focusModeIdx = 0;
    private boolean userSetFam = false;   // only push area mode after the user cycles it
    String focusMode() { return FOCUS_MODES[focusModeIdx]; }

    /** wheel action: cycle area mode; returns the mode now active */
    String cycleFocusMode() {
        focusModeIdx = (focusModeIdx + 1) % FOCUS_MODES.length;
        userSetFam = true;
        spotDirty = true;
        pushSpot();
        return focusMode();
    }

    String focusModeName() { return FOCUS_NAMES[focusModeIdx]; }
    String focusModeDisplay() { return FOCUS_DISP[focusModeIdx]; }

    String cycleFocusModeBack() {
        focusModeIdx = (focusModeIdx + FOCUS_MODES.length - 1) % FOCUS_MODES.length;
        spotDirty = true;
        pushSpot();
        return focusMode();
    }

    void moveSpot(int dx, int dy) { setSpot(spotX + dx, spotY + dy); }

    /** read the system focus point (-1000..1000) from the param table; null if unavailable */
    static int[] readSystemSpot(android.hardware.Camera.Parameters p) {
        try {
            String sx = p.get("focus-point-x"), sy = p.get("focus-point-y");
            if (sx == null || sy == null) return null;
            int x = Integer.parseInt(sx.trim()), y = Integer.parseInt(sy.trim());
            if (x < -1000 || x > 1000 || y < -1000 || y > 1000) return null;
            return new int[]{x, y};
        } catch (Throwable t) { return null; }
    }

    /** adopt a position for display/AIM without writing any camera parameters */
    void adoptSpot(int x, int y) {
        spotX = Math.max(-1000, Math.min(1000, x));
        spotY = Math.max(-1000, Math.min(1000, y));
    }
    void spotCenter() { setSpot(0, 0); }
    void setSpot(int x, int y) {
        spotX = Math.max(-1000, Math.min(1000, x));
        spotY = Math.max(-1000, Math.min(1000, y));
        spotTouched = true;
        spotDirty = true;
        pushSpot();
    }
    /** adopt the firmware's current focus area mode (called at camera open) */
    void syncFocusMode(android.hardware.Camera.Parameters p) {
        String note = "";
        try {
            String cur = p.get("focus-area-type");        // param-table source of truth
            if (cur == null) cur = (String) modifier(p).getClass().getMethod("getFocusAreaMode").invoke(modifier(p));
            note = "ok: " + cur;
            Logger.log("sys focus-area-type: " + cur);
            userSetFam = false;
            if (cur != null) {
                String[] known = {"multi", "wide", "local", "zone", "center", "flex-spot", "spot", "flexible-spot"};
                int[] map = {0, 0, 0, 0, 1, 2, 1, 2};   // multi/wide/local/zone all => area
                for (int i = 0; i < known.length; i++)
                    if (known[i].equals(cur)) { focusModeIdx = map[i]; break; }
            }
            try {                                   // dump focus-related vendor params for diagnosis
                for (String kv : p.flatten().split(";"))
                    if (kv.toLowerCase().contains("focus")) Logger.log("P " + kv);
            } catch (Throwable ig) {}
            try {                                   // dump zoom/lens params (RX100 III W/T lever mapping)
                Logger.log("Z total=" + p.flatten().split(";").length);
                for (String kv : p.flatten().split(";"))
                    if (kv.toLowerCase().matches(".*(zoom|lens|tele|wide).*")) Logger.log("Z " + kv);
            } catch (Throwable ig) {}
            dumpFileFormat();                       // file/format params (RAW detection)
        } catch (Throwable t) { note = "EX: " + t; Logger.log("syncFocusMode: " + t); }
        try { java.io.FileWriter fw = new java.io.FileWriter("/sdcard/OpenFilm6K/focusmode.txt", true);
              fw.write(note + "\n"); fw.close();
        } catch (Throwable ig) {
            Logger.log("fm write: " + ig);
            try { java.io.FileWriter fw2 = new java.io.FileWriter("/sdcard/OpenFilm6K/fm_err.txt", true);
                  fw2.write(ig + "\n"); fw2.close(); } catch (Throwable ig2) {}
        }
    }

    /** the Sony still-storage format (RAW/JPEG), null when unavailable */
    String storageFormat() {
        try {
            if (camera == null) return null;
            Object m = modifier(camera.getParameters());
            Object v = modClass.getMethod("getPictureStorageFormat").invoke(m);
            return v == null ? null : v.toString();
        } catch (Throwable t) { return null; }
    }

    /** force the Sony storage format to jpeg; true when it is a pure JPEG value afterwards */
    boolean forceJpegStorage() {
        try {
            if (camera == null) return false;
            Object m = modifier(camera.getParameters());
            String cur = null;
            try { Object v = modClass.getMethod("getPictureStorageFormat").invoke(m); cur = v == null ? null : v.toString(); } catch (Throwable ig) {}
            Object sup = null;
            try { sup = modClass.getMethod("getSupportedPictureStorageFormats").invoke(m); } catch (Throwable ig) {}
            Logger.log("still: storage=" + cur + " supported=" + listStr(sup));
            if (isJpegStorage(cur)) return true;
            android.hardware.Camera.Parameters p = emptyParams();
            Object m2 = modifier(p);
            modClass.getMethod("setPictureStorageFormat", String.class).invoke(m2, "jpeg");
            camera.setParameters(p);
            String now = null;
            try { Object v = modClass.getMethod("getPictureStorageFormat").invoke(modifier(camera.getParameters())); now = v == null ? null : v.toString(); } catch (Throwable ig) {}
            Logger.log("still: set storage=jpeg -> " + now);
            return isJpegStorage(now);
        } catch (Throwable t) {
            Logger.log("still: set storage EX " + t);
            return false;
        }
    }

    /** true for a pure JPEG value (equal/starting with jpeg, never raw) */
    private static boolean isJpegStorage(String v) {
        if (v == null) return false;
        String s = v.toLowerCase();
        return (s.equals("jpeg") || s.startsWith("jpeg")) && !s.contains("raw");
    }

    private static String listStr(Object v) {
        if (v == null) return "null";
        if (v instanceof Object[]) {
            Object[] a = (Object[]) v;
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < a.length; i++) { if (i > 0) b.append(','); b.append(a[i]); }
            return b.append(']').toString();
        }
        return String.valueOf(v);
    }

    /** Sony zoom direction constants (ZOOM_DIRECTION_TELE/WIDE), with a 2/1 fallback */
    int zoomDirection(String which) {
        try {
            java.lang.reflect.Field f = cxClass.getField("ZOOM_DIRECTION_" + which.toUpperCase());
            Object v = f.get(null);
            if (v instanceof Integer) { Logger.log("zoom: const " + which + "=" + v); return (Integer) v; }
        } catch (Throwable t) { Logger.log("zoom: const " + which + " EX " + t); }
        return "tele".equals(which) ? 2 : 1;
    }

    /** RX100-series optical zoom: CameraEx.startZoom(dir, speed) / stopZoom() (the param table is unusable) */
    void zoomStart(int dir) {
        try {
            if (cameraEx == null || cxClass == null) { Logger.log("zoom: no cameraEx"); return; }
            Logger.log("zoom: start dir=" + dir + " speed=4");
            cxClass.getMethod("startZoom", int.class, int.class).invoke(cameraEx, dir, 4);
        } catch (Throwable t) { Logger.log("zoom: start EX " + t); }
    }

    void zoomStop() {
        try {
            if (cameraEx == null || cxClass == null) { Logger.log("zoom: no cameraEx"); return; }
            Logger.log("zoom: stop");
            cxClass.getMethod("stopZoom").invoke(cameraEx);
        } catch (Throwable t) { Logger.log("zoom: stop EX " + t); }
    }

    /** diagnostics: file/format parameter table (finding the RX100 RAW key) */
    void dumpFileFormat() {
        try {
            android.hardware.Camera.Parameters p = camera == null ? null : camera.getParameters();
            if (p == null) return;
            String[] kvs = p.flatten().split(";");
            Logger.log("FM total=" + kvs.length);
            for (String kv : kvs) {
                int eq = kv.indexOf('=');
                String k = (eq >= 0 ? kv.substring(0, eq) : kv).toLowerCase();
                if (k.matches(".*(raw|quality|format|still|jpeg|image|exif|drive|file).*"))
                    Logger.log("FM " + kv);
            }
        } catch (Throwable t) { Logger.log("FM EX " + t); }
    }

    void pushSpot() {
        if (camera == null || !spotDirty) return;
        try {
            android.hardware.Camera.Parameters p = emptyParams();
            // Sony CameraEx flexible spot (Camera.Area does not exist on API 10)
            Object mod = modifier(p);
            Class mc = mod.getClass();
            String fam = focusMode();
            if (userSetFam) {
                try {
                    mc.getMethod("setFocusAreaMode", String.class).invoke(mod, fam);
                    Logger.log("setFAM ok: " + fam);
                } catch (Throwable t) { Logger.log("setFAM: " + t); }
                try {
                    p.set("focus-area-type", fam);   // param-table write-back: persists to the system camera
                    Logger.log("setFAT ok: " + fam);
                } catch (Throwable t) { Logger.log("setFAT: " + t); }
            }
            if (userSetFam && "flex-spot".equals(fam)) try {
                mc.getMethod("setFocusAreaFlexibleSpotSize", String.class).invoke(mod, "midium");
            } catch (Throwable t) { Logger.log("setSpotSize: " + t); }
            // focus point via the param-table keys — same -1000..1000 normalized space
            // (the modifier's setFocusPoint was previously fed pixel coords; center coincided
            //  in both spaces so the mismatch only surfaced on off-center points)
            p.set("focus-point-x", String.valueOf(spotX));
            p.set("focus-point-y", String.valueOf(spotY));
            camera.cancelAutoFocus();
            camera.setParameters(p);
            spotDirty = false;
            Logger.log("spot " + spotX + "," + spotY + " (param keys)");
        } catch (Throwable t) { Logger.log("setSpot: " + t); }
    }


    void focus() {
        if (camera == null) { Logger.log("focus: camera not open"); return; }
        try {
            camera.cancelAutoFocus();   // reset first (cancel clears AF area)
            pushSpot();                 // then apply flex-spot point
            try {
                cxClass.getMethod("executeAutoFocusStartTrigger", boolean.class, String.class).invoke(cameraEx, true, "af-s");
                Logger.log("afTrigger ok");
            } catch (Throwable tt) { Logger.log("afTrigger: " + tt); }
            camera.autoFocus(new android.hardware.Camera.AutoFocusCallback() {
                public void onAutoFocus(boolean ok, android.hardware.Camera c) {
                    com.openfilm6k.camera.MainActivity.focusFeedback(ok);
                    try {
                        Object lm = modifier(camera.getParameters());
                        Class lc = lm.getClass();
                        Object fpv = lc.getMethod("getFocusPoint").invoke(lm);
                        String fps = "?";
                        try { android.util.Pair pr = (android.util.Pair) fpv; fps = "(" + pr.first + "," + pr.second + ")"; } catch (Throwable ig) {}
                        Logger.log("postAF fp=" + fps + " famode=" + lc.getMethod("getFocusAreaMode").invoke(lm));
                    } catch (Throwable ig2) {}
                }
            });
        } catch (Throwable t) {
            Logger.log("autoFocus: " + t);
        }
    }

    /** S1 released mid-focus: stop the in-progress auto-focus (matches the firmware behaviour) */
    void cancelFocus() {
        if (camera == null) return;
        try { camera.cancelAutoFocus(); Logger.log("cancelFocus"); } catch (Throwable t) { Logger.log("cancelFocus: " + t); }
    }
}

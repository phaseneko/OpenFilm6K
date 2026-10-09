package com.openfilm6k.host;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** per-film pipeline editor: node list (add / reorder / delete), per-node params + alpha,
 *  split-line preview against the original photo */
public class EditorActivity extends Activity {

    // ---------- node model ----------
    static class Node { String type, id; LinkedHashMap<String, Float> p = new LinkedHashMap<String, Float>(); String list = ""; String file; }   // file = per-node lut (lut nodes only; null = the film lut)
    static final String[] TYPES = {"lut", "grade", "glow", "grain", "vig", "sharp", "overlay", "hue"};
    static final String[] TLABEL = {"tLut", "tGrade", "tGlow", "tGrain", "tVig", "tSharp", "tOverlay", "tHue"};   // i18n keys
    static String[] tlTr() {   // translated copy for adapters
        String[] a = new String[TLABEL.length];
        for (int i = 0; i < TLABEL.length; i++) a[i] = L.s(TLABEL[i]);
        return a;
    }
    /** per type: {key, label, min, max, def} — low/mid/high render as checkboxes */
    static final String[][][] PSPEC = {
        {},   // lut strength = universal α
        {{"exposure", "exposure", "-1", "1", "0"}, {"contrast", "contrast", "-1", "1", "0"}, {"saturation", "saturation", "-1", "1", "0"},
         {"temp", "temp", "-4", "4", "0"}, {"tint", "tint", "-1", "1", "0"},
         {"low", "low", "0", "1", "1"}, {"mid", "mid", "0", "1", "1"}, {"high", "high", "0", "1", "1"}},
        {{"threshold", "threshold", "0", "1", "0.6"}, {"thresh2", "thresh2", "0", "1", "1"},
         {"radius", "radius", "0", "100", "8"}, {"mode", "mode", "0", "1", "0"},
         {"r", "rmul", "0", "2", "1"}, {"g", "gmul", "0", "2", "1"}, {"b", "bmul", "0", "2", "1"},
         {"cmix", "cmix", "0", "1", "0"}, {"peak", "peak", "0", "1", "0"}},
        {{"size", "grainSize", "0.5", "8", "1"}, {"mono", "mono", "0", "1", "1"}},
        {{"start", "start", "0", "1", "0"}, {"end", "end", "0", "1", "1"},
         {"r", "red", "0", "0.5", "0"}, {"g", "green", "0", "0.5", "0"}, {"b", "blue", "0", "0.5", "0"}},
        {{"amount", "amount", "-2", "2", "0"}, {"radius", "radius", "0.5", "8", "1.5"}},
        {{"blend", "blendMode", "0", "4", "0"}, {"scale", "scale", "0.5", "3", "1"}},
        {{"hue", "hue", "0", "1", "0.583"}, {"range", "range", "0.02", "0.5", "0.15"},
         {"shift", "shift", "-0.5", "0.5", "0"}, {"exposure", "exposure", "-1", "1", "0"}, {"saturation", "satLabel", "-1", "1", "0"},
         {"low", "low", "0", "1", "1"}, {"mid", "mid", "0", "1", "1"}, {"high", "high", "0", "1", "1"}},
    };

    // ---------- views ----------
    SplitView split;
    Spinner filmSpin, sceneSpin, addSpin;
    Button delBtn;   // enabled only for user-created films
    LinearLayout nodesBox;
    android.widget.ImageView spyChkView;   // title-bar spy button: checkbox half (state = prefs spy_on)
    Spinner spyDirSpin;                    // spy dialog: watched-folder combobox
    android.app.Dialog spyDlg;             // spy dialog handle (reopened after the folder picker returns)

    // renderBtn/annBtn removed with the render group: preview refreshes automatically, annotation kept separate
    // ---------- region annotation ----------
    boolean annMode = false;
    ArrayList<float[]> annRects = new ArrayList<float[]>();   // image pixels x,y,w,h
    ArrayList<String[]> annMeta = new ArrayList<String[]>();  // {scene, film, comment, ts}
    float[] annDragScr = null;                                 // while dragging (screen coords x0,y0,x1,y1)
    static final String ANN_FILE = "/sdcard/OpenFilm6K/annotations.json";
    ArrayList<Node> nodes = new ArrayList<Node>();
    int expanded = -1;
    boolean editNight = false;   // day/night tab when the film is split
    boolean memSplit = false;    // in-memory split flag — persisted only on save
    String memLut = null;        // in-memory lut= (bare filename) — persisted only on save; null = as loaded
    ArrayList<Node> nodesDay = new ArrayList<Node>(), nodesNight = new ArrayList<Node>();
    Button dnTabDay, dnTabNight, dnPrev;
    LinearLayout dnRow;
    boolean dnPreview = false;   // time-preview mode: render by photo score, hide config controls
    android.app.Dialog settingsDlg;   // live settings dialog (rebuilt after import)
    Spinner setSceneSpin, setLutSpin;   // comboboxes inside it
    android.widget.FrameLayout frameNodes;   // the big group box wrapping the node list
    android.widget.FrameLayout groupOps;     // the node-ops group frame
    final ArrayList<SeekBar> alphaBars = new ArrayList<SeekBar>();
    final ArrayList<Node> alphaOwners = new ArrayList<Node>();
    final ArrayList<TextView> alphaVals = new ArrayList<TextView>();
    LinearLayout addRowRef, dnPrevRow;
    SeekBar dnSlider;
    TextView dnVal;
    CheckBox dnAuto;
    float manualScore = 0f;
    CheckBox splitCbRef;
    boolean splitGuard = false;
    // pipeline clipboard (chain + params + overlay lists), paste is NOT persisted until save
    static String clipChain = null;
    static LinkedHashMap<String, Float> clipParams = new LinkedHashMap<String, Float>();
    static LinkedHashMap<String, String> clipLists = new LinkedHashMap<String, String>();

    interface HueCb { void pick(float hue); }

    /** draggable rainbow hue bar: touch to pick target hue, shows name + degrees */
    class HueBar extends View {
        HueCb cb; float hue = 0.583f;
        final String[] NAMES = {"red","cOrange","cYellow","cChartreuse","green","cTeal","cCyan","cSkyblue","blue","cViolet","cPurple","cMagenta","cRose"};
        HueBar(android.content.Context c) { super(c); }
        void setHue(float h) { hue = h; invalidate(); }
        String name() {
            int seg = (int) ((hue % 1f) * 12f + 0.5f) % 12;
            return L.s(NAMES[seg]) + " " + Math.round((hue % 1f) * 360f) + "°";
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            float h = Math.max(0f, Math.min(1f, e.getX() / Math.max(1f, getWidth())));
            hue = h; cb.pick(h); invalidate(); performClick();
            return true;
        }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float[] hsv = {0f, 1f, 1f};
            int steps = 48;
            for (int i = 0; i < steps; i++) {
                hsv[0] = (float) i / (float) steps * 360f;   // Color.HSVToColor takes degrees
                p.setColor(android.graphics.Color.HSVToColor(hsv));
                cv.drawRect(w * i / steps, 0, w * (i + 1) / steps, h, p);
            }
            // marker
            float mx = w * (hue % 1f);
            p.setColor(0xFFFFFFFF); p.setStrokeWidth(4f);
            cv.drawLine(mx, 0, mx, h, p);
            p.setColor(0xFF000000); p.setStrokeWidth(2f);
            cv.drawLine(mx, 0, mx, h, p);
        }
    }

    /** programmatic eye toggle: open eye (green) = enabled, slashed gray eye = hidden */
    class EyeView extends View {
        boolean open = true;
        EyeView(android.content.Context c) { super(c); }
        void setOpen(boolean o) { open = o; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2, cy = h / 2, ew = w * 0.38f, eh = h * 0.26f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, w * 0.06f));
            p.setColor(open ? 0xFF1B6F45 : 0xFF9E9E9E);
            android.graphics.Path eye = new android.graphics.Path();
            eye.moveTo(cx - ew, cy);
            eye.quadTo(cx, cy - eh * 1.6f, cx + ew, cy);
            eye.quadTo(cx, cy + eh * 1.6f, cx - ew, cy);
            eye.close();
            cv.drawPath(eye, p);
            if (open) {
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(cx, cy, Math.min(ew, eh) * 0.55f, p);
            } else {
                cv.drawLine(cx - ew * 0.9f, cy + eh * 1.2f, cx + ew * 0.9f, cy - eh * 1.2f, p);
            }
        }
    }

    // ---------- split preview ----------
    class SplitView extends View {
        Bitmap ref, adj; float div = 0.5f; int fitX, fitY, fitW, fitH;
        final Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);   // bilinear+ trilinear display scaling
        void setRef(Bitmap b) { ref = b; try { if (b != null) b.setHasMipMap(true); } catch (Throwable ig) {} invalidate(); }
        void setAdj(Bitmap b) { adj = b; try { if (b != null) b.setHasMipMap(true); } catch (Throwable ig) {} invalidate(); }
        float zoom = 1f, panX = 0f, panY = 0f;      // pinch zoom/pan, shared by BOTH sides
        float lastDist = 0f, lastX = 0f, lastY = 0f;
        float downX = 0f, downY = 0f; boolean axisSet = false, axisHoriz = false; float mvX = 0f, mvY = 0f; boolean pinched = false;   // axis lock: first axis to cross slop wins exclusively until finger-up
        final float touchSlop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
        SplitView(android.content.Context c) { super(c); }
        void setImages(Bitmap r, Bitmap a) {
            setRef(r); setAdj(a);
            if (r != null && getWidth() > 0) {   // widget height follows the photo aspect: no letterbox bands
                int h = Math.round(getWidth() * r.getHeight() / (float) r.getWidth());
                android.view.ViewGroup.LayoutParams lp = getLayoutParams();
                if (lp != null && Math.abs(lp.height - h) > 8) { lp.height = h; setLayoutParams(lp); }
            }
        }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (ref != null && w > 0) {   // first layout pass: re-derive height from the photo aspect
                int nh = Math.round(w * ref.getHeight() / (float) ref.getWidth());
                if (Math.abs(h - nh) > 8 && getHeight() != nh) {
                    android.view.ViewGroup.LayoutParams lp = getLayoutParams();
                    if (lp != null) { lp.height = nh; setLayoutParams(lp); }
                }
            }
        }
        Bitmap getRefBitmap() { return ref; }
        private void clampPan() {
            float mx = getWidth() * (zoom - 1) / 2f, my = getHeight() * (zoom - 1) / 2f;
            panX = Math.max(-mx, Math.min(mx, panX));
            panY = Math.max(-my, Math.min(my, panY));
            if (zoom <= 1.001f) { zoom = 1f; panX = panY = 0f; }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            int act = e.getAction() & MotionEvent.ACTION_MASK;
            if (act == MotionEvent.ACTION_DOWN) {
                lastX = e.getX(); lastY = e.getY(); lastDist = 0;
                downX = lastX; downY = lastY; axisSet = false; axisHoriz = false; mvX = lastX; mvY = lastY; pinched = false;
                if (annMode) {
                    annDragScr = new float[]{e.getX(), e.getY(), e.getX(), e.getY()};
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
            if (act == MotionEvent.ACTION_POINTER_DOWN) {
                if (e.getPointerCount() >= 2) {
                    float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
                    lastDist = (float) Math.sqrt(dx * dx + dy * dy);
                    lastX = (e.getX(0) + e.getX(1)) / 2f; lastY = (e.getY(0) + e.getY(1)) / 2f;
                }
                axisSet = true; axisHoriz = true;   // pinch owns both axes
                pinched = true;   // remaining-finger drags after a pinch must NOT touch the divider
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            if (act == MotionEvent.ACTION_POINTER_UP) { lastDist = 0; return true; }
            if (act == MotionEvent.ACTION_MOVE || act == MotionEvent.ACTION_UP) {
                if (!annMode && !axisSet && act == MotionEvent.ACTION_MOVE) {   // axis lock at ANY zoom: H=divider, V=page scroll; single finger never pans
                    float ddx = e.getX() - downX, ddy = e.getY() - downY;
                    if (Math.abs(ddx) > touchSlop) {
                        axisSet = true; axisHoriz = true;   // horizontal wins: divider drag, vertical locked out
                        getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (Math.abs(ddy) > touchSlop) {
                        axisSet = true; axisHoriz = false;  // vertical wins: page scroll, horizontal locked out
                        return false;
                    } else return true;      // within slop: keep waiting
                }
                if (e.getPointerCount() >= 2) {           // pinch: anchored at finger midpoint
                    float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    float mx = (e.getX(0) + e.getX(1)) / 2f, my = (e.getY(0) + e.getY(1)) / 2f;
                    if (lastDist > 1f) {
                        float nz = Math.max(1f, Math.min(12f, zoom * (d / lastDist)));
                        float feff = nz / zoom;              // effective factor after clamp
                        float cx = getWidth() / 2f, cy = getHeight() / 2f;
                        panX = (mx - cx) * (1f - feff) + feff * panX + (mx - lastX);
                        panY = (my - cy) * (1f - feff) + feff * panY + (my - lastY);
                        zoom = nz;
                        clampPan();
                    }
                    lastDist = d; lastX = mx; lastY = my;
                } else if (annMode) {                       // annotate: single finger draws a box
                    if (annDragScr != null) { annDragScr[2] = e.getX(); annDragScr[3] = e.getY(); }
                    if (act == MotionEvent.ACTION_UP && annDragScr != null) {
                        float[] d = annDragScr; annDragScr = null;
                        float dx = Math.abs(d[2] - d[0]), dy = Math.abs(d[3] - d[1]);
                        if (dx > 40 && dy > 40) {
                            float[] a = scrToImg(Math.min(d[0], d[2]), Math.min(d[1], d[3]));
                            float[] b = scrToImg(Math.max(d[0], d[2]), Math.max(d[1], d[3]));
                            annPrompt(new float[]{a[0], a[1], b[0] - a[0], b[1] - a[1]});
                        } else {
                            float[] ip = scrToImg(d[0], d[1]);
                            annTap(ip[0], ip[1]);
                        }
                    }
                } else if (!pinched && adj != null && fitW > 0) {     // single finger: divider follows X only (axis-locked; never right after a pinch)
                    div = Math.max(0.02f, Math.min(0.98f, (e.getX() - fitX) / (float) fitW));
                }
                mvX = e.getX(); mvY = e.getY();
                invalidate();
                return true;
            }
            return true;
        }
        @Override protected void onDraw(Canvas cv) {
            if (ref == null) { Paint p = new Paint(); p.setColor(0xFF202020); cv.drawRect(0, 0, getWidth(), getHeight(), p); return; }
            float s = Math.min(getWidth() / (float) ref.getWidth(), getHeight() / (float) ref.getHeight());
            int dw = Math.round(ref.getWidth() * s), dh = Math.round(ref.getHeight() * s);
            int dx = (getWidth() - dw) / 2, dy = (getHeight() - dh) / 2;
            fitX = dx; fitY = dy; fitW = dw; fitH = dh;
            Rect dst = new Rect(dx, dy, dx + dw, dy + dh);
            int sx = dx + Math.round(dw * div);
            cv.save();                                   // left: reference under shared zoom
            cv.clipRect(0, 0, sx, getHeight());
            cv.translate(panX, panY);
            cv.scale(zoom, zoom, getWidth() / 2f, getHeight() / 2f);
            cv.drawBitmap(ref, null, dst, fp);
            cv.restore();
            if (adj != null) {                           // right: adjusted, SAME transform
                cv.save();
                cv.clipRect(sx, 0, getWidth(), getHeight());
                cv.translate(panX, panY);
                cv.scale(zoom, zoom, getWidth() / 2f, getHeight() / 2f);
                cv.drawBitmap(adj, null, dst, fp);
                cv.restore();
                Paint lp = new Paint(); lp.setColor(0xFFFFFFFF); lp.setStrokeWidth(3);
                cv.drawLine(sx, dy, sx, dy + dh, lp);
                lp.setStrokeWidth(0); lp.setAntiAlias(true);
                float cr = Math.max(14, dh / 30);
                lp.setColor(0xFF000000);   // black rim
                cv.drawCircle(sx, dy + dh / 2, cr, lp);
                lp.setColor(0xFFFFFFFF);   // white core
                cv.drawCircle(sx, dy + dh / 2, cr * 0.6f, lp);
                Paint tp = new Paint(); tp.setColor(0xFFFFFFFF); tp.setTextSize(dh / 28f); tp.setShadowLayer(3, 1, 2, 0xFF000000);
                cv.drawText(L.s("origLabel"), 8, dy + dh / 28f + 6, tp);
                String rgt = L.s("adjLabel");
                cv.drawText(rgt, getWidth() - tp.measureText(rgt) - 8, dy + dh / 28f + 6, tp);
            }
            drawAnns(cv);
        }
    }

    // ---------- annotation: coordinate mapping ----------
    float[] scrToImg(float sx, float sy) {
        float cx = split.getWidth() / 2f, cy = split.getHeight() / 2f;
        float ux = cx + (sx - cx - split.panX) / split.zoom;
        float uy = cy + (sy - cy - split.panY) / split.zoom;
        float iw = split.ref.getWidth(), ih = split.ref.getHeight();
        return new float[]{(ux - split.fitX) / split.fitW * iw, (uy - split.fitY) / split.fitH * ih};
    }
    float[] imgToScr(float ix, float iy) {
        float ux = split.fitX + ix / split.ref.getWidth() * split.fitW;
        float uy = split.fitY + iy / split.ref.getHeight() * split.fitH;
        float cx = split.getWidth() / 2f, cy = split.getHeight() / 2f;
        return new float[]{cx + split.panX + split.zoom * (ux - cx), cy + split.panY + split.zoom * (uy - cy)};
    }
    void drawAnns(Canvas cv) {
        if (split.ref == null) return;
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        bp.setStyle(Paint.Style.STROKE);
        Paint np = new Paint(); np.setShadowLayer(3, 1, 2, 0xFF000000);
        String sc = sceneTag();
        int shown = 0;
        for (int i = 0; i < annRects.size(); i++) {
            if (!annMeta.get(i)[0].equals(sc)) continue;
            float[] r = annRects.get(i);
            float[] a = imgToScr(r[0], r[1]), b = imgToScr(r[0] + r[2], r[1] + r[3]);
            bp.setColor(0xFF00E5FF); bp.setStrokeWidth(annMode ? 5 : 3);
            cv.drawRect(a[0], a[1], b[0], b[1], bp);
            np.setColor(0xFF00E5FF); np.setTextSize(Math.max(24, split.fitH / 40f));
            cv.drawText("#" + i + " " + annMeta.get(i)[2], a[0] + 6, a[1] + np.getTextSize(), np);
            shown++;
        }
        if (annMode) {
            np.setColor(0xFFFFFF00); np.setTextSize(Math.max(22, split.fitH / 45f));
            String hint = shown + " " + L.s("annHint");
            cv.drawText(hint, 12, split.fitY + np.getTextSize() + 4, np);
        }
        if (annDragScr != null) {
            bp.setColor(0xFFFFFF00); bp.setStrokeWidth(4);
            cv.drawRect(annDragScr[0], annDragScr[1], annDragScr[2], annDragScr[3], bp);
        }
    }
    void annPrompt(final float[] rect) {
        final EditText et = new EditText(this);
        et.setHint(L.s("annHintText"));
        new AlertDialog.Builder(this)
            .setTitle(String.format(Locale.US, L.s("annTitle") + "%d  (%.0f,%.0f %.0fx%.0f)", annRects.size(), rect[0], rect[1], rect[2], rect[3]))
            .setView(et)
            .setPositiveButton(L.s("btnSave"), new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    annRects.add(rect);
                    annMeta.add(new String[]{sceneTag(), selFilm(), et.getText().toString(),
                            new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new java.util.Date())});
                    annSave(); split.invalidate();
                }})
            .setNegativeButton(L.s("btnCancel"), null).show();
    }
    void annTap(float ix, float iy) {
        for (int i = annRects.size() - 1; i >= 0; i--) {
            if (!annMeta.get(i)[0].equals(sceneTag())) continue;
            float[] r = annRects.get(i);
            if (ix >= r[0] && ix <= r[0] + r[2] && iy >= r[1] && iy <= r[1] + r[3]) {
                final int idx = i;
                String[] m = annMeta.get(i);
                new AlertDialog.Builder(this)
                    .setTitle(L.s("annTitle") + idx)
                    .setMessage(m[1] + " / " + m[0] + "\n" + m[3] + "\n\n" + m[2] +
                        String.format(Locale.US, "\n\n(%.0f,%.0f) %.0fx%.0f", r[0], r[1], r[2], r[3]))
                    .setPositiveButton(L.s("btnDelete"), new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            annRects.remove(idx); annMeta.remove(idx); annSave(); split.invalidate();
                        }})
                    .setNeutralButton(L.s("btnClose"), null).show();
                return;
            }
        }
    }
    void annLoad() {
        annRects.clear(); annMeta.clear();
        try {
            File f = new File(ANN_FILE);
            if (!f.exists()) return;
            FileInputStream fi = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            fi.read(b); fi.close();
            JSONArray a = new JSONArray(new String(b, "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                annRects.add(new float[]{(float) o.getDouble("x"), (float) o.getDouble("y"),
                        (float) o.getDouble("w"), (float) o.getDouble("h")});
                annMeta.add(new String[]{o.optString("scene", ""), o.optString("film", ""),
                        o.optString("comment", ""), o.optString("ts", "")});
            }
        } catch (Throwable t) { MainActivity.say("annLoad " + t); }
    }
    void annSave() {
        try {
            JSONArray a = new JSONArray();
            for (int i = 0; i < annRects.size(); i++) {
                JSONObject o = new JSONObject();
                float[] r = annRects.get(i); String[] m = annMeta.get(i);
                o.put("scene", m[0]); o.put("film", m[1]); o.put("comment", m[2]); o.put("ts", m[3]);
                o.put("x", r[0]); o.put("y", r[1]); o.put("w", r[2]); o.put("h", r[3]);
                a.put(o);
            }
            FileOutputStream fo = new FileOutputStream(ANN_FILE);
            fo.write(a.toString().getBytes("UTF-8")); fo.close();
        } catch (Throwable t) { MainActivity.say("annSave " + t); }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);   // resolve saved/system language before any UI is built
        MainActivity.firstRunFlow(this);   // EditorActivity IS the launcher: permission + first data install
        startForegroundService(new android.content.Intent(this, HostService.class));   // engine init entry (Editor may be the launch activity)
        SpyWatcher.sync(getApplicationContext());   // resume spy watching if the flag survived
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ux.FACE);   // content column face: keeps section gaps gray while the scroll behind turns desktop-teal
        root.setPadding(Ux.dp(6), Ux.dp(6), Ux.dp(6), Ux.dp(14));   // breathing room between the last group and the window bottom edge
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(0xFF008080);   // fillViewport leftover (short content on tall screens) reads as desktop, not gray dead space
        container.addView(root, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(container);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);

        LinearLayout rowTop = new LinearLayout(this);
        filmSpin = new Spinner(this);
        android.widget.FrameLayout filmSpinWrap = Ux.wrapSpinner(this, filmSpin);
        List<String> films = filmChoices();
        StdSpinnerAdapter fa = new StdSpinnerAdapter(this, films);
        applyDiv(fa, films);
        filmSpin.setAdapter(fa);
        fa.spin = filmSpin;
        filmSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { loadFilm(); updateDelBtn(); }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        Button newBtn = new Button(this); newBtn.setText("＋"); newBtn.setMinWidth(0); newBtn.setMinimumWidth(0); newBtn.setPadding(0,0,0,0); newBtn.setContentDescription(L.s("newFilm"));
        newBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showNewFilm(); }});
        delBtn = new Button(this); delBtn.setText("✕"); delBtn.setMinWidth(0); delBtn.setMinimumWidth(0); delBtn.setPadding(0,0,0,0); delBtn.setContentDescription(L.s("delFilm"));
        delBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            final String n = (String) filmSpin.getSelectedItem();
            if (n == null || n.isEmpty()) { MainActivity.say(L.s("noFilmToDelete")); return; }
            if (!"user".equals(Films.s(n, "origin", ""))) { MainActivity.say(L.s("officialFilm")); return; }   // only user films are deletable
            showDelFilm(n);
        }});
        rowTop.addView(filmSpinWrap, new LinearLayout.LayoutParams(0, -2, 1.6f));   // flush to the group's inner left edge
        rowTop.addView(newBtn, new LinearLayout.LayoutParams(0, -2, 0.45f));
        rowTop.addView(delBtn, new LinearLayout.LayoutParams(0, -2, 0.45f));
        Button saveBtn = new Button(this); saveBtn.setText("💾"); saveBtn.setMinWidth(0); saveBtn.setMinimumWidth(0); saveBtn.setPadding(0,0,0,0); saveBtn.setContentDescription(L.s("saveFilm"));
        saveBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { savePipeline(); } });
        Button resetBtn = new Button(this); resetBtn.setText("⟳"); resetBtn.setMinWidth(0); resetBtn.setMinimumWidth(0); resetBtn.setPadding(0,0,0,0); resetBtn.setContentDescription(L.s("reload"));
        resetBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { loadFilm(); } });
        rowTop.addView(saveBtn, new LinearLayout.LayoutParams(0, -2, 0.45f));
        rowTop.addView(resetBtn, new LinearLayout.LayoutParams(0, -2, 0.45f));
        LinearLayout colR = new LinearLayout(this); colR.setOrientation(LinearLayout.VERTICAL);
        sceneSpin = new Spinner(this);
        android.widget.FrameLayout sceneSpinWrap = Ux.wrapSpinner(this, sceneSpin);
        refreshSceneSpinner(null);
        sceneSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                // scene switch: outside dn-preview, auto-pick the day/night tab by the photo's score
                if (!dnPreview && Films.isSplit(selFilm() != null ? selFilm() : "")) {
                    float sc = Engine.dayNightScore(sceneFile());
                    boolean toNight = sc > 0.5f;
                    if (editNight != toNight) {
                        editNight = toNight;
                        nodes = copyNodes(editNight ? nodesNight : nodesDay);
                        buildNodeUI();
                    }
                }
                updateDnUI();
                renderPreview();
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        LinearLayout rowSM = new LinearLayout(this);
        Button prevBtn = new Button(this); prevBtn.setText("◀"); prevBtn.setMinWidth(0); prevBtn.setMinimumWidth(0); prevBtn.setPadding(0,0,0,0);
        prevBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            int n = sceneSpin.getAdapter().getCount();
            if (n > 0) sceneSpin.setSelection((sceneSpin.getSelectedItemPosition() - 1 + n) % n);
        }});
        rowSM.addView(sceneSpinWrap, new LinearLayout.LayoutParams(0, -2, 1f));
        Button nextBtn = new Button(this); nextBtn.setText("▶"); nextBtn.setMinWidth(0); nextBtn.setMinimumWidth(0); nextBtn.setPadding(0,0,0,0);
        nextBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            int n = sceneSpin.getAdapter().getCount();
            if (n > 0) sceneSpin.setSelection((sceneSpin.getSelectedItemPosition() + 1) % n);
        }});
        colR.addView(rowSM);
        rowTop.addView(colR, new LinearLayout.LayoutParams(0, -2, 2f));
        root.addView(rowTop);

        split = new SplitView(this);
        // ◀/▶ always live on the preview: flush with its left/right edges, vertically centered (Win98 raised style kept)
        android.widget.FrameLayout splitWrap = new android.widget.FrameLayout(this);
        splitWrap.addView(split, new android.widget.FrameLayout.LayoutParams(-1, 900));
        android.widget.FrameLayout.LayoutParams pl = new android.widget.FrameLayout.LayoutParams(Ux.dp(40), Ux.dp(40), android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL);
        splitWrap.addView(prevBtn, pl);
        android.widget.FrameLayout.LayoutParams pr = new android.widget.FrameLayout.LayoutParams(Ux.dp(40), Ux.dp(40), android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        splitWrap.addView(nextBtn, pr);
        root.addView(splitWrap, new LinearLayout.LayoutParams(-1, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView nh = new TextView(this); nh.setText(L.s("pipelineNodes")); nh.setPadding(12, 14, 12, 2);
        root.addView(nh);
        // day/night split controls: checkbox + day/night tabs + override buttons
        LinearLayout rowDN = new LinearLayout(this);
        final CheckBox splitCb = new CheckBox(this); splitCb.setText(L.s("split"));
        splitCbRef = splitCb;
        splitCb.setChecked(Films.isSplit(selFilm() != null ? selFilm() : ""));
        splitCb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                if (splitGuard) return;
                // in-memory only; persisted when Save is clicked
                memSplit = v;
                if (v) {   // seed both tabs with the current in-memory nodes
                    nodesDay = copyNodes(nodes);
                    nodesNight = copyNodes(nodes);
                    MainActivity.say(L.s("splitDone"));
                }
                editNight = false; buildNodeUI(); renderPreview();
            }
        });
        dnTabDay = new Button(this); dnTabDay.setText(L.s("tabDay")); dnTabDay.setMinWidth(0); dnTabDay.setMinimumWidth(0);
        dnTabNight = new Button(this); dnTabNight.setText(L.s("tabNight")); dnTabNight.setMinWidth(0); dnTabNight.setMinimumWidth(0);
        android.view.View.OnClickListener tab = new android.view.View.OnClickListener() { public void onClick(View v) {
            boolean toNight = (v == dnTabNight);
            if (!dnPreview && editNight == toNight) return;
            commitSlot();   // stash current nodes into their slot
            dnPreview = false;
            editNight = toNight;
            nodes = copyNodes(editNight ? nodesNight : nodesDay);
            expanded = -1; buildNodeUI(); renderPreview();
        }};
        dnPrev = new Button(this); dnPrev.setText(L.s("tabPrev")); dnPrev.setMinWidth(0); dnPrev.setMinimumWidth(0);
        dnPrev.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (dnPreview) return;
            commitSlot();
            dnPreview = true;
            syncSliderToPhoto();   // slider shows the photo-computed score on entry
            updateDnUI();
            renderPreview();
        }});
        dnTabDay.setOnClickListener(tab); dnTabNight.setOnClickListener(tab);
        rowDN.addView(splitCb, new LinearLayout.LayoutParams(0, -2, 1.2f));
        rowDN.addView(dnTabDay, new LinearLayout.LayoutParams(0, -2, 0.6f));
        rowDN.addView(dnTabNight, new LinearLayout.LayoutParams(0, -2, 0.6f));
        rowDN.addView(dnPrev, new LinearLayout.LayoutParams(0, -2, 0.7f));
        dnRow = rowDN;
        root.addView(rowDN);

        nodesBox = new LinearLayout(this); nodesBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(nodesBox);

        LinearLayout addRow = new LinearLayout(this);
        addRowRef = addRow;
        addSpin = new Spinner(this);
        android.widget.FrameLayout addSpinWrap = Ux.wrapSpinner(this, addSpin);
        addSpin.setAdapter(new StdSpinnerAdapter(this, tlTr()));
        ((StdSpinnerAdapter) addSpin.getAdapter()).spin = addSpin;
        addRow.addView(addSpinWrap, new LinearLayout.LayoutParams(0, -2, 2f));
        Button addBtn = new Button(this); addBtn.setText(L.s("addNode"));
        addBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { addNode(); }});
        addRow.addView(addBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        // time-preview score row (visible only in preview mode)
        dnPrevRow = new LinearLayout(this);
        TextView sl = new TextView(this); sl.setText(L.s("scoreLabel")); sl.setPadding(8, 22, 4, 0);
        dnPrevRow.addView(sl, new LinearLayout.LayoutParams(-2, -2));
        dnSlider = new SeekBar(this); dnSlider.setMax(1000); dnSlider.setThumb(Ux.thumb98());
        dnVal = new TextView(this); dnVal.setText("auto"); dnVal.setTypeface(Ux.seg(this)); dnVal.setLetterSpacing(0.25f); dnVal.getPaint().setFakeBoldText(true); dnVal.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(14)); dnVal.setPadding(10, 22, 10, 0);
        dnAuto = new CheckBox(this); dnAuto.setText("Auto");
        dnAuto.setChecked(true);
        dnSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) {
                manualScore = v / 1000f;
                if (!dnAuto.isChecked()) dnVal.setText(String.format(Locale.US, "%.2f", manualScore));
            }
            public void onStartTrackingTouch(SeekBar s) {
                if (dnAuto.isChecked()) {   // dragging implies manual control
                    dnAuto.setChecked(false);   // listener triggers re-render
                }
            }
            public void onStopTrackingTouch(SeekBar s) { if (!dnAuto.isChecked()) renderPreview(); }
        });
        dnAuto.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                if (v) syncSliderToPhoto();
                else dnVal.setText(String.format(Locale.US, "%.2f", manualScore));
                renderPreview();
            }
        });
        dnPrevRow.addView(dnSlider, new LinearLayout.LayoutParams(0, Ux.dp(22), 1.6f));
        dnPrevRow.addView(dnVal, new LinearLayout.LayoutParams(-2, -2));
        dnPrevRow.addView(dnAuto, new LinearLayout.LayoutParams(-2, -2));
        root.addView(dnPrevRow);
        dnPrevRow.setVisibility(android.view.View.GONE);

        Button copyBtn = new Button(this); copyBtn.setText(L.s("copyCfg"));
        copyBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            commitSlot();
            clipChain = chainStr();
            clipParams = new LinkedHashMap<String, Float>(allParams());
            clipLists = new LinkedHashMap<String, String>();
            for (Node nd2 : nodes) if (nd2.type.equals("overlay")) clipLists.put(nd2.type + "@" + nd2.id, nd2.list);
            MainActivity.say(L.s("copied") + nodes.size() + L.s("nodesSuffix"));
        }});
        addRow.addView(copyBtn, new LinearLayout.LayoutParams(0, -2, 0.8f));
        Button pasteBtn = new Button(this); pasteBtn.setText(L.s("pasteCfg"));
        pasteBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (clipChain == null) { MainActivity.say(L.s("clipEmpty")); return; }
            nodes = parseClipboard();
            expanded = -1; buildNodeUI(); renderPreview();
            MainActivity.say(L.s("pasted"));
        }});
        addRow.addView(pasteBtn, new LinearLayout.LayoutParams(0, -2, 0.8f));
        root.addView(addRow);

        // (render group removed: save/reload moved into Film & Scene; preview is automatic)
        // (usage hint removed per user request)
        // ---- win98 chrome: navy title bar, etched group boxes, status bar ----
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setBackgroundColor(Ux.NAVY);
        titleBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        titleBar.setPadding(Ux.dp(8), Ux.dp(0), 0, Ux.dp(0));   // tight bar: height comes from the 38dp buttons; 1 cell = bevel line = 2dp
        android.widget.ImageView appIcon = new android.widget.ImageView(this);
        appIcon.setImageResource(R.drawable.ic_launcher);
        android.widget.LinearLayout.LayoutParams ilp = new android.widget.LinearLayout.LayoutParams(Ux.dp(26), Ux.dp(26));
        ilp.rightMargin = Ux.dp(6);
        titleBar.addView(appIcon, ilp);
        TextView appTitle = new TextView(this);
        appTitle.setText("OPENFILM6K");
        appTitle.setTextColor(0xFFFFFFFF);
        appTitle.setTypeface(Ux.seg14(this));   // 14-seg: real uppercase letterforms (7-seg letters look lowercase)
        appTitle.setLetterSpacing(0.25f);
        appTitle.getPaint().setFakeBoldText(true);
        appTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(14));
        titleBar.addView(appTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        // spy mode: [win98 checkbox | spy glyph] compound button; checkbox mirrors prefs spy_on, dialog owns toggling
        LinearLayout spyBtn = new LinearLayout(this);
        spyBtn.setOrientation(LinearLayout.HORIZONTAL);
        spyBtn.setBackground(Ux.buttonDrawable());
        spyBtn.setGravity(android.view.Gravity.CENTER);
        spyBtn.setPadding(Ux.dp(8), 0, Ux.dp(8), 0);
        spyBtn.setContentDescription(L.s("spyMode"));
        spyChkView = new android.widget.ImageView(this);
        spyChkView.setImageDrawable(Ux.checkbox98(this, getSharedPreferences("of6k", 0).getBoolean("spy_on", false)));
        spyChkView.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {   // the checkbox half toggles in place; the rest opens the dialog
            android.content.SharedPreferences pf = getSharedPreferences("of6k", 0);
            boolean nv = !pf.getBoolean("spy_on", false);
            pf.edit().putBoolean("spy_on", nv).commit();
            refreshSpyBtn();
            SpyWatcher.sync(getApplicationContext());
        }});
        spyBtn.addView(spyChkView);
        TextView spyIco = new TextView(this);
        spyIco.setText("\uD83D\uDD75\uFE0F");   // 🕵️ detective emoji (replaced the hand-drawn pixel glyph per user)
        spyIco.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(20));
        spyIco.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams spyilp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        spyilp.leftMargin = Ux.dp(4);
        spyBtn.addView(spyIco, spyilp);
        spyBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showSpy(); }});
        android.widget.LinearLayout.LayoutParams spylp = new android.widget.LinearLayout.LayoutParams(-2, Ux.dp(38));
        spylp.gravity = android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.RIGHT;
        spylp.leftMargin = Ux.dp(6);
        titleBar.addView(spyBtn, spylp);
        Button setBtn = new Button(this); setBtn.setText("⚙️"); setBtn.setPadding(0, 0, 0, 0); setBtn.setContentDescription(L.s("settings"));
        setBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showSettings(); }});
        android.widget.LinearLayout.LayoutParams slp = new android.widget.LinearLayout.LayoutParams(Ux.dp(38), Ux.dp(38));
        slp.gravity = android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.RIGHT;
        slp.leftMargin = Ux.dp(6);
        titleBar.addView(setBtn, slp);
        Button closeBtn = new Button(this); closeBtn.setText("✕"); closeBtn.setMinWidth(0); closeBtn.setMinimumWidth(0); closeBtn.setPadding(0, 0, Ux.dp(6), Ux.dp(6));   // center on the inset bevel body
        closeBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); }});
        android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(Ux.dp(38), Ux.dp(38));
        clp.gravity = android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.RIGHT;
        clp.leftMargin = Ux.dp(6);   // same inter-button gap as spy|settings
        titleBar.addView(closeBtn, clp);
        root.addView(titleBar, 0, new LinearLayout.LayoutParams(-1, -2));

        java.util.ArrayList<LinearLayout> gBoxes = new java.util.ArrayList<LinearLayout>();
        String[] glabels = {L.s("groupFilm"), L.s("groupDN"), L.s("groupNodes")};
        LinearLayout[] groups = {rowTop, rowDN, addRow};
        for (int gi = 0; gi < groups.length; gi++) {
            LinearLayout g = new LinearLayout(this);
            g.setOrientation(LinearLayout.VERTICAL);
            g.setBackground(new Ux.Etched());
            int pad = Ux.dp(8);
            g.setPadding(pad, Ux.dp(10), pad, pad);
            gBoxes.add(g);
        }
        // day/night group also hosts the preview-mode score row
        int dnIdx = 1;
        LinearLayout dnGroup = gBoxes.get(dnIdx);
        int prevIdx = root.indexOfChild(dnPrevRow);
        root.removeView(dnPrevRow);
        dnGroup.addView(dnPrevRow, new LinearLayout.LayoutParams(-1, -2));
        ((LinearLayout.LayoutParams) dnPrevRow.getLayoutParams()).topMargin = Ux.dp(6);
        for (int gi = 0; gi < groups.length; gi++) {
            TextView lab = new TextView(this);
            lab.setText(glabels[gi]);
            Ux.styleHeader(lab);
            android.widget.FrameLayout fl2 = new android.widget.FrameLayout(this);
            fl2.setClipChildren(false);   // the label rises half a glyph above the frame — keep it unclipped
            fl2.addView(gBoxes.get(gi), new android.widget.FrameLayout.LayoutParams(-1, -2));
            android.widget.FrameLayout.LayoutParams llp = new android.widget.FrameLayout.LayoutParams(-2, -2);
            llp.leftMargin = Ux.dp(10); llp.topMargin = -Ux.dp(1) - (int) (Ux.BODY * 0.5f * Ux.tscale());   // rise half a glyph above the frame
            fl2.addView(lab, llp);
            int idx = root.indexOfChild(groups[gi]);
            root.removeView(groups[gi]);
            gBoxes.get(gi).addView(groups[gi], new LinearLayout.LayoutParams(-1, -2));
            root.addView(fl2, idx, new LinearLayout.LayoutParams(-1, -2));
            ((LinearLayout.LayoutParams) fl2.getLayoutParams()).topMargin = Ux.dp(8);
            root.setClipChildren(false);
            scroll.setClipChildren(false);
            if (gi == 2) groupOps = fl2;   // node-ops frame — hidden during day/night preview
        }

        {   // the whole node list lives in one big group box, label riding the frame
            LinearLayout gN = new LinearLayout(this); gN.setOrientation(LinearLayout.VERTICAL);
            gN.setBackground(new Ux.Etched());
            gN.setPadding(Ux.dp(8), Ux.dp(10), Ux.dp(8), Ux.dp(8));
            root.removeView(nodesBox);
            gN.addView(nodesBox, new LinearLayout.LayoutParams(-1, -2));
            Ux.styleHeader(nh);
            root.removeView(nh);   // nh still sits on root from its creation — detach before mounting on the frame
            android.widget.FrameLayout flN = new android.widget.FrameLayout(this);
            flN.setClipChildren(false);
            frameNodes = flN;
            flN.addView(gN, new android.widget.FrameLayout.LayoutParams(-1, -2));
            android.widget.FrameLayout.LayoutParams nl2 = new android.widget.FrameLayout.LayoutParams(-2, -2);
            nl2.leftMargin = Ux.dp(10);
            nl2.topMargin = -Ux.dp(1) - (int) (Ux.BODY * 0.5f * Ux.tscale());
            flN.addView(nh, nl2);
            root.addView(flN, root.indexOfChild(nodesBox), new LinearLayout.LayoutParams(-1, -2));
            ((LinearLayout.LayoutParams) flN.getLayoutParams()).topMargin = Ux.dp(8);
        }


        // ---- strict metrics pass: one control height, aligned labels, even section rhythm ----
        LinearLayout[] rows = {rowTop, rowSM, rowDN, addRow, dnPrevRow};
        int[] rowTopM = {0, 10, 0, 0, 6};   // group-leading rows sit flush in the box padding; nested rows keep their gap
        for (int ri = 0; ri < rows.length; ri++) {
            LinearLayout r = rows[ri];
            LinearLayout.LayoutParams rp = (LinearLayout.LayoutParams) r.getLayoutParams();
            rp.topMargin = Ux.dp(rowTopM[ri]);
            rp.bottomMargin = 0;
            r.setLayoutParams(rp);
            for (int i = 0; i < r.getChildCount(); i++) {
                View c = r.getChildAt(i);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.getLayoutParams();
                lp.height = Ux.dp(44);   // every control one height
                c.setLayoutParams(lp);
                if (c instanceof TextView && !(c instanceof Button)) {   // field labels: fixed column
                    lp.width = Ux.dp(56);
                    Ux.styleLabel((TextView) c);
                }
            }
        }
        rowSM.setLayoutParams(new LinearLayout.LayoutParams(-1, Ux.dp(44)));   // nested scene row: fixed height
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(0, -1, 2f);
        clp2.leftMargin = Ux.dp(10);                                            // gap after the film delete X
        colR.setLayoutParams(clp2);
        for (int i = 0; i < rowSM.getChildCount(); i++) {                       // children fill the row
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) rowSM.getChildAt(i).getLayoutParams();
            lp.height = -1;
            rowSM.getChildAt(i).setLayoutParams(lp);
        }

        {   // section header + nodes area rhythm
            LinearLayout.LayoutParams np = (LinearLayout.LayoutParams) nodesBox.getLayoutParams();
            np.topMargin = Ux.dp(6); nodesBox.setLayoutParams(np);
            android.view.ViewGroup.MarginLayoutParams sp2 = (android.view.ViewGroup.MarginLayoutParams) split.getLayoutParams();
            sp2.topMargin = Ux.dp(8); sp2.bottomMargin = Ux.dp(2); split.setLayoutParams(sp2);
        }
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Ux.NAVY);   // system status bar matches the app title bar: navy, white icons
        android.view.View dcv = getWindow().getDecorView();
        dcv.setSystemUiVisibility(dcv.getSystemUiVisibility() & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setNavigationBarColor(Ux.FACE);
        scroll.setBackgroundColor(Ux.FACE);
        Ux.themeTree(scroll);
        appTitle.setTextColor(0xFFFFFFFF);   // re-apply after theming (themeTree forces TXT on every TextView)
        appTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(Ux.BODY + 2));
        appTitle.getPaint().setFakeBoldText(true);
        // window bottom edge (shadow side only, 2dp per line) + full-bleed teal "desktop"
        LinearLayout edge = new LinearLayout(this);
        edge.setOrientation(LinearLayout.VERTICAL);
        View e1 = new View(this); e1.setBackgroundColor(Ux.SH);
        View e2 = new View(this); e2.setBackgroundColor(Ux.DK);
        edge.addView(e1, new LinearLayout.LayoutParams(-1, Ux.dp(2)));
        edge.addView(e2, new LinearLayout.LayoutParams(-1, Ux.dp(2)));
        container.addView(edge, new LinearLayout.LayoutParams(-1, Ux.dp(4)));
        // desktop area: an app icon with its label, like a win98 desktop shortcut at the top-left
        LinearLayout desktop = new LinearLayout(this);
        desktop.setOrientation(LinearLayout.HORIZONTAL);
        desktop.setGravity(android.view.Gravity.CENTER_VERTICAL);   // shortcut block vertically centered on the desktop
        desktop.setPadding(Ux.dp(14) + Ux.dp(16), Ux.dp(10), Ux.dp(12), Ux.dp(10));   // +half-icon shift for the shortcut
        desktop.setBackgroundColor(0xFF008080);   // classic win98 desktop teal
        LinearLayout shortcut = new LinearLayout(this);   // icon + label block, like a desktop shortcut
        shortcut.setOrientation(LinearLayout.VERTICAL);
        shortcut.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        android.widget.ImageView di = new android.widget.ImageView(this);
        di.setImageResource(R.drawable.ic_launcher);
        shortcut.addView(di, new LinearLayout.LayoutParams(Ux.dp(32) * 2, Ux.dp(32) * 2));   // icon 2/3 as well
        android.widget.TextView dlabel = new android.widget.TextView(this);
        dlabel.setText("OpenFilm6K");
        dlabel.setTextColor(0xFFFFFFFF);
        dlabel.getPaint().setFakeBoldText(true);
        dlabel.setShadowLayer(4, 2, 2, 0xE6000000);   // same drop shadow as the info lines
        dlabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(12 * 4 / 3));   // 2/3 again (of 24sp)
        dlabel.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        android.widget.LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = Ux.dp(4);
        dlp.gravity = android.view.Gravity.CENTER_HORIZONTAL;   // keep label centered under the icon
        shortcut.addView(dlabel, dlp);

        desktop.addView(shortcut, new LinearLayout.LayoutParams(-2, -2));
        // right side: app intro + author info
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ux.dp(16), Ux.dp(6), Ux.dp(10), 0);
        CharSequence[] texts = {
            L.s("aboutTitle"),
            L.s("aboutDesc"),
        };
        int[] cols = {0xFFFFFFFF, 0xFFDDEEEE};
        for (int i2 = 0; i2 < 2; i2++) {
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText(texts[i2]);
            tv.setTextColor(cols[i2]);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(i2 == 0 ? 15 : 13));
            if (i2 == 1) tv.setLineSpacing(Ux.dp(2), 1f);
            if (i2 == 0) tv.getPaint().setFakeBoldText(true);
            tv.setShadowLayer(4, 2, 2, 0xE6000000);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-2, -2);
            lp2.topMargin = Ux.dp(i2 == 0 ? 0 : 6);
            info.addView(tv, lp2);
        }
        // author + links share one line
        LinearLayout authorLine = new LinearLayout(this);
        authorLine.setOrientation(LinearLayout.HORIZONTAL);
        authorLine.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView au = new android.widget.TextView(this);
        au.setText(L.s("authorLabel") + "NekoV");
        au.setTextColor(0xFFE8E8E8); au.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(13));
        au.setShadowLayer(4, 2, 2, 0xE6000000);
        authorLine.addView(au, new LinearLayout.LayoutParams(-2, -2));
        android.text.SpannableString em = new android.text.SpannableString("phaseneko@gmail.com");
        em.setSpan(new android.text.style.UnderlineSpan(), 0, em.length(), 0);
        android.widget.TextView t4 = new android.widget.TextView(this);
        t4.setText(em); t4.setTextColor(0xFF7FA8FF);
        t4.getPaint().setUnderlineText(true);
        t4.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(13));
        t4.setShadowLayer(4, 2, 2, 0xE6000000);
        android.widget.LinearLayout.LayoutParams t4p = new android.widget.LinearLayout.LayoutParams(-2, -2);
        t4p.leftMargin = Ux.dp(16);
        authorLine.addView(t4, t4p);
        android.text.SpannableString gh = new android.text.SpannableString("github.com/phaseneko/OpenFilm6K");
        gh.setSpan(new android.text.style.UnderlineSpan(), 0, gh.length(), 0);
        android.widget.TextView t5 = new android.widget.TextView(this);
        t5.setText(gh); t5.setTextColor(0xFF7FA8FF);
        t5.getPaint().setUnderlineText(true);
        t5.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(13));
        t5.setShadowLayer(4, 2, 2, 0xE6000000);
        android.widget.LinearLayout.LayoutParams t5p = new android.widget.LinearLayout.LayoutParams(-2, -2);
        t5p.leftMargin = Ux.dp(16);
        authorLine.addView(t5, t5p);
        android.widget.LinearLayout.LayoutParams alp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        alp.topMargin = Ux.dp(16);
        info.addView(authorLine, alp);
        android.widget.LinearLayout.LayoutParams infop = new LinearLayout.LayoutParams(0, -2, 1f);
        infop.leftMargin = Ux.dp(20);
        infop.gravity = android.view.Gravity.BOTTOM;
        // author-line bottom == icon-label bottom: label sits (desktop - shortcut)/2 above the bottom edge
        infop.bottomMargin = Ux.dp(46);   // calibrated on-screen: author line baseline == icon label baseline
        desktop.addView(info, infop);
        desktop.setMinimumHeight(Ux.dp(38) * 5);   // designed desktop floor; wrap-height lets the author line fully fit (fixed 190dp clipped it on both devices)
        container.addView(desktop, new LinearLayout.LayoutParams(-1, -2));
        setContentView(scroll);
        annLoad();
    }

    // ---------- node helpers ----------
    private int typeIdx(String t) { for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equals(t)) return i; return -1; }

    /** spinner adapter with the unified body font size inside and out */
    static final String DIV = "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500";   // dropdown divider sentinel row

    static class StdSpinnerAdapter extends ArrayAdapter<String> {
        android.widget.Spinner spin;   // owner, for the selected-position highlight
        java.util.HashSet<Integer> div = new java.util.HashSet<Integer>();   // divider row positions
        String label(String s) { return s; }   // display-only transform (items stay the data keys)
        StdSpinnerAdapter(android.content.Context c, java.util.List<String> items) { super(c, android.R.layout.simple_spinner_item, items); }
        StdSpinnerAdapter(android.content.Context c, String[] items) { super(c, android.R.layout.simple_spinner_item, items); }
        private TextView fix(TextView v) { v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(Ux.BODY)); v.setTextColor(Ux.TXT); return v; }
        @Override public boolean isEnabled(int pos) { return !div.contains(pos); }
        @Override public View getView(int pos, View cv, android.view.ViewGroup pg) {
            if (div.contains(pos)) { TextView t = new TextView(pg.getContext()); t.setVisibility(View.INVISIBLE); return t; }
            TextView v = fix((TextView) super.getView(pos, null, pg));   // never recycle: divider rows poison TextView convertViews
            v.setText(label(getItem(pos)));
            return v;
        }
        @Override public View getDropDownView(int pos, View cv, android.view.ViewGroup pg) {
            if (div.contains(pos)) {   // win98 menu separator: 1px dark over 1px light etched line
                android.content.Context c = pg.getContext();
                LinearLayout l = new LinearLayout(c);
                l.setOrientation(LinearLayout.VERTICAL);
                l.setPadding(0, Ux.dp(4), 0, Ux.dp(4));
                View a = new View(c); a.setLayoutParams(new LinearLayout.LayoutParams(-1, Ux.dp(1)));
                a.setBackgroundColor(0xFF808080);
                View b = new View(c); b.setLayoutParams(new LinearLayout.LayoutParams(-1, Ux.dp(1)));
                b.setBackgroundColor(0xFFFFFFFF);
                l.addView(a); l.addView(b);
                return l;
            }
            TextView v = fix((TextView) super.getView(pos, null, pg));   // fresh row: a recycled divider LinearLayout breaks the TextView cast
            v.setText(label(getItem(pos)));
            v.setPadding(Ux.dp(10), Ux.dp(12), Ux.dp(10), Ux.dp(12));   // roomier rows
            int selPos = spin == null ? -1 : spin.getSelectedItemPosition();
            boolean sel = (pos == selPos);
            if (sel) {   // current selection: solid navy, white text
                v.setBackgroundColor(Ux.NAVY);
                v.setTextColor(android.graphics.Color.WHITE);
            } else {     // pressed flashes navy too (was the stock gray highlight)
                android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
                bg.addState(new int[]{android.R.attr.state_pressed}, new android.graphics.drawable.ColorDrawable(Ux.NAVY));
                bg.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                v.setBackground(bg);
                v.setTextColor(new android.content.res.ColorStateList(
                        new int[][]{{android.R.attr.state_pressed}, {}},
                        new int[]{android.graphics.Color.WHITE, Ux.TXT}));
            }
            return v;
        }
    }

    /** ✕ delete button: disabled + dimmed unless the selected film is user-created */
    private void updateDelBtn() {
        if (delBtn == null) return;
        String f = selFilm();
        boolean user = f != null && "user".equals(Films.s(f, "origin", ""));
        delBtn.setEnabled(user);
        delBtn.setAlpha(user ? 1f : 0.38f);
    }

    /** film dropdown entries: user-made films first, then an un-selectable divider, then the built-ins */
    private java.util.List<String> filmChoices() {
        java.util.List<String> user = new java.util.ArrayList<String>(), off = new java.util.ArrayList<String>();
        for (String f : Films.list()) {
            if (f.startsWith("EDITTMP") || f.equals("TEST0") || f.equals("G2TEST")) continue;
            if ("user".equals(Films.s(f, "origin", ""))) user.add(f); else off.add(f);
        }
        java.util.List<String> out = new java.util.ArrayList<String>(user);
        if (!user.isEmpty() && !off.isEmpty()) out.add(DIV);
        out.addAll(off);
        return out;
    }

    private void applyDiv(StdSpinnerAdapter a, java.util.List<String> l) {
        for (int i = 0; i < l.size(); i++) if (DIV.equals(l.get(i))) a.div.add(i);
    }

    private void rebuildFilmSpinner(String select) {
        java.util.List<String> fs2 = filmChoices();
        StdSpinnerAdapter fa2 = new StdSpinnerAdapter(this, fs2);
        applyDiv(fa2, fs2);
        filmSpin.setAdapter(fa2);
        fa2.spin = filmSpin;
        if (select != null) for (int i = 0; i < fs2.size(); i++) if (fs2.get(i).equals(select)) { filmSpin.setSelection(i); break; }
        else if (!fs2.isEmpty()) filmSpin.setSelection(0);
        updateDelBtn();
    }

    /** sync the score slider + label to the current photo's EXIF-computed day/night value */
    private void syncSliderToPhoto() {
        float sc = Engine.dayNightScore(sceneFile());
        manualScore = sc;
        dnSlider.setProgress(Math.round(sc * 1000f));
        dnVal.setText(String.format(Locale.US, "%.2f", sc));
    }

    /** day/night tab + preview button appearance and config-area visibility */
    private void updateDnUI() {
        boolean split = memSplit;
        if (dnTabDay == null) return;
        // radio-tab look: the active side is pressed IN, the inactive side stays raised (no alpha dimming)
        int tabVis = split ? android.view.View.VISIBLE : android.view.View.GONE;   // day/night controls only exist while split is on
        dnTabDay.setVisibility(tabVis);
        dnTabNight.setVisibility(tabVis);
        dnPrev.setVisibility(tabVis);
        boolean daySel = split && !dnPreview && !editNight;
        boolean nightSel = split && !dnPreview && editNight;
        boolean prevSel = split && dnPreview;
        dnTabDay.setAlpha(1f);
        dnTabNight.setAlpha(1f);
        dnPrev.setAlpha(1f);
        dnTabDay.setBackground(daySel ? Ux.sunkenWhite() : Ux.raised());
        dnTabNight.setBackground(nightSel ? Ux.sunkenWhite() : Ux.raised());
        dnPrev.setBackground(prevSel ? Ux.sunkenWhite() : Ux.raised());
        int vis = (split && dnPreview) ? android.view.View.GONE : android.view.View.VISIBLE;
        if (frameNodes != null) frameNodes.setVisibility(vis);   // hide the whole node group (frame + label), not just the list
        nodesBox.setVisibility(vis);
        if (addRowRef != null) addRowRef.setVisibility(vis);
        if (groupOps != null) groupOps.setVisibility(vis);   // hide the whole node-ops frame, label included
        if (dnPrevRow != null) dnPrevRow.setVisibility((split && dnPreview) ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private static ArrayList<Node> copyNodes(ArrayList<Node> src) {
        ArrayList<Node> out = new ArrayList<Node>();
        for (Node n : src) {
            Node c = new Node(); c.type = n.type; c.id = n.id; c.list = n.list; c.file = n.file;
            c.p.putAll(n.p);
            out.add(c);
        }
        return out;
    }

    private void commitSlot() {
        if (memSplit) {
            if (editNight) nodesNight = copyNodes(nodes);
            else nodesDay = copyNodes(nodes);
        }
    }

    private void loadFilm() {
        String f0 = selFilm();
        if (f0 == null) return;
        memSplit = Films.isSplit(f0);
        memLut = Films.s(f0, "lut", null);   // reload (⟳) re-reads the stored value, discarding unsaved picks
        if (!memSplit) dnPreview = false;   // day/night preview is meaningless (and would stage empty chains) on a non-split film
        if (splitCbRef != null) { splitGuard = true; splitCbRef.setChecked(memSplit); splitGuard = false; }
        updateDnUI();   // keep the day/night buttons in step with the reloaded split state
        nodesDay = new ArrayList<Node>(); nodesNight = new ArrayList<Node>();
        String film = editKey();
        nodes.clear(); expanded = -1;
        LinkedHashMap<String, Integer> seq = new LinkedHashMap<String, Integer>();
        for (String step : Films.s(film, "chain", "lut,grade,glow,grain,vig").split(",")) {
            step = step.trim();
            if (step.isEmpty()) continue;
            String[] pr = step.split(":", 2);
            String ty = pr[0], id = pr.length > 1 ? pr[1] : "";
            if (typeIdx(ty) < 0) continue;
            int n = seq.containsKey(ty) ? seq.get(ty) + 1 : 1; seq.put(ty, n);
            if (id.isEmpty()) id = String.valueOf(n);
            Node nd = new Node(); nd.type = ty; nd.id = id;
            if (ty.equals("overlay")) nd.list = Films.s(film, ty + "@" + id + ".list", "");
            if (ty.equals("lut")) {
                String pf = Films.props(film).getProperty("lut@" + id + ".file", "").trim();
                nd.file = pf.isEmpty() ? null : pf;
            }
            nd.p.put("enable", Films.f(film, ty + "@" + id + ".enable", 1f));
            nd.p.put("alpha", strengthOf(film, ty, id));
            for (String[] sp : PSPEC[typeIdx(ty)]) {
                float v = Films.f(film, ty + "@" + id + "." + sp[0], Float.NaN);
                nd.p.put(sp[0], Float.isNaN(v) ? Films.f(film, ty + "." + sp[0], Float.parseFloat(sp[4])) : v);
            }
            nodes.add(nd);
        }
        if (memSplit) {
            nodesDay = parsePipeline(f0 + ".day");
            nodesNight = parsePipeline(f0 + ".night");
            nodes = copyNodes(editNight ? nodesNight : nodesDay);
        }
        buildNodeUI();
        renderPreview();
    }

    /** strength alpha with legacy inheritance: saved amount/on was the operative strength, legacy wins;
     *  alpha applies for new-style saves. (lut:on glow:amount grain:amount/50 vig/overlay:amount) */
    static float strengthOf(String film, String ty, String nid) {
        String lk = ty.equals("lut") ? "on" : (ty.equals("sharp") ? null : "amount");   // sharp amount is bipolar — never folded into α
        float sc = ty.equals("grain") ? 1f / 50f : 1f;
        float v = Float.NaN;
        if (lk != null) {
            v = Films.f(film, ty + "@" + nid + "." + lk, Float.NaN);
            if (Float.isNaN(v)) v = Films.f(film, ty + "." + lk, Float.NaN);
            if (!Float.isNaN(v)) return Math.max(0f, Math.min(1f, v * sc));
        }
        float a = Films.f(film, ty + "@" + nid + ".alpha", Float.NaN);
        if (Float.isNaN(a)) a = Films.f(film, ty + ".alpha", Float.NaN);
        return Float.isNaN(a) ? 1f : a;
    }

    private ArrayList<Node> parseClipboard() {
        ArrayList<Node> out = new ArrayList<Node>();
        LinkedHashMap<String, Integer> seq = new LinkedHashMap<String, Integer>();
        for (String step : clipChain.split(",")) {
            step = step.trim();
            if (step.isEmpty()) continue;
            String[] pr = step.split(":", 2);
            String ty = pr[0], id = pr.length > 1 ? pr[1] : "";
            if (typeIdx(ty) < 0) continue;
            int n = seq.containsKey(ty) ? seq.get(ty) + 1 : 1; seq.put(ty, n);
            if (id.isEmpty()) id = String.valueOf(n);
            Node nd = new Node(); nd.type = ty; nd.id = id;
            nd.p.put("enable", clipParam(ty, id, "enable", 1f));
            float ca = clipParam(ty, id, "alpha", Float.NaN);
            if (Float.isNaN(ca) && !ty.equals("sharp")) {   // sharp amount is bipolar — default α to 1
                String lk = ty.equals("lut") ? "on" : "amount";
                ca = clipParam(ty, id, lk, ty.equals("grain") ? 25f / 50f : 1f);
            }
            if (Float.isNaN(ca)) ca = 1f;
            nd.p.put("alpha", Math.max(0f, Math.min(1f, ca)));
            if (ty.equals("overlay")) nd.list = clipLists.containsKey(ty + "@" + id) ? clipLists.get(ty + "@" + id) : "";
            for (String[] sp : PSPEC[typeIdx(ty)]) nd.p.put(sp[0], clipParam(ty, id, sp[0], Float.parseFloat(sp[4])));
            out.add(nd);
        }
        return out;
    }

    private float clipParam(String ty, String id, String k, float def) {
        Float v = clipParams.get(ty + "@" + id + "." + k);
        if (v != null) return v;
        v = clipParams.get(ty + "." + k);
        return v != null ? v : def;
    }

    private ArrayList<Node> parsePipeline(String key) {
        ArrayList<Node> out = new ArrayList<Node>();
        LinkedHashMap<String, Integer> seq = new LinkedHashMap<String, Integer>();
        for (String step : Films.s(key, "chain", "").split(",")) {
            step = step.trim();
            if (step.isEmpty()) continue;
            String[] pr = step.split(":", 2);
            String ty = pr[0], id = pr.length > 1 ? pr[1] : "";
            if (typeIdx(ty) < 0) continue;
            int n = seq.containsKey(ty) ? seq.get(ty) + 1 : 1; seq.put(ty, n);
            if (id.isEmpty()) id = String.valueOf(n);
            Node nd = new Node(); nd.type = ty; nd.id = id;
            if (ty.equals("overlay")) nd.list = Films.s(key, ty + "@" + id + ".list", "");
            if (ty.equals("lut")) {
                String pf = Films.props(key).getProperty("lut@" + id + ".file", "").trim();
                nd.file = pf.isEmpty() ? null : pf;
            }
            nd.p.put("enable", Films.f(key, ty + "@" + id + ".enable", 1f));
            nd.p.put("alpha", strengthOf(key, ty, id));
            for (String[] sp : PSPEC[typeIdx(ty)]) {
                float v = Films.f(key, ty + "@" + id + "." + sp[0], Float.NaN);
                nd.p.put(sp[0], Float.isNaN(v) ? Films.f(key, ty + "." + sp[0], Float.parseFloat(sp[4])) : v);
            }
            out.add(nd);
        }
        return out;
    }

    private void addNode() {
        int ti = addSpin.getSelectedItemPosition();
        if (ti < 0) return;
        String ty = TYPES[ti]; int n = 1;
        for (Node nd : nodes) if (nd.type.equals(ty)) n++;
        Node nd = new Node(); nd.type = ty; nd.id = String.valueOf(n);
        nd.list = "";
        nd.p.put("enable", 1f);
        nd.p.put("alpha", 1f);
        for (String[] sp : PSPEC[ti]) nd.p.put(sp[0], Float.parseFloat(sp[4]));
        nodes.add(nd); expanded = nodes.size() - 1;
        buildNodeUI(); renderPreview();
    }

    private String chainStr() {
        StringBuilder sb = new StringBuilder();
        for (Node nd : nodes) { if (sb.length() > 0) sb.append(','); sb.append(nd.type).append(':').append(nd.id); }
        return sb.toString();
    }

    private Map<String, Float> allParams() {
        LinkedHashMap<String, Float> m = new LinkedHashMap<String, Float>();
        for (Node nd : nodes) {
            m.put(nd.type + "@" + nd.id + ".enable", nd.p.get("enable"));
            m.put(nd.type + "@" + nd.id + ".alpha", nd.p.get("alpha"));
            for (String[] sp : PSPEC[typeIdx(nd.type)]) m.put(nd.type + "@" + nd.id + "." + sp[0], nd.p.get(sp[0]));
        }
        return m;
    }

    private void savePipeline() {
        String f0 = selFilm();
        if (f0 == null) return;
        commitSlot();
        boolean wasSplit = Films.isSplit(f0);
        if (memLut != null) Films.setProp(f0, "lut", memLut);   // the only place lut= is written
        if (memSplit && !wasSplit) Films.enableSplit(f0);   // creates variant files (seeded from base)
        if (!memSplit && wasSplit) Films.setProp(f0, "daynight", "");
        if (memSplit) {
            // persist BOTH slots so the render engine always sees a complete pair
            saveSlot(f0 + ".day", nodesDay);
            saveSlot(f0 + ".night", nodesNight);
        } else {
            saveSlot(f0, nodes);
        }
        Toast.makeText(this, L.s("saved") + (memSplit ? L.s("savedSplit") : ""), Toast.LENGTH_SHORT).show();
    }

    /** write an in-memory slot (or flag-only base) into a pipelines key — preview staging, not user config */
    private void writeTmpPipeline(String key, ArrayList<Node> slot, String lutName, boolean splitFlag) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (splitFlag) { sb.append("daynight=split\n").append("lut=").append(lutName).append('\n'); }
        else {
            ArrayList<Node> keep = nodes; nodes = slot;
            sb.append("lut=").append(lutName).append('\n').append("chain=").append(chainStr()).append('\n');
            for (Map.Entry<String, Float> e : allParams().entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            for (Node nd2 : slot) if (nd2.type.equals("overlay") && nd2.list.length() > 0)
                sb.append("overlay@").append(nd2.id).append(".list=").append(nd2.list).append('\n');
            nodes = keep;
        }
        FileOutputStream fo = new FileOutputStream(new File(Films.PIPE, key + ".properties"));
        fo.write(sb.toString().getBytes()); fo.close();
    }

    private void saveSlot(String key, ArrayList<Node> slot) {
        ArrayList<Node> keep = nodes;
        nodes = slot;   // reuse chainStr()/allParams()
        Films.savePipeline(key, chainStr(), allParams());
        for (Node nd2 : slot) if (nd2.type.equals("overlay"))
            Films.setProp(key, "overlay@" + nd2.id + ".list", nd2.list);
        for (Node nd2 : slot) if (nd2.type.equals("lut"))
            Films.setProp(key, "lut@" + nd2.id + ".file", nd2.file == null ? "" : nd2.file);
        nodes = keep;
    }

    // ---------- node UI ----------
    private void buildNodeUI() {
        nodesBox.removeAllViews();
        alphaBars.clear(); alphaOwners.clear(); alphaVals.clear();
        nodesBox.post(new Runnable() { public void run() {   // after rows are added below: card + re-theme
            for (int i = 0; i < nodesBox.getChildCount(); i++) {
                android.view.View c = nodesBox.getChildAt(i);
                if (c instanceof LinearLayout) {
                    Ux.card(c);
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.getLayoutParams();
                    lp.setMargins(0, i == 0 ? 0 : Ux.dp(6), 0, Ux.dp(2));
                    c.setLayoutParams(lp);
                }
            }
            Ux.themeTree(nodesBox);
            for (int i = 0; i < alphaBars.size(); i++) {   // theming resets drawable levels — re-apply progress last
                float a = alphaOwners.get(i).p.get("alpha");   // capture BEFORE setProgress(0): the listener writes back
                alphaBars.get(i).setProgress(0);
                alphaBars.get(i).setProgress(Math.round(a * 1000f));
                alphaVals.get(i).setText(String.format(Locale.US, "%.2f", a));
            }
        }});
        String f0 = selFilm();
        boolean split = f0 != null && Films.isSplit(f0);
        if (dnRow != null) {   // checkbox always visible; tabs + preview only when split
            dnTabDay.setVisibility(split ? android.view.View.VISIBLE : android.view.View.GONE);
            dnTabNight.setVisibility(split ? android.view.View.VISIBLE : android.view.View.GONE);
            dnPrev.setVisibility(split ? android.view.View.VISIBLE : android.view.View.GONE);
        }
        updateDnUI();
        if (split && dnTabDay != null) {


        }
        for (int i = 0; i < nodes.size(); i++) {
            final int idx = i;
            final Node nd = nodes.get(i);
            LinearLayout row = new LinearLayout(this);
            final EyeView vis = new EyeView(this);
            boolean en = nd.p.get("enable") == null || nd.p.get("enable") > 0.5f;
            vis.setOpen(en);
            vis.setContentDescription(en ? L.s("hideNode") : L.s("showNode"));
            vis.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                boolean ne = (nd.p.get("enable") == null || nd.p.get("enable") > 0.5f);
                nd.p.put("enable", ne ? 0f : 1f);
                vis.setOpen(!ne);
                vis.setContentDescription(ne ? L.s("showNode") : L.s("hideNode"));
                renderPreview();
            }});
            row.addView(vis, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.32f));
            Button head = new Button(this);
            head.setText((expanded == idx ? "▼ " : "▶ ") + L.s(TLABEL[typeIdx(nd.type)]) + (nodes.size() > 0 ? (" " + nd.id) : ""));
            head.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { expanded = (expanded == idx) ? -1 : idx; buildNodeUI(); } });
            head.setGravity(android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL);   // arrow + label left-aligned
            row.addView(head, new LinearLayout.LayoutParams(0, -2, 1.25f));

            boolean showAlpha = true;   // universal α on every node type (incl. bipolar sharp)
            TextView al = new TextView(this); al.setText("α"); al.setPadding(6, 18, 2, 0);
            if (showAlpha) row.addView(al, new LinearLayout.LayoutParams(-2, -2));
            Button amin = new Button(this); amin.setText("−"); amin.setMinWidth(0); amin.setMinimumWidth(0); amin.setPadding(0,0,0,0);
            SeekBar asb = new SeekBar(this);
            asb.setMax(1000); asb.setThumb(Ux.thumb98()); asb.setProgress(Math.round(nd.p.get("alpha") * 1000));
            alphaBars.add(asb); alphaOwners.add(nd);
            final TextView aval = new TextView(this);
            aval.setText(String.format(Locale.US, "%.2f", nd.p.get("alpha")));
            aval.setTypeface(Ux.seg(this));
            aval.setLetterSpacing(0.25f);
            aval.getPaint().setFakeBoldText(true);
            aval.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(14));
            aval.setGravity(android.view.Gravity.CENTER);
            alphaVals.add(aval);
            final boolean[] astep = {false};
            asb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int v, boolean u) {
                    if (!astep[0]) nd.p.put("alpha", v / 1000f);
                    aval.setText(String.format(Locale.US, "%.2f", nd.p.get("alpha")));
                }
                public void onStartTrackingTouch(SeekBar s) {}
                public void onStopTrackingTouch(SeekBar s) { renderPreview(); }
            });
            Button apl = new Button(this); apl.setText("+"); apl.setMinWidth(0); apl.setMinimumWidth(0); apl.setPadding(0,0,0,0);
            LinearLayout acell = new LinearLayout(this); acell.setOrientation(LinearLayout.VERTICAL);
            acell.addView(asb, new LinearLayout.LayoutParams(-1, Ux.dp(22)));   // slider hugs the top edge
            LinearLayout abtnRow = new LinearLayout(this);                      // [−] value [+] on one line below
            abtnRow.addView(amin, new LinearLayout.LayoutParams(0, -2, 1f));
            abtnRow.addView(aval, new LinearLayout.LayoutParams(0, -2, 1.4f));
            abtnRow.addView(apl, new LinearLayout.LayoutParams(0, -2, 1f));
            acell.addView(abtnRow, new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(acell, new LinearLayout.LayoutParams(0, Ux.dp(46), showAlpha ? 1.0f : 0f));
            android.view.View.OnClickListener aclk = new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    float dv = (v == apl) ? 0.1f : -0.1f;
                    float fv = Math.max(0f, Math.min(1f, Math.round((nd.p.get("alpha") + dv) * 10f) / 10f));
                    nd.p.put("alpha", fv);
                    astep[0] = true;
                    asb.setProgress(Math.round(fv * 1000));
                    astep[0] = false;
                    renderPreview();
                }
            };
            amin.setOnClickListener(aclk);
            apl.setOnClickListener(aclk);
            if (!showAlpha) { asb.setVisibility(android.view.View.GONE); }

            Button up = new Button(this); up.setText("↑"); up.setPadding(0, 0, 0, 0);
            up.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                if (idx > 0) { nodes.remove(idx); nodes.add(idx - 1, nd); expanded = idx - 1; buildNodeUI(); renderPreview(); } }});
            Button dn = new Button(this); dn.setText("↓"); dn.setPadding(0, 0, 0, 0);
            dn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                if (idx < nodes.size() - 1) { nodes.remove(idx); nodes.add(idx + 1, nd); expanded = idx + 1; buildNodeUI(); renderPreview(); } }});
            Button cp = new Button(this); cp.setText("⧉"); cp.setPadding(0, 0, 0, 0);
            cp.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                int c = 1;
                for (Node n2 : nodes) if (n2.type.equals(nd.type)) {
                    try { c = Math.max(c, Integer.parseInt(n2.id) + 1); } catch (Throwable ig) {} }
                Node nn = new Node(); nn.type = nd.type; nn.id = String.valueOf(c);
                nn.p.putAll(nd.p);
                int at = idx + 1;
                nodes.add(at, nn); expanded = at;
                buildNodeUI(); renderPreview(); }});
            Button del = new Button(this); del.setText("✕"); del.setPadding(0, 0, 0, 0);
            del.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                nodes.remove(idx); expanded = -1; buildNodeUI(); renderPreview(); }});
            for (Button bt : new Button[]{up, dn, cp, del}) {
                bt.setMinWidth(0); bt.setMinimumWidth(0);
                row.addView(bt, new LinearLayout.LayoutParams(0, -2, 0.34f));
            }
            LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);   // header + expanded rows share ONE panel
            panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
            nodesBox.addView(panel);

            if (expanded == idx) {
                for (String[] sp : PSPEC[typeIdx(nd.type)]) {
                    final String key = sp[0];
                    if (nd.type.equals("overlay") && key.equals("blend")) continue;   // rendered as spinner below
                    if (nd.type.equals("hue") && (key.equals("low") || key.equals("mid") || key.equals("high"))) continue;
                    if (nd.type.equals("hue") && key.equals("hue")) {   // rainbow bar picker
                        LinearLayout hr = new LinearLayout(this);
                        hr.setGravity(android.view.Gravity.CENTER_VERTICAL);
                        TextView hl = new TextView(this); hl.setText(L.s("hueLabel")); hl.setPadding(16, 20, 4, 0);
                        hr.addView(hl, new LinearLayout.LayoutParams(-2, -2));
                        HueBar hb = new HueBar(this);
                        hb.setHue(nd.p.get("hue"));
                        final TextView hn = new TextView(this);
                        hn.setText(hb.name()); hn.setPadding(10, 20, 8, 0);
                        hb.cb = new HueCb() { public void pick(float h) {
                            nd.p.put("hue", h); hn.setText(hb.name());
                        } };
                        hr.addView(hb, new LinearLayout.LayoutParams(0, 46, 1.3f));
                        hr.addView(hn, new LinearLayout.LayoutParams(-2, -2));
                        panel.addView(hr, new LinearLayout.LayoutParams(-1, -2));
                        continue;
                    }
                    if (key.equals("low") || key.equals("mid") || key.equals("high")) continue;
                    final float mn = Float.parseFloat(sp[2]), mx = Float.parseFloat(sp[3]);
                    LinearLayout r = new LinearLayout(this);
                    r.setGravity(android.view.Gravity.CENTER_VERTICAL);   // slider centered like the alpha row
                    TextView lab = new TextView(this); lab.setText(L.s(sp[1])); lab.setPadding(16, 16, 8, 0);
                    r.addView(lab, new LinearLayout.LayoutParams(0, -2, 1f));
                    Button minus = new Button(this); minus.setText("−"); minus.setMinWidth(0); minus.setMinimumWidth(0); minus.setPadding(0,0,0,0);
                    r.addView(minus, new LinearLayout.LayoutParams(0, -2, 0.24f));
                    final SeekBar sb = new SeekBar(this);
                    sb.setMax(2000); sb.setThumb(Ux.thumb98());
                    sb.setProgress(Math.round((nd.p.get(key) - mn) / (mx - mn) * 2000));
                    final TextView val = new TextView(this);
                    final boolean fine = key.equals("threshold") || key.equals("thresh2");
                    final boolean med = nd.type.equals("hue") && key.equals("shift");   // 0.01 quantization
                    final String fmt = fine ? "%.4f" : "%.2f";
                    val.setText(String.format(Locale.US, fmt, nd.p.get(key)));
                    val.setTypeface(Ux.seg(this));
                    val.setLetterSpacing(0.25f);
                    val.getPaint().setFakeBoldText(true);
                    val.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(13));
                    val.setPadding(8, 20, 12, 0);
                    final boolean[] stepping = {false};
                    sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        public void onProgressChanged(SeekBar s, int v, boolean u) {
                            if (stepping[0]) return;          // programmatic setProgress: keep exact ±0.1 value
                            float fv = mn + (mx - mn) * v / 2000f;
                            if (med) fv = Math.round(fv * 100f) / 100f;
                            nd.p.put(key, fv); val.setText(String.format(Locale.US, fmt, fv));
                        }
                        public void onStartTrackingTouch(SeekBar s) {}
                        public void onStopTrackingTouch(SeekBar s) { renderPreview(); }
                    });
                    Button plus = new Button(this); plus.setText("+"); plus.setMinWidth(0); plus.setMinimumWidth(0); plus.setPadding(0,0,0,0);
                    r.addView(sb, new LinearLayout.LayoutParams(0, Ux.dp(22), 1.45f));   // same style as the alpha sliders
                    r.addView(plus, new LinearLayout.LayoutParams(0, -2, 0.24f));
                    r.addView(val, new LinearLayout.LayoutParams(0, -2, 0.5f));
                    final float mn2 = mn, mx2 = mx;
                    android.view.View.OnClickListener step = new android.view.View.OnClickListener() {
                        public void onClick(android.view.View v) {
                            float step1 = fine ? 0.001f : (med ? 0.01f : 0.1f);
                            float dv = (v == plus) ? step1 : -step1;
                            float grid = fine ? 1000f : (med ? 100f : 10f);
                            float fv = Math.max(mn2, Math.min(mx2, Math.round((nd.p.get(key) + dv) * grid) / grid));
                            nd.p.put(key, fv);
                            stepping[0] = true;
                            sb.setProgress(Math.round((fv - mn2) / (mx2 - mn2) * 2000));
                            stepping[0] = false;
                            val.setText(String.format(Locale.US, fmt, fv));
                            renderPreview();
                        }
                    };
                    minus.setOnClickListener(step);
                    plus.setOnClickListener(step);
                    panel.addView(r, new LinearLayout.LayoutParams(-1, -2));
                }
                if (nd.type.equals("lut")) {   // LUT file picker: custom (top) | luts/ + luts/imported
                    final String flm = editKey();
                    LinearLayout lr = new LinearLayout(this);
                    TextView ll = new TextView(this); ll.setText(L.s("lutFile")); ll.setPadding(16, 20, 4, 0);
                    lr.addView(ll, new LinearLayout.LayoutParams(-2, -2));
                    java.util.ArrayList<String> cl = listDirNames(CUSTOM_LUTS, ".cube");
                    int nCustom = cl.size();
                    if (nCustom > 0) cl.add(DIV);
                    try {
                        java.io.File[] a2 = new java.io.File(Films.LUTS).listFiles();
                        if (a2 != null) {
                            java.util.ArrayList<String> sys = new java.util.ArrayList<String>();
                            for (java.io.File f : a2) { String n = f.getName();
                                if (n.endsWith(".cube")) sys.add(n.substring(0, n.length() - 5)); }   // bare name, like the custom block
                            java.util.Collections.sort(sys);
                            cl.addAll(sys);
                        }
                        java.io.File[] a3 = new java.io.File(Films.LUTS + "/imported").listFiles();
                        if (a3 != null) for (java.io.File f : a3) { String n = f.getName();
                            if (n.endsWith(".cube")) { String p2 = n.substring(0, n.length() - 5); if (!cl.contains(p2)) cl.add(p2); } }
                    } catch (Throwable ig) {}
                    if (cl.isEmpty()) cl.add("lut.cube");
                    Spinner csp = new Spinner(this);
                    StdSpinnerAdapter cspA = new StdSpinnerAdapter(this, cl);
                    cspA.spin = csp;
                    if (nCustom > 0) cspA.div.add(nCustom);   // divider after the custom block
                    csp.setAdapter(cspA);
                    android.widget.FrameLayout cspW = Ux.wrapSpinner(this, csp);
                    String cur = nd.file != null ? nd.file
                               : (memLut != null ? memLut : Films.s(selFilm(), "lut", "lut.cube"));   // the file this node actually renders through
                    String curName = new java.io.File(cur).getName();
                    if (curName.endsWith(".cube")) curName = curName.substring(0, curName.length() - 5);
                    boolean matched = false;
                    for (int q = 0; q < cl.size(); q++) if (cl.get(q).equals(curName)) { csp.setSelection(q); matched = true; break; }
                    if (!matched) csp.setSelection(android.view.View.NO_ID);   // dangling reference: show empty
                    csp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                        public void onItemSelected(AdapterView<?> pp, View vv, int pos, long id2) {
                            String pick = cl.get(pos);
                            // per-node edit state: stored in the node, lands on disk at save. The bare
                            // filename is resolved by Films.lutFileByName() across luts/ + custom/luts/.
                            nd.file = pick + ".cube";
                            renderPreview();
                        }
                        public void onNothingSelected(AdapterView<?> pp) {}
                    });
                    lr.addView(cspW, new LinearLayout.LayoutParams(0, Ux.dp(38), 1.6f));
                    panel.addView(lr, new LinearLayout.LayoutParams(-1, -2));
                }
                if (nd.type.equals("overlay")) {   // blend mode + overlay file pool
                    LinearLayout br = new LinearLayout(this);
                    TextView bl = new TextView(this); bl.setText(L.s("blendLabel")); bl.setPadding(16, 20, 4, 0);
                    br.addView(bl, new LinearLayout.LayoutParams(-2, -2));
                    final String[] bn = {L.s("bmNormal"), L.s("bmScreen"), L.s("bmMultiply"), L.s("bmOverlay"), L.s("bmSoft")};
                    Spinner bsp = new Spinner(this);
                    StdSpinnerAdapter bspA = new StdSpinnerAdapter(this, bn);
                    bspA.spin = bsp;
                    bsp.setAdapter(bspA);
                    android.widget.FrameLayout bspW = Ux.wrapSpinner(this, bsp);
                    bsp.setSelection(Math.max(0, Math.min(4, (int) (float) nd.p.get("blend"))));
                    bsp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                        public void onItemSelected(AdapterView<?> pp, View vv, int pos, long id2) { nd.p.put("blend", (float) pos); renderPreview(); }
                        public void onNothingSelected(AdapterView<?> pp) {}
                    });
                    br.addView(bspW, new LinearLayout.LayoutParams(0, Ux.dp(38), 1f));
                    nodesBox.addView(br);
                    java.util.ArrayList<String> ovl = new java.util.ArrayList<String>();
                    try {
                        java.io.File od = new java.io.File("/sdcard/OpenFilm6K/overlays");
                        java.io.File[] a3 = od.listFiles();
                        if (a3 != null) for (java.io.File f : a3) {
                            String n2 = f.getName();
                            if (n2.endsWith(".jpg") || n2.endsWith(".JPG") || n2.endsWith(".jpeg")) ovl.add(n2);
                        }
                        java.util.Collections.sort(ovl);
                    } catch (Throwable ig) {}
                    if (!ovl.isEmpty()) {
                        TextView oh = new TextView(this); oh.setText(L.s("ovPool")); oh.setPadding(16, 12, 0, 2);
                        nodesBox.addView(oh);
                        java.util.HashSet<String> sel = new java.util.HashSet<String>();
                        for (String s2 : nd.list.split(",")) if (s2.trim().length() > 0) sel.add(s2.trim());
                        for (int q = 0; q < ovl.size(); q++) {
                            final String fn = ovl.get(q);
                            CheckBox cb = new CheckBox(this); cb.setText(fn); cb.setChecked(sel.contains(fn));
                            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                                public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                                    java.util.HashSet<String> s3 = new java.util.HashSet<String>();
                                    for (String s2 : nd.list.split(",")) if (s2.trim().length() > 0) s3.add(s2.trim());
                                    if (v) s3.add(fn); else s3.remove(fn);
                                    java.util.ArrayList<String> l2 = new java.util.ArrayList<String>(s3);
                                    java.util.Collections.sort(l2);
                                    StringBuilder sb2 = new StringBuilder();
                                    for (String s2 : l2) { if (sb2.length() > 0) sb2.append(','); sb2.append(s2); }
                                    nd.list = sb2.toString();
                                    renderPreview();
                                }
                            });
                            nodesBox.addView(cb);
                        }
                    } else {
                        TextView oh = new TextView(this); oh.setText(L.s("ovEmpty")); oh.setPadding(16, 12, 0, 2);
                        nodesBox.addView(oh);
                    }
                }
                if (nd.type.equals("grade") || nd.type.equals("hue")) {   // tonal-range qualifiers
                    LinearLayout zr = new LinearLayout(this);
                    TextView zl = new TextView(this); zl.setText(L.s("zone")); zl.setPadding(16, 20, 4, 0);
                    zr.addView(zl, new LinearLayout.LayoutParams(-2, -2));
                    String[] zn = {"high", "mid", "low"}; String[] zk = {"high", "mid", "low"};
                    for (int k = 0; k < 3; k++) {
                        final String kk = zk[k];
                        CheckBox cb = new CheckBox(this); cb.setText(zn[k]); cb.setChecked(nd.p.get(kk) > 0.5f);
                        cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) { nd.p.put(kk, v ? 1f : 0f); renderPreview(); }
                        });
                        zr.addView(cb, new LinearLayout.LayoutParams(0, -2, 1f));
                    }
                    nodesBox.addView(zr);
                }
            }
        }
    }

    // ---------- preview ----------
    private String selFilm() { return (String) filmSpin.getSelectedItem(); }
    /** editing target pipeline key: base, or .day/.night variant when split */
    private String editKey() {
        String f = selFilm();
        if (f == null) return f;
        if (Films.isSplit(f)) return f + (editNight ? ".night" : ".day");
        return f;
    }
    static final String CUSTOM_SCENES = "/sdcard/OpenFilm6K/custom/scenes";   // user reference photos
    static final String CUSTOM_LUTS = Films.CUSTOM_LUTS;                      // user .cube files (single source of truth)

    static java.util.ArrayList<String> listDirNames(String dir, String ext) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        java.io.File[] fs = new java.io.File(dir).listFiles();
        if (fs != null) for (java.io.File f : fs) {
            String n = f.getName();
            if (n.toLowerCase(java.util.Locale.US).endsWith(ext)) out.add(n.substring(0, n.length() - ext.length()));
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** dynamic scene list: custom reference photos on top + separator + system preview_*.jpg tags */
    private java.util.List<String> sceneList() {
        java.util.ArrayList<String> all = new java.util.ArrayList<String>();
        java.util.ArrayList<String> custom = listDirNames(CUSTOM_SCENES, ".jpg");
        all.addAll(custom);
        if (!custom.isEmpty()) all.add(DIV);
        java.io.File[] fs = new java.io.File("/sdcard/OpenFilm6K").listFiles();
        java.util.ArrayList<String> sys = new java.util.ArrayList<String>();
        if (fs != null) for (java.io.File f : fs) {
            String n = f.getName();
            if (n.startsWith("preview_") && n.endsWith(".jpg"))
                sys.add(n.substring(8, n.length() - 4));
        }
        java.util.Collections.sort(sys);
        all.addAll(sys);
        return all;
    }
    private void refreshSceneSpinner(String keep) {
        java.util.ArrayList<String> cs = listDirNames(CUSTOM_SCENES, ".jpg");
        java.util.ArrayList<String> tags = new java.util.ArrayList<String>(cs);   // custom tags (unchanged names)
        java.io.File[] fs = new java.io.File("/sdcard/OpenFilm6K").listFiles();
        java.util.ArrayList<String> sys = new java.util.ArrayList<String>();
        if (fs != null) for (java.io.File f : fs) {
            String n = f.getName();
            if (n.startsWith("preview_") && n.endsWith(".jpg"))
                sys.add(n.substring(8, n.length() - 4));
        }
        java.util.Collections.sort(sys);
        int divAt = -1;
        if (!cs.isEmpty()) { divAt = cs.size(); tags.add(DIV); }
        tags.addAll(sys);
        java.util.ArrayList<String> disp = new java.util.ArrayList<String>();
        for (String t : tags) disp.add(t.equals(DIV) ? DIV : L.scene(t));   // localized display names
        StdSpinnerAdapter a = new StdSpinnerAdapter(this, disp);
        a.spin = sceneSpin;
        if (divAt >= 0) a.div.add(divAt);
        sceneSpin.setAdapter(a);
        if (keep != null) for (int i = 0; i < disp.size(); i++) if (disp.get(i).equals(L.scene(keep)) || disp.get(i).equals(keep)) { sceneSpin.setSelection(i); break; }
    }
    private String sceneTag() {
        Object it = sceneSpin.getSelectedItem();
        return it == null ? "bar1" : L.tagOf(it.toString());   // reverse-map display name -> file tag
    }
    private String sceneFile() {
        String t = sceneTag();
        java.io.File cf = new java.io.File(CUSTOM_SCENES, t + ".jpg");
        if (cf.exists()) return cf.getAbsolutePath();   // custom reference photo wins on name clash
        return "/sdcard/OpenFilm6K/preview_" + t + ".jpg";
    }

    // ---- settings dialog: manage custom reference photos & LUTs (win98 chrome) ----
    /** settings row group: [import+] [combobox of custom assets] [delete X] inside an etched labeled frame */
    private android.widget.FrameLayout settingsGroup(String label, final String dir, final String ext,
                                                      final int reqCode, final android.app.Dialog dlg) {
        final EditorActivity self = this;
        android.widget.FrameLayout g = new android.widget.FrameLayout(this);
        android.widget.LinearLayout inner = new android.widget.LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(android.view.Gravity.CENTER_VERTICAL);
        inner.setBackground(new Ux.Etched());
        inner.setPadding(Ux.dp(8), Ux.dp(14), Ux.dp(8), Ux.dp(8));
        // import button
        Button add = new Button(this); add.setText("+"); add.setMinWidth(0); add.setMinimumWidth(0); add.setPadding(0, 0, 0, 0);
        add.setContentDescription(L.s("btnImport"));
        add.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            try { self.startActivityForResult(it, reqCode); } catch (Throwable e) { MainActivity.say("picker " + e); }
        }});
        inner.addView(add, new LinearLayout.LayoutParams(Ux.dp(38), Ux.dp(34)));
        // combobox of custom assets
        final Spinner sp = new Spinner(this);
        final Runnable[] refill = new Runnable[1];
        refill[0] = new Runnable() { public void run() {
            java.util.ArrayList<String> names = listDirNames(dir, ext);
            if (names.isEmpty()) names.add(L.s("noneItem"));
            StdSpinnerAdapter a = new StdSpinnerAdapter(self, names);
            a.spin = sp;
            sp.setAdapter(a);
        }};
        refill[0].run();
        if (reqCode == 1001) setSceneSpin = sp; else setLutSpin = sp;   // remembered for post-import selection
        android.widget.FrameLayout spW = Ux.wrapSpinner(this, sp);
        inner.addView(spW, new LinearLayout.LayoutParams(0, Ux.dp(38), 1f));
        // delete button: removes the selected asset
        Button del = new Button(this); del.setText("✕"); del.setMinWidth(0); del.setMinimumWidth(0); del.setPadding(0, 0, 0, 0);
        del.setContentDescription(L.s("btnDelete"));
        del.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            Object it = sp.getSelectedItem();
            if (it == null || it.toString().equals(L.s("noneItem"))) { MainActivity.say(L.s("noDelTarget")); return; }
            new java.io.File(dir, it.toString() + ext).delete();
            MainActivity.say(L.s("deleted") + it + ext);
            refill[0].run();
            refreshSceneSpinner(sceneTag());   // dropdowns pick up the removal
            buildNodeUI();
            renderPreview();
        }});
        android.widget.LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ux.dp(38), Ux.dp(34));
        dlp.leftMargin = Ux.dp(4);
        inner.addView(del, dlp);
        TextView lab = new TextView(this); lab.setText(label); Ux.styleHeader(lab);
        g.setClipChildren(false);   // label rides the frame edge half a glyph high — keep it unclipped
        // structural safety: the label lives INSIDE g's own bounds (no negative-margin reliance),
        // straddling inner's top border from above — no parent can ever clip it
        android.widget.FrameLayout.LayoutParams ilp = new android.widget.FrameLayout.LayoutParams(-1, -2);
        ilp.topMargin = Ux.dp(10);
        g.addView(inner, ilp);
        android.widget.FrameLayout.LayoutParams llp = new android.widget.FrameLayout.LayoutParams(-2, -2);
        llp.leftMargin = Ux.dp(10);
        llp.topMargin = Ux.dp(10) - Ux.dp(1) - (int) (Ux.BODY * 0.5f * Ux.tscale());
        g.addView(lab, llp);
        return g;   // no wrapping layout: a clipping parent would cut the label riding the frame
    }

    /** delete-film confirm: win98 message box — warning icon + text + buttons (same chrome as settings) */
    private void showDelFilm(final String n) {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        int fp = Ux.dp(2);
        box.setPadding(fp, fp, fp, fp);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        body.setBackgroundColor(Ux.FACE);
        int bp = Ux.dp(12);
        body.setPadding(bp, bp, bp, bp);
        // icon + message row
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageResource(R.drawable.warn32);
        row.addView(iv, new LinearLayout.LayoutParams(Ux.dp(32), Ux.dp(32)));
        TextView msg = new TextView(this);
        msg.setText(L.s("confirmDel") + n + L.s("delCfgTail"));
        msg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(Ux.BODY)); msg.setTextColor(Ux.TXT);
        msg.setPadding(Ux.dp(12), 0, 0, 0);
        row.addView(msg, new LinearLayout.LayoutParams(0, -2, 1f));
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
        // buttons: centered, win98 width
        LinearLayout rowBtn = new LinearLayout(this);
        Button del = new Button(this); del.setText(L.s("btnDelete"));
        del.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            new java.io.File(Films.PIPE, n + ".properties").delete();
            Films.reload(n);
            dlg.dismiss();
            rebuildFilmSpinner(null);
        }});
        rowBtn.addView(del, new LinearLayout.LayoutParams(0, -2, 1f));
        Button cancel = new Button(this); cancel.setText(L.s("btnCancel"));
        cancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dlg.dismiss(); }});
        rowBtn.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.LinearLayout.LayoutParams rbp = new LinearLayout.LayoutParams(-1, -2);
        rbp.topMargin = Ux.dp(16);
        body.addView(rowBtn, rbp);
        Ux.themeTree(body);
        box.addView(body, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setBackground(Ux.navySunkenBar());
        tb.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tb.setPadding(Ux.dp(6), Ux.dp(1), Ux.dp(1), Ux.dp(1));
        TextView ttl = new TextView(this); ttl.setText(L.s("delFilm")); ttl.setTextColor(0xFFFFFFFF);
        ttl.getPaint().setFakeBoldText(true); ttl.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(14));
        tb.addView(ttl, new LinearLayout.LayoutParams(0, -2, 1f));
        Button x = new Button(this); x.setText("✕"); x.setPadding(0, 0, Ux.dp(5), Ux.dp(5));
        Ux.styleButton(x);
        x.setPadding(0, 0, Ux.dp(5), Ux.dp(5));
        x.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dlg.dismiss(); }});
        android.widget.LinearLayout.LayoutParams xlp = new android.widget.LinearLayout.LayoutParams(Ux.dp(34), Ux.dp(34));
        xlp.gravity = android.view.Gravity.CENTER_VERTICAL;
        tb.addView(x, xlp);
        box.addView(tb, 0, new LinearLayout.LayoutParams(-1, -2));
        dlg.setContentView(box);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.getDecorView().setPadding(0, 0, 0, 0);
            w.setBackgroundDrawable(Ux.frame98());
            w.setLayout(Math.min(Ux.dp(420), getResources().getDisplayMetrics().widthPixels - Ux.dp(24)), -2);
        }
        dlg.show();
    }

    /** new-film dialog: name + blank/copy-of-current choice, win98 chrome identical to the settings dialog */
    private void showNewFilm() {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        int fp = Ux.dp(2);
        box.setPadding(fp, fp, fp, fp);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        body.setBackgroundColor(Ux.FACE);
        int bp = Ux.dp(10);
        body.setPadding(bp, bp, bp, bp);
        // group: name
        android.widget.FrameLayout g1 = new android.widget.FrameLayout(this);
        LinearLayout g1i = new LinearLayout(this);
        g1i.setOrientation(LinearLayout.VERTICAL);
        g1i.setBackground(new Ux.Etched());
        g1i.setPadding(Ux.dp(8), Ux.dp(14), Ux.dp(8), Ux.dp(8));
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint(L.s("newFilmName"));
        et.setSingleLine(true);
        et.setBackground(Ux.sunkenField());
        et.setPadding(Ux.dp(8), Ux.dp(8), Ux.dp(8), Ux.dp(8));
        et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(Ux.BODY));
        g1i.addView(et, new LinearLayout.LayoutParams(-1, -2));
        final Button[] okRef = new Button[1];   // create button, toggled by name emptiness
        et.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence cs, int a, int b, int c2) {}
            public void onTextChanged(CharSequence cs, int a, int b, int c2) {}
            public void afterTextChanged(android.text.Editable e2) {
                if (okRef[0] != null) {
                    boolean has = e2.toString().trim().length() > 0;
                    okRef[0].setEnabled(has);
                    okRef[0].setAlpha(has ? 1f : 0.38f);
                }
            }
        });
        TextView g1l = new TextView(this); g1l.setText(L.s("filmName")); Ux.styleHeader(g1l);
        g1.setClipChildren(false);
        android.widget.FrameLayout.LayoutParams g1il = new android.widget.FrameLayout.LayoutParams(-1, -2);
        g1il.topMargin = Ux.dp(10);
        g1.addView(g1i, g1il);
        android.widget.FrameLayout.LayoutParams g1ll = new android.widget.FrameLayout.LayoutParams(-2, -2);
        g1ll.leftMargin = Ux.dp(10);
        g1ll.topMargin = Ux.dp(10) - Ux.dp(1) - (int) (Ux.BODY * 0.5f * Ux.tscale());
        g1.addView(g1l, g1ll);
        body.addView(g1, new LinearLayout.LayoutParams(-1, -2));
        // group: blank / copy radio pair
        android.widget.FrameLayout g2 = new android.widget.FrameLayout(this);
        LinearLayout g2i = new LinearLayout(this);
        g2i.setOrientation(LinearLayout.VERTICAL);
        g2i.setBackground(new Ux.Etched());
        g2i.setPadding(Ux.dp(8), Ux.dp(14), Ux.dp(8), Ux.dp(8));
        final boolean[] copyMode = {true};   // default: copy the current film
        android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        rg.setOrientation(LinearLayout.VERTICAL);
        android.widget.RadioButton rbBlank = new android.widget.RadioButton(this);
        rbBlank.setText(L.s("blankFilm"));
        rbBlank.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(Ux.BODY)); rbBlank.setTextColor(Ux.TXT);
        rbBlank.setButtonDrawable(Ux.radio98(EditorActivity.this));
        rbBlank.setPadding(Ux.dp(8), Ux.dp(6), Ux.dp(8), Ux.dp(6));
        android.widget.RadioButton rbCopy = new android.widget.RadioButton(this);
        String curName = selFilm() != null ? selFilm() : "";
        rbCopy.setText(L.s("copyCurFilm") + (curName.isEmpty() ? "" : " (" + curName + ")"));
        rbCopy.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(Ux.BODY)); rbCopy.setTextColor(Ux.TXT);
        rbCopy.setButtonDrawable(Ux.radio98(EditorActivity.this));
        rbCopy.setPadding(Ux.dp(8), Ux.dp(6), Ux.dp(8), Ux.dp(6));
        rg.addView(rbBlank); rg.addView(rbCopy);
        rg.check(rbCopy.getId());
        rg.setOnCheckedChangeListener(new android.widget.RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.RadioGroup g, int id) { copyMode[0] = (id == rbCopy.getId()); }
        });
        g2i.addView(rg, new LinearLayout.LayoutParams(-1, -2));
        TextView g2l = new TextView(this); g2l.setText(L.s("createMode")); Ux.styleHeader(g2l);
        g2.setClipChildren(false);
        android.widget.FrameLayout.LayoutParams g2il = new android.widget.FrameLayout.LayoutParams(-1, -2);
        g2il.topMargin = Ux.dp(10);
        g2.addView(g2i, g2il);
        android.widget.FrameLayout.LayoutParams g2ll = new android.widget.FrameLayout.LayoutParams(-2, -2);
        g2ll.leftMargin = Ux.dp(10);
        g2ll.topMargin = Ux.dp(10) - Ux.dp(1) - (int) (Ux.BODY * 0.5f * Ux.tscale());
        g2.addView(g2l, g2ll);
        android.widget.LinearLayout.LayoutParams g2p = new LinearLayout.LayoutParams(-1, -2);
        g2p.topMargin = Ux.dp(14);
        body.addView(g2, g2p);
        // ok / cancel row
        LinearLayout rowBtn = new LinearLayout(this);
        Button ok = new Button(this); ok.setText(L.s("create"));
        okRef[0] = ok;
        ok.setEnabled(false); ok.setAlpha(0.38f);   // empty name at open -> disabled
        ok.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            final String n2 = et.getText().toString().trim().replaceAll("[/\\\\]", "_");   // any name allowed (incl. Chinese); only path separators are replaced
            if (n2.isEmpty()) { MainActivity.say(L.s("invalidName")); return; }
            if (new java.io.File(Films.PIPE, n2 + ".properties").exists()) { MainActivity.say(L.s("exists") + n2); return; }
            try {
                if (copyMode[0]) {   // copy current film (base + day/night variants), re-marked as user film
                    String cur = selFilm();
                    if (cur == null || cur.isEmpty()) { MainActivity.say(L.s("noFilmToDelete")); return; }
                    copyPropsFile(cur, n2, true);
                    copyPropsFile(cur + ".day", n2 + ".day", false);
                    copyPropsFile(cur + ".night", n2 + ".night", false);
                } else {
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(Films.PIPE, n2 + ".properties"));
                    fo.write(("origin=user\nlut=" + n2 + ".cube\nchain=lut:1\nlut@1.on=1.0\n").getBytes()); fo.close();
                }
                Films.reload(n2); Films.reload(n2 + ".day"); Films.reload(n2 + ".night");
            } catch (Throwable e) { MainActivity.say(L.s("createFail") + e); return; }
            dlg.dismiss();
            rebuildFilmSpinner(n2);
        }});
        rowBtn.addView(ok, new LinearLayout.LayoutParams(0, -2, 1f));
        Button cancel = new Button(this); cancel.setText(L.s("btnCancel"));
        cancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dlg.dismiss(); }});
        rowBtn.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.LinearLayout.LayoutParams rbp = new LinearLayout.LayoutParams(-1, -2);
        rbp.topMargin = Ux.dp(14);
        body.addView(rowBtn, rbp);
        Ux.themeTree(body);   // theme BEFORE mounting the title bar
        box.addView(body, new LinearLayout.LayoutParams(-1, -2));
        // win98 tool-dialog title bar (identical to settings)
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setBackground(Ux.navySunkenBar());
        tb.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tb.setPadding(Ux.dp(6), Ux.dp(1), Ux.dp(1), Ux.dp(1));
        TextView ttl = new TextView(this); ttl.setText(L.s("newFilm")); ttl.setTextColor(0xFFFFFFFF);
        ttl.getPaint().setFakeBoldText(true); ttl.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(14));
        tb.addView(ttl, new LinearLayout.LayoutParams(0, -2, 1f));
        Button x = new Button(this); x.setText("✕"); x.setPadding(0, 0, Ux.dp(5), Ux.dp(5));
        Ux.styleButton(x);
        x.setPadding(0, 0, Ux.dp(5), Ux.dp(5));
        x.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dlg.dismiss(); }});
        android.widget.LinearLayout.LayoutParams xlp = new android.widget.LinearLayout.LayoutParams(Ux.dp(34), Ux.dp(34));
        xlp.gravity = android.view.Gravity.CENTER_VERTICAL;
        tb.addView(x, xlp);
        box.addView(tb, 0, new LinearLayout.LayoutParams(-1, -2));
        dlg.setContentView(box);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.getDecorView().setPadding(0, 0, 0, 0);
            w.setBackgroundDrawable(Ux.frame98());
            w.setLayout(Math.min(Ux.dp(420), getResources().getDisplayMetrics().widthPixels - Ux.dp(24)), -2);
        }
        dlg.show();
        et.requestFocus();   // open straight into typing the name
    }

    /** copy a pipeline properties file to a new name; markUser forces origin=user on the new base */
    private void copyPropsFile(String from, String to, boolean markUser) {
        java.io.File src = new java.io.File(Films.PIPE, from + ".properties");
        if (!src.exists()) return;
        try {
            java.util.Properties pr = new java.util.Properties();
            java.io.FileInputStream fi = new java.io.FileInputStream(src);
            pr.load(fi); fi.close();
            if (markUser) pr.setProperty("origin", "user");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(Films.PIPE, to + ".properties"));
            pr.store(fo, null); fo.close();
        } catch (Throwable e) { MainActivity.say("copy " + from + " " + e); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        MainActivity.firstRunFlow(this);   // re-check after returning from the permission screen
    }

    /** sync the title-bar spy checkbox with the persisted spy_on flag (dialog is the single writer) */
    void refreshSpyBtn() {
        if (spyChkView != null)
            spyChkView.setImageDrawable(Ux.checkbox98(this, getSharedPreferences("of6k", 0).getBoolean("spy_on", false)));
    }

    private void showSettings() {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        settingsDlg = dlg;
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);   // group titles rise above their frames
        int fp = Ux.dp(2);   // inside the raised bevel frame drawn by the window background
        box.setPadding(fp, fp, fp, fp);
        box.setPadding(fp, fp, fp, fp);   // no top inset: the title bar sits flush under the bevel frame
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        body.setBackgroundColor(Ux.FACE);
        int bp = Ux.dp(10);
        body.setPadding(bp, bp, bp, bp);
        // language switcher group: combobox only
        android.widget.FrameLayout lg = new android.widget.FrameLayout(this);
        android.widget.LinearLayout lgi = new android.widget.LinearLayout(this);
        lgi.setOrientation(LinearLayout.HORIZONTAL);
        lgi.setGravity(android.view.Gravity.CENTER_VERTICAL);
        lgi.setBackground(new Ux.Etched());
        lgi.setPadding(Ux.dp(8), Ux.dp(14), Ux.dp(8), Ux.dp(8));
        Spinner lsp = new Spinner(this);
        StdSpinnerAdapter lsA = new StdSpinnerAdapter(this, new java.util.ArrayList<String>(java.util.Arrays.asList(L.NAMES)));
        lsA.spin = lsp;
        lsp.setAdapter(lsA);
        for (int i = 0; i < L.CODES.length; i++) if (L.CODES[i].equals(L.lang)) { lsp.setSelection(i); break; }
        lsp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (!L.CODES[pos].equals(L.lang)) {
                    L.setLang(EditorActivity.this, L.CODES[pos]);
                    MainActivity.say(L.s("langChangedToast"));
                    dlg.dismiss();
                    recreate();   // rebuild the whole UI in the new language
                }
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        android.widget.FrameLayout lspW = Ux.wrapSpinner(this, lsp);
        lgi.addView(lspW, new LinearLayout.LayoutParams(-1, Ux.dp(38)));
        TextView lgl = new TextView(this); lgl.setText(L.s("langLabel")); Ux.styleHeader(lgl);
        lg.setClipChildren(false);
        android.widget.FrameLayout.LayoutParams il2 = new android.widget.FrameLayout.LayoutParams(-1, -2);
        il2.topMargin = Ux.dp(10);
        lg.addView(lgi, il2);
        android.widget.FrameLayout.LayoutParams ll3 = new android.widget.FrameLayout.LayoutParams(-2, -2);
        ll3.leftMargin = Ux.dp(10);
        ll3.topMargin = Ux.dp(10) - Ux.dp(1) - (int) (Ux.BODY * 0.5f * Ux.tscale());
        lg.addView(lgl, ll3);
        body.addView(lg, new LinearLayout.LayoutParams(-1, -2));
        // data group: incremental reinstall of the bundled film library (never clears anything)
        android.widget.FrameLayout rg = new android.widget.FrameLayout(this);
        android.widget.LinearLayout rgi = new android.widget.LinearLayout(this);
        rgi.setOrientation(LinearLayout.HORIZONTAL);
        rgi.setGravity(android.view.Gravity.CENTER_VERTICAL);
        rgi.setBackground(new Ux.Etched());
        rgi.setPadding(Ux.dp(8), Ux.dp(14), Ux.dp(8), Ux.dp(8));
        Button rbtn = new Button(this);
        Ux.styleButton(rbtn); rbtn.setText(L.s("reinstallData"));
        rbtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (settingsDlg != null) settingsDlg.dismiss();
            MainActivity.showInstall(EditorActivity.this);
        }});
        rgi.addView(rbtn, new LinearLayout.LayoutParams(-2, Ux.dp(38)));
        android.widget.FrameLayout.LayoutParams rl2 = new android.widget.FrameLayout.LayoutParams(-1, -2);
        rl2.topMargin = Ux.dp(10);
        rg.addView(rgi, rl2);
        TextView rgl = new TextView(this);
        rgl.setText(L.s("reinstallData"));
        Ux.styleHeader(rgl);
        android.widget.FrameLayout.LayoutParams rll = new android.widget.FrameLayout.LayoutParams(-2, -2);
        rll.leftMargin = Ux.dp(10);
        rll.topMargin = Ux.dp(10) - Ux.dp(1) - (int) (Ux.BODY * 0.5f * Ux.tscale());
        rg.addView(rgl, rll);
        rg.setClipChildren(false);
        body.addView(rg, new LinearLayout.LayoutParams(-1, -2));
        // preview watermark group (editor-only; the camera's own stamp choice is unaffected)
        android.widget.FrameLayout pg = new android.widget.FrameLayout(this);
        android.widget.LinearLayout pgi = new android.widget.LinearLayout(this);
        pgi.setOrientation(LinearLayout.HORIZONTAL);
        pgi.setGravity(android.view.Gravity.CENTER_VERTICAL);
        pgi.setBackground(new Ux.Etched());
        pgi.setPadding(Ux.dp(8), Ux.dp(14), Ux.dp(8), Ux.dp(8));
        final int[] stCodes = {0, 1, 2, 3, 6, 7};
        String[] stNames = {L.s("stNone"), L.s("stD"), L.s("stE"), L.s("stDE"), L.s("stF"), L.s("stFE")};
        Spinner psp = new Spinner(this);
        StdSpinnerAdapter psA = new StdSpinnerAdapter(this, new java.util.ArrayList<String>(java.util.Arrays.asList(stNames)));
        psA.spin = psp;
        psp.setAdapter(psA);
        int pvNow = Server.pvStamp(this);
        for (int i = 0; i < stCodes.length; i++) if (stCodes[i] == pvNow) { psp.setSelection(i); break; }
        psp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (stCodes[pos] != Server.pvStamp(EditorActivity.this)) {
                    getSharedPreferences("of6k", 0).edit().putInt("pvstamp", stCodes[pos]).commit();
                    renderPreview();
                }
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        android.widget.FrameLayout pspW = Ux.wrapSpinner(this, psp);
        pgi.addView(pspW, new LinearLayout.LayoutParams(-1, Ux.dp(38)));
        TextView pgl = new TextView(this); pgl.setText(L.s("pvStampLabel")); Ux.styleHeader(pgl);
        pg.setClipChildren(false);
        android.widget.FrameLayout.LayoutParams pil = new android.widget.FrameLayout.LayoutParams(-1, -2);
        pil.topMargin = Ux.dp(10);
        pg.addView(pgi, pil);
        android.widget.FrameLayout.LayoutParams pll = new android.widget.FrameLayout.LayoutParams(-2, -2);
        pll.leftMargin = Ux.dp(10);
        pll.topMargin = Ux.dp(10) - Ux.dp(1) - (int) (Ux.BODY * 0.5f * Ux.tscale());
        pg.addView(pgl, pll);
        android.widget.LinearLayout.LayoutParams pgp = new LinearLayout.LayoutParams(-1, -2);
        pgp.topMargin = Ux.dp(14);
        body.addView(pg, pgp);
        android.widget.LinearLayout.LayoutParams glp1 = new LinearLayout.LayoutParams(-1, -2);
        glp1.topMargin = Ux.dp(14);
        body.addView(settingsGroup(L.s("customScene"), CUSTOM_SCENES, ".jpg", 1001, dlg), glp1);
        android.widget.LinearLayout.LayoutParams glp2 = new LinearLayout.LayoutParams(-1, -2);
        glp2.topMargin = Ux.dp(14);   // room for group 2's title rising above its frame, clear of group 1
        body.addView(settingsGroup(L.s("customLut"), CUSTOM_LUTS, ".cube", 1002, dlg), glp2);
        Ux.themeTree(body);   // theme BEFORE mounting the title bar: themeTree would recolor the white title text
        box.addView(body, new LinearLayout.LayoutParams(-1, -2));
        // win98 tool-dialog title bar: narrow, recessed navy bar, close button flush with bar height
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setBackground(Ux.navySunkenBar());
        tb.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tb.setPadding(Ux.dp(6), Ux.dp(1), Ux.dp(1), Ux.dp(1));
        TextView tico = new TextView(this); tico.setText("⚙️"); tico.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(18)); tico.setTextColor(0xFF000000);   // same glyph as its title-bar button
        tico.setGravity(android.view.Gravity.CENTER);
        tb.addView(tico, new android.widget.LinearLayout.LayoutParams(-2, -2));
        TextView ttl = new TextView(this); ttl.setText(L.s("settings")); ttl.setTextColor(0xFFFFFFFF);
        ttl.getPaint().setFakeBoldText(true); ttl.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(14));
        android.widget.LinearLayout.LayoutParams ttlp = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
        ttlp.leftMargin = Ux.dp(6);
        tb.addView(ttl, ttlp);
        Button x = new Button(this); x.setText("✕"); x.setPadding(0, 0, Ux.dp(5), Ux.dp(5));   // center on the inset bevel body
        Ux.styleButton(x);   // raised bevel chrome (title bar is mounted after themeTree)
        x.setPadding(0, 0, Ux.dp(5), Ux.dp(5));
        x.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dlg.dismiss(); }});
        android.widget.LinearLayout.LayoutParams xlp = new android.widget.LinearLayout.LayoutParams(Ux.dp(34), Ux.dp(34));
        xlp.gravity = android.view.Gravity.CENTER_VERTICAL;
        tb.addView(x, xlp);
        box.addView(tb, 0, new LinearLayout.LayoutParams(-1, -2));
        dlg.setContentView(box);
        android.view.Window w = dlg.getWindow();
        if (w != null) {   // square dialog window with a raised bevel frame
            w.getDecorView().setPadding(0, 0, 0, 0);   // kill the default decor insets (gap above the title bar)
            w.setBackgroundDrawable(Ux.frame98());
            w.setLayout(Math.min(Ux.dp(420), getResources().getDisplayMetrics().widthPixels - Ux.dp(24)), -2);
        }
        dlg.show();
    }

    // ---- spy mode dialog (监视模式): master switch + film/stamp pickers + watched dirs ----
    private void showSpy() {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        spyDlg = dlg;
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        final android.content.SharedPreferences pf = getSharedPreferences("of6k", 0);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        int fp = Ux.dp(2);   // inside the raised bevel frame drawn by the window background
        box.setPadding(fp, fp, fp, fp);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        body.setBackgroundColor(Ux.FACE);
        int bp = Ux.dp(10);
        body.setPadding(bp, bp, bp, bp);
        // group: master switch (single writer of spy_on; the title-bar checkbox mirrors it)
        android.widget.FrameLayout eg = new android.widget.FrameLayout(this);
        LinearLayout egi = new LinearLayout(this);
        egi.setOrientation(LinearLayout.HORIZONTAL);
        egi.setGravity(android.view.Gravity.CENTER_VERTICAL);
        egi.setBackground(new Ux.Etched());
        egi.setPadding(Ux.dp(8), Ux.dp(14), Ux.dp(8), Ux.dp(8));
        CheckBox en = new CheckBox(this);
        en.setText(L.s("spyEnable"));
        en.setButtonDrawable(Ux.checkbox98States(this));   // explicit: authentic 98.css pixel art
        en.setChecked(pf.getBoolean("spy_on", false));
        en.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                pf.edit().putBoolean("spy_on", v).commit();
                refreshSpyBtn();
                SpyWatcher.sync(getApplicationContext());
            }
        });
        egi.addView(en, new LinearLayout.LayoutParams(-2, -2));
        TextView egl = new TextView(this); egl.setText(L.s("spyMode")); Ux.styleHeader(egl);
        eg.setClipChildren(false);
        android.widget.FrameLayout.LayoutParams egil = new android.widget.FrameLayout.LayoutParams(-1, -2);
        egil.topMargin = Ux.dp(10);
        eg.addView(egi, egil);
        android.widget.FrameLayout.LayoutParams egll = new android.widget.FrameLayout.LayoutParams(-2, -2);
        egll.leftMargin = Ux.dp(10);
        egll.topMargin = Ux.dp(10) - Ux.dp(1) - (int) (Ux.BODY * 0.5f * Ux.tscale());
        eg.addView(egl, egll);
        body.addView(eg, new LinearLayout.LayoutParams(-1, -2));
        // group: film combobox — same choices as the main list, independent selection
        android.widget.FrameLayout fg = new android.widget.FrameLayout(this);
        LinearLayout fgi = new LinearLayout(this);
        fgi.setOrientation(LinearLayout.HORIZONTAL);
        fgi.setGravity(android.view.Gravity.CENTER_VERTICAL);
        fgi.setBackground(new Ux.Etched());
        fgi.setPadding(Ux.dp(8), Ux.dp(14), Ux.dp(8), Ux.dp(8));
        Spinner fsp = new Spinner(this);
        final java.util.List<String> films = filmChoices();
        StdSpinnerAdapter fsA = new StdSpinnerAdapter(this, new java.util.ArrayList<String>(films));
        fsA.spin = fsp;
        fsp.setAdapter(fsA);
        String curFilm = pf.getString("spy_film", selFilm());
        if (curFilm != null) for (int i = 0; i < films.size(); i++) if (films.get(i).equals(curFilm)) { fsp.setSelection(i); break; }
        fsp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= films.size()) return;
                String name = films.get(pos);
                if (DIV.equals(name)) return;   // divider sentinel row is not a film
                if (!name.equals(pf.getString("spy_film", null)))
                    pf.edit().putString("spy_film", name).commit();
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        android.widget.FrameLayout fspW = Ux.wrapSpinner(this, fsp);
        fgi.addView(fspW, new LinearLayout.LayoutParams(-1, Ux.dp(38)));
        TextView fgl = new TextView(this); fgl.setText(L.s("spyFilm")); Ux.styleHeader(fgl);
        fg.setClipChildren(false);
        android.widget.FrameLayout.LayoutParams fgil = new android.widget.FrameLayout.LayoutParams(-1, -2);
        fgil.topMargin = Ux.dp(10);
        fg.addView(fgi, fgil);
        android.widget.FrameLayout.LayoutParams fgll = new android.widget.FrameLayout.LayoutParams(-2, -2);
        fgll.leftMargin = Ux.dp(10);
        fgll.topMargin = Ux.dp(10) - Ux.dp(1) - (int) (Ux.BODY * 0.5f * Ux.tscale());
        fg.addView(fgl, fgll);
        body.addView(fg, new LinearLayout.LayoutParams(-1, -2));
        // group: stamp/border mode — the camera's full C2 set (settings dialog hides polaroid/collage)
        android.widget.FrameLayout sg = new android.widget.FrameLayout(this);
        LinearLayout sgi = new LinearLayout(this);
        sgi.setOrientation(LinearLayout.HORIZONTAL);
        sgi.setGravity(android.view.Gravity.CENTER_VERTICAL);
        sgi.setBackground(new Ux.Etched());
        sgi.setPadding(Ux.dp(8), Ux.dp(14), Ux.dp(8), Ux.dp(8));
        final int[] spyCodes = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        String[] spyNames = {L.s("stNone"), L.s("stD"), L.s("stE"), L.s("stDE"), L.s("stPolaroid"), L.s("stCollage"), L.s("stF"), L.s("stFE"), L.s("stHalf"), L.s("stDouble"), L.s("stB2")};
        Spinner ssp = new Spinner(this);
        StdSpinnerAdapter ssA = new StdSpinnerAdapter(this, new java.util.ArrayList<String>(java.util.Arrays.asList(spyNames)));
        ssA.spin = ssp;
        ssp.setAdapter(ssA);
        int curStamp = pf.getInt("spy_stamp", 0);
        for (int i = 0; i < spyCodes.length; i++) if (spyCodes[i] == curStamp) { ssp.setSelection(i); break; }
        ssp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (spyCodes[pos] != pf.getInt("spy_stamp", 0))
                    pf.edit().putInt("spy_stamp", spyCodes[pos]).commit();
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        android.widget.FrameLayout sspW = Ux.wrapSpinner(this, ssp);
        sgi.addView(sspW, new LinearLayout.LayoutParams(-1, Ux.dp(38)));
        TextView sgl = new TextView(this); sgl.setText(L.s("spyStamp")); Ux.styleHeader(sgl);
        sg.setClipChildren(false);
        android.widget.FrameLayout.LayoutParams sgil = new android.widget.FrameLayout.LayoutParams(-1, -2);
        sgil.topMargin = Ux.dp(10);
        sg.addView(sgi, sgil);
        android.widget.FrameLayout.LayoutParams sgll = new android.widget.FrameLayout.LayoutParams(-2, -2);
        sgll.leftMargin = Ux.dp(10);
        sgll.topMargin = Ux.dp(10) - Ux.dp(1) - (int) (Ux.BODY * 0.5f * Ux.tscale());
        sg.addView(sgl, sgll);
        body.addView(sg, new LinearLayout.LayoutParams(-1, -2));
        // group: watched dirs — the same [+|spinner|✕] row as the settings import groups; + opens the system folder picker
        android.widget.FrameLayout dg = new android.widget.FrameLayout(this);
        LinearLayout dgi = new LinearLayout(this);
        dgi.setOrientation(LinearLayout.HORIZONTAL);
        dgi.setGravity(android.view.Gravity.CENTER_VERTICAL);
        dgi.setBackground(new Ux.Etched());
        dgi.setPadding(Ux.dp(8), Ux.dp(14), Ux.dp(8), Ux.dp(8));
        Button addDir = new Button(this); addDir.setText("+"); addDir.setMinWidth(0); addDir.setMinimumWidth(0); addDir.setPadding(0, 0, 0, 0);
        addDir.setContentDescription(L.s("btnImport"));
        addDir.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE);
            try { startActivityForResult(it, 1003); } catch (Throwable e) { MainActivity.say("picker " + e); }
        }});
        dgi.addView(addDir, new android.widget.LinearLayout.LayoutParams(Ux.dp(38), Ux.dp(34)));
        spyDirSpin = new Spinner(this);
        final Runnable[] refill = new Runnable[1];
        refill[0] = new Runnable() { public void run() {
            java.util.ArrayList<String> dirs = spyDirs(pf);
            if (dirs.isEmpty()) dirs.add(L.s("noneItem"));
            StdSpinnerAdapter da = new StdSpinnerAdapter(EditorActivity.this, dirs) {
                @Override String label(String s) {   // full paths are unreadable: show the final directory name only
                    int c = s.lastIndexOf('/');
                    return c >= 0 ? s.substring(c + 1) : s;
                }
            };
            da.spin = spyDirSpin;
            spyDirSpin.setAdapter(da);
        }};
        refill[0].run();
        android.widget.FrameLayout dspW = Ux.wrapSpinner(this, spyDirSpin);
        dgi.addView(dspW, new android.widget.LinearLayout.LayoutParams(0, Ux.dp(38), 1f));
        Button delDir = new Button(this); delDir.setText("✕"); delDir.setMinWidth(0); delDir.setMinimumWidth(0); delDir.setPadding(0, 0, 0, 0);
        delDir.setContentDescription(L.s("btnDelete"));
        delDir.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            Object it = spyDirSpin.getSelectedItem();
            if (it == null || it.toString().equals(L.s("noneItem"))) { MainActivity.say(L.s("noDelTarget")); return; }
            java.util.ArrayList<String> l2 = spyDirs(pf); l2.remove(it.toString());
            pf.edit().putString("spy_dirs", android.text.TextUtils.join("\n", l2)).commit();
            refill[0].run();
        }});
        android.widget.LinearLayout.LayoutParams ddlp = new android.widget.LinearLayout.LayoutParams(Ux.dp(38), Ux.dp(34));
        ddlp.leftMargin = Ux.dp(4);
        dgi.addView(delDir, ddlp);
        TextView dgl2 = new TextView(this); dgl2.setText(L.s("spyDirs")); Ux.styleHeader(dgl2);
        dg.setClipChildren(false);
        android.widget.FrameLayout.LayoutParams dgil = new android.widget.FrameLayout.LayoutParams(-1, -2);
        dgil.topMargin = Ux.dp(10);
        dg.addView(dgi, dgil);
        android.widget.FrameLayout.LayoutParams dgll = new android.widget.FrameLayout.LayoutParams(-2, -2);
        dgll.leftMargin = Ux.dp(10);
        dgll.topMargin = Ux.dp(10) - Ux.dp(1) - (int) (Ux.BODY * 0.5f * Ux.tscale());
        dg.addView(dgl2, dgll);
        body.addView(dg, new LinearLayout.LayoutParams(-1, -2));
        Ux.themeTree(body);   // theme BEFORE mounting the title bar: themeTree would recolor the white title text
        box.addView(body, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setBackground(Ux.navySunkenBar());
        tb.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tb.setPadding(Ux.dp(6), Ux.dp(1), Ux.dp(1), Ux.dp(1));
        TextView tico = new TextView(this); tico.setText("\uD83D\uDD75\uFE0F"); tico.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(18)); tico.setTextColor(0xFF000000);   // same glyph as the title-bar button
        tico.setGravity(android.view.Gravity.CENTER);
        tb.addView(tico, new android.widget.LinearLayout.LayoutParams(-2, -2));
        TextView ttl = new TextView(this); ttl.setText(L.s("spyMode")); ttl.setTextColor(0xFFFFFFFF);
        ttl.getPaint().setFakeBoldText(true); ttl.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(14));
        android.widget.LinearLayout.LayoutParams ttlp = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
        ttlp.leftMargin = Ux.dp(6);
        tb.addView(ttl, ttlp);
        Button x = new Button(this); x.setText("✕");
        Ux.styleButton(x);
        x.setPadding(0, 0, Ux.dp(5), Ux.dp(5));
        x.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dlg.dismiss(); }});
        android.widget.LinearLayout.LayoutParams xlp = new android.widget.LinearLayout.LayoutParams(Ux.dp(34), Ux.dp(34));
        xlp.gravity = android.view.Gravity.CENTER_VERTICAL;
        tb.addView(x, xlp);
        box.addView(tb, 0, new LinearLayout.LayoutParams(-1, -2));
        dlg.setContentView(box);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.getDecorView().setPadding(0, 0, 0, 0);
            w.setBackgroundDrawable(Ux.frame98());
            w.setLayout(Math.min(Ux.dp(420), getResources().getDisplayMetrics().widthPixels - Ux.dp(24)), -2);
        }
        dlg.show();
    }

    /** ACTION_OPEN_DOCUMENT_TREE result → real filesystem path on the primary volume ("primary:DCIM/Camera" → /sdcard/DCIM/Camera) */
    static String treePath(android.net.Uri uri) {
        try {
            String id = android.provider.DocumentsContract.getTreeDocumentId(uri);
            int c = id.indexOf(':');
            String vol = c > 0 ? id.substring(0, c) : "primary";
            String sub = (c >= 0 && id.length() > c + 1) ? id.substring(c + 1) : "";
            if (!"primary".equals(vol)) return null;   // only the primary volume is watchable
            String base = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            return sub.length() == 0 ? base : new java.io.File(base, sub).getAbsolutePath();
        } catch (Throwable t) { return null; }
    }

    static java.util.ArrayList<String> spyDirs(android.content.SharedPreferences pf) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        String s = pf.getString("spy_dirs", "");
        if (s.length() > 0) for (String p : s.split("\n")) { p = p.trim(); if (p.length() > 0) out.add(p); }
        return out;
    }

    @Override protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1003) {   // spy: the system folder picker returned — store the real path, reopen, preselect
            if (res != android.app.Activity.RESULT_OK || data == null || data.getData() == null) return;
            String p = treePath(data.getData());
            if (p == null || !new java.io.File(p).isDirectory()) return;
            android.content.SharedPreferences pf = getSharedPreferences("of6k", 0);
            java.util.ArrayList<String> l2 = spyDirs(pf);
            if (!l2.contains(p)) {
                l2.add(p);
                pf.edit().putString("spy_dirs", android.text.TextUtils.join("\n", l2)).commit();
            }
            if (spyDlg != null && spyDlg.isShowing()) {   // stay in the dialog, rebuild + preselect the new folder
                spyDlg.dismiss();
                showSpy();
                if (spyDirSpin != null && spyDirSpin.getAdapter() != null)
                    for (int i = 0; i < spyDirSpin.getAdapter().getCount(); i++)
                        if (p.equals(spyDirSpin.getAdapter().getItem(i).toString())) { spyDirSpin.setSelection(i); break; }
            }
            return;
        }
        if (res != android.app.Activity.RESULT_OK || data == null || data.getData() == null) return;
        String dir = req == 1001 ? CUSTOM_SCENES : CUSTOM_LUTS;
        String ext = req == 1001 ? ".jpg" : ".cube";
        String name = null;
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(data.getData(), null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ci = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (ci >= 0) name = c.getString(ci);
            }
        } catch (Throwable ig) {} finally { if (c != null) c.close(); }
        if (name == null) name = "imported" + ext;
        name = name.replaceAll("[/\\\\:]", "_");
        if (req == 1002 && !name.toLowerCase(java.util.Locale.US).endsWith(".cube")) {
            MainActivity.say(L.s("notCube") + name);
            return;
        }
        if (req == 1001 && !name.toLowerCase(java.util.Locale.US).endsWith(".jpg")
                && !name.toLowerCase(java.util.Locale.US).endsWith(".jpeg")) {
            MainActivity.say(L.s("notJpg") + name);
            return;
        }
        new java.io.File(dir).mkdirs();
        java.io.File dst = new java.io.File(dir, name);
        try {
            java.io.InputStream in = getContentResolver().openInputStream(data.getData());
            java.io.FileOutputStream fo = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            in.close(); fo.close();
            MainActivity.say(L.s("imported") + name);
        } catch (Throwable t) { MainActivity.say(L.s("importFail") + t); return; }
        String selName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        if (settingsDlg != null && settingsDlg.isShowing()) {   // stay in the dialog, rebuild + preselect the new asset
            settingsDlg.dismiss();
            showSettings();
            Spinner t2 = req == 1001 ? setSceneSpin : setLutSpin;
            if (t2 != null && t2.getAdapter() != null)
                for (int i = 0; i < t2.getAdapter().getCount(); i++)
                    if (selName.equals(t2.getAdapter().getItem(i).toString())) { t2.setSelection(i); break; }
        }
        refreshSceneSpinner(sceneTag());
        buildNodeUI();   // LUT picker picks up new custom cubes
    }

    private void renderPreview() {
        final String film = selFilm();
        if (film == null) return;
        if (dnPreview && !memSplit) dnPreview = false;   // safety: never stage empty day/night slots for a non-split film
        if (dnPreview) {   // final-effect preview: EXIF score interpolation over the IN-MEMORY day/night slots
            final String src2 = sceneFile();
            final boolean auto = dnAuto != null && dnAuto.isChecked();
            float sc = auto ? Engine.dayNightScore(src2) : manualScore;
            if (auto) syncSliderToPhoto();   // scene switch: slider follows the new photo's score
            else dnVal.setText(String.format(Locale.US, "%.2f", manualScore));
            Engine.scoreOverride = auto ? null : Float.valueOf(manualScore);
            commitSlot();
            try {
                String dayLut = Films.s(film + ".day", "lut", Films.s(film, "lut", "lut.cube"));
                String nightLut = Films.s(film + ".night", "lut", dayLut);
                writeTmpPipeline("EDITTMP", null, dayLut, true);                 // base: split flag only
                writeTmpPipeline("EDITTMP.day", nodesDay, dayLut, false);
                writeTmpPipeline("EDITTMP.night", nodesNight, nightLut, false);
                Films.reload("EDITTMP"); Films.reload("EDITTMP.day"); Films.reload("EDITTMP.night");
            } catch (Throwable e2) { MainActivity.say("tmp pipeline " + e2); }
            final String pvFilm = "EDITTMP";
            new Thread(new Runnable() { public void run() {
                Bitmap adj2 = null, ref2 = null;
                try {
                    String out2 = "/sdcard/OpenFilm6K/edit_preview.jpg";
                    java.io.File ps2 = Server.pvStampSource(src2, film);
                    Engine.get().process(ps2.getAbsolutePath(), pvFilm, out2);   // watermark burned BEFORE the pipeline (incl. LUT)
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    adj2 = BitmapFactory.decodeFile(out2, o);
                    ref2 = BitmapFactory.decodeFile(src2, o);   // reference is always the original
                } catch (Throwable t2) { MainActivity.say("preview EX " + t2); }
                final Bitmap fAdj = adj2, fRef = ref2;
                runOnUiThread(new Runnable() { public void run() {
                    split.setImages(fRef != null ? fRef : split.getRefBitmap(), fAdj);
                }});
                Engine.scoreOverride = null;
            }}, "dnpreview").start();
            if (auto) android.widget.Toast.makeText(this, L.s("photoScore") + String.format(java.util.Locale.US, "%.2f", sc), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (film == null) return;
        final String src = sceneFile();
        final String chain = chainStr();
        final Map<String, Float> prm = allParams();
        new Thread(new Runnable() { public void run() {
            Bitmap ref = null, adj = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                ref = BitmapFactory.decodeFile(src, o);   // reference is always the original
                File tmpDir = new File(Films.ROOT + "/films", "EDITTMP");
                tmpDir.mkdirs();
                String lutRef = memLut != null ? memLut : Films.s(selFilm(), "lut", editKey() + ".cube");   // memory first, then stored
                java.io.File lsrc = lutRef == null ? null : Films.lutFileByName(selFilm(), lutRef);
                // no LUT for this film (or its file vanished): stage with no lut= key at all so the
                // engine renders the chain without colour grading, instead of throwing on a missing copy.
                String lutLine = lsrc == null ? "" : "lut=lut.cube\n";
                if (lsrc != null) copy(lsrc, new File(tmpDir, "lut.cube"));
                StringBuilder props = new StringBuilder(lutLine).append("chain=").append(chain).append('\n');
                for (Map.Entry<String, Float> e : prm.entrySet())
                    props.append(e.getKey()).append('=').append(e.getValue()).append('\n');
                for (Node nd2 : nodes) if (nd2.type.equals("overlay") && nd2.list.length() > 0)
                    props.append("overlay@").append(nd2.id).append(".list=").append(nd2.list).append('\n');
                for (Node nd2 : nodes) if (nd2.type.equals("lut") && nd2.file != null)
                    props.append("lut@").append(nd2.id).append(".file=").append(nd2.file).append('\n');
                FileOutputStream fo = new FileOutputStream(new File(Films.PIPE, "EDITTMP.properties"));
                fo.write(props.toString().getBytes()); fo.close();
                Films.reload("EDITTMP");
                String out = "/sdcard/OpenFilm6K/edit_preview.jpg";
                java.io.File ps = Server.pvStampSource(src, film);
                String r = Engine.get().process(ps.getAbsolutePath(), "EDITTMP", out);   // watermark burned BEFORE the pipeline (incl. LUT)
                if (r != null) adj = BitmapFactory.decodeFile(out);
            } catch (Throwable t) { MainActivity.say("preview EX " + t); }
            final Bitmap fRef = ref, fAdj = adj;
            runOnUiThread(new Runnable() { public void run() {
                split.setImages(fRef, fAdj);
            }});
        }}, "editpreview").start();
    }

    private static void copy(File s, File d) throws Exception {
        FileInputStream in = new FileInputStream(s);
        FileOutputStream out = new FileOutputStream(d);
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close(); out.close();
    }
}

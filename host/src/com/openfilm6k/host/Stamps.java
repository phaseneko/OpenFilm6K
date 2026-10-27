package com.openfilm6k.host;

import java.util.ArrayList;
import java.util.List;

/**
 * The one stamp / border list used everywhere: custom watermark cards first (by name)
 * like custom films, then the 15 stock codes. Codes: "WM:&lt;id&gt;" for cards, "0".."14" for stock.
 */
final class Stamps {
    static final class Entry {
        final String code;
        final String label;
        final String wmId;   // non-null for a custom watermark card

        Entry(String code, String label, String wmId) {
            this.code = code;
            this.label = label;
            this.wmId = wmId;
        }
    }

    /** divider row between the custom cards and the stock codes (display only) */
    static final String DIV = "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500";

    static boolean isDiv(Entry e) { return e != null && DIV.equals(e.label); }

    private Stamps() {}

    static List<Entry> list() {
        List<Entry> out = new ArrayList<Entry>();
        List<String> ids = new ArrayList<String>(Watermark.ids());   // canonical order: oldest first
        boolean anyCards = !ids.isEmpty();
        for (String id : ids) {
            Watermark.Tpl t = Watermark.load(id);
            String nm = t == null ? id : t.name;
            out.add(new Entry("WM:" + id, "\u25a3 " + nm, id));
        }
        if (anyCards) out.add(new Entry("", DIV, null));
        String[] labels = {L.s("stNone"), L.s("stD"), L.s("stE"), L.s("stDE"), L.s("stB1"), L.s("stB2"),
                L.s("stF"), L.s("stFE"), L.s("stHalf"), L.s("stDouble"), L.s("stCollage"), L.s("stWide"),
                L.s("stWB1"), L.s("stWB2"), L.s("stWB3")};
        for (int i = 0; i < labels.length; i++) out.add(new Entry(String.valueOf(i), labels[i], null));
        return out;
    }

    /** spinner index for the persisted (stock code, wm id) pair */
    static int indexFor(List<Entry> es, int stock, String wmId) {
        if (wmId != null && wmId.length() > 0) {
            for (int i = 0; i < es.size(); i++) if (!isDiv(es.get(i)) && wmId.equals(es.get(i).wmId)) return i;
        }
        String code = String.valueOf(stock);
        for (int i = 0; i < es.size(); i++) {
            Entry e = es.get(i);
            if (!isDiv(e) && e.wmId == null && e.code.equals(code)) return i;
        }
        for (int i = 0; i < es.size(); i++) {
            Entry e = es.get(i);
            if (e.wmId == null && "0".equals(e.code)) return i;
        }
        return 0;
    }
}

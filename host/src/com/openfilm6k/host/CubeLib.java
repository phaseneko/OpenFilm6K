package com.openfilm6k.host;

import java.io.*;
import java.util.*;

/** .cube 3D LUT parser -> float[3*N*N*N] (RGB triplets, R fastest) */
public class CubeLib {
    public static class Lut { public int n; public float[] data; }

    public static Lut parseCube(File f) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
        String ln; int n = 0;
        List<float[]> v = new ArrayList<float[]>();
        while ((ln = r.readLine()) != null) {
            ln = ln.trim();
            if (ln.length() == 0 || ln.startsWith("#")) continue;
            if (ln.startsWith("TITLE") || ln.startsWith("DOMAIN_")) continue;
            if (ln.startsWith("LUT_3D_SIZE")) { n = Integer.parseInt(ln.split("\\s+")[1]); continue; }
            if (ln.startsWith("LUT_1D_SIZE")) throw new IOException("1D LUT unsupported");
            String[] t = ln.split("\\s+");
            if (t.length < 3) continue;
            try { v.add(new float[]{ Float.parseFloat(t[0]), Float.parseFloat(t[1]), Float.parseFloat(t[2]) }); }
            catch (NumberFormatException e) { /* header junk */ }
        }
        r.close();
        if (n <= 0 || v.size() != n * n * n) throw new IOException("bad cube: n=" + n + " entries=" + v.size());
        CubeLib.Lut lut = new CubeLib.Lut(); lut.n = n; lut.data = new float[v.size() * 3];
        for (int i = 0; i < v.size(); i++) {
            float[] c = v.get(i);
            lut.data[i * 3] = c[0]; lut.data[i * 3 + 1] = c[1]; lut.data[i * 3 + 2] = c[2];
        }
        return lut;
    }

    public static Lut identity(int n) {
        CubeLib.Lut lut = new CubeLib.Lut(); lut.n = n;
        lut.data = new float[n * n * n * 3];
        int i = 0;
        for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
            lut.data[i++] = r / (float) (n - 1);
            lut.data[i++] = g / (float) (n - 1);
            lut.data[i++] = b / (float) (n - 1);
        }
        return lut;
    }
}

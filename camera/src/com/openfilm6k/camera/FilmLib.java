package com.openfilm6k.camera;

/** native engine bridge — .flm films on the SD card, graded JPEG output */
public final class FilmLib {
    static {
        System.loadLibrary("of6k");
    }

    private FilmLib() {}

    /** full load (lattice + textures); returns handle, 0 on failure */
    public static native long nativeLoad(String flmPath, String texDir);
    public static native void nativeFree(long film);
    public static native String nativeName(long film);

    /** @return 0 ok, 1 io error, 2 format error */
    public static native int nativeProcess(long film, String in, String out,
                                           int scaleDenom, int quality);
}

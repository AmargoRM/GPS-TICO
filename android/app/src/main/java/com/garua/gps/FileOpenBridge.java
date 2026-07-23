package com.garua.gps;

/**
 * Guarda el contenido de un archivo con el que se abrió la app (GeoJSON/GPX)
 * para que JavaScript lo consuma e importe. En arranque en caliente lo entrega
 * al plugin como evento.
 */
public final class FileOpenBridge {
    private static GnssPlugin plugin;
    private static String pendingName;
    private static String pendingKind;   // "geojson" | "gpx"
    private static String pendingText;

    private FileOpenBridge() {}

    public static void register(GnssPlugin p) { plugin = p; }
    public static void unregister(GnssPlugin p) { if (plugin == p) plugin = null; }

    // Arranque en frío: se guarda hasta que JS lo pida con consume().
    public static void setPending(String name, String kind, String text) {
        if (text == null) return;
        pendingName = name; pendingKind = kind; pendingText = text;
    }

    // Arranque en caliente: si el plugin (y JS) están listos, emite; si no, guarda.
    public static void deliver(String name, String kind, String text) {
        if (text == null) return;
        if (plugin != null) plugin.emitFileOpened(name, kind, text);
        else setPending(name, kind, text);
    }

    public static String[] consume() {
        if (pendingText == null) return null;
        String[] r = new String[]{ pendingName, pendingKind, pendingText };
        pendingName = null; pendingKind = null; pendingText = null;
        return r;
    }
}

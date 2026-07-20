package com.garua.gps;

/**
 * Puente entre el widget de la pantalla de inicio (GpsTicoWidget) y el
 * GnssPlugin. En arranque en frío guarda la acción como "pendiente" y JS la
 * consume al iniciar; en arranque en caliente la entrega al plugin, que la
 * reenvía a JavaScript como evento 'widgetAction'.
 */
public final class WidgetActionBridge {
    private static GnssPlugin plugin;
    private static String pending;

    private WidgetActionBridge() {}

    public static void register(GnssPlugin p) {
        plugin = p;
    }

    public static void unregister(GnssPlugin p) {
        if (plugin == p) plugin = null;
    }

    /** Arranque en frío: se guarda hasta que JS lo pida con consume(). */
    public static void setPending(String action) {
        pending = action;
    }

    /** Arranque en caliente: si el plugin está listo, emite; si no, guarda. */
    public static void deliver(String action) {
        if (plugin != null) {
            plugin.emitWidgetAction(action);
        } else {
            pending = action;
        }
    }

    public static String consume() {
        String a = pending;
        pending = null;
        return a;
    }
}

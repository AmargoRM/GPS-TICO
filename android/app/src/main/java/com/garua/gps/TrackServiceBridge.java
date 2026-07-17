package com.garua.gps;

/**
 * Puente estático mínimo entre el TrackForegroundService (que corre fuera del
 * plugin) y el GnssPlugin, para reenviar a JavaScript el evento de "detener"
 * disparado desde la notificación persistente.
 */
public final class TrackServiceBridge {
    private static GnssPlugin plugin;

    private TrackServiceBridge() {}

    public static void register(GnssPlugin p) {
        plugin = p;
    }

    public static void unregister(GnssPlugin p) {
        if (plugin == p) plugin = null;
    }

    public static void notifyStopRequested() {
        if (plugin != null) {
            plugin.onStopRequestedFromNotification();
        }
    }
}

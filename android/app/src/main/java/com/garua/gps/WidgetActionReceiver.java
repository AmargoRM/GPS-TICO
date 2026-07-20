package com.garua.gps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/**
 * Recibe los toques del widget y los manda al servicio nativo, que graba el
 * track y marca puntos SIN abrir la app. Los datos quedan en archivos que la
 * app importa a IndexedDB cuando se abre.
 */
public class WidgetActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent != null ? intent.getStringExtra("widget_action") : null;
        if (action == null) return;

        Intent svc = new Intent(ctx, TrackForegroundService.class);
        switch (action) {
            case "start": svc.setAction(TrackForegroundService.ACTION_TRACK_START); break;
            case "punto": svc.setAction(TrackForegroundService.ACTION_POINT); break;
            case "stop":  svc.setAction(TrackForegroundService.ACTION_TRACK_STOP); break;
            default: return;
        }
        try {
            ContextCompat.startForegroundService(ctx, svc);
        } catch (Exception ignored) {}
    }
}

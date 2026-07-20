package com.garua.gps;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * Widget de la pantalla de inicio con tres botones: Iniciar track, Marcar
 * punto y Detener. Cada botón abre MainActivity con una acción; la app la
 * ejecuta y (para track) sigue en segundo plano con el servicio de la Fase 4.
 */
public class GpsTicoWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            mgr.updateAppWidget(id, build(context));
        }
    }

    static RemoteViews build(Context ctx) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_gps_tico);
        rv.setOnClickPendingIntent(R.id.wStart, pi(ctx, "start"));
        rv.setOnClickPendingIntent(R.id.wPunto, pi(ctx, "punto"));
        rv.setOnClickPendingIntent(R.id.wStop, pi(ctx, "stop"));
        return rv;
    }

    private static PendingIntent pi(Context ctx, String action) {
        // Broadcast: el receiver decide si abre la app o la maneja en 2do plano.
        Intent i = new Intent(ctx, WidgetActionReceiver.class);
        i.setAction("com.garua.gps.WIDGET_" + action);
        i.putExtra("widget_action", action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, action.hashCode(), i, flags);
    }
}

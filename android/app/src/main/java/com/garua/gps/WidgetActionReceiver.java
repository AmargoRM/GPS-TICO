package com.garua.gps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Recibe los toques del widget. Si la app está viva (proceso con el plugin
 * cargado, típico mientras se graba un track en segundo plano), entrega la
 * acción a JavaScript SIN abrir la interfaz. Si la app no está viva, para
 * 'start'/'punto' abre MainActivity (se necesita el WebView para grabar).
 */
public class WidgetActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent != null ? intent.getStringExtra("widget_action") : null;
        if (action == null) return;

        if (WidgetActionBridge.isAppAlive()) {
            WidgetActionBridge.deliver(action);
        } else if (!"stop".equals(action)) {
            Intent i = new Intent(ctx, MainActivity.class);
            i.setAction("com.garua.gps.WIDGET_" + action);
            i.putExtra("widget_action", action);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(i);
        }
    }
}

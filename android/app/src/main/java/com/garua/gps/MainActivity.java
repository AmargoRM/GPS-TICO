package com.garua.gps;

import android.content.Intent;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Registrar plugins nativos antes de super.
        registerPlugin(KeepAwakePlugin.class);
        registerPlugin(GnssPlugin.class);
        super.onCreate(savedInstanceState);
        // Acción del widget en arranque en frío: se guarda y JS la consume al iniciar.
        String action = getIntent() != null ? getIntent().getStringExtra("widget_action") : null;
        if (action != null) WidgetActionBridge.setPending(action);
        manejarArchivo(getIntent(), true);   // frío: guardar pendiente
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // App ya viva (arranque en caliente): entregar la acción al plugin → JS.
        String action = intent != null ? intent.getStringExtra("widget_action") : null;
        if (action != null) WidgetActionBridge.deliver(action);
        manejarArchivo(intent, false);       // caliente: entregar al plugin
    }

    // Abrió un archivo con GPS TICO (GeoJSON / GPX): leerlo y pasarlo a JS.
    private void manejarArchivo(Intent intent, boolean frio) {
        if (intent == null) return;
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) return;
        android.net.Uri uri = intent.getData();
        if (uri == null) return;
        try {
            String nombre = nombreDe(uri);
            String lower = nombre != null ? nombre.toLowerCase() : "";
            String kind = lower.endsWith(".gpx") ? "gpx" : lower.endsWith(".kml") ? "kml" : "geojson";
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String texto = new String(bos.toByteArray(), "UTF-8");
            if (kind.equals("geojson") && texto.contains("<gpx")) kind = "gpx";
            if (kind.equals("geojson") && (texto.contains("<kml") || texto.contains("<Placemark"))) kind = "kml";
            String nom = nombre != null ? nombre : "archivo";
            if (frio) FileOpenBridge.setPending(nom, kind, texto);
            else FileOpenBridge.deliver(nom, kind, texto);
        } catch (Exception ignored) {}
    }

    private String nombreDe(android.net.Uri uri) {
        String nombre = null;
        try {
            if ("content".equals(uri.getScheme())) {
                android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
                if (c != null) {
                    int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (c.moveToFirst() && i >= 0) nombre = c.getString(i);
                    c.close();
                }
            } else {
                nombre = uri.getLastPathSegment();
            }
        } catch (Exception ignored) {}
        return nombre;
    }
}

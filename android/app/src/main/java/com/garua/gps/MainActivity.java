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

    // Abrió un archivo con GPS TICO (GeoJSON / GPX / KML): pasarlo a JS.
    // Los GeoJSON se copian a caché en streaming y se pasa la RUTA, para que
    // JS los transmita con el motor compacto sin cargar 80 MB+ en memoria
    // (leerlos completos a un String desbordaba el proceso y cerraba la app).
    private void manejarArchivo(Intent intent, boolean frio) {
        if (intent == null) return;
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) return;
        android.net.Uri uri = intent.getData();
        if (uri == null) return;
        try {
            String nombre = nombreDe(uri);
            String lower = nombre != null ? nombre.toLowerCase() : "";
            String nom = nombre != null ? nombre : "archivo";
            boolean esGeojson = lower.endsWith(".geojson") || lower.endsWith(".json");
            if (esGeojson) {
                java.io.File out = new java.io.File(getCacheDir(), "import_" + System.currentTimeMillis() + ".geojson");
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) return;
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                fos.flush(); fos.close(); in.close();
                if (frio) FileOpenBridge.setPending(nom, "geojsonpath", out.getAbsolutePath());
                else FileOpenBridge.deliver(nom, "geojsonpath", out.getAbsolutePath());
                return;
            }
            // GPX / KML / otros: leer texto con tope de tamaño para no desbordar.
            final int LIMITE = 45 * 1024 * 1024;
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n; boolean grande = false;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > LIMITE) { grande = true; break; }
            }
            in.close();
            if (grande) {
                if (frio) FileOpenBridge.setPending(nom, "toobig", nom);
                else FileOpenBridge.deliver(nom, "toobig", nom);
                return;
            }
            String texto = new String(bos.toByteArray(), "UTF-8");
            String kind = lower.endsWith(".gpx") ? "gpx" : lower.endsWith(".kml") ? "kml" : lower.endsWith(".dxf") ? "dxf" : "geojson";
            if (kind.equals("geojson") && texto.contains("<gpx")) kind = "gpx";
            if (kind.equals("geojson") && (texto.contains("<kml") || texto.contains("<Placemark"))) kind = "kml";
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

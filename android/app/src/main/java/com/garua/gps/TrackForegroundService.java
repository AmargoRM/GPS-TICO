package com.garua.gps;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

/**
 * Servicio en primer plano. Dos modos:
 *  - KEEPALIVE: solo mantiene el proceso vivo (lo usa la app cuando graba en JS).
 *  - Grabación NATIVA (desde el widget): registra ubicaciones y guarda puntos
 *    sin necesidad de abrir la app. Los datos quedan en archivos que la app
 *    importa a IndexedDB al abrirse.
 */
public class TrackForegroundService extends Service {
    public static final String CHANNEL_ID = "gps_tico_track";
    public static final String ALERT_CHANNEL_ID = "gps_tico_alert";
    public static final int NOTIF_ID = 4711;
    public static final int NOTIF_ALERT_ID = 4712;
    public static final String EXTRA_TEXT = "text";

    public static final String ACTION_STOP        = "com.garua.gps.STOP_TRACK";     // desde notificación (app JS)
    public static final String ACTION_KEEPALIVE   = "com.garua.gps.KEEPALIVE";      // app JS: solo mantener vivo
    public static final String ACTION_TRACK_START = "com.garua.gps.TRACK_START";    // widget: grabar nativo
    public static final String ACTION_TRACK_STOP  = "com.garua.gps.TRACK_STOP";     // widget: detener nativo
    public static final String ACTION_POINT       = "com.garua.gps.POINT";          // widget: marcar punto

    // Estado consultable por la app.
    public static volatile boolean grabandoNativo = false;
    public static volatile int puntosNativos = 0;
    public static volatile String provNativo = "";      // "fused" | "gps" | "network"
    public static volatile float ultimaExacNativa = -1; // exactitud del último fix (m)
    public static volatile long ultimoFixNativo = 0;    // timestamp del último fix recibido

    private PowerManager.WakeLock wakeLock;
    private LocationManager lm;
    private LocationListener listener;
    private com.google.android.gms.location.FusedLocationProviderClient fused;
    private com.google.android.gms.location.LocationCallback fusedCb;
    private long trackIni = 0;
    private double[] ultimoPt = null;   // [lat,lon] del último punto grabado (filtro de distancia)
    private long ultimoT = 0;           // timestamp del último punto grabado (filtro anti-salto)
    private android.os.HandlerThread hilo;   // hilo dedicado para los callbacks de ubicación
    private static final float EXAC_MAX = 35f;   // rechazar fixes con exactitud peor a 35 m
    private static final double VEL_MAX = 55.0;  // m/s (~200 km/h): salto físicamente imposible

    @Override
    public void onCreate() {
        super.onCreate();
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_KEEPALIVE;
        if (action == null) action = ACTION_KEEPALIVE;

        // Siempre entrar a primer plano cuanto antes (contrato de FGS).
        entrarPrimerPlano(textoDe(intent, action));

        try {
            switch (action) {
                case ACTION_STOP:
                    // "Detener" de la notificación del track JS de la app.
                    TrackServiceBridge.notifyStopRequested();
                    if (!grabandoNativo) { pararTodo(); return START_NOT_STICKY; }
                    // si además había track nativo, seguimos abajo a detenerlo
                case ACTION_TRACK_STOP:
                    detenerTrackNativo();
                    pararTodo();
                    return START_NOT_STICKY;
                case ACTION_TRACK_START:
                    iniciarTrackNativo();
                    return START_STICKY;
                case ACTION_POINT:
                    capturarPunto();
                    return START_STICKY;
                case ACTION_KEEPALIVE:
                default:
                    acquireWakeLock();
                    return START_STICKY;
            }
        } catch (Exception e) {
            return START_STICKY;
        }
    }

    private String textoDe(Intent intent, String action) {
        if (intent != null && intent.getStringExtra(EXTRA_TEXT) != null) return intent.getStringExtra(EXTRA_TEXT);
        if (ACTION_TRACK_START.equals(action)) return "Grabando track (widget)…";
        if (ACTION_POINT.equals(action)) return "Marcando punto…";
        return "GPS TICO activo";
    }

    // ---------------- Grabación nativa ----------------

    // El track SIEMPRE usa GPS puro (satélites). El proveedor fusionado, con la
    // pantalla apagada, cae a ubicación por celda/WiFi (cientos de metros de
    // error) y produce saltos extremos. Solo si el equipo no tiene GPS se cae a
    // red como último recurso.
    private String proveedor() {
        try {
            if (lm.getAllProviders().contains(LocationManager.GPS_PROVIDER))
                return LocationManager.GPS_PROVIDER;
            if (lm.getAllProviders().contains(LocationManager.NETWORK_PROVIDER))
                return LocationManager.NETWORK_PROVIDER;
        } catch (Exception ignored) {}
        return LocationManager.GPS_PROVIDER;
    }

    @SuppressLint("MissingPermission")
    private void iniciarTrackNativo() {
        acquireWakeLock();
        // Nuevo track: reiniciar archivo.
        trackIni = System.currentTimeMillis();
        ultimoPt = null;
        ultimoT = 0;
        puntosNativos = 0;
        grabandoNativo = true;
        JSONObject t = new JSONObject();
        try { t.put("ini", trackIni); t.put("pts", new JSONArray()); t.put("fuente", "gps"); } catch (Exception ignored) {}
        escribir(archTrack(), t.toString());

        quitarUpdates();
        // Hilo dedicado: los callbacks NO dependen del hilo principal de la app
        // (que se pausa/mata cuando la pantalla se apaga o la app se cierra).
        if (hilo == null || !hilo.isAlive()) {
            hilo = new android.os.HandlerThread("GpsTicoTrack");
            hilo.start();
        }
        // FUENTE PRIMARIA: FusedLocationProviderClient (Play Services) con máxima
        // precisión. Es mucho más fiable que LocationManager crudo para seguir
        // recibiendo fixes con la pantalla apagada / la app en segundo plano en
        // los distintos fabricantes (Xiaomi, Samsung, Huawei…). Filtramos por
        // exactitud igual que antes, así que no entran posiciones de celda/WiFi.
        boolean fusedOk = false;
        try {
            fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this);
            com.google.android.gms.location.LocationRequest req =
                com.google.android.gms.location.LocationRequest.create();
            req.setPriority(com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY);
            req.setInterval(1000L);
            req.setFastestInterval(1000L);
            fusedCb = new com.google.android.gms.location.LocationCallback() {
                @Override public void onLocationResult(com.google.android.gms.location.LocationResult r) {
                    if (r == null) return;
                    Location l = r.getLastLocation();
                    if (l != null) onFixTrack(l);
                }
            };
            fused.requestLocationUpdates(req, fusedCb, hilo.getLooper());
            provNativo = "fused";
            fusedOk = true;
        } catch (Throwable e) { fused = null; fusedCb = null; }
        if (!fusedOk) {
            // Fallback: LocationManager con GPS puro (equipos sin Play Services).
            listener = new LocationListener() {
                @Override public void onLocationChanged(Location loc) { onFixTrack(loc); }
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
                @Override public void onStatusChanged(String p, int s, Bundle b) {}
            };
            try {
                String prov = proveedor();
                provNativo = LocationManager.NETWORK_PROVIDER.equals(prov) ? "network" : "gps";
                lm.requestLocationUpdates(prov, 1000L, 0f, listener, hilo.getLooper());
            } catch (Exception e) { /* sin permiso o proveedor */ }
        }
        updateNotification(this, "Grabando… 0 pts (" + provNativo + ")");
    }

    // Quita cualquier suscripción de ubicación activa (fused y/o LocationManager).
    private void quitarUpdates() {
        if (fusedCb != null && fused != null) { try { fused.removeLocationUpdates(fusedCb); } catch (Exception ignored) {} }
        fusedCb = null; fused = null;
        if (listener != null) { try { lm.removeUpdates(listener); } catch (Exception ignored) {} listener = null; }
    }

    private void onFixTrack(Location loc) {
        if (!grabandoNativo || loc == null) return;
        ultimoFixNativo = System.currentTimeMillis();
        ultimaExacNativa = loc.hasAccuracy() ? loc.getAccuracy() : -1;
        double lat = loc.getLatitude(), lon = loc.getLongitude();
        long tNow = loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis();
        // --- Filtros de calidad (evitan saltos extremos) ---
        // 1) Rechazar fixes con exactitud peor a EXAC_MAX (típico de celda/WiFi).
        if (loc.hasAccuracy() && loc.getAccuracy() > EXAC_MAX) {
            updateNotification(this, "Grabando… " + puntosNativos + " pts (buscando señal)");
            return;
        }
        // 2) Rechazar saltos físicamente imposibles (velocidad implícita irreal).
        if (ultimoPt != null && ultimoT > 0) {
            double d = distanciaM(ultimoPt[0], ultimoPt[1], lat, lon);
            double dt = Math.max(0.001, (tNow - ultimoT) / 1000.0);
            if (d / dt > VEL_MAX) return;   // salto imposible → descartar
            if (d < 1.5) return;            // filtro de distancia mínima
        }
        ultimoPt = new double[]{lat, lon};
        ultimoT = tNow;
        try {
            String s = leer(archTrack());
            JSONObject t = (s != null) ? new JSONObject(s) : new JSONObject();
            JSONArray pts = t.optJSONArray("pts"); if (pts == null) pts = new JSONArray();
            pts.put(fixToArray(loc));
            t.put("pts", pts);
            if (!t.has("ini")) t.put("ini", trackIni);
            escribir(archTrack(), t.toString());
            puntosNativos = pts.length();
        } catch (Exception ignored) {}
        String exacTxt = loc.hasAccuracy() ? (" ±" + Math.round(loc.getAccuracy()) + "m") : "";
        updateNotification(this, "Grabando… " + puntosNativos + " pts" + exacTxt);
    }

    @SuppressLint("MissingPermission")
    private void capturarPunto() {
        acquireWakeLock();
        final boolean eraGrabando = grabandoNativo;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(proveedor(), null, ContextCompat.getMainExecutor(this), loc -> {
                    guardarPunto(loc);
                    if (!eraGrabando && !grabandoNativo) pararTodo();
                });
            } else {
                Location loc = lm.getLastKnownLocation(proveedor());
                guardarPunto(loc);
                if (!eraGrabando && !grabandoNativo) pararTodo();
            }
        } catch (Exception e) {
            if (!eraGrabando && !grabandoNativo) pararTodo();
        }
    }

    private void guardarPunto(Location loc) {
        if (loc == null) { alerta("GPS TICO", "Sin señal para el punto", true); return; }
        try {
            String s = leer(archPuntos());
            JSONArray arr = (s != null) ? new JSONArray(s) : new JSONArray();
            arr.put(fixToArray(loc));
            escribir(archPuntos(), arr.toString());
            updateNotification(this, "Punto guardado (" + arr.length() + ")");
            String exac = loc.hasAccuracy() ? ("  ±" + Math.round(loc.getAccuracy()) + " m") : "";
            alerta("Punto guardado ✓", "Punto " + arr.length() + exac, true);
        } catch (Exception ignored) {}
    }

    // Aviso emergente (heads-up) arriba + vibración, aunque la app esté cerrada.
    private void alerta(String titulo, String texto, boolean vibrar) {
        if (vibrar) {
            try {
                android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    if (Build.VERSION.SDK_INT >= 26)
                        v.vibrate(android.os.VibrationEffect.createOneShot(140, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                    else v.vibrate(140);
                }
            } catch (Exception ignored) {}
        }
        createAlertChannel();
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setAutoCancel(true);
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(4000);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) try { nm.notify(NOTIF_ALERT_ID, b.build()); } catch (Exception ignored) {}
    }
    private void createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(ALERT_CHANNEL_ID, "Avisos GPS TICO", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Confirmaciones al marcar puntos desde el widget.");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void detenerTrackNativo() {
        grabandoNativo = false;
        quitarUpdates();
        if (hilo != null) { try { hilo.quitSafely(); } catch (Exception ignored) {} hilo = null; }
    }

    private JSONArray fixToArray(Location loc) {
        JSONArray a = new JSONArray();
        try {
            a.put(loc.getLatitude());
            a.put(loc.getLongitude());
            a.put(loc.hasAltitude() ? loc.getAltitude() : JSONObject.NULL);
            a.put(loc.hasAccuracy() ? (double) loc.getAccuracy() : JSONObject.NULL);
            a.put((Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy()) ? (double) loc.getVerticalAccuracyMeters() : JSONObject.NULL);
            a.put(loc.getTime());
        } catch (org.json.JSONException e) { /* coordenada inválida (NaN); se ignora */ }
        return a;
    }

    private static double distanciaM(double la1, double lo1, double la2, double lo2) {
        double R = 6371000, dLat = Math.toRadians(la2-la1), dLon = Math.toRadians(lo2-lo1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2) + Math.cos(Math.toRadians(la1))*Math.cos(Math.toRadians(la2))*Math.sin(dLon/2)*Math.sin(dLon/2);
        return 2*R*Math.asin(Math.min(1, Math.sqrt(a)));
    }

    // ---------------- Archivos (para importar en la app) ----------------
    private File archTrack()  { return new File(getFilesDir(), "pending_track.json"); }
    private File archPuntos() { return new File(getFilesDir(), "pending_points.json"); }
    private void escribir(File f, String s) {
        try (FileWriter w = new FileWriter(f, false)) { w.write(s); } catch (Exception ignored) {}
    }
    private String leer(File f) {
        if (!f.exists()) return null;
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b); in.close();
            return n > 0 ? new String(b, 0, n, "UTF-8") : null;
        } catch (Exception e) { return null; }
    }

    // ---------------- Primer plano / wakelock / notificación ----------------
    private void entrarPrimerPlano(String text) {
        createChannel();
        Notification notif = baseBuilder(this, text).build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, notif);
            }
        } catch (Exception e) {
            try { startForeground(NOTIF_ID, notif); } catch (Exception ignored) {}
        }
    }

    private void pararTodo() {
        detenerTrackNativo();
        if (wakeLock != null && wakeLock.isHeld()) { try { wakeLock.release(); } catch (Exception ignored) {} }
        wakeLock = null;
        try { stopForeground(true); } catch (Exception ignored) {}
        stopSelf();
    }

    public static void updateNotification(Context ctx, String text) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try { nm.notify(NOTIF_ID, baseBuilder(ctx, text).build()); } catch (Exception ignored) {}
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GpsTico:TrackWakeLock");
        wakeLock.setReferenceCounted(false);
        try { wakeLock.acquire(); } catch (Exception ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Grabación de track", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Mantiene la grabación activa con la pantalla apagada.");
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private static NotificationCompat.Builder baseBuilder(Context ctx, String text) {
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openPi = PendingIntent.getActivity(ctx, 0, open, flags);

        Intent stop = new Intent(ctx, TrackForegroundService.class);
        stop.setAction(ACTION_TRACK_STOP);
        PendingIntent stopPi = PendingIntent.getService(ctx, 1, stop, flags);

        return new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("GPS TICO — grabando")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPi);
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) { try { wakeLock.release(); } catch (Exception ignored) {} }
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

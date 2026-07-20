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
    public static final int NOTIF_ID = 4711;
    public static final String EXTRA_TEXT = "text";

    public static final String ACTION_STOP        = "com.garua.gps.STOP_TRACK";     // desde notificación (app JS)
    public static final String ACTION_KEEPALIVE   = "com.garua.gps.KEEPALIVE";      // app JS: solo mantener vivo
    public static final String ACTION_TRACK_START = "com.garua.gps.TRACK_START";    // widget: grabar nativo
    public static final String ACTION_TRACK_STOP  = "com.garua.gps.TRACK_STOP";     // widget: detener nativo
    public static final String ACTION_POINT       = "com.garua.gps.POINT";          // widget: marcar punto

    // Estado consultable por la app.
    public static volatile boolean grabandoNativo = false;
    public static volatile int puntosNativos = 0;

    private PowerManager.WakeLock wakeLock;
    private LocationManager lm;
    private LocationListener listener;
    private long trackIni = 0;
    private double[] ultimoPt = null;   // [lat,lon] del último punto grabado (filtro de distancia)

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

    private String proveedor() {
        SharedPreferences sp = getSharedPreferences("gps_tico_prefs", MODE_PRIVATE);
        String fuente = sp.getString("fuente", "fused");
        try {
            if ("gps".equals(fuente) && lm.getAllProviders().contains(LocationManager.GPS_PROVIDER))
                return LocationManager.GPS_PROVIDER;
            if (Build.VERSION.SDK_INT >= 31 && lm.getAllProviders().contains(LocationManager.FUSED_PROVIDER))
                return LocationManager.FUSED_PROVIDER;
            if (lm.getAllProviders().contains(LocationManager.GPS_PROVIDER))
                return LocationManager.GPS_PROVIDER;
        } catch (Exception ignored) {}
        return LocationManager.GPS_PROVIDER;
    }

    @SuppressLint("MissingPermission")
    private void iniciarTrackNativo() {
        acquireWakeLock();
        // Nuevo track: reiniciar archivo.
        trackIni = System.currentTimeMillis();
        ultimoPt = null;
        puntosNativos = 0;
        grabandoNativo = true;
        JSONObject t = new JSONObject();
        try { t.put("ini", trackIni); t.put("pts", new JSONArray()); t.put("fuente", getSharedPreferences("gps_tico_prefs", MODE_PRIVATE).getString("fuente","fused")); } catch (Exception ignored) {}
        escribir(archTrack(), t.toString());

        if (listener != null) { try { lm.removeUpdates(listener); } catch (Exception ignored) {} }
        listener = new LocationListener() {
            @Override public void onLocationChanged(Location loc) { onFixTrack(loc); }
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
            @Override public void onStatusChanged(String p, int s, Bundle b) {}
        };
        try {
            lm.requestLocationUpdates(proveedor(), 1000L, 0f, listener, Looper.getMainLooper());
        } catch (Exception e) { /* sin permiso o proveedor */ }
        updateNotification(this, "Grabando… 0 pts");
    }

    private void onFixTrack(Location loc) {
        if (!grabandoNativo || loc == null) return;
        double lat = loc.getLatitude(), lon = loc.getLongitude();
        if (ultimoPt != null) {
            double d = distanciaM(ultimoPt[0], ultimoPt[1], lat, lon);
            if (d < 3.0) return; // filtro de distancia mínima
        }
        ultimoPt = new double[]{lat, lon};
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
        updateNotification(this, "Grabando… " + puntosNativos + " pts");
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
        if (loc == null) { updateNotification(this, "Sin señal para el punto"); return; }
        try {
            String s = leer(archPuntos());
            JSONArray arr = (s != null) ? new JSONArray(s) : new JSONArray();
            arr.put(fixToArray(loc));
            escribir(archPuntos(), arr.toString());
            updateNotification(this, "Punto guardado (" + arr.length() + ")");
        } catch (Exception ignored) {}
    }

    private void detenerTrackNativo() {
        grabandoNativo = false;
        if (listener != null) { try { lm.removeUpdates(listener); } catch (Exception ignored) {} listener = null; }
    }

    private JSONArray fixToArray(Location loc) {
        JSONArray a = new JSONArray();
        a.put(loc.getLatitude());
        a.put(loc.getLongitude());
        a.put(loc.hasAltitude() ? loc.getAltitude() : JSONObject.NULL);
        a.put(loc.hasAccuracy() ? loc.getAccuracy() : JSONObject.NULL);
        a.put((Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy()) ? loc.getVerticalAccuracyMeters() : JSONObject.NULL);
        a.put(loc.getTime());
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

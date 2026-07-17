package com.garua.gps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/**
 * Servicio en primer plano que mantiene el proceso vivo (y la CPU despierta
 * con un wakelock parcial) mientras se graba un track, para que Android no
 * mate la app al apagar la pantalla. La lectura de GPS la sigue haciendo el
 * GnssPlugin (LocationManager); este servicio solo garantiza que el proceso
 * no muera y muestra la notificación persistente.
 */
public class TrackForegroundService extends Service {
    public static final String CHANNEL_ID = "gps_tico_track";
    public static final int NOTIF_ID = 4711;
    public static final String ACTION_STOP = "com.garua.gps.STOP_TRACK";
    public static final String EXTRA_TEXT = "text";

    private PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // El usuario tocó "Detener" en la notificación.
            TrackServiceBridge.notifyStopRequested();
            stopSelf();
            return START_NOT_STICKY;
        }

        String text = "Grabando track…";
        if (intent != null && intent.getStringExtra(EXTRA_TEXT) != null) {
            text = intent.getStringExtra(EXTRA_TEXT);
        }

        createChannel();
        Notification notif = buildNotification(text);

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, notif);
        }

        acquireWakeLock();
        return START_STICKY;
    }

    /** Actualiza el texto de la notificación (ej. "3 min · 240 m"). */
    public static void updateNotification(Context ctx, String text) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationCompat.Builder b = baseBuilder(ctx, text);
        nm.notify(NOTIF_ID, b.build());
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GpsTico:TrackWakeLock");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Grabación de track",
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Mantiene la grabación activa con la pantalla apagada.");
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        return baseBuilder(this, text).build();
    }

    private static NotificationCompat.Builder baseBuilder(Context ctx, String text) {
        // Intent para reabrir la app al tocar la notificación.
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openPi = PendingIntent.getActivity(ctx, 0, open, flags);

        // Acción "Detener".
        Intent stop = new Intent(ctx, TrackForegroundService.class);
        stop.setAction(ACTION_STOP);
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
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

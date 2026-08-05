package com.garua.gps;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.concurrent.Executors;

@CapacitorPlugin(name = "Gnss")
public class GnssPlugin extends Plugin {
    private LocationManager locationManager;
    private GnssStatus.Callback gnssStatusCallback;
    private LocationListener locationListener;
    private OnNmeaMessageListener nmeaListener;
    private boolean isListening = false;

    @Override
    public void load() {
        locationManager = (LocationManager) getContext().getSystemService(android.content.Context.LOCATION_SERVICE);
        TrackServiceBridge.register(this);
        WidgetActionBridge.register(this);
        FileOpenBridge.register(this);
    }

    @Override
    protected void handleOnDestroy() {
        TrackServiceBridge.unregister(this);
        WidgetActionBridge.unregister(this);
        FileOpenBridge.unregister(this);
        super.handleOnDestroy();
    }

    // ---- Abrir archivos GeoJSON/GPX asociados a la app ----
    public void emitFileOpened(String name, String kind, String text) {
        JSObject o = new JSObject();
        o.put("name", name); o.put("kind", kind); o.put("text", text);
        notifyListeners("fileOpened", o);
    }

    @PluginMethod
    public void consumePendingFile(PluginCall call) {
        JSObject o = new JSObject();
        String[] f = FileOpenBridge.consume();
        if (f != null) { o.put("name", f[0]); o.put("kind", f[1]); o.put("text", f[2]); }
        call.resolve(o);
    }

    // Descarga un APK (misma firma) a la caché y lanza el instalador del sistema.
    // Permite que la app se actualice sola desde GitHub Releases sin pasar por
    // Play. Requiere el permiso REQUEST_INSTALL_PACKAGES y usa el FileProvider ya
    // configurado (cache-path "." cubre getCacheDir()).
    @PluginMethod
    public void instalarApkDesde(final PluginCall call) {
        final String url = call.getString("url");
        if (url == null || url.isEmpty()) { call.reject("URL vacía"); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                java.net.HttpURLConnection conn = null;
                try {
                    conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(120000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "GPS-TICO/1.0");
                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 400) { call.reject("HTTP " + code); return; }
                    java.io.File dir = new java.io.File(getContext().getCacheDir(), "updates");
                    dir.mkdirs();
                    java.io.File apk = new java.io.File(dir, "gps-tico-update.apk");
                    java.io.InputStream is = conn.getInputStream();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(apk);
                    byte[] buf = new byte[8192]; int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                    fos.close(); is.close();
                    android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        getContext(), getContext().getPackageName() + ".fileprovider", apk);
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    getContext().startActivity(i);
                    call.resolve(new JSObject().put("ok", true));
                } catch (Exception e) {
                    call.reject("No se pudo instalar: " + e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    // Abre una app externa (Waze, Maps, navegador) con una URL/intent.
    @PluginMethod
    public void abrirExterno(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) { call.reject("URL vacía"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("No hay app para abrir eso: " + e.getMessage());
        }
    }

    // ---- Widget de la pantalla de inicio ----

    // Emite la acción del widget a JS (arranque en caliente).
    public void emitWidgetAction(String action) {
        JSObject o = new JSObject();
        o.put("action", action);
        notifyListeners("widgetAction", o);
    }

    // JS lo llama al iniciar para recoger una acción del widget en arranque en frío.
    @PluginMethod
    public void consumePendingWidgetAction(PluginCall call) {
        JSObject o = new JSObject();
        o.put("action", WidgetActionBridge.consume());
        call.resolve(o);
    }

    // Manda la app al segundo plano (tras arrancar un track desde el widget).
    @PluginMethod
    public void moverAppAlFondo(PluginCall call) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(new Runnable() {
                @Override public void run() { getActivity().moveTaskToBack(true); }
            });
        }
        call.resolve();
    }

    // ---- Servicio en primer plano para grabar con pantalla apagada (F4) ----

    @PluginMethod
    public void startForegroundTracking(PluginCall call) {
        try {
            String text = call.getString("text", "Grabando track…");
            // 'track' = graba puntos por sí solo con LocationManager (sobrevive
            // a la pantalla apagada). 'keepalive' = solo mantiene el proceso vivo.
            String modo = call.getString("modo", "keepalive");
            Intent i = new Intent(getContext(), TrackForegroundService.class);
            if ("track".equals(modo)) {
                i.setAction(TrackForegroundService.ACTION_TRACK_START);
            } else {
                i.setAction(TrackForegroundService.ACTION_KEEPALIVE);
            }
            i.putExtra(TrackForegroundService.EXTRA_TEXT, text);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(i);
            } else {
                getContext().startService(i);
            }
            call.resolve(new JSObject().put("status", "started"));
        } catch (Exception e) {
            call.reject("No se pudo iniciar el servicio: " + e.getMessage());
        }
    }
    // Para el track nativo (deja de grabar puntos en background pero mantiene la app viva).
    @PluginMethod
    public void stopNativeTrack(PluginCall call) {
        try {
            Intent i = new Intent(getContext(), TrackForegroundService.class);
            i.setAction(TrackForegroundService.ACTION_TRACK_STOP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(i);
            } else {
                getContext().startService(i);
            }
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage()); }
    }

    // Guarda la fuente (fused/gps) para que el servicio nativo del widget la use.
    @PluginMethod
    public void setFuenteServicio(PluginCall call) {
        try {
            String fuente = call.getString("fuente", "fused");
            getContext().getSharedPreferences("gps_tico_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("fuente", fuente).apply();
            call.resolve();
        } catch (Exception e) { call.resolve(); }
    }

    // Estado del servicio nativo (grabando por widget, cuántos puntos).
    @PluginMethod
    public void estadoServicio(PluginCall call) {
        JSObject o = new JSObject();
        o.put("grabando", TrackForegroundService.grabandoNativo);
        o.put("puntos", TrackForegroundService.puntosNativos);
        call.resolve(o);
    }

    // Lee los archivos que dejó el servicio nativo y los devuelve para importar.
    @PluginMethod
    public void importarPendientes(PluginCall call) {
        JSObject o = new JSObject();
        try {
            java.io.File dir = getContext().getFilesDir();
            java.io.File ft = new java.io.File(dir, "pending_track.json");
            java.io.File fp = new java.io.File(dir, "pending_points.json");
            // No importar un track que se está grabando ahora mismo.
            if (ft.exists() && !TrackForegroundService.grabandoNativo) {
                String s = leerArchivo(ft);
                if (s != null) o.put("track", new org.json.JSONObject(s));
                ft.delete();
            }
            if (fp.exists()) {
                String s = leerArchivo(fp);
                if (s != null) o.put("puntos", new org.json.JSONArray(s));
                fp.delete();
            }
        } catch (Exception e) { /* devolver lo que se pudo */ }
        call.resolve(o);
    }

    private String leerArchivo(java.io.File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b); in.close();
            return n > 0 ? new String(b, 0, n, "UTF-8") : null;
        } catch (Exception e) { return null; }
    }

    @PluginMethod
    public void stopForegroundTracking(PluginCall call) {
        try {
            Intent i = new Intent(getContext(), TrackForegroundService.class);
            getContext().stopService(i);
            call.resolve(new JSObject().put("status", "stopped"));
        } catch (Exception e) {
            call.reject("No se pudo detener el servicio: " + e.getMessage());
        }
    }

    @PluginMethod
    public void updateTrackingNotification(PluginCall call) {
        try {
            String text = call.getString("text", "Grabando track…");
            TrackForegroundService.updateNotification(getContext(), text);
            call.resolve();
        } catch (Exception e) {
            call.reject("No se pudo actualizar la notificación: " + e.getMessage());
        }
    }

    @PluginMethod
    public void requestNotifPermission(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && getActivity() != null) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    getActivity(),
                    new String[]{ android.Manifest.permission.POST_NOTIFICATIONS },
                    9911);
            }
            call.resolve();
        } catch (Exception e) {
            call.resolve();
        }
    }

    // Llamado desde la notificación (botón Detener) vía TrackServiceBridge.
    public void onStopRequestedFromNotification() {
        notifyListeners("stopTrackRequested", new JSObject());
    }

    // ---- HTTP nativo (evita CORS del WebView para WMS/WFS/GeoJSON) ----
    @PluginMethod
    public void httpGet(final PluginCall call) {
        final String url = call.getString("url");
        if (url == null || url.isEmpty()) { call.reject("URL vacía"); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                java.net.HttpURLConnection conn = null;
                try {
                    conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(25000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "GPS-TICO/1.0");
                    conn.setRequestProperty("Accept", "*/*");
                    int code = conn.getResponseCode();
                    java.io.InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n;
                    if (is != null) { while ((n = is.read(buf)) > 0) bos.write(buf, 0, n); is.close(); }
                    String ct = conn.getContentType();
                    String b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
                    JSObject r = new JSObject();
                    r.put("status", code);
                    r.put("contentType", ct == null ? "" : ct);
                    r.put("dataBase64", b64);
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject("http error: " + e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    @PluginMethod
    @SuppressLint("MissingPermission")
    public void startGnssListener(PluginCall call) {
        try {
            if (isListening) {
                call.resolve(new JSObject().put("status", "already_listening"));
                return;
            }

            gnssStatusCallback = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(GnssStatus status) {
                    JSObject satellites = new JSObject();
                    JSArray sats = new JSArray();
                    int usedCount = 0;

                    for (int i = 0; i < status.getSatelliteCount(); i++) {
                        JSObject sat = new JSObject();
                        sat.put("svid", status.getSvid(i));
                        sat.put("constellation", status.getConstellationType(i));
                        sat.put("cn0", status.getCn0DbHz(i));
                        sat.put("elevation", status.getElevationDegrees(i));
                        sat.put("azimuth", status.getAzimuthDegrees(i));

                        boolean used = status.usedInFix(i);
                        sat.put("usedInFix", used);
                        if (used) usedCount++;

                        if (status.hasCarrierFrequencyHz(i)) {
                            long freqHz = (long) status.getCarrierFrequencyHz(i);
                            sat.put("carrierFreq", freqHz);
                            sat.put("isL5", Math.abs(freqHz - 1176450000L) < 100000);
                        }

                        sats.put(sat);
                    }

                    satellites.put("count", status.getSatelliteCount());
                    satellites.put("usedInFix", usedCount);
                    satellites.put("satellites", sats);

                    JSObject data = new JSObject();
                    data.put("data", satellites);
                    data.put("timestamp", System.currentTimeMillis());
                    notifyListeners("gnssStatus", data);
                }
            };

            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    JSObject data = new JSObject();
                    data.put("data", locationToJs(location));
                    data.put("timestamp", System.currentTimeMillis());
                    notifyListeners("location", data);
                }

                @Override
                public void onProviderEnabled(String provider) {}

                @Override
                public void onProviderDisabled(String provider) {}

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}
            };

            // NMEA: parsea GSA (PDOP/HDOP/VDOP) y GGA (separación del geoide).
            nmeaListener = new OnNmeaMessageListener() {
                @Override
                public void onNmeaMessage(String message, long timestamp) {
                    parseNmea(message, timestamp);
                }
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.registerGnssStatusCallback(
                    Executors.newSingleThreadExecutor(),
                    gnssStatusCallback
                );
                locationManager.addNmeaListener(
                    Executors.newSingleThreadExecutor(),
                    nmeaListener
                );
            }

            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                locationListener,
                Looper.getMainLooper()
            );

            isListening = true;
            call.resolve(new JSObject().put("status", "listening"));
        } catch (Exception e) {
            call.reject("Error starting GNSS listener: " + e.getMessage());
        }
    }

    // Serializa un Location con todas las exactitudes que expone Android.
    private JSObject locationToJs(Location location) {
        JSObject j = new JSObject();
        j.put("latitude", location.getLatitude());
        j.put("longitude", location.getLongitude());
        j.put("accuracy", location.getAccuracy());
        j.put("altitude", location.getAltitude());
        j.put("speed", location.getSpeed());
        j.put("bearing", location.getBearing());
        j.put("provider", location.getProvider());
        j.put("timestamp", location.getTime());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (location.hasVerticalAccuracy()) j.put("verticalAccuracy", location.getVerticalAccuracyMeters());
            if (location.hasSpeedAccuracy())    j.put("speedAccuracy", location.getSpeedAccuracyMetersPerSecond());
            if (location.hasBearingAccuracy())  j.put("bearingAccuracy", location.getBearingAccuracyDegrees());
        }
        if (Build.VERSION.SDK_INT >= 31) {
            j.put("isMock", location.isMock());
        } else {
            j.put("isMock", location.isFromMockProvider());
        }
        // Altura ortométrica (sobre el geoide) calculada por el sistema. API 34+.
        if (Build.VERSION.SDK_INT >= 34) {
            if (location.hasMslAltitude()) {
                j.put("mslAltitude", location.getMslAltitudeMeters());
                if (location.hasMslAltitudeAccuracy()) {
                    j.put("mslAltitudeAccuracy", location.getMslAltitudeAccuracyMeters());
                }
            }
        }
        return j;
    }

    // Parsea sentencias NMEA-0183 para extraer DOP y altura.
    // GSA: $--GSA,modo,fix,sv...(12),PDOP,HDOP,VDOP*cs
    // GGA: $--GGA,hora,lat,N,lon,E,calidad,nSats,HDOP,altMSL,M,geoide,M,...*cs
    //   campo 9 = altura sobre el geoide (ORTOMÉTRICA/MSL), directa.
    //   campo 11 = separación del geoide (N). Elipsoidal = MSL + N.
    private void parseNmea(String message, long timestamp) {
        if (message == null || message.length() < 6) return;
        // El talker son 2 chars (GP, GN, GL...); el tipo de sentencia empieza en índice 3.
        String type = message.substring(3, 6);
        String[] f = message.split(",");
        try {
            if (type.equals("GSA") && f.length >= 18) {
                JSObject dop = new JSObject();
                dop.put("pdop", parseFloatSafe(f[15]));
                dop.put("hdop", parseFloatSafe(f[16]));
                // VDOP puede traer el checksum pegado: "1.5*3A"
                String vdopStr = f[17].split("\\*")[0];
                dop.put("vdop", parseFloatSafe(vdopStr));
                JSObject data = new JSObject();
                data.put("data", dop);
                data.put("timestamp", System.currentTimeMillis());
                notifyListeners("dop", data);
            } else if (type.equals("GGA") && f.length >= 12) {
                JSObject gga = new JSObject();
                gga.put("hdop", parseFloatSafe(f[8]));
                gga.put("mslAltitude", parseFloatSafe(f[9]));       // ortométrica directa
                gga.put("geoidSeparation", parseFloatSafe(f[11]));
                gga.put("fixQuality", parseIntSafe(f[6]));
                JSObject data = new JSObject();
                data.put("data", gga);
                data.put("timestamp", System.currentTimeMillis());
                notifyListeners("gga", data);
            }
        } catch (Exception ignored) {}
    }

    private Double parseFloatSafe(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private Integer parseIntSafe(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    @PluginMethod
    public void stopGnssListener(PluginCall call) {
        try {
            if (gnssStatusCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            }
            if (nmeaListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.removeNmeaListener(nmeaListener);
            }
            if (locationListener != null) {
                locationManager.removeUpdates(locationListener);
            }
            isListening = false;
            call.resolve(new JSObject().put("status", "stopped"));
        } catch (Exception e) {
            call.reject("Error stopping GNSS listener: " + e.getMessage());
        }
    }

    @PluginMethod
    @SuppressLint("MissingPermission")
    public void getLastKnownLocation(PluginCall call) {
        try {
            Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (location != null) {
                call.resolve(locationToJs(location));
            } else {
                call.reject("No last known location");
            }
        } catch (Exception e) {
            call.reject("Error getting location: " + e.getMessage());
        }
    }
}

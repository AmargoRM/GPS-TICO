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
    }

    @Override
    protected void handleOnDestroy() {
        TrackServiceBridge.unregister(this);
        WidgetActionBridge.unregister(this);
        super.handleOnDestroy();
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

    // ---- Servicio en primer plano para grabar con pantalla apagada (F4) ----

    @PluginMethod
    public void startForegroundTracking(PluginCall call) {
        try {
            String text = call.getString("text", "Grabando track…");
            Intent i = new Intent(getContext(), TrackForegroundService.class);
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

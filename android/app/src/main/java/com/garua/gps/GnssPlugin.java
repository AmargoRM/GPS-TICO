package com.garua.gps;

import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.concurrent.Executors;

@CapacitorPlugin(name = "Gnss")
public class GnssPlugin extends Plugin {
    private LocationManager locationManager;
    private GnssStatus.Callback gnssStatusCallback;
    private LocationListener locationListener;
    private boolean isListening = false;

    @Override
    public void load() {
        locationManager = (LocationManager) getContext().getSystemService(android.content.Context.LOCATION_SERVICE);
    }

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
                    JSObject[] sats = new JSObject[status.getSatelliteCount()];

                    for (int i = 0; i < status.getSatelliteCount(); i++) {
                        JSObject sat = new JSObject();
                        sat.put("svid", status.getSvid(i));
                        sat.put("constellation", status.getConstellationType(i));
                        sat.put("cn0", status.getCn0DbHz(i));
                        sat.put("elevation", status.getElevationDegrees(i));
                        sat.put("azimuth", status.getAzimuthDegrees(i));

                        if (status.hasCarrierFrequencyHz(i)) {
                            long freqHz = (long) status.getCarrierFrequencyHz(i);
                            sat.put("carrierFreq", freqHz);
                            sat.put("isL5", Math.abs(freqHz - 1176450000L) < 100000);
                        }

                        sats[i] = sat;
                    }

                    JSObject satArray = new JSObject();
                    for (int i = 0; i < sats.length; i++) {
                        satArray.put(String.valueOf(i), sats[i]);
                    }

                    satellites.put("count", status.getSatelliteCount());
                    satellites.put("usedInFix", status.getUsedInFixCount());
                    satellites.put("satellites", satArray);

                    JSObject data = new JSObject();
                    data.put("data", satellites);
                    data.put("timestamp", System.currentTimeMillis());
                    notifyListeners("gnssStatus", data);
                }
            };

            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    JSObject locData = new JSObject();
                    locData.put("latitude", location.getLatitude());
                    locData.put("longitude", location.getLongitude());
                    locData.put("accuracy", location.getAccuracy());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        locData.put("verticalAccuracy", location.getVerticalAccuracyMeters());
                    }
                    locData.put("altitude", location.getAltitude());
                    locData.put("speed", location.getSpeed());
                    locData.put("bearing", location.getBearing());
                    locData.put("provider", location.getProvider());
                    locData.put("timestamp", location.getTime());

                    JSObject data = new JSObject();
                    data.put("data", locData);
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.registerGnssStatusCallback(
                    Executors.newSingleThreadExecutor(),
                    gnssStatusCallback
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

    public void stopGnssListener(PluginCall call) {
        try {
            if (gnssStatusCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
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

    public void getLastKnownLocation(PluginCall call) {
        try {
            Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (location != null) {
                JSObject locData = new JSObject();
                locData.put("latitude", location.getLatitude());
                locData.put("longitude", location.getLongitude());
                locData.put("accuracy", location.getAccuracy());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    locData.put("verticalAccuracy", location.getVerticalAccuracyMeters());
                }
                locData.put("altitude", location.getAltitude());
                call.resolve(locData);
            } else {
                call.reject("No last known location");
            }
        } catch (Exception e) {
            call.reject("Error getting location: " + e.getMessage());
        }
    }
}

package com.garua.gps

import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.annotation.CapacitorPlugin
import java.util.concurrent.Executors

@CapacitorPlugin(name = "Gnss")
class GnssPlugin : Plugin() {
    private var locationManager: LocationManager? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var locationListener: LocationListener? = null
    private var isListening = false

    override fun load() {
        locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
    }

    fun startGnssListener(call: PluginCall) {
        try {
            if (isListening) {
                call.resolve(JSObject().put("status", "already_listening"))
                return
            }

            gnssStatusCallback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    val satellites = JSObject()
                    val sats = mutableListOf<JSObject>()

                    for (i in 0 until status.satelliteCount) {
                        val sat = JSObject()
                        sat.put("svid", status.getSvid(i))
                        sat.put("constellation", status.getConstellationType(i))
                        sat.put("cn0", status.getCn0DbHz(i))
                        sat.put("elevation", status.getElevationDegrees(i))
                        sat.put("azimuth", status.getAzimuthDegrees(i))

                        if (status.hasCarrierFrequencyHz(i)) {
                            val freqHz = status.getCarrierFrequencyHz(i)
                            sat.put("carrierFreq", freqHz)
                            sat.put("isL5", kotlin.math.abs(freqHz - 1176450000.0) < 100000)
                        }

                        sats.add(sat)
                    }

                    satellites.put("count", status.satelliteCount)
                    satellites.put("usedInFix", status.usedInFixCount)
                    satellites.put("satellites", sats)

                    notifyListeners("gnssStatus", JSObject().apply {
                        put("data", satellites)
                        put("timestamp", System.currentTimeMillis())
                    })
                }
            }

            locationListener = LocationListener { location ->
                val locData = JSObject()
                locData.put("latitude", location.latitude)
                locData.put("longitude", location.longitude)
                locData.put("accuracy", location.accuracy)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    locData.put("verticalAccuracy", location.verticalAccuracyMeters)
                }
                locData.put("altitude", location.altitude)
                locData.put("speed", location.speed)
                locData.put("bearing", location.bearing)
                locData.put("provider", location.provider)
                locData.put("timestamp", location.time)

                notifyListeners("location", JSObject().apply {
                    put("data", locData)
                    put("timestamp", System.currentTimeMillis())
                })
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager?.registerGnssStatusCallback(
                    Executors.newSingleThreadExecutor(),
                    gnssStatusCallback!!
                )
            }

            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                locationListener!!,
                Looper.getMainLooper()
            )

            isListening = true
            call.resolve(JSObject().put("status", "listening"))
        } catch (e: Exception) {
            call.reject("Error starting GNSS listener: ${e.message}")
        }
    }

    fun stopGnssListener(call: PluginCall) {
        try {
            if (gnssStatusCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager?.unregisterGnssStatusCallback(gnssStatusCallback!!)
            }
            if (locationListener != null) {
                locationManager?.removeUpdates(locationListener!!)
            }
            isListening = false
            call.resolve(JSObject().put("status", "stopped"))
        } catch (e: Exception) {
            call.reject("Error stopping GNSS listener: ${e.message}")
        }
    }

    fun getLastKnownLocation(call: PluginCall) {
        try {
            val location = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (location != null) {
                val locData = JSObject()
                locData.put("latitude", location.latitude)
                locData.put("longitude", location.longitude)
                locData.put("accuracy", location.accuracy)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    locData.put("verticalAccuracy", location.verticalAccuracyMeters)
                }
                locData.put("altitude", location.altitude)
                call.resolve(locData)
            } else {
                call.reject("No last known location")
            }
        } catch (e: Exception) {
            call.reject("Error getting location: ${e.message}")
        }
    }
}

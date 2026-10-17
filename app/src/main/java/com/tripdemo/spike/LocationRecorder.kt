package com.tripdemo.spike

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * 定位采集：录制期间持续记录最新位置（GPS / 网络 / 被动三路取最快）。
 * 设备无定位权限或室内无 fix 时 lastLocation 为 null，各环节自动降级（不写位置，不报错）。
 */
class LocationRecorder(private val context: Context) {

    @Volatile
    var lastLocation: Location? = null
        private set

    private var listening = false
    private val lm by lazy { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // 只接受更新/更准的 fix
            val cur = lastLocation
            if (cur == null || location.time >= cur.time) lastLocation = location
        }

        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
        @Deprecated("unused") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun start() {
        if (listening || !hasPermission()) return
        listening = true
        try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
            for (p in providers) {
                try {
                    lm.requestLocationUpdates(p, 2000L, 0f, listener, android.os.Looper.getMainLooper())
                } catch (_: Exception) {
                }
            }
            // 先用最后一次已知位置兜底
            for (p in providers) {
                val last = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
                if (last != null && (lastLocation == null || last.time >= lastLocation!!.time)) {
                    lastLocation = last
                }
            }
        } catch (_: Exception) {
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        try {
            lm.removeUpdates(listener)
        } catch (_: Exception) {
        }
    }
}

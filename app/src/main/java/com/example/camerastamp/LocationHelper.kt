package com.example.camerastamp

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale

/**
 * Wraps FusedLocationProviderClient + Geocoder to keep a rolling "last known"
 * location + human readable address string, formatted similarly to:
 * "Nagasari, Karangduwur, Kec. ayah, Kab. kebumen, Jawa Tengah 54473"
 */
class LocationHelper(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    var lastLocation: Location? = null
        private set

    var lastAddressText: String = ""
        private set

    private var callback: ((String, Location) -> Unit)? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastLocation = loc
            resolveAddress(loc)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(onUpdate: (String, Location) -> Unit) {
        callback = onUpdate
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())

        // Kick off with last known location immediately if available
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                lastLocation = loc
                resolveAddress(loc)
            }
        }
    }

    fun stop() {
        fusedClient.removeLocationUpdates(locationCallback)
    }

    private fun resolveAddress(location: Location) {
        Thread {
            val text = try {
                if (!Geocoder.isPresent()) {
                    "${location.latitude}, ${location.longitude}"
                } else {
                    val geocoder = Geocoder(context, Locale("in", "ID"))
                    val addresses: List<Address> = if (Build.VERSION.SDK_INT >= 33) {
                        val latch = java.util.concurrent.CountDownLatch(1)
                        var result: List<Address> = emptyList()
                        geocoder.getFromLocation(location.latitude, location.longitude, 1) {
                            result = it
                            latch.countDown()
                        }
                        // Reverse geocoding does real network I/O; 300ms was nowhere near
                        // enough, causing this to always fall back to raw coordinates.
                        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
                        result
                    } else {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocation(location.latitude, location.longitude, 1) ?: emptyList()
                    }
                    if (addresses.isNotEmpty()) formatAddress(addresses[0])
                    else "${location.latitude}, ${location.longitude}"
                }
            } catch (e: Exception) {
                "${location.latitude}, ${location.longitude}"
            }
            lastAddressText = text
            callback?.invoke(text, location)
        }.start()
    }

    private fun formatAddress(addr: Address): String {
        // Prefer Android/Google's own fully-formatted address line — it already knows
        // the right field order per country. Building it ourselves from subLocality/
        // locality/subAdminArea/adminArea was fragile: those fields don't carry the
        // same meaning in every region, which is what caused duplicated "Kec./Kab."
        // and repeated province names.
        val addressLine = addr.getAddressLine(0)
        if (!addressLine.isNullOrBlank()) {
            return cleanAddressLine(addressLine)
        }

        // Fallback if no formatted line is available: use whatever discrete fields
        // exist, de-duplicated so the same value never appears twice.
        return listOfNotNull(addr.subLocality, addr.locality, addr.subAdminArea, addr.adminArea, addr.postalCode)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
    }

    private fun cleanAddressLine(line: String): String {
        val withoutCountry = line.removeSuffix(", Indonesia").trim()
        val segments = withoutCountry.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val deduped = mutableListOf<String>()
        for (seg in segments) {
            if (deduped.isEmpty() || !deduped.last().equals(seg, ignoreCase = true)) {
                deduped.add(seg)
            }
        }
        return deduped.joinToString(", ")
    }
}

package eu.magicsk.transi

import android.Manifest
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val locationListener: LocationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                println("location changed activity")
                val navHostFragment = supportFragmentManager.findFragmentById(R.id.navHostFragment)
                val mainFragment = navHostFragment?.childFragmentManager?.fragments?.get(0) as? MainFragment?
                mainFragment?.locationChange(location)
            }

            override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
                println("location provider :$provider")
                println("location status :$status")
            }
            override fun onProviderEnabled(provider: String) {
                println("location provider :$provider Enabled")
            }
            override fun onProviderDisabled(provider: String) {
                println("location provider :$provider Disabled")
            }
        }
        fun requestLocation() {
            println("location requested")
            val lastLocation = locationManager.getLastKnownLocation(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
            )
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.navHostFragment)
            val mainFragment = navHostFragment?.childFragmentManager?.fragments?.get(0) as MainFragment?
            lastLocation?.let { mainFragment?.locationChange(it)}
            locationManager.requestLocationUpdates(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER,
                0L,
                10f,
                locationListener
            )
        }
        val locationPermissionRequest = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            when {
                permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> requestLocation()
                permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> requestLocation()
            }
        }

        locationPermissionRequest.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

//    override fun onBackPressed() {
//        super.onBackPressed()
//        exitProcess(0)
//    }
}
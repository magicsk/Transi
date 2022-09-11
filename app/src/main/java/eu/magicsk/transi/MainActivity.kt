package eu.magicsk.transi

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.color.MaterialColors
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.data.remote.responses.StopsJSONItem
import eu.magicsk.transi.databinding.ActivityMainBinding
import eu.magicsk.transi.util.UpdateAlert
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.ReleaseInfoViewModel
import eu.magicsk.transi.view_models.StopsListVersionViewModel
import eu.magicsk.transi.view_models.StopsListViewModel
import kotlin.math.*

@AndroidEntryPoint
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {
    private var actualLocation: Location? = null
    private var stopList: StopsJSON = StopsJSON()
    private val stopListBundle = Bundle()
    private val stopsViewModel: StopsListViewModel by viewModels()
    private val stopsVersionViewModel: StopsListVersionViewModel by viewModels()
    private val releaseInfoViewModel: ReleaseInfoViewModel by viewModels()
    private val mainViewModel: MainViewModel by viewModels()
    private lateinit var sharedPreferences: SharedPreferences
    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!


    private fun calcDistance(x: StopsJSONItem): Double {
        val xLat = x.lat
        val xLong = x.long
        val radius = 6378137.toDouble()
        val deltaLat = xLat - actualLocation!!.latitude
        val deltaLong = xLong - actualLocation!!.longitude
        val angle = 2 * asin(
            sqrt(
                sin(deltaLat / 2).pow(2.0) +
                        cos(actualLocation!!.latitude) * cos(xLat) *
                        sin(deltaLong / 2).pow(2.0)
            )
        )
        return radius * angle
    }

    private val sortByNearest = Comparator<StopsJSONItem> { a, b ->
        val aDist = calcDistance(a)
        val bDist = calcDistance(b)
        return@Comparator (aDist - bDist).roundToInt()
    }

    private fun locationChange(location: Location) {
        actualLocation = location
        mainViewModel.setActualLocation(location)
        if (stopList.size > 0) {
            stopList.sortWith(sortByNearest)
            mainViewModel.setStopList(stopList)
        }
    }

    private fun loadStopList() {
        val savedStopListJson = sharedPreferences.getString("stopList", "")
        val savedStopListVersion = sharedPreferences.getString("stopsVersion", "")
        if (savedStopListJson != "") {
            val savedStopList = Gson().fromJson(savedStopListJson, StopsJSON::class.java)
            stopList.clear()
            stopList.addAll(savedStopList)
            stopListBundle.clear()
            stopListBundle.putSerializable("stopsList", stopList)
            actualLocation?.let { l -> locationChange(l) }
        }
        stopsVersionViewModel.stopsVersion.observe(this) { stopsVersion ->
            if (stopsVersion != null) {
                if (savedStopListVersion != stopsVersion.version || savedStopListJson == "") {
                    stopsViewModel.stops.observe(this) { stops ->
                        if (stops != null && stopList.size < 1) {
                            println("stops fetched")
                            stopList.clear()
                            stopList.addAll(stops)
                            val stopListJson = Gson().toJson(stopList)
                            sharedPreferences.edit().putString("stopList", stopListJson).apply()
                            sharedPreferences.edit().putString("stopsVersion", stopsVersion.version)
                                .apply()

                        }
                    }
                }
                stopListBundle.clear()
                stopListBundle.putSerializable("stopsList", stopList)
                actualLocation?.let { l -> locationChange(l) }
            } else {
                println("no connection")
                Handler(Looper.getMainLooper()).postDelayed({
                    loadStopList()
                }, 1000)
            }
        }
    }

    private fun createChannel(channelId: String, channelName: String, description: String) {
        val notificationChannel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setShowBadge(false)
        }

        notificationChannel.description = description

        val notificationManager = getSystemService(
            NotificationManager::class.java
        )
        notificationManager.createNotificationChannel(notificationChannel)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        window?.statusBarColor = MaterialColors.getColor(window.decorView, R.attr.colorMyBackground)
        if (supportFragmentManager.findFragmentById(R.id.tripSearchFragmentLayout) != null) {
            supportFragmentManager.popBackStack(
                "tripTypeAhead",
                1
            )
        } else if (supportFragmentManager.findFragmentById(R.id.tripSearchFragmentLayout) != null) {
            supportFragmentManager.popBackStack("typeAhead", 1)
        } else {
            super.onBackPressed()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val versionName = BuildConfig.VERSION_NAME
        releaseInfoViewModel.releaseInfo.observe(this) { releaseInfo ->
            val version = releaseInfo?.tag_name
            if (versionName != version.toString().replace("v", "") && !version.isNullOrEmpty()) {
                val updateAlert = UpdateAlert(
                    version.toString().replace("v", ""),
                    releaseInfo.body,
                    releaseInfo.assets[0].browser_download_url
                )
                updateAlert.show(supportFragmentManager, "updateAlert")
            }
        }
        _binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        sharedPreferences = getSharedPreferences("Transi", Context.MODE_PRIVATE)!!
        loadStopList()

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.navView.setupWithNavController(navController)
        binding.navView.setOnItemSelectedListener { item ->
            if (supportFragmentManager.backStackEntryCount > 0) {
                window.statusBarColor = MaterialColors.getColor(view, R.attr.colorMyBackground)
                supportFragmentManager.popBackStackImmediate("typeAhead", 1)
                supportFragmentManager.popBackStackImmediate("tripTypeAhead", 1)
            }
            NavigationUI.onNavDestinationSelected(item, navController)
            true
        }


        createChannel(
            getString(R.string.table_notification_channel_id),
            getString(R.string.table_notification_channel_name),
            getString(R.string.table_notification_channel_description)
        )
        createChannel(
            getString(R.string.trip_planner_notification_channel_id),
            getString(R.string.trip_planner_notification_channel_name),
            getString(R.string.trip_planner_notification_channel_description)
        )

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val locationListener = LocationListener { location ->
            locationChange(location)
        }

        fun requestLocation() {
            println("location requested")
            val lastLocation = locationManager.getLastKnownLocation(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
            )
            try {
                lastLocation?.let { locationChange(it) }
                locationManager.requestLocationUpdates(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER,
                    0L,
                    10f,
                    locationListener
                )
            } catch (_: ClassCastException) {

            }
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
}
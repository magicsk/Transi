package eu.magicsk.transi

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.location.LocationManagerCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import dagger.hilt.android.AndroidEntryPoint
import eu.magicsk.transi.databinding.ActivityMainBinding
import eu.magicsk.transi.timetable.TimetableDatabase
import eu.magicsk.transi.util.Prefs
import eu.magicsk.transi.util.UpdateAlert
import eu.magicsk.transi.util.hasLocationPermission
import eu.magicsk.transi.util.WhatsNewSheet
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.ReleaseInfoViewModel
import javax.inject.Inject
import kotlin.math.max

@AndroidEntryPoint
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var timetableDatabase: TimetableDatabase

    private val releaseInfoViewModel: ReleaseInfoViewModel by viewModels()
    private val mainViewModel: MainViewModel by viewModels()
    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!
    private lateinit var locationManager: LocationManager
    private lateinit var navController: NavController
    // Until the first permission prompt is answered the table neither follows the location nor falls back to the default stop.
    private var locationPermissionPending = false

    // Implements every callback: before API 30 the framework calls them and they have no default bodies.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            mainViewModel.setActualLocation(location)
        }

        override fun onProviderDisabled(provider: String) = updateLocation()
        override fun onProviderEnabled(provider: String) = updateLocation()
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val locationProvider
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER

    // iOS locationManagerDidChangeAuthorization: follow the location when allowed, otherwise show the default stop.
    private fun updateLocation() {
        if (locationPermissionPending) return
        val granted = hasLocationPermission()
        mainViewModel.setLocationAvailable(granted && LocationManagerCompat.isLocationEnabled(locationManager))
        if (!granted || locationProvider !in locationManager.allProviders) return
        locationManager.getLastKnownLocation(locationProvider)?.let { mainViewModel.setActualLocation(it) }
        locationManager.requestLocationUpdates(locationProvider, 0L, 50f, locationListener)
    }

    override fun onStart() {
        super.onStart()
        updateLocation()
        timetableDatabase.retryIfFailed()
    }

    override fun onStop() {
        super.onStop()
        locationManager.removeUpdates(locationListener)
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

    // The only activity back stack entries are the stop search overlays opened by MainFragment and TripPlannerFragment.
    private fun closeSearch() {
        supportFragmentManager.popBackStackImmediate("typeAhead", FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.popBackStackImmediate("tripTypeAhead", FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        // Exported for transi:// links: nothing here reads extras, and dropping them keeps NavController
        // from following the deep-link destination and arguments extras it would otherwise trust.
        intent.replaceExtras(null as Bundle?)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val versionName = BuildConfig.VERSION_NAME
        // A fresh install has no update to describe; checked before MainViewModel can cache a stops version.
        val prefs = Prefs.get(this)
        if (!prefs.contains(Prefs.CHANGELOG_VERSION) && !prefs.contains(Prefs.STOPS_VERSION)) {
            prefs.edit { putString(Prefs.CHANGELOG_VERSION, versionName) }
        }
        releaseInfoViewModel.releaseInfo.observe(this) { releaseInfo ->
            val version = releaseInfo?.tag_name?.removePrefix("v")
            val downloadUrl = releaseInfo?.assets?.firstOrNull()?.browser_download_url
            if (!version.isNullOrEmpty() && version != versionName &&
                downloadUrl != null && supportFragmentManager.findFragmentByTag("updateAlert") == null
            ) {
                UpdateAlert.newInstance(version, releaseInfo.body.orEmpty(), downloadUrl)
                    .show(supportFragmentManager, "updateAlert")
            } else if (version == versionName) {
                // ponytail: only the latest release is fetched, so builds behind it get its notes in the Update dialog instead
                WhatsNewSheet.showOnce(this, releaseInfo.body)
            }
        }
        _binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHostFragment.navController
        ViewCompat.setOnApplyWindowInsetsListener(view) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            // While the keyboard is open the bottom navigation hides and the root padding clears the keyboard,
            // whose inset already includes the navigation bar (a floating keyboard reports none, hence max).
            root.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = if (imeVisible) max(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom, bars.bottom) else 0
            )
            // The top margin is the status bar strip; statusBarBackground fills it. The map runs under the status bar
            // and pads its own controls (MapFragment).
            val mapShown = navController.currentDestination?.id == R.id.navigationMap
            binding.navHostFragment.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = if (mapShown) 0 else bars.top }
            binding.navView.isVisible = !imeVisible
            binding.navView.updatePadding(bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        val closeSearchOnBack = onBackPressedDispatcher.addCallback(this, supportFragmentManager.backStackEntryCount > 0) {
            closeSearch()
        }
        supportFragmentManager.addOnBackStackChangedListener {
            closeSearchOnBack.isEnabled = supportFragmentManager.backStackEntryCount > 0
        }
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        navController.addOnDestinationChangedListener { _, _, _ -> ViewCompat.requestApplyInsets(view) }
        binding.navView.setupWithNavController(navController)
        binding.navView.setOnItemSelectedListener { item ->
            if (supportFragmentManager.backStackEntryCount > 0) closeSearch()
            NavigationUI.onNavDestinationSelected(item, navController)
            true
        }


        // Followed departures post to LiveUpdatesService's own channels now.
        getSystemService(NotificationManager::class.java).deleteNotificationChannel(LiveUpdatesService.LEGACY_CHANNEL)
        createChannel(
            getString(R.string.trip_planner_notification_channel_id),
            getString(R.string.trip_planner_notification_channel_name),
            getString(R.string.trip_planner_notification_channel_description)
        )

        val locationPermissionRequest = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            locationPermissionPending = false
            updateLocation()
        }


        // Departure and trip notifications are posted later from long presses; ask for the permission up front.
        val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }
        if (savedInstanceState == null) {
            locationPermissionPending = !hasLocationPermission()
            locationPermissionRequest.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) + notificationPermission
            )
            openLink(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        intent.replaceExtras(null as Bundle?)
        super.onNewIntent(intent)
        setIntent(intent)
        openLink(intent)
    }

    // A task relaunched from Recents gets its original intent again, so its link was already followed.
    private fun openLink(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        navigate(parseDeepLink(intent.dataString))
    }

    /** The one way to switch tabs from links and from screens (iOS pendingNavigation): the tab plus what it should show. */
    fun navigate(link: DeepLink) {
        val tab = when (link) {
            DeepLink.Trip, DeepLink.ActiveTrip -> R.id.navigationTripPlanner
            is DeepLink.Table -> {
                link.stopId?.let(mainViewModel::changeTableStop)
                mainViewModel.expandDeparture.value = link.departureId?.let { id -> link.stopId?.let { it to id } }
                R.id.navigationTable
            }
            is DeepLink.Timetable -> R.id.navigationTimetables
            is DeepLink.Map -> {
                mainViewModel.mapStop.value = link.stopId
                R.id.navigationMap
            }
            DeepLink.Invalid -> {
                UnableToNavigateDialog().show(supportFragmentManager, "unableToNavigate")
                return
            }
        }
        if (supportFragmentManager.backStackEntryCount > 0) closeSearch()
        // Selecting the current tab again would recreate its screen.
        if (binding.navView.selectedItemId != tab) binding.navView.selectedItemId = tab
        if (link is DeepLink.Timetable) {
            // A line opens from the list, so a line screen restored with the tab makes way for it first.
            if (link.line != null) navController.popBackStack(R.id.navigationTimetables, false)
            mainViewModel.timetableLine.value = link.line
        }
    }
}

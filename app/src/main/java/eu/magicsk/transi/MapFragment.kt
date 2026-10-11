package eu.magicsk.transi

import android.content.Context
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.android.gms.maps.model.UrlTileProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.clustering.ClusterManager
import com.google.maps.android.clustering.view.DefaultClusterRenderer
import dagger.hilt.android.AndroidEntryPoint
import eu.magicsk.transi.adapters.MHDTableAdapter
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.databinding.FragmentMapBinding
import eu.magicsk.transi.util.hasLocationPermission
import eu.magicsk.transi.util.isDarkTheme
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.TableViewModel
import eu.magicsk.transi.view_models.TripPlannerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URL

/**
 * The Map tab (iOS MapKitView): Thunderforest transport tiles, clustered stops, following the location, and a sheet
 * with the tapped stop's live board. With [ARG_ORIGIN] it is the stop search's "From map" picker instead: tapping a
 * stop's info window picks it for that search. The stops come from [MainViewModel], so a restored map has them too.
 */
@AndroidEntryPoint
class MapFragment : Fragment(R.layout.fragment_map) {
    private val mainViewModel: MainViewModel by activityViewModels()
    private val tripPlannerViewModel: TripPlannerViewModel by activityViewModels()
    // The sheet's own board, created only in the tab; the Table tab keeps its own.
    private val tableViewModel: TableViewModel by viewModels()

    private var binding: FragmentMapBinding? = null
    private var sheet: BottomSheetBehavior<View>? = null
    private var sheetBack: OnBackPressedCallback? = null
    private var map: GoogleMap? = null
    private var clusterManager: ClusterManager<Place>? = null
    private var tableAdapter: MHDTableAdapter? = null
    private var shownStops: Set<Stop> = emptySet()
    private var topInset = 0
    private var boardLive = false

    private var stops: List<Stop> = emptyList()
    private var selectedStop: Stop? = null
    /** The stop whose sheet was open before the view was recreated, shown again once the stops are known. */
    private var restoredStopId: Int? = null
    private var camera: CameraPosition? = null
    private var follow = true

    private val origin get() = arguments?.getString(ARG_ORIGIN)
    private val isPicker get() = origin != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            camera = BundleCompat.getParcelable(savedInstanceState, STATE_CAMERA, CameraPosition::class.java)
            follow = savedInstanceState.getBoolean(STATE_FOLLOW, true)
            restoredStopId = savedInstanceState.getInt(STATE_STOP_ID).takeIf { it > 0 }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        camera?.let { outState.putParcelable(STATE_CAMERA, it) }
        outState.putBoolean(STATE_FOLLOW, follow)
        (selectedStop?.id ?: restoredStopId)?.let { outState.putInt(STATE_STOP_ID, it) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentMapBinding.bind(view)
        this.binding = binding
        val sheet = BottomSheetBehavior.from<View>(binding.mapSheet)
        this.sheet = sheet
        // Restored afterwards with the view state when it was open.
        sheet.state = BottomSheetBehavior.STATE_HIDDEN

        binding.mapLocationButton.setOnClickListener {
            when {
                follow -> setFollow(false)
                mainViewModel.actualLocation.value == null ->
                    Toast.makeText(requireContext(), R.string.no_location, Toast.LENGTH_SHORT).show()
                else -> setFollow(true)
            }
        }
        renderFollow()

        mainViewModel.stopList.observe(viewLifecycleOwner) { stops ->
            this.stops = stops
            showStops()
            restoreSheet()
            showRequestedStop()
        }
        mainViewModel.actualLocation.observe(viewLifecycleOwner) { location ->
            if (follow) map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(location.latitude, location.longitude)))
        }
        // Answered permission prompts and location turned on or off.
        mainViewModel.locationAvailable.observe(viewLifecycleOwner) {
            map?.let(::enableMyLocation)
            renderChrome()
        }

        if (isPicker) {
            // The stop search's keyboard would cover the map.
            requireContext().getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
        } else {
            setUpSheet(view, binding, sheet)
        }
        (childFragmentManager.findFragmentById(R.id.mapContainer) as? SupportMapFragment)?.getMapAsync(::onMapReady)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // Saved while sliding away, the sheet would come back collapsed without a stop.
        if (restoredStopId == null) sheet?.state = BottomSheetBehavior.STATE_HIDDEN
        renderChrome()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        restoredStopId = selectedStop?.id ?: restoredStopId
        selectedStop = null
        boardLive = false
        shownStops = emptySet()
        binding = null
        sheet = null
        sheetBack = null
        map = null
        clusterManager = null
        tableAdapter = null
    }

    // The tab runs under the status bar (MainActivity), so the map's controls and the full sheet keep clear of it.
    private fun setUpSheet(view: View, binding: FragmentMapBinding, sheet: BottomSheetBehavior<View>) {
        view.doOnAttach {
            topInset = ViewCompat.getRootWindowInsets(it)
                ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())?.top ?: 0
            sheet.expandedOffset = topInset
            // Fully expanded, the sheet ends that far below the map; the padding lets the last row scroll into view.
            binding.mapSheetList.updatePadding(bottom = topInset)
            map?.setPadding(0, topInset, 0, 0)
        }
        ViewCompat.setAccessibilityHeading(binding.mapSheetStopName, true)

        val adapter = MHDTableAdapter { departure ->
            selectedStop?.let { DepartureActionsSheet.newInstance(departure, it).show(childFragmentManager, "departureActions") }
        }
        tableAdapter = adapter
        binding.mapSheetList.adapter = adapter
        binding.mapSheetList.layoutManager = LinearLayoutManager(context)
        binding.mapSheetList.itemAnimator?.changeDuration = 0
        tableViewModel.departures.observe(viewLifecycleOwner) { departures ->
            adapter.submitList(departures)
            renderBoardState()
        }
        tableViewModel.vehicles.observe(viewLifecycleOwner) { adapter.vehicles = it }
        tableViewModel.status.observe(viewLifecycleOwner) { renderBoardState() }
        tableViewModel.empty.observe(viewLifecycleOwner) { renderBoardState() }
        mainViewModel.mapStop.observe(viewLifecycleOwner) { showRequestedStop() }

        binding.mapSheetClose.setOnClickListener { closeSheet() }
        // iOS: switch to the planner and plan from Actual location to this stop.
        binding.mapSheetDirections.setOnClickListener {
            val stop = selectedStop ?: return@setOnClickListener
            tripPlannerViewModel.planFromActualLocationTo(stop)
            closeSheet()
            (activity as? MainActivity)?.navigate(DeepLink.Trip)
        }

        // Predictive back slides the sheet away before back leaves the tab.
        val back = object : OnBackPressedCallback(false) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) = sheet.startBackProgress(backEvent)
            override fun handleOnBackProgressed(backEvent: BackEventCompat) = sheet.updateBackProgress(backEvent)
            override fun handleOnBackPressed() = sheet.handleBackInvoked()
            override fun handleOnBackCancelled() = sheet.cancelBackProgress()
        }
        sheetBack = back
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        sheet.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) onSheetClosed()
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
        })

        // The remaining times tick while the map is on screen.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                syncBoard()
                try {
                    while (true) {
                        delay(10_000)
                        adapter.refresh()
                    }
                } finally {
                    boardLive = false
                    tableViewModel.disconnect()
                }
            }
        }
    }

    private fun onMapReady(googleMap: GoogleMap) {
        if (binding == null) return
        map = googleMap
        val dark = isDarkTheme(resources)
        if (BuildConfig.THUNDERFOREST_API_KEY.isEmpty()) {
            // Without a Thunderforest key the Google basemap stays, dark in dark mode.
            if (dark) googleMap.setMapStyle(MapStyleOptions(getString(R.string.style_json)))
        } else {
            googleMap.mapType = GoogleMap.MAP_TYPE_NONE
            googleMap.addTileOverlay(
                TileOverlayOptions().fadeIn(false).tileProvider(TransportTiles(if (dark) "transport-dark" else "transport"))
            )
        }
        googleMap.setMaxZoomPreference(TransportTiles.MAX_ZOOM.toFloat())
        googleMap.uiSettings.isMapToolbarEnabled = false
        googleMap.uiSettings.isMyLocationButtonEnabled = false
        googleMap.setPadding(0, topInset, 0, 0)
        googleMap.moveCamera(CameraUpdateFactory.newCameraPosition(camera ?: CameraPosition.fromLatLngZoom(DEFAULT_CENTER, DEFAULT_ZOOM)))
        mainViewModel.actualLocation.value?.takeIf { follow }?.let {
            googleMap.moveCamera(CameraUpdateFactory.newLatLng(LatLng(it.latitude, it.longitude)))
        }
        enableMyLocation(googleMap)

        val context = requireContext()
        val manager = ClusterManager<Place>(context, googleMap)
        manager.renderer = PlaceRenderer(context, googleMap, manager)
        manager.setOnClusterClickListener { cluster ->
            setFollow(false)
            googleMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(cluster.position, googleMap.cameraPosition.zoom + CLUSTER_ZOOM_STEP)
            )
            true
        }
        if (isPicker) {
            manager.setOnClusterItemInfoWindowClickListener { pick(it.stop) }
        } else {
            manager.setOnClusterItemClickListener { place ->
                select(place.stop)
                true
            }
            // iOS: deselecting the stop closes its sheet.
            googleMap.setOnMapClickListener { closeSheet() }
        }
        clusterManager = manager
        googleMap.setOnCameraIdleListener {
            camera = googleMap.cameraPosition
            manager.onCameraIdle()
        }
        googleMap.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) setFollow(false)
        }
        showStops()
        showRequestedStop()
    }

    // The list is sorted again with every location fix; the markers change only when the stops do.
    private fun showStops() {
        val manager = clusterManager ?: return
        val shown = stops.filter { it.id > 0 }.toSet()
        if (shown.isEmpty() || shown == shownStops) return
        shownStops = shown
        manager.clearItems()
        manager.addItems(shown.map(::Place))
        manager.cluster()
    }

    private fun restoreSheet() {
        val id = restoredStopId ?: return
        if (stops.isEmpty()) return
        restoredStopId = null
        stops.firstOrNull { it.id == id }?.let(::showSheet) ?: closeSheet()
    }

    // iOS openedURL "transi://map/{id}": selects that stop and zooms to about 500 m around it.
    private fun showRequestedStop() {
        if (isPicker) return
        val id = mainViewModel.mapStop.value ?: return
        val map = map ?: return
        if (stops.isEmpty()) return
        mainViewModel.mapStop.value = null
        val stop = stops.firstOrNull { it.id == id } ?: return
        setFollow(false)
        select(stop)
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(stop.lat, stop.lng), DEFAULT_ZOOM))
    }

    /** iOS didSelect: the Table tab changes to the stop too (and stops following the nearest one). */
    private fun select(stop: Stop) {
        mainViewModel.changeTableStop(stop.id)
        showSheet(stop)
    }

    private fun showSheet(stop: Stop) {
        val binding = binding ?: return
        val sheet = sheet ?: return
        selectedStop = stop
        binding.mapSheetStopName.text = stop.name
        tableAdapter?.platformLabels = stop.platform_labels
        tableViewModel.setStop(stop)
        // Also while it slides away, so a stop tapped meanwhile keeps it open.
        if (sheet.state != BottomSheetBehavior.STATE_EXPANDED) sheet.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        syncBoard()
        renderChrome()
    }

    private fun closeSheet() {
        sheet?.state = BottomSheetBehavior.STATE_HIDDEN
        onSheetClosed()
    }

    private fun onSheetClosed() {
        if (selectedStop == null) return
        selectedStop = null
        syncBoard()
        renderChrome()
    }

    // The board is connected only while the sheet is open and the map is on screen.
    private fun syncBoard() {
        val live = selectedStop != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (live == boardLive) return
        boardLive = live
        if (live) tableViewModel.connect() else tableViewModel.disconnect()
    }

    private fun renderBoardState() {
        val binding = binding ?: return
        binding.mapSheetLoading.isVisible = tableViewModel.showsLoading
        binding.mapSheetEmpty.isVisible = tableViewModel.showsEmpty
    }

    private fun renderChrome() {
        val binding = binding ?: return
        val open = selectedStop != null
        sheetBack?.isEnabled = open
        // The sheet covers the button, as on iOS.
        binding.mapLocationButton.isVisible = !open && requireContext().hasLocationPermission()
    }

    private fun setFollow(value: Boolean) {
        if (follow == value) return
        follow = value
        if (value) {
            mainViewModel.actualLocation.value?.let {
                map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(it.latitude, it.longitude)))
            }
        }
        renderFollow()
    }

    private fun renderFollow() {
        val button = binding?.mapLocationButton ?: return
        button.setImageResource(if (follow) R.drawable.ic_my_location else R.drawable.ic_location)
        ViewCompat.setStateDescription(
            button, getString(if (follow) R.string.map_following_location else R.string.map_not_following_location)
        )
    }

    private fun enableMyLocation(map: GoogleMap) {
        if (!requireContext().hasLocationPermission()) return
        // Lint does not see the check above as a permission check, so the call handles the exception explicitly.
        try {
            map.isMyLocationEnabled = true
        } catch (_: SecurityException) {
            // Revoked between the check and the call: the location dot stays off.
        }
    }

    private fun pick(stop: Stop) {
        val fragments = parentFragmentManager
        when (origin) {
            "editTextFrom" -> {
                fragments.popBackStack("tripTypeAhead", FragmentManager.POP_BACK_STACK_INCLUSIVE)
                tripPlannerViewModel.setSelectedFromStop(stop)
            }

            "editTextTo" -> {
                fragments.popBackStack("tripTypeAhead", FragmentManager.POP_BACK_STACK_INCLUSIVE)
                tripPlannerViewModel.setSelectedToStop(stop)
            }

            else -> {
                fragments.popBackStack("typeAhead", FragmentManager.POP_BACK_STACK_INCLUSIVE)
                mainViewModel.changeTableStop(stop.id)
            }
        }
    }

    private class Place(val stop: Stop) : ClusterItem {
        override val position = LatLng(stop.lat, stop.lng)
        override val title: String get() = stop.name
        override val snippet: String? get() = null
        override val zIndex: Float get() = 0f
    }

    private class PlaceRenderer(
        context: Context, map: GoogleMap, clusterManager: ClusterManager<Place>,
    ) : DefaultClusterRenderer<Place>(context, map, clusterManager) {
        private val markerIcon: BitmapDescriptor by lazy {
            vectorToBitmap(context, R.drawable.ic_marker, ContextCompat.getColor(context, R.color.md_theme_light_primary))
        }

        // The title is the marker's TalkBack label and the picker's info window.
        override fun onBeforeClusterItemRendered(item: Place, markerOptions: MarkerOptions) {
            markerOptions.title(item.title).icon(markerIcon)
        }

        private fun vectorToBitmap(context: Context, @DrawableRes id: Int, @ColorInt color: Int): BitmapDescriptor {
            val drawable = ResourcesCompat.getDrawable(context.resources, id, null) ?: return BitmapDescriptorFactory.defaultMarker()
            val bitmap = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            DrawableCompat.setTint(drawable, color)
            drawable.draw(canvas)
            return BitmapDescriptorFactory.fromBitmap(bitmap)
        }
    }

    /** Thunderforest [style] tiles at 512 px (@2x), as iOS TransportTiles; zooms the server lacks get none. */
    private class TransportTiles(private val style: String) : UrlTileProvider(TILE_SIZE, TILE_SIZE) {
        // Built from parts: a malformed URL's exception message would carry the API key.
        override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {
            if (zoom !in 0..MAX_ZOOM || x !in 0 until (1 shl zoom) || y !in 0 until (1 shl zoom)) return null
            return URL("https", HOST, "/$style/$zoom/$x/$y@2x.png?apikey=${BuildConfig.THUNDERFOREST_API_KEY}")
        }

        companion object {
            const val MAX_ZOOM = 22
            const val TILE_SIZE = 512
            const val HOST = "tile.thunderforest.com"
        }
    }

    companion object {
        /** The stop search that opened the map as a picker: "editText", "editTextFrom" or "editTextTo". */
        const val ARG_ORIGIN = "origin"
        private const val STATE_CAMERA = "camera"
        private const val STATE_FOLLOW = "follow"
        private const val STATE_STOP_ID = "stopId"
        private val DEFAULT_CENTER = LatLng(48.145, 17.107)
        // About 500 m across a phone in portrait, iOS's default region.
        private const val DEFAULT_ZOOM = 16.3f
        // iOS zooms into a cluster to 0.6× its span: log2(1 / 0.6).
        private const val CLUSTER_ZOOM_STEP = 0.74f
    }
}

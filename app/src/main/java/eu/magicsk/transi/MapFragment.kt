package eu.magicsk.transi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMap.MAP_TYPE_NONE
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.clustering.ClusterManager
import com.google.maps.android.clustering.view.DefaultClusterRenderer
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.Stops
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.TripPlannerViewModel
import java.net.MalformedURLException
import java.net.URL

class MapFragment : SupportMapFragment() {
    private lateinit var stopsList: Stops
    private lateinit var origin: String
    private val placesList = arrayListOf<Place>()

    private val tileProvider: TileProvider = object : UrlTileProvider(256, 256) {
        override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {

            val url = "https://tile.thunderforest.com/transport/${zoom}/$x/$y@2x.png?apikey=6170aad10dfd42a38d4d8c709a536f38"
            return if (!checkTileExists(zoom)) {
                null
            } else try {
                URL(url)
            } catch (e: MalformedURLException) {
                throw AssertionError(e)
            }
        }

        private fun checkTileExists(zoom: Int): Boolean {
            val minZoom = 0
            val maxZoom = 19
            return zoom in minZoom..maxZoom
        }
    }

    object BitmapHelper {
        fun vectorToBitmap(
            context: Context, @DrawableRes id: Int, @ColorInt color: Int
        ): BitmapDescriptor {
            val vectorDrawable =
                ResourcesCompat.getDrawable(context.resources, id, null) ?: return BitmapDescriptorFactory.defaultMarker()
            val bitmap = Bitmap.createBitmap(
                vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight, Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            vectorDrawable.setBounds(0, 0, canvas.width, canvas.height)
            DrawableCompat.setTint(vectorDrawable, color)
            vectorDrawable.draw(canvas)
            return BitmapDescriptorFactory.fromBitmap(bitmap)
        }
    }


    class PlaceRenderer(
        context: Context, map: GoogleMap, clusterManager: ClusterManager<Place>
    ) : DefaultClusterRenderer<Place>(context, map, clusterManager) {
        private val markerIcon: BitmapDescriptor by lazy {
            val color = ContextCompat.getColor(
                context, R.color.md_theme_light_primary
            )
            BitmapHelper.vectorToBitmap(
                context, R.drawable.ic_marker, color
            )
        }

        override fun onBeforeClusterItemRendered(
            item: Place, markerOptions: MarkerOptions
        ) {
            markerOptions.title(item.name).position(item.latLng).icon(markerIcon)
        }

        override fun onClusterItemRendered(clusterItem: Place, marker: Marker) {
            marker.tag = clusterItem
        }
    }


    data class Place(
        val id: Int,
        val name: String,
        val type: String,
        val latLng: LatLng,
    ) : ClusterItem {
        override fun getPosition(): LatLng = latLng
        override fun getTitle(): String = name
        override fun getSnippet(): String = name
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stopsList = requireArguments().getSerializable("stopsList") as Stops
        origin = requireArguments().getString("origin").toString()
        stopsList.forEach { stop ->
            if (stop.id != 0) placesList.add(
                Place(
                    stop.id, stop.name, stop.type, LatLng(stop.lat, stop.lng)
                )
            )
        }
    }

    private fun getItem(name: String): Stop {
        stopsList.forEach {
            if (it.name == name) return it
        }
        return stopsList[0]
    }

    @SuppressLint("RestrictedApi")
    private fun addMarkers(googleMap: GoogleMap) {
        val clusterManager = ClusterManager<Place>(activity, googleMap)
        clusterManager.renderer = context?.let {
            PlaceRenderer(
                it, googleMap, clusterManager
            )
        }

        // Set custom info window adapter
        clusterManager.markerCollection.setOnInfoWindowClickListener { marker ->
            val stopInfo = marker.title?.let { getItem(it) }
            val mainViewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
            val tripPlannerViewModel = ViewModelProvider(requireActivity())[TripPlannerViewModel::class.java]
            stopInfo?.let { stop ->
                when (origin) {
                    "editTextFrom" -> {
                        activity?.supportFragmentManager?.popBackStack("tripTypeAhead", 1)
                        tripPlannerViewModel.setSelectedFromStop(stop)
                    }

                    "editTextTo" -> {
                        activity?.supportFragmentManager?.popBackStack("tripTypeAhead", 1)
                        tripPlannerViewModel.setSelectedToStop(stop)
                    }

                    else -> {
                        activity?.supportFragmentManager?.popBackStack("typeAhead", 1)
                        mainViewModel.setSelectedStop(stop)
                    }
                }
            }
        }

        clusterManager.addItems(placesList)
        clusterManager.cluster()

        googleMap.setOnCameraIdleListener {
            clusterManager.onCameraIdle()
        }

    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val im: InputMethodManager? = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        im?.hideSoftInputFromWindow(view.windowToken, 0)
        val bounds = LatLngBounds.builder()
        stopsList.forEach {
            if (it.id != 0) bounds.include(LatLng(it.lat, it.lng))
        }
        val locationManager = activity?.getSystemService(AppCompatActivity.LOCATION_SERVICE) as LocationManager
        val actualLocation = locationManager.getLastKnownLocation(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
        )
        findNavController().previousBackStackEntry?.savedStateHandle
        getMapAsync { googleMap ->
            googleMap.mapType = MAP_TYPE_NONE
            googleMap.addTileOverlay(
                TileOverlayOptions()
                    .fadeIn(false)
                    .tileProvider(tileProvider)
            )
            googleMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 50))
//            if (isDarkTheme(resources)) googleMap.setMapStyle(MapStyleOptions(resources.getString(R.string.style_json)))
            googleMap.uiSettings.isMapToolbarEnabled = false
            googleMap.setOnMapLoadedCallback {
                googleMap.isMyLocationEnabled = true
                if (actualLocation != null) {
                    googleMap.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(
                                actualLocation.latitude, actualLocation.longitude
                            ), 16f
                        )
                    )
                }
            }
            addMarkers(googleMap)
        }
    }
}
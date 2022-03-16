package eu.magicsk.transi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.navigation.fragment.findNavController
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.clustering.ClusterManager
import com.google.maps.android.clustering.view.DefaultClusterRenderer
import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.data.remote.responses.StopsJSONItem
import kotlinx.android.synthetic.main.fragment_plan.*
import kotlinx.android.synthetic.main.fragment_search.*

class MapFragment : SupportMapFragment() {

    private lateinit var stopsList: StopsJSON
    private lateinit var origin: String
    private val placesList = arrayListOf<Place>()

    object BitmapHelper {
        fun vectorToBitmap(
            context: Context,
            @DrawableRes id: Int,
            @ColorInt color: Int
        ): BitmapDescriptor {
            val vectorDrawable = ResourcesCompat.getDrawable(context.resources, id, null)
                ?: return BitmapDescriptorFactory.defaultMarker()
            val bitmap = Bitmap.createBitmap(
                vectorDrawable.intrinsicWidth,
                vectorDrawable.intrinsicHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            vectorDrawable.setBounds(0, 0, canvas.width, canvas.height)
            DrawableCompat.setTint(vectorDrawable, color)
            vectorDrawable.draw(canvas)
            return BitmapDescriptorFactory.fromBitmap(bitmap)
        }
    }


    class PlaceRenderer(
        context: Context,
        map: GoogleMap,
        clusterManager: ClusterManager<Place>
    ) : DefaultClusterRenderer<Place>(context, map, clusterManager) {
        private val markerIcon: BitmapDescriptor by lazy {
            val color = ContextCompat.getColor(
                context,
                R.color.md_theme_light_primary
            )
            BitmapHelper.vectorToBitmap(
                context,
                R.drawable.ic_marker,
                color
            )
        }

        override fun onBeforeClusterItemRendered(
            item: Place,
            markerOptions: MarkerOptions
        ) {
            markerOptions.title(item.name)
                .position(item.latLng)
                .icon(markerIcon)
        }

        override fun onClusterItemRendered(clusterItem: Place, marker: Marker) {
            marker.tag = clusterItem
        }
    }


    data class Place(
        val station_id: Int,
        val name: String,
        val html: String,
        val url: String,
        val value: String,
        val type: String,
        val id: Int,
        val latLng: LatLng,
    ) : ClusterItem {
        override fun getPosition(): LatLng =
            latLng

        override fun getTitle(): String =
            name

        override fun getSnippet(): String =
            html
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stopsList = requireArguments().getSerializable("stopsList") as StopsJSON
        origin = requireArguments().getString("origin").toString()
        stopsList.forEach { stop ->
            if (stop.id != 0) placesList.add(
                Place(
                    stop.station_id,
                    stop.name,
                    stop.html,
                    stop.url,
                    stop.value,
                    stop.type,
                    stop.id,
                    LatLng(stop.lat, stop.long)
                )
            )
        }
    }

    private fun getItem(name: String): StopsJSONItem {
        stopsList.forEach {
            if (it.name == name) return it
        }
        return stopsList[0]
    }

    @SuppressLint("RestrictedApi")
    private fun addMarkers(googleMap: GoogleMap) {
        val clusterManager = ClusterManager<Place>(activity, googleMap)
        clusterManager.renderer =
            context?.let {
                PlaceRenderer(
                    it,
                    googleMap,
                    clusterManager
                )
            }

        // Set custom info window adapter
        clusterManager.markerCollection.setOnInfoWindowClickListener { marker ->
            val stopInfo = marker.title?.let { getItem(it) }
            val navController = findNavController()
            val planFragment =
                try {
                    activity?.supportFragmentManager?.findFragmentById(R.id.search_barFL) as PlanFragment?
                } catch (e: ClassCastException) {
                    println("not PlanFragment")
                    null
                }
            navController.backQueue.forEach {
                it.savedStateHandle.apply {
                    remove<Int>("selectedToStopId")
                    set("selectedStopId", stopInfo?.id)
                    set("origin", origin)
                }
            }
            stopInfo?.let { stop ->
                when (origin) {
                    "editTextFrom" -> {
                        activity?.editTextFrom?.setText(stop.name)
                        planFragment?.getTrip(from = stop.value)
                    }
                    "editTextTo" -> {
                        activity?.editTextTo?.setText(stop.name)
                        planFragment?.getTrip(to = stop.value)
                    }
                    else -> activity?.editText?.setText(stop.name)
                }
            }
            navController.popBackStack()
            navController.popBackStack()
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
        val locationManager =
            activity?.getSystemService(AppCompatActivity.LOCATION_SERVICE) as LocationManager
        val actualLocation = locationManager.getLastKnownLocation(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
        )
        findNavController().previousBackStackEntry?.savedStateHandle
        getMapAsync { googleMap ->
            googleMap.setMapStyle(MapStyleOptions(resources.getString(R.string.style_json)))
            googleMap.uiSettings.isMapToolbarEnabled = false
            googleMap.setOnMapLoadedCallback {
                googleMap.isMyLocationEnabled = true
                val bounds = LatLngBounds.builder()
                stopsList.forEach {
                    if (it.id != 0) bounds.include(LatLng(it.lat, it.long))
                }
                if (actualLocation != null) {
                    googleMap.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(
                                actualLocation.latitude,
                                actualLocation.longitude
                            ), 16f
                        )
                    )
                } else {
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 50))
                }
            }
            addMarkers(googleMap)
        }
    }
}
package eu.magicsk.transi

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
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

    class PlaceRenderer(
        private val context: Context,
        map: GoogleMap,
        clusterManager: ClusterManager<Place>
    ) : DefaultClusterRenderer<Place>(context, map, clusterManager) {

        //          ICON
//        private val bicycleIcon: BitmapDescriptor by lazy {
//            val color = ContextCompat.getColor(context,
//                R.color.colorPrimary
//            )
//            BitmapHelper.vectorToBitmap(
//                context,
//                R.drawable.ic_directions_bike_black_24dp,
//                color
//            )
//        }
        override fun onBeforeClusterItemRendered(
            item: Place,
            markerOptions: MarkerOptions
        ) {
            markerOptions.title(item.name)
                .position(item.latLng)
//                .icon(bicycleIcon)
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
            if (it.name == name) return it;
        }
        return stopsList[0];
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
            val stop = getItem(marker.title)
            val navController = findNavController()
            val planFragment =
                try {
                    activity?.supportFragmentManager?.findFragmentById(R.id.search_barFL) as PlanFragment?
                } catch (e: ClassCastException) {
                    println("not PlanFragment")
                    null
                }
            navController.backStack.forEach {
                it.savedStateHandle.apply {
                    remove<Int>("selectedToStopId")
                    set("selectedStopId", stop.id)
                    set("origin", origin)
                }
            }
            println(origin)
            println(stop.id)
            when (origin) {
                "editText" -> activity?.editText?.setText(stop.name)
                "editTextFrom" -> {
                    activity?.editTextFrom?.setText(stop.name)
                    planFragment?.getTrip(from = stop.value)
                }
                "editTextTo" -> {
                    activity?.editTextTo?.setText(stop.name)
                    planFragment?.getTrip(to = stop.value)
                }
            }
            navController.popBackStack()
            navController.popBackStack()
        }

        // Add the places to the ClusterManager.
        clusterManager.addItems(placesList)
        clusterManager.cluster()

        // Set ClusterManager as the OnCameraIdleListener so that it
        // can re-cluster when zooming in and out.
        googleMap.setOnCameraIdleListener {
            clusterManager.onCameraIdle()
        }

    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        findNavController().previousBackStackEntry?.savedStateHandle
        getMapAsync { googleMap ->
            googleMap.setOnMapLoadedCallback {
                val bounds = LatLngBounds.builder()
                stopsList.forEach {
                    if (it.id != 0) bounds.include(LatLng(it.lat.toDouble(), it.long.toDouble()))
                }
                googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 50))
                googleMap.isMyLocationEnabled = true
            }
            addMarkers(googleMap)
        }
    }
}
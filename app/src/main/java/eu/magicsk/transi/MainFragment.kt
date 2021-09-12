package eu.magicsk.transi

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context.LOCATION_SERVICE
import android.content.DialogInterface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import eu.magicsk.transi.adapters.MHDTableAdapter
import eu.magicsk.transi.adapters.TripPlannerAdapter
import eu.magicsk.transi.data.remote.responses.*
import eu.magicsk.transi.view_models.StopsListViewModel
import eu.magicsk.transi.view_models.TripPlannerViewModel
import kotlinx.android.synthetic.main.fragment_main.*
import kotlinx.android.synthetic.main.fragment_main.view.*
import kotlinx.android.synthetic.main.fragment_plan.*
import kotlinx.android.synthetic.main.fragment_search.*
import java.util.*
import kotlin.Comparator
import kotlin.math.*

@AndroidEntryPoint
class MainFragment : Fragment(R.layout.fragment_main) {
    private val tripPlannerAdapter = TripPlannerAdapter(mutableListOf())
    val searchFragment = SearchFragment()
    private val planFragment = PlanFragment()
    private val stopListBundle = Bundle()
    private val viewModel: StopsListViewModel by viewModels()
    val tripViewModel: TripPlannerViewModel by viewModels()
    var stopList: StopsJSON = StopsJSON()
    private lateinit var tripHolder: TripPlannerJSON
    var nearestSwitching: Boolean = true
    var waitingForLocation: Boolean = false
    private var selected: StopsJSONItem = StopsJSONItem(
        "Looking for the nearest stop…",
        "none",
        "/ba/zastavka/Hronsk%C3%A1/b68883",
        "g94",
        "bus",
        94,
        "48,13585663",
        "17,20938683",
        101,
        null
    )

    fun getStopById(id: Int): StopsJSONItem {
        stopList.let {
            for (i in 0 until stopList.size) {
                if (id == stopList[i].id) {
                    return stopList[i]
                }
            }
        }
        return selected
    }

    val tableAdapter: MHDTableAdapter = MHDTableAdapter(mutableListOf(), mutableListOf(), "")
    var actualLocation: Location? = null

    private fun calcDistance(x: StopsJSONItem): Double {
        x.lat?.let { lat ->
            x.long?.let { long ->
                val xLat = lat.replace(",", ".").toDouble()
                val xLong = long.replace(",", ".").toDouble()
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
                return radius * angle;
            }
        }
        return 100000000.0
    }

    private val sortByNearest = Comparator<StopsJSONItem> { a, b ->
        val aDist = calcDistance(a)
        val bDist = calcDistance(b)
        return@Comparator (aDist - bDist).roundToInt()
    }

    private val locationListener: LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            println("location changed")
            if (waitingForLocation) planFragment.getTrip()
            if (stopList.size > 0) {
                actualLocation = location
                stopList.sortWith(sortByNearest)
                if (nearestSwitching && selected != stopList[0]) {
                    val id = selected.id
                    selected = stopList[0]
                    MHDTableStopName?.text = selected.name
                    activity?.positionBtn?.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_my_location, context?.theme)
                    activity?.positionPlanBtn?.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_my_location, context?.theme)
                    if (id != selected.id) {
                        tableAdapter.ioDisconnect()
                        tableAdapter.ioConnect(selected.id)
                    }
                }
            }
        }

        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tableAdapter.ioConnect(selected.id)

        val locationManager = activity?.getSystemService(LOCATION_SERVICE) as LocationManager?
        val locationPermissionRequest = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            fun requestLocation() {
                locationManager?.requestLocationUpdates(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER,
                    0L,
                    0f,
                    locationListener
                )
            }
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

        activity?.let {

            tableAdapter.ioObservers(it)
            viewModel.stops.observe(it) { stops ->
                if (stops != null && stopList.size < 1) {
                    stopList.addAll(stops)

                    tableAdapter.putStopList(stopList)

                    stopListBundle.clear()
                    stopListBundle.putSerializable("stopsList", stopList)
                    val searchBundle = stopListBundle
                    searchBundle.putBoolean("nearestSwitching", nearestSwitching)
                    searchFragment.arguments = searchBundle
                    println("create search fragment")
                    activity?.supportFragmentManager?.beginTransaction()?.apply {
                        replace(R.id.search_barFL, searchFragment)
                        commit()
                    }
                    if (actualLocation != null) {
                        stopList.sortWith(sortByNearest)
                        if (nearestSwitching && selected != stopList[0]) {
                            val id = selected.id
                            selected = stopList[0]
                            MHDTableStopName?.text = selected.name
                            if (id != selected.id) {
                                tableAdapter.ioDisconnect()
                                tableAdapter.ioConnect(selected.id)
                            }
                        }
                    }
                    val infoText = tableAdapter.getInfoText()
                    if (infoText != "") {
                        MHDTableInfoText.text = infoText
                        MHDTableInfoText.visibility = View.VISIBLE
                    } else MHDTableInfoText.visibility = View.GONE
                }
            }
            tripViewModel.trip.observe(it) { trip ->
                waitingForLocation = false
                if (trip != null) {
                    it.progressBar_bg?.visibility = View.GONE
                    it.progressBar_ic?.visibility = View.GONE
                    if (trip.code == 200) {
                        it.TripPlannerList.visibility = View.VISIBLE
                        tripHolder = trip
                        tripPlannerAdapter.addItems(trip.routes as MutableList<Route>)
                    } else {
                        val errorAlertBuilder = AlertDialog.Builder(activity)
                        errorAlertBuilder.setTitle(getString(R.string.ops))
                            .setPositiveButton("OK", DialogInterface.OnClickListener { dialog, _ ->
                                dialog.cancel()
                            })
                        val errorAlert = errorAlertBuilder.create()

                        when (trip.code) {
                            400 -> errorAlert.setMessage(getString(R.string.error400))
                            404 -> errorAlert.setMessage(getString(R.string.error404))
                            else -> errorAlert.setMessage(getString(R.string.unknownError))
                        }
                        errorAlert.show()
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        println("pause")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.editText?.clearFocus()
        activity?.editTextFrom?.clearFocus()
        activity?.editTextTo?.clearFocus()

        findNavController().currentBackStackEntry?.savedStateHandle?.apply {
            getLiveData<Int>("selectedStopId").observe(viewLifecycleOwner) { id ->
                getLiveData<String>("origin").observe(viewLifecycleOwner) { origin ->
                    if (origin == "editText") {
                        nearestSwitching = false
                        selected = getStopById(id)
                        MHDTableStopName.text = selected.name
                        activity?.positionBtn?.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_my_location, context?.theme)
                        activity?.positionPlanBtn?.icon =
                            ResourcesCompat.getDrawable(resources, R.drawable.ic_my_location, context?.theme)
                        tableAdapter.ioDisconnect()
                        tableAdapter.ioConnect(selected.id)
                    } else planFragment.getTrip()
                }
            }

            getLiveData<Int>("selectedToStopId").observe(viewLifecycleOwner) { id ->
                activity?.apply {
                    val planBundle = stopListBundle
                    planBundle.putInt("selectedToStopId", id)
                    planFragment.arguments = planBundle
                    println("create plan fragment")
                    supportFragmentManager.beginTransaction().apply {
                        replace(R.id.search_barFL, planFragment)
//                        addToBackStack(null)
                        commit()
                    }
                }
            }
        }

        MHDTableStopName.text = selected.name
        MHDTableList.adapter = tableAdapter
        MHDTableList.layoutManager = LinearLayoutManager(context)
        TripPlannerList.adapter = tripPlannerAdapter
        TripPlannerList.layoutManager = LinearLayoutManager(context)

        TripPlannerList.visibility = if (::tripHolder.isInitialized) View.VISIBLE else View.GONE

        val infoText = tableAdapter.getInfoText()
        if (infoText != "") {
            MHDTableInfoText.text = infoText
            MHDTableInfoText.visibility = View.VISIBLE
        } else MHDTableInfoText.visibility = View.GONE
    }
}

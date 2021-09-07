package eu.magicsk.transi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context.LOCATION_SERVICE
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import eu.magicsk.transi.adapters.MHDTableAdapter
import eu.magicsk.transi.adapters.TripPlannerAdapter
import eu.magicsk.transi.data.remote.responses.Route
import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.data.remote.responses.StopsJSONItem
import eu.magicsk.transi.databinding.FragmentMainBinding
import eu.magicsk.transi.view_models.StopsListViewModel
import eu.magicsk.transi.view_models.TripPlannerViewModel
import kotlinx.android.synthetic.main.fragment_main.*
import kotlinx.android.synthetic.main.fragment_main.view.*
import kotlinx.android.synthetic.main.fragment_plan.*
import kotlinx.android.synthetic.main.fragment_search.*
import kotlinx.android.synthetic.main.fragment_search.positionBtn
import java.text.SimpleDateFormat
import java.util.*
import kotlin.Comparator
import kotlin.math.*

@AndroidEntryPoint
class MainFragment : Fragment(R.layout.fragment_main) {
    private lateinit var tripPlannerAdapter: TripPlannerAdapter
    private val searchFragment = SearchFragment()
    private val planFragment = PlanFragment()
    private val stopListBundle = Bundle()
    private val viewModel: StopsListViewModel by viewModels()
    private val tripViewModel: TripPlannerViewModel by viewModels()
    private var stopList: StopsJSON = StopsJSON()
    private var nearestSwitching: Boolean = true
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

    private fun getStopById(id: Int): StopsJSONItem {
        stopList.let {
            for (i in 0 until stopList.size) {
                if (id == stopList[i].id) {
                    return stopList[i]
                }
            }
        }
        return selected
    }

    private fun onListItemClick(pos: Int) {
        val info = tableAdapter.getListItem(pos)
        Toast.makeText(
            context,
            "Departure: ${
                SimpleDateFormat(
                    "H:mm",
                    Locale.UK
                ).format(info.departureTime)
            } Delay: ${info.delay} min LastStop: ${info.lastStopName} ID: ${info.busID}",
            Toast.LENGTH_SHORT
        ).show()
    }


    private var tableAdapter: MHDTableAdapter =
        MHDTableAdapter(mutableListOf(), "") { position -> onListItemClick(position) }

    private var _binding: FragmentMainBinding? = null
    private val binding: FragmentMainBinding get() = _binding!!
    private lateinit var actualLocation: Location

    private fun calcDistance(x: StopsJSONItem): Double {
        x.lat?.let { lat ->
            x.long?.let { long ->
                val xLat = lat.replace(",", ".").toDouble()
                val xLong = long.replace(",", ".").toDouble()
                val radius = 6378137.toDouble()
                val deltaLat = xLat - actualLocation.latitude
                val deltaLong = xLong - actualLocation.longitude
                val angle = 2 * asin(
                    sqrt(
                        sin(deltaLat / 2).pow(2.0) +
                                cos(actualLocation.latitude) * cos(xLat) *
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

    private var locationType: String = "NONE"
    private val locationListener: LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            println("location changed")
            if (stopList.size > 0) {
                actualLocation = location
                stopList.sortWith(sortByNearest)
                if (nearestSwitching && selected != stopList[0]) {
                    activity?.positionBtn?.icon =
                        resources.getDrawable(R.drawable.ic_my_location, context?.theme)
                    val id = selected.id
                    selected = stopList[0]
                    MHDTableStopName?.text = selected.name
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
            when {
                permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
                    locationType = "FINE"
                    locationManager?.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        0L,
                        0f,
                        locationListener
                    )
                }
                permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                    locationType = "COARSE"
                    locationManager?.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        0L,
                        0f,
                        locationListener
                    )
                }
                else -> {
                    locationType = "REFUSED"
                }
            }
        }

        if (locationType == "NONE") {
            locationPermissionRequest.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
        activity?.let { tableAdapter.ioObservers(it) }
    }

    override fun onPause() {
        super.onPause()
        println("pause")
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.editText?.clearFocus()
        if (stopList.size < 1) {
            viewModel.stops.observe(viewLifecycleOwner) { stops ->
                if (stops != null) {
                    stopList.clear()
                    stopList.addAll(stops)
                    tableAdapter.putStopList(stopList)
                    stopListBundle.clear()
                    stopListBundle.putSerializable("stopsList", stopList)
                    searchFragment.arguments = stopListBundle
                    activity?.apply {
                        supportFragmentManager.beginTransaction().apply {
                            replace(R.id.search_barFL, searchFragment)
                            commit()
                            runOnCommit {
                                activity?.positionBtn?.setOnClickListener {
                                    nearestSwitching = !nearestSwitching
                                    if (nearestSwitching) {
                                        activity?.positionBtn?.icon =
                                            resources.getDrawable(
                                                R.drawable.ic_my_location,
                                                context?.theme
                                            )
                                        selected = stopList[0]
                                        MHDTableStopName?.text = selected.name
                                        tableAdapter.ioDisconnect()
                                        tableAdapter.ioConnect(selected.id)
                                    } else {
                                        activity?.positionBtn?.icon =
                                            resources.getDrawable(
                                                R.drawable.ic_location_disabled,
                                                context?.theme
                                            )
                                    }
                                }
                            }
                        }
                    }
                    if (this::actualLocation.isInitialized) {
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
        }
        tripViewModel.trip.observe(viewLifecycleOwner) { trip ->
            if (trip != null) {
                println("Trip status: ${trip.status}")
                if (trip.status == "OK") {
                    println("Trip length: ${trip.routes.size}")
                    tripPlannerAdapter = TripPlannerAdapter(trip.routes as MutableList<Route>)
                    TripPlannerList.adapter = tripPlannerAdapter
                    TripPlannerList.layoutManager = LinearLayoutManager(context)
                    TripPlannerList.visibility = View.VISIBLE
                }
            }
        }
        _binding = FragmentMainBinding.bind(view)

        // get selected stop
        findNavController().currentBackStackEntry?.savedStateHandle?.getLiveData<Int>("selectedStopId")
            ?.observe(viewLifecycleOwner) { id ->
                nearestSwitching = false
                activity?.positionBtn?.icon =
                    resources.getDrawable(R.drawable.ic_location_disabled, context?.theme)
                selected = getStopById(id)
                MHDTableStopName.text = selected.name
                tableAdapter.ioDisconnect()
                tableAdapter.ioConnect(selected.id)
            }
        findNavController().currentBackStackEntry?.savedStateHandle?.getLiveData<Int>("selectedToStopId")
            ?.observe(viewLifecycleOwner) { id ->
                activity?.apply {
                    supportFragmentManager.beginTransaction().apply {
                        replace(R.id.search_barFL, planFragment)
//                        addToBackStack(null)
                        commit()
                        runOnCommit {
                            editTextFrom?.setText(getString(R.string.actual_position))
                            editTextTo?.setText(getStopById(id).name)
                            positionBtn?.setOnClickListener {
                                nearestSwitching = !nearestSwitching
                                if (nearestSwitching) {
                                    positionBtn?.icon =
                                        resources.getDrawable(
                                            R.drawable.ic_my_location,
                                            context?.theme
                                        )
                                    selected = stopList[0]
                                    MHDTableStopName?.text = selected.name
                                    tableAdapter.ioDisconnect()
                                    tableAdapter.ioConnect(selected.id)
                                } else {
                                    positionBtn?.icon =
                                        resources.getDrawable(
                                            R.drawable.ic_location_disabled,
                                            context?.theme
                                        )
                                }
                            }
                            positionBtn?.icon = if (nearestSwitching) {
                                resources.getDrawable(
                                    R.drawable.ic_my_location,
                                    context?.theme
                                )
                            } else {
                                resources.getDrawable(
                                    R.drawable.ic_location_disabled,
                                    context?.theme
                                )
                            }
                            backBtn?.setOnClickListener {
                                supportFragmentManager.beginTransaction().apply {
                                    replace(R.id.search_barFL, searchFragment)
                                    commit()
                                }
                            }
                        }
                    }
                }
            }

        MHDTableStopName.text = selected.name
        MHDTableList.adapter = tableAdapter
        MHDTableList.layoutManager = LinearLayoutManager(context)
        val infoText = tableAdapter.getInfoText()
        if (infoText != "") {
            MHDTableInfoText.text = infoText
            MHDTableInfoText.visibility = View.VISIBLE
        } else MHDTableInfoText.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

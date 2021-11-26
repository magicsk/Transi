package eu.magicsk.transi

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.os.Bundle
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import eu.magicsk.transi.adapters.MHDTableAdapter
import eu.magicsk.transi.adapters.TripPlannerAdapter
import eu.magicsk.transi.data.remote.responses.Route
import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.data.remote.responses.StopsJSONItem
import eu.magicsk.transi.data.remote.responses.TripPlannerJSON
import eu.magicsk.transi.view_models.StopsListVersionViewModel
import eu.magicsk.transi.view_models.StopsListViewModel
import eu.magicsk.transi.view_models.TripPlannerViewModel
import kotlinx.android.synthetic.main.fragment_main.*
import kotlinx.android.synthetic.main.fragment_plan.*
import kotlinx.android.synthetic.main.fragment_search.*
import java.util.*
import kotlin.math.*

@AndroidEntryPoint
class MainFragment : Fragment(R.layout.fragment_main) {
    private val tripPlannerAdapter = TripPlannerAdapter(mutableListOf())
    val searchFragment = SearchFragment()
    private val planFragment = PlanFragment()
    private val stopListBundle = Bundle()
    private val stopsViewModel: StopsListViewModel by viewModels()
    private val stopsVersionViewModel: StopsListVersionViewModel by viewModels()
    val tripViewModel: TripPlannerViewModel by viewModels()
    var stopList: StopsJSON = StopsJSON()
    private lateinit var sharedPreferences: SharedPreferences
    private var tripHolder = TripPlannerJSON(listOf(), "", 0)
    private var selectedToStop = 0
    var nearestSwitching: Boolean = true
    var waitingForLocation: Boolean = false
    var selected: StopsJSONItem = StopsJSONItem(
        0,
        "Locating nearest stop…",
        "none",
        "/ba/zastavka/Hronsk%C3%A1/b68883",
        "g94",
        "bus",
        94,
        48.13585663,
        17.20938683,
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

    val tableAdapter: MHDTableAdapter = MHDTableAdapter(mutableListOf(), mutableListOf())
    var actualLocation: Location? = null

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

    private fun createChannel(channelId: String, channelName: String, description: String) {
        val notificationChannel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setShowBadge(false)
        }

        notificationChannel.description = description

        val notificationManager = requireActivity().getSystemService(
            NotificationManager::class.java
        )
        notificationManager.createNotificationChannel(notificationChannel)
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        val savedSelected = savedInstanceState?.getSerializable("selectedStop") as? StopsJSONItem
        val savedLocation = savedInstanceState?.getParcelable("actualLocation") as? Location
        val savedNearestSwitching = savedInstanceState?.getBoolean("nearestSwitching")
        val savedWaitingForLocation = savedInstanceState?.getBoolean("waitingForLocation")
        val savedTripHolder = savedInstanceState?.getParcelable("tripHolder") as? TripPlannerJSON
        val savedSelectedToStop = savedInstanceState?.getInt("selectedToStop")
        if (savedSelected != null) selected = savedSelected
        if (savedLocation != null) actualLocation = savedLocation
        if (savedNearestSwitching != null) nearestSwitching = savedNearestSwitching
        if (savedWaitingForLocation != null) waitingForLocation = savedWaitingForLocation
//        if (savedTripHolder != null) tripHolder = savedTripHolder
//        if (savedSelectedToStop != null) selectedToStop = savedSelectedToStop

        sharedPreferences = context?.getSharedPreferences("Transi", Context.MODE_PRIVATE)!!
        val savedStopListJson = sharedPreferences.getString("stopList", "")
        activity?.let {
            val savedStopListVersion = sharedPreferences.getString("stopsVersion", "")
            stopsVersionViewModel.stopsVersion.observe(it) { stopsVersion ->
                if (savedStopListVersion != stopsVersion.version || savedStopListJson == "") {
                    stopsViewModel.stops.observe(it) { stops ->
                        if (stops != null && stopList.size < 1) {
                            println("stops fetched")
                            stopList.addAll(stops)
                            val stopListJson = Gson().toJson(stopList)
                            sharedPreferences.edit().putString("stopList", stopListJson).apply()
                            sharedPreferences.edit().putString("stopsVersion", stopsVersion.version)
                                .apply()
                            actualLocation?.let { l -> locationChange(l) }
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
                        }
                    }
                } else {
                    val savedStopList = Gson().fromJson(savedStopListJson, StopsJSON::class.java)
                    stopList.addAll(savedStopList)
                    actualLocation?.let { l -> locationChange(l) }
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

                }
            }
            tableAdapter.ioObservers(it)
            tripViewModel.trip.observe(it) { trip ->
                waitingForLocation = false
                if (trip != null) {
                    it.progressBar_bg?.visibility = View.GONE
                    it.progressBar_ic?.visibility = View.GONE
                    if (trip.code == 200) {
                        it.TripPlannerList?.visibility = View.VISIBLE
                        tripHolder = trip
                        tripPlannerAdapter.addItems(trip.routes as MutableList<Route>)
                    } else {
                        val errorAlertBuilder = AlertDialog.Builder(activity)
                        errorAlertBuilder.setTitle(getString(R.string.ops))
                            .setPositiveButton("OK") { dialog, _ ->
                                dialog.cancel()
                            }
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
            val thread: Thread = object : Thread() {
                override fun run() {
                    try {
                        while (!this.isInterrupted) {
                            sleep(1000)
                            it.runOnUiThread {
                                val calendar = Calendar.getInstance()
                                MHDTableActualTime?.text = it.getString(
                                    R.string.actualTime,
                                    calendar.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0'),
                                    calendar.get(Calendar.MINUTE).toString().padStart(2, '0'),
                                    calendar.get(Calendar.SECOND).toString().padStart(2, '0')
                                )
                            }
                        }
                    } catch (e: InterruptedException) {
                    }
                }
            }
            thread.start()
            tableAdapter.startUpdater(it)
        }
    }

    fun locationChange(location: Location) {
        actualLocation = location
        if (waitingForLocation) planFragment.getTrip()
        if (stopList.size > 0) {
            stopList.sortWith(sortByNearest)
            if (nearestSwitching && selected != stopList[0]) {
                val id = selected.id
                selected = stopList[0]
                MHDTableStopName?.text = selected.name
                activity?.positionBtn?.icon = ResourcesCompat.getDrawable(
                    resources,
                    R.drawable.ic_my_location,
                    context?.theme
                )
                activity?.positionPlanBtn?.icon = ResourcesCompat.getDrawable(
                    resources,
                    R.drawable.ic_my_location,
                    context?.theme
                )
                if (id != selected.id) {
                    tableAdapter.ioDisconnect()
                    tableAdapter.ioConnect(selected.id)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        tableAdapter.ioDisconnect()
    }

    override fun onResume() {
        super.onResume()
        tableAdapter.ioConnect(selected.id)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.editText?.clearFocus()
        activity?.editTextFrom?.clearFocus()
        activity?.editTextTo?.clearFocus()

        actualLocation?.let { locationChange(it) }

        findNavController().currentBackStackEntry?.savedStateHandle?.apply {
            getLiveData<Int>("selectedStopId").observe(viewLifecycleOwner) { id ->
                getLiveData<String>("origin").observe(viewLifecycleOwner) { origin ->
                    if (origin != "editTextTo") {
                        nearestSwitching = false
                        selected = getStopById(id)
                        MHDTableStopName.text = selected.name
                        activity?.positionBtn?.icon =
                            ResourcesCompat.getDrawable(
                                resources,
                                R.drawable.ic_location_disabled,
                                context?.theme
                            )
                        tableAdapter.ioDisconnect()
                        tableAdapter.ioConnect(selected.id)
                    }
                }
            }

            getLiveData<Int>("selectedToStopId").observe(viewLifecycleOwner) { id ->
                activity?.apply {
                    selectedToStop = id
                    val planBundle = stopListBundle
                    planBundle.putInt("selectedToStopId", id)
                    planFragment.arguments = planBundle
                    println("create plan fragment")
                    supportFragmentManager.beginTransaction().apply {
                        replace(R.id.search_barFL, planFragment)
                        commit()
                    }
                }
            }

            if (selectedToStop > 0) {
                activity?.apply {
                    val planBundle = stopListBundle
                    planBundle.putInt("selectedToStopId", id)
                    planFragment.arguments = planBundle
                    println("create plan fragment")
                    supportFragmentManager.beginTransaction().apply {
                        replace(R.id.search_barFL, planFragment)
                        commit()
                    }
                }
            }
        }

        if (selected.html != "none") MHDTableStopName.text = selected.name
        val calendar = Calendar.getInstance()
        MHDTableActualTime?.text = context?.getString(
            R.string.actualTime,
            calendar.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0'),
            calendar.get(Calendar.MINUTE).toString().padStart(2, '0'),
            calendar.get(Calendar.SECOND).toString().padStart(2, '0')
        )
        MHDTableList.adapter = tableAdapter
        MHDTableList.layoutManager = LinearLayoutManager(context)
        TripPlannerList.adapter = tripPlannerAdapter
        TripPlannerList.layoutManager = LinearLayoutManager(context)
        TripPlannerList.visibility = if (tripHolder.code == 200) View.VISIBLE else View.GONE
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun onMove(
                v: RecyclerView,
                h: RecyclerView.ViewHolder,
                t: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(h: RecyclerView.ViewHolder, dir: Int) {
                tripPlannerAdapter.clear()
                TripPlannerList.visibility = View.GONE
                tripHolder = TripPlannerJSON(listOf(), "", 0)
            }
        }).attachToRecyclerView(TripPlannerList)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable("selectedStop", selected)
        if (actualLocation != null) outState.putParcelable("actualLocation", actualLocation)
        outState.putParcelable("tripHolder", tripHolder)
        outState.putInt("selectedToStop", selectedToStop)
        outState.putBoolean("nearestSwitching", nearestSwitching)
        outState.putBoolean("waitingForLocation", waitingForLocation)
    }
}

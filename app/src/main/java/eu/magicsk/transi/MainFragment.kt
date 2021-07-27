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
import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.data.remote.responses.StopsJSONItem
import eu.magicsk.transi.databinding.FragmentMainBinding
import eu.magicsk.transi.view_models.StopsListViewModel
import kotlinx.android.synthetic.main.fragment_main.*
import java.text.SimpleDateFormat
import java.util.*


@AndroidEntryPoint
class MainFragment : Fragment(R.layout.fragment_main) {
    private val viewModel: StopsListViewModel by viewModels()
    private var stopList: StopsJSON = StopsJSON()
    private var selected: StopsJSONItem = StopsJSONItem(
        "stop",
        "none",
        94,
        "48,13585663",
        "17,20938683",
        "none",
        "/ba/zastavka/Hronsk%C3%A1/b68883",
        "g94"
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
        val info = tableAdapter.getInfo(pos)
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
        MHDTableAdapter(mutableListOf()) { position -> onListItemClick(position) }

    private var _binding: FragmentMainBinding? = null
    private val binding: FragmentMainBinding get() = _binding!!


//    val comp: Comparator<in StopsJSONItem> = Comparator()

    private var locationType: String = "NONE"
    private val locationListener: LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            println("" + location.longitude + ":" + location.latitude)
//            stopList.sortWith(comp)
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
                    locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, locationListener)
                }
                permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                    locationType = "COARSE"
                    locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, locationListener)
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
        val thread: Thread = object : Thread() {
            override fun run() {
                try {
                    while (!this.isInterrupted) {
                        sleep(1000)
                        activity?.runOnUiThread(Runnable {
//                            tableAdapter.update()
                        })
                    }
                } catch (e: InterruptedException) {
                }
            }
        }
//        thread.start();
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.stops.observe(viewLifecycleOwner) { stops ->
            if (stops != null) {
                stopList.clear()
                stopList.addAll(stops)
            }
        }
        _binding = FragmentMainBinding.bind(view)

        // get selected stop
        findNavController().currentBackStackEntry?.savedStateHandle?.getLiveData<Int>("selectedStopId")
            ?.observe(viewLifecycleOwner) { id ->
                selected = getStopById(id)
                MHDTableStopName.text = selected.name
                tableAdapter.ioDisconnect()
                tableAdapter.ioConnect(selected.id)
            }

        MHDTableList.adapter = tableAdapter
        MHDTableList.layoutManager = LinearLayoutManager(context)

        // send list to searchbar
        val bundle = Bundle()
        bundle.clear()
        bundle.putSerializable("stopsList", stopList)
        binding.editTextFake.setOnClickListener {
            findNavController().navigate(
                R.id.action_mainFragment_to_typeAheadFragment,
                bundle,
                null
            )
        }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

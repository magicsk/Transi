package eu.magicsk.transi

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.location.Location
import android.os.Bundle
import android.os.Parcelable
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import eu.magicsk.transi.adapters.TripPlannerAdapter
import eu.magicsk.transi.data.models.SelectedTrip
import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.databinding.FragmentTripPlannerBinding
import eu.magicsk.transi.util.Trip
import eu.magicsk.transi.util.simpleErrorAlert
import eu.magicsk.transi.util.tripPlannerJsonParser
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.TripPlannerViewModel
import java.util.*


@AndroidEntryPoint
class TripPlannerFragment : Fragment() {
    private var _binding: FragmentTripPlannerBinding? = null
    private val binding get() = _binding!!
    private val tripPlannerAdapter = TripPlannerAdapter(mutableListOf())
    private val typeAheadFragment = TypeAheadFragment()
    private val tripViewModel: TripPlannerViewModel by viewModels()
    private var tripHolder: MutableList<Trip> = mutableListOf()
    private var selectedTripCalendar = Calendar.getInstance()
    private var selectedTrip = SelectedTrip()
    private var waitingForLocation: Boolean = true
    private var stopList = StopsJSON()
    private var actualLocation: Location? = null
    private var loadingMore: Boolean = false
    private var loadingNewTrip: Boolean = false
    private var listState: Parcelable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTripPlannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun getTrip(
        v: Int = selectedTrip.v,
        from: String = selectedTrip.from,
        to: String = selectedTrip.to,
        date: String = selectedTrip.date,
        time: String = selectedTrip.time,
        arrivalDeparture: Int = selectedTrip.arrivalDeparture,
        features: String = selectedTrip.features,
        preference: Int = selectedTrip.preference,
        moreTimeForTransfer: Int = selectedTrip.moreTimeForTransfer,
        transportType: String = selectedTrip.transportType,
        rate: String = selectedTrip.rate,
        carriers: String = selectedTrip.carriers,
        service: String = selectedTrip.service,
        format: Int = selectedTrip.format
    ) {
        loadingNewTrip = true
        selectedTrip = SelectedTrip(
            v,
            from,
            to,
            date,
            time,
            arrivalDeparture,
            features,
            preference,
            moreTimeForTransfer,
            transportType,
            rate,
            carriers,
            service,
            format
        )
        if (selectedTrip.to != "") {
            activity?.findViewById<ProgressBar>(R.id.progressBar_ic)?.isVisible = true
            if ((to == "" || from == "") || (to == "0" && from == "0")) {
                activity?.let { simpleErrorAlert(it, getString(R.string.ops), getString(R.string.error400)) }
            } else {
                if ((to == "0" || from == "0") && actualLocation == null) {
                    waitingForLocation = true
                } else if (actualLocation != null) {
                    val lat = actualLocation!!.latitude
                    val long = actualLocation!!.longitude
                    tripViewModel.getTrip(
                        v,
                        if (from == "0") "c$lat,$long" else from,
                        if (to == "0") "c$lat,$long" else to,
                        date,
                        time,
                        arrivalDeparture,
                        features,
                        preference,
                        moreTimeForTransfer,
                        transportType,
                        rate,
                        carriers,
                        service,
                        format
                    )
                } else {
                    activity?.let { simpleErrorAlert(it, getString(R.string.ops), getString(R.string.error400)) }
                }
            }
        }
    }

    private fun openTypeAhead(typeAheadFragment: TypeAheadFragment) {
//        if (navController.backQueue.size > 2) navController.popBackStack()
        binding.TripPlannerList.visibility = View.GONE
        binding.tripSearchFragmentLayout.visibility = View.VISIBLE
        activity?.supportFragmentManager?.beginTransaction()?.apply {
            replace(R.id.tripSearchFragmentLayout, typeAheadFragment).addToBackStack("tripTypeAhead").commit()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activity?.let {
            tripViewModel.trip.observe(it) { trip ->
                if (trip != null && (loadingMore || loadingNewTrip)) {
                    activity?.findViewById<LinearLayout>(R.id.progressBar_bg)?.isVisible = false
                    activity?.findViewById<ProgressBar>(R.id.progressBar_ic)?.isVisible = false
                    println(trip)
                    val parsedTrip = tripPlannerJsonParser(trip, activity!!, context!!)
                    if (parsedTrip != null) {
                        if (loadingMore) {
                            loadingMore = false
                            tripPlannerAdapter.addMoreItems(parsedTrip)
                            tripHolder.addAll(parsedTrip)
                        } else {
                            tripHolder = parsedTrip
                            tripPlannerAdapter.addItems(parsedTrip)
                            if (!_binding?.editTextTo?.text.isNullOrEmpty()) {
                                _binding?.TripPlannerName?.text =
                                    resources.getString(R.string.tripPlannerTitle)
                                        .format(_binding?.editTextFrom?.text, _binding?.editTextTo?.text)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mainViewModel = ViewModelProvider(requireActivity()).get(MainViewModel::class.java)
        val tripPlannerViewModel = ViewModelProvider(requireActivity()).get(TripPlannerViewModel::class.java)
        binding.apply {
            editTextFrom.clearFocus()
            editTextTo.clearFocus()
            TripPlannerList.adapter = tripPlannerAdapter
            TripPlannerList.layoutManager = LinearLayoutManager(context)
            TripPlannerName.isSelected = true

            mainViewModel.stopList.observe(viewLifecycleOwner) { stopsJSON ->
                stopList = stopsJSON
                val typeAheadBundle = Bundle()
                typeAheadBundle.clear()
                typeAheadBundle.putSerializable("stopsList", stopList)
                typeAheadBundle.putBoolean("directions", false)
                typeAheadFragment.arguments = typeAheadBundle
                if (tripPlannerAdapter.itemCount > 0) TripPlannerName.text =
                    resources.getString(R.string.tripPlannerTitle).format(editTextFrom.text, editTextTo.text)
            }
            mainViewModel.actualLocation.observe(viewLifecycleOwner) { location ->
                actualLocation = location
                waitingForLocation = false
                if (editTextFrom.text.isNullOrEmpty()) {
                    editTextFrom.setText(context?.getString(R.string.actual_position))
                    selectedTrip.from = "0"
                    tripPlannerAdapter.setFromTo(newFrom = context!!.getString(R.string.actual_position))
                }
            }
            tripPlannerViewModel.clear()
            tripPlannerViewModel.selectedFromStop.observe(viewLifecycleOwner) {
                activity?.window?.statusBarColor = MaterialColors.getColor(view, R.attr.colorMyBackground)
                if (selectedTrip.from != it?.value && it != null) {
                    editTextFrom.setText(it.name)
                    getTrip(from = it.value)
                    tripPlannerAdapter.setFromTo(newFrom = it.name)
                }

            }
            tripPlannerViewModel.selectedToStop.observe(viewLifecycleOwner) {
                activity?.window?.statusBarColor = MaterialColors.getColor(view, R.attr.colorMyBackground)
                if (selectedTrip.to != it?.value && it != null) {
                    editTextTo.setText(it.name)
                    getTrip(to = it.value)
                    tripPlannerAdapter.setFromTo(newTo = it.name)
                }
            }

            activity?.supportFragmentManager?.addOnBackStackChangedListener {
                val fragment = activity?.supportFragmentManager?.findFragmentById(R.id.tripSearchFragmentLayout)
                if (fragment == null) {
                    _binding?.editTextFrom?.clearFocus()
                    _binding?.editTextTo?.clearFocus()
                    _binding?.TripPlannerList?.visibility = View.VISIBLE
                    _binding?.tripSearchFragmentLayout?.visibility = View.GONE
                }
            }

            TripPlannerList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    if (layoutManager.findLastCompletelyVisibleItemPosition() >= tripPlannerAdapter.itemCount - 1) {
                        if (!loadingMore) {
                            loadingMore = true
                            val lastTrip = tripPlannerAdapter.TripPlannerItemList.last()
                            getTrip(date = lastTrip.date, time = lastTrip.departure)
                        }
                    }
                }
            })

            val timePickerListener = TimePickerDialog.OnTimeSetListener { _, hours, minutes ->
                selectedTripCalendar.set(
                    selectedTripCalendar.get(Calendar.YEAR),
                    selectedTripCalendar.get(Calendar.MONTH),
                    selectedTripCalendar.get(Calendar.DAY_OF_MONTH),
                    hours,
                    minutes
                )
                getTrip(
                    date = "${selectedTripCalendar.get(Calendar.YEAR)}-${
                        (selectedTripCalendar.get(Calendar.MONTH) + 1).toString().padStart(2, Char(48))
                    }-${selectedTripCalendar.get(Calendar.DAY_OF_MONTH).toString().padStart(2, Char(48))}",
                    time = "${
                        selectedTripCalendar.get(Calendar.HOUR_OF_DAY).toString().padStart(2, Char(48))
                    }:${selectedTripCalendar.get(Calendar.MINUTE).toString().padStart(2, Char(48))}"
                )
            }

            switchBtn.setOnClickListener {
                val tempText = editTextFrom.text
                editTextFrom.text = editTextTo.text
                editTextTo.text = tempText
                val tempValue = selectedTrip.to
                selectedTrip.to = selectedTrip.from
                selectedTrip.from = tempValue
                if (selectedTrip.from != "" && selectedTrip.to != "") {
                    getTrip()
                }
            }

            timeDateBtn.setOnClickListener {
                val datePickerDialog = DatePickerDialog(
                    requireContext(),
                    null,
                    selectedTripCalendar.get(Calendar.YEAR),
                    selectedTripCalendar.get(Calendar.MONTH) + 1,
                    selectedTripCalendar.get(Calendar.DAY_OF_MONTH)
                )
                datePickerDialog.datePicker.minDate = System.currentTimeMillis() - 86400000
                datePickerDialog.show()
                datePickerDialog.setOnDateSetListener { _, year, month, day ->
                    selectedTripCalendar.set(year, month, day)
                    val timePickerDialog = TimePickerDialog(
                        requireContext(),
                        timePickerListener,
                        selectedTripCalendar.get(Calendar.HOUR_OF_DAY),
                        selectedTripCalendar.get(Calendar.MINUTE),
                        DateFormat.is24HourFormat(context)
                    )
                    timePickerDialog.show()
                }
            }

            adPlanBtn.setOnClickListener {
                val toastText: String
                if (selectedTrip.arrivalDeparture == 0) {
                    selectedTrip.arrivalDeparture = 1
                    toastText = "arrival"
                } else {
                    selectedTrip.arrivalDeparture = 0
                    toastText = "departure"
                }
                getTrip()
                Toast.makeText(context, "Set to time of $toastText.", Toast.LENGTH_SHORT).show()
            }

            editTextFrom.setOnFocusChangeListener { _, b ->
                typeAheadFragment.arguments?.putString("origin", "editTextFrom")
                if (b) openTypeAhead(typeAheadFragment)
            }


            editTextTo.setOnFocusChangeListener { _, b ->
                typeAheadFragment.arguments?.putString("origin", "editTextTo")
                if (b) openTypeAhead(typeAheadFragment)
            }

            timeDateBtn.setOnLongClickListener {
                selectedTripCalendar = Calendar.getInstance()
                getTrip(date = "", time = "")
                Toast.makeText(context, "Changed to current date and time.", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        listState = savedInstanceState?.getParcelable("tripPlannerAdapter")
        val thar = savedInstanceState?.getParcelableArray("tripHolder") as? Array<Trip>
        tripHolder = thar?.toMutableList() ?: tripHolder
        println(tripHolder.size)
        selectedTrip = savedInstanceState?.getSerializable("selectedTrip") as? SelectedTrip ?: selectedTrip
    }

    override fun onResume() {
        println("resume")
        super.onResume()
        if (tripHolder.isNotEmpty()) {
            tripPlannerAdapter.addItems(tripHolder)
            if (!_binding?.editTextTo?.text.isNullOrEmpty()) {
                _binding?.TripPlannerName?.text =
                    resources.getString(R.string.tripPlannerTitle)
                        .format(_binding?.editTextFrom?.text, _binding?.editTextTo?.text)
            }
        }
        if (listState != null) activity?.findViewById<RecyclerView>(R.id.TripPlannerList)?.layoutManager?.onRestoreInstanceState(
            listState
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable("selectedTrip", selectedTrip)
        outState.putParcelableArray("tripHolder", tripHolder.toTypedArray())
        listState = activity?.findViewById<RecyclerView>(R.id.TripPlannerList)?.layoutManager?.onSaveInstanceState()
        outState.putParcelable("tripPlannerAdapter", listState)
    }

}
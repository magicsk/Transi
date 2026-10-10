package eu.magicsk.transi

import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import eu.magicsk.transi.adapters.TimetableAdapter
import eu.magicsk.transi.data.remote.responses.idsbk.Departure
import eu.magicsk.transi.data.remote.responses.idsbk.DirectionDeparture
import eu.magicsk.transi.data.remote.responses.idsbk.TimetableDirection
import eu.magicsk.transi.databinding.FragmentTimetableBinding
import eu.magicsk.transi.timetable.ARG_DATE
import eu.magicsk.transi.timetable.ARG_DIRECTION_ID
import eu.magicsk.transi.timetable.ARG_DIRECTION_NAME
import eu.magicsk.transi.timetable.ARG_OFFLINE
import eu.magicsk.transi.timetable.ARG_ROUTE_ID
import eu.magicsk.transi.timetable.ARG_SHORT_NAME
import eu.magicsk.transi.timetable.ARG_STATION_ID
import eu.magicsk.transi.timetable.ARG_STATION_NAME
import eu.magicsk.transi.timetable.ARG_STOP_CODE
import eu.magicsk.transi.timetable.BRATISLAVA
import eu.magicsk.transi.timetable.formatServiceTime
import eu.magicsk.transi.timetable.leftClosedOfflineDatabase
import eu.magicsk.transi.timetable.nextTripIndex
import eu.magicsk.transi.timetable.showTimetableError
import eu.magicsk.transi.util.customizeLineText
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.TimetablesViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One line: date, direction and a trip picked with the slider; lists the trip's stops. The state lives in the
 * fragment, so it survives the trip to a stop's timetable and back; date, direction and trip survive recreation.
 */
class TimetableFragment : Fragment(R.layout.fragment_timetable) {
    private val viewModel: TimetablesViewModel by activityViewModels()
    private val mainViewModel: MainViewModel by activityViewModels()

    private var binding: FragmentTimetableBinding? = null
    private var adapter: TimetableAdapter? = null
    private var job: Job? = null

    private var date: LocalDate = LocalDate.now(BRATISLAVA)
    private var directions: List<TimetableDirection>? = null
    private var directionIndex = 0
    private var departures: List<Departure>? = null
    private var tripIndex = NEXT_TRIP
    private var loading = false

    @StringRes
    private var error: Int? = null

    private val routeId get() = requireArguments().getInt(ARG_ROUTE_ID)
    private val shortName get() = requireArguments().getString(ARG_SHORT_NAME).orEmpty()
    private val offline get() = requireArguments().getBoolean(ARG_OFFLINE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            date = LocalDate.ofEpochDay(it.getLong(STATE_DATE))
            directionIndex = it.getInt(STATE_DIRECTION)
            tripIndex = it.getInt(STATE_TRIP)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_DATE, date.toEpochDay())
        outState.putInt(STATE_DIRECTION, directionIndex)
        outState.putInt(STATE_TRIP, tripIndex)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentTimetableBinding.bind(view)
        val adapter = TimetableAdapter(::openStop)
        this.binding = binding
        this.adapter = adapter

        customizeLineText(binding.TimetableTitleLine, shortName, requireContext(), resources)
        binding.TimetableTitleLine.contentDescription = getString(R.string.timetable_line_description, shortName)
        binding.TimetableStops.adapter = adapter
        binding.TimetableDateButton.setOnClickListener { pickDate() }
        binding.TimetableDirections.setOnCheckedStateChangeListener { group, checkedIds ->
            val index = checkedIds.firstOrNull()?.let { group.indexOfChild(group.findViewById(it)) }
            if (index != null && index != directionIndex) {
                directionIndex = index
                tripIndex = NEXT_TRIP
                load { fetchDepartures() }
            }
        }
        binding.TimetableTripSlider.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                slider.performHapticFeedback(TRIP_HAPTIC)
                selectTrip(value.toInt())
            }
        }
        binding.TimetableTripMinus.setOnClickListener { stepTrip(-1) }
        binding.TimetableTripPlus.setOnClickListener { stepTrip(1) }
        // Its listener belonged to the previous view; reopening is one tap.
        (childFragmentManager.findFragmentByTag(DATE_PICKER) as? DialogFragment)?.dismiss()

        render()
        val pendingError = error
        when {
            pendingError != null -> showError(pendingError)
            directions == null -> loadDirections()
            departures == null -> load { fetchDepartures() }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // The view's coroutine scope is cancelled with it; anything it did not finish is loaded again.
        loading = false
        binding = null
        adapter = null
    }

    private fun load(block: suspend () -> Unit) {
        if (leftClosedOfflineDatabase(offline, viewModel.isOfflineSource)) return
        job?.cancel()
        loading = true
        error = null
        render()
        job = viewLifecycleOwner.lifecycleScope.launch {
            block()
            loading = false
            render()
            error?.let(::showError)
        }
    }

    private fun loadDirections() = load {
        val result = viewModel.directions(routeId, shortName, offline, mainViewModel.idsbkSession.value)
        if (result == null) {
            error = R.string.timetable_directions_error
            return@load
        }
        directions = result
        directionIndex = directionIndex.coerceIn(0, (result.size - 1).coerceAtLeast(0))
        if (result.isEmpty()) departures = emptyList() else fetchDepartures()
    }

    private suspend fun fetchDepartures() {
        val direction = directions?.getOrNull(directionIndex) ?: return
        departures = null
        render()
        val result = viewModel.departures(routeId, shortName, offline, direction, date, mainViewModel.idsbkSession.value)
        if (result == null) {
            error = R.string.timetable_error
            return
        }
        departures = result
        if (tripIndex !in result.indices) tripIndex = nextTripIndex(result.map { it.departure }, LocalTime.now(BRATISLAVA))
    }

    private fun showError(@StringRes title: Int) = showTimetableError(
        title,
        onRetry = ::reload,
        onCancel = { findNavController().popBackStack() },
    )

    // Before the directions arrive there are no departures to refetch; load the directions (and then them) again.
    private fun reload() = if (directions == null) loadDirections() else load { fetchDepartures() }

    private fun render() {
        val binding = binding ?: return
        // Not show()/hide(): hide() on a shown indicator keeps a repeated show()'s delayed show pending (Material 1.14).
        binding.TimetableLoading.isVisible = loading

        val label = date.format(DATE_LABEL)
        binding.TimetableDateButton.text = label
        binding.TimetableDateButton.contentDescription = getString(R.string.timetable_date_description, label)

        val group = binding.TimetableDirections
        val directions = directions.orEmpty()
        if (group.childCount != directions.size) {
            group.removeAllViews()
            directions.forEach { direction ->
                val chip = layoutInflater.inflate(R.layout.timetable_direction_chip, group, false) as Chip
                chip.id = View.generateViewId()
                chip.text = direction.direction
                group.addView(chip)
            }
        }
        group.getChildAt(directionIndex)?.let { if (group.checkedChipId != it.id) group.check(it.id) }

        val departures = departures.orEmpty()
        val last = (departures.size - 1).coerceAtLeast(0)
        binding.TimetableTripSlider.apply {
            valueTo = last.coerceAtLeast(1).toFloat()
            value = tripIndex.coerceIn(0, last).toFloat()
            isEnabled = departures.size > 1
            setLabelFormatter { departures.getOrNull(it.toInt())?.let { trip -> formatServiceTime(trip.departure) }.orEmpty() }
        }
        binding.TimetableEmpty.isVisible = this.departures?.isEmpty() == true
        renderTrip()
    }

    private fun renderTrip() {
        val binding = binding ?: return
        val departures = departures.orEmpty()
        val stops = departures.getOrNull(tripIndex)?.direction_departures.orEmpty()
        adapter?.submitList(stops.map { it to formatServiceTime(it.departure) })
        binding.TimetableTripMinus.isEnabled = tripIndex > 0
        binding.TimetableTripPlus.isEnabled = tripIndex < departures.size - 1
    }

    private fun selectTrip(index: Int) {
        tripIndex = index
        renderTrip()
    }

    private fun stepTrip(delta: Int) {
        val binding = binding ?: return
        val index = tripIndex + delta
        if (index !in departures.orEmpty().indices) return
        binding.TimetableTripSlider.value = index.toFloat()
        binding.TimetableTripSlider.performHapticFeedback(TRIP_HAPTIC)
        selectTrip(index)
    }

    private fun pickDate() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setSelection(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val picked = Instant.ofEpochMilli(selection).atZone(ZoneOffset.UTC).toLocalDate()
            if (picked != date) {
                date = picked
                tripIndex = NEXT_TRIP
                reload()
            }
        }
        picker.show(childFragmentManager, DATE_PICKER)
    }

    private fun openStop(stop: DirectionDeparture) {
        val direction = directions?.getOrNull(directionIndex) ?: return
        findNavController().navigate(
            R.id.action_navigationTimetable_to_navigationTimetableDetail,
            Bundle().apply {
                putInt(ARG_ROUTE_ID, routeId)
                putString(ARG_SHORT_NAME, shortName)
                putBoolean(ARG_OFFLINE, offline)
                putInt(ARG_DIRECTION_ID, direction.direction_id)
                putString(ARG_DIRECTION_NAME, direction.direction)
                putInt(ARG_STATION_ID, stop.station_id)
                putString(ARG_STATION_NAME, stop.station_name)
                putString(ARG_STOP_CODE, stop.stop_code)
                putLong(ARG_DATE, date.toEpochDay())
            },
        )
    }

    private companion object {
        const val NEXT_TRIP = -1
        const val STATE_DATE = "date"
        const val STATE_DIRECTION = "direction"
        const val STATE_TRIP = "trip"
        const val DATE_PICKER = "timetableDatePicker"
        val DATE_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
        val TRIP_HAPTIC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
    }
}

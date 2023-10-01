package eu.magicsk.transi

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import eu.magicsk.transi.adapters.TimetableAdapter
import eu.magicsk.transi.databinding.FragmentTimetableBinding
import eu.magicsk.transi.util.*
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.TimetablesViewModel

class TimetableFragment : Fragment() {

    private var _binding: FragmentTimetableBinding? = null
    private val binding get() = _binding!!


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimetableBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val routeId = requireArguments().getInt("route_id")
        val lineNum = requireArguments().getString("short_name") ?: "Error"
        val lineDirections = requireArguments().getString("long_name") ?: "Error"
        binding.apply {
            val context = root.context
            val resources = root.resources
            customizeLineText(TimetableTitleLine, lineNum, context, resources)
            TimetableTitleDirections.text = lineDirections
            TimetableTitleDirections.isSelected = true
            val timetablesViewModel =
                ViewModelProvider(requireActivity())[TimetablesViewModel::class.java]
            val mainViewModel =
                ViewModelProvider(requireActivity())[MainViewModel::class.java]
            mainViewModel.idsbkSession.observe(viewLifecycleOwner) { idsbkSession ->
                idsbkSession?.let {
                    timetablesViewModel.getTimetableDirections(routeId, idsbkSession)
                    timetablesViewModel.timetablesDirections.observe(viewLifecycleOwner) { data ->
                        val directions = data.directions
                        if (directions.isNotEmpty()) {
                            TimetableDirection1.text = directions[0].direction
                            if (directions.size > 1) TimetableDirection2.text =
                                directions[1].direction
                            TimetableDirectionToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
                                if (isChecked) {
                                    when (checkedId) {
                                        R.id.TimetableDirection1 -> {
                                            animatedAlphaChange(
                                                0F,
                                                1F,
                                                0,
                                                TimetableLoadingIndicator
                                            )
                                            timetablesViewModel.getTimetable(
                                                routeId,
                                                "departures",
                                                directions[0].direction_id,
                                                getDate(),
                                                idsbkSession
                                            )
                                        }

                                        R.id.TimetableDirection2 -> {
                                            animatedAlphaChange(
                                                0F,
                                                1F,
                                                0,
                                                TimetableLoadingIndicator
                                            )
                                            timetablesViewModel.getTimetable(
                                                routeId,
                                                "departures",
                                                directions[1].direction_id,
                                                getDate(),
                                                idsbkSession
                                            )
                                        }

                                    }
                                }
                            }
                            TimetableDirectionToggle.clearChecked()
                            TimetableDirectionToggle.check(R.id.TimetableDirection1)
                            timetablesViewModel.timetable.observe(viewLifecycleOwner) { timetableData ->
                                if (timetableData.departures.isNotEmpty()) {
                                    val departures = timetableData.departures
                                    TimetableTimeSlider.valueFrom = 0F
                                    TimetableTimeSlider.valueTo = (departures.size - 1).toFloat()
                                    TimetableTimeSlider.value = 0F
                                    TimetableTimeSlider.stepSize = 1F
                                    val minutes = getMinutes()
                                    var tooLate = true
                                    departures.forEachIndexed { i, d ->
                                        if (d.departure - minutes > 1 && tooLate) {
                                            tooLate = false
                                            TimetableTimeSlider.value = i.toFloat()
                                        }
                                    }
                                    if (tooLate) TimetableTimeSlider.value =
                                        (departures.size - 1).toFloat()
                                    TimetableTimeSlider.setLabelFormatter { value ->
                                        val departureTime = departures[value.toInt()].departure
                                        return@setLabelFormatter "${departureTime / 60}:${
                                            (departureTime % 60).toString().padStart(2, Char(48))
                                        }"
                                    }
                                    fun onTimetableStopClick(stationId: Int, stationName: String) {
                                        val timetableDetailBundle = Bundle()
                                        timetableDetailBundle.putInt("route_id", routeId)
                                        timetableDetailBundle.putString("short_name", lineNum)
                                        val direction =
                                            if (TimetableDirectionToggle.checkedButtonId == R.id.TimetableDirection1) directions[0] else directions[1]
                                        timetableDetailBundle.putString(
                                            "long_name",
                                            direction.direction
                                        )
                                        timetableDetailBundle.putInt(
                                            "direction",
                                            direction.direction_id
                                        )
                                        timetableDetailBundle.putInt("station_id", stationId)
                                        timetableDetailBundle.putString("station_name", stationName)
                                        findNavController().navigate(
                                            R.id.action_navigationTimetable_to_navigationTimetableDetail,
                                            timetableDetailBundle
                                        )
                                    }

                                    val timetableAdapter =
                                        TimetableAdapter(timetableData.departures[TimetableTimeSlider.value.toInt()]) { stationId, stationName ->
                                            onTimetableStopClick(
                                                stationId,
                                                stationName
                                            )
                                        }
                                    TimetableStopsList.adapter = timetableAdapter
                                    TimetableStopsList.layoutManager = LinearLayoutManager(context)
                                    TimetableTimeSlider.addOnChangeListener { _, value, _ ->
                                        if (value < timetableData.departures.size)
                                            timetableAdapter.replaceList(timetableData.departures[value.toInt()])
                                    }
                                    TimetableTimePlusButton.setOnClickListener {
                                        val newValue = TimetableTimeSlider.value + 1F
                                        if (newValue <= (departures.size - 1).toFloat())
                                            TimetableTimeSlider.value = newValue
                                    }
                                    TimetableTimeMinusButton.setOnClickListener {
                                        val newValue = TimetableTimeSlider.value - 1F
                                        if (newValue >= 0F)
                                            TimetableTimeSlider.value = newValue
                                    }
                                } else {
                                    println("Error") // TODO: display error and retry button
                                }
                                animatedAlphaChange(1F, 0F, 100, TimetableLoadingIndicator)
                            }
                        }
                    }
                }
            }
        }
    }
}
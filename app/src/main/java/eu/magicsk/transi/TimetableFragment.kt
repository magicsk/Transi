package eu.magicsk.transi

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import eu.magicsk.transi.adapters.TimetableAdapter
import eu.magicsk.transi.data.remote.responses.idsbk.Session
import eu.magicsk.transi.databinding.FragmentTimetableBinding
import eu.magicsk.transi.util.*
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.TimetablesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TimetableFragment : Fragment() {

    private var _binding: FragmentTimetableBinding? = null
    private val binding get() = _binding!!


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
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
            TimetableError.isVisible = false
            TimetableNoDeparturesInfo.isVisible = false
            val timetablesViewModel =
                ViewModelProvider(requireActivity())[TimetablesViewModel::class.java]
            val context = root.context
            val resources = root.resources
            customizeLineText(TimetableTitleLine, lineNum, context, resources)
            TimetableTitleDirections.text = lineDirections
            TimetableTitleDirections.isSelected = true
            val mainViewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
            mainViewModel.idsbkSession.observe(viewLifecycleOwner) { idsbkSession ->
                idsbkSession?.let {
                    TimetableErrorBtn.setOnClickListener {
                        TimetableError.isVisible = false
                        animatedAlphaChange(0F, 1F, 0, TimetableLoadingIndicator)
                        fetchTimetableDirection(
                            timetablesViewModel, routeId, idsbkSession, lineNum
                        )
                    }
                    fetchTimetableDirection(timetablesViewModel, routeId, idsbkSession, lineNum)
                }
            }
        }
    }

    private fun fetchTimetableDirection(
        timetablesViewModel: TimetablesViewModel,
        routeId: Int,
        idsbkSession: Session,
        lineNum: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val directions =
                timetablesViewModel.getTimetableDirections(routeId, idsbkSession)?.directions
            binding.apply {
                CoroutineScope(Dispatchers.Main).launch {
                    if (!directions.isNullOrEmpty()) {
                        TimetableDirection1.text = directions[0].direction
                        if (directions.size > 1) {
                            TimetableDirection2.isVisible = true
                            TimetableDirection2.text = directions[1].direction
                        } else {
                            TimetableDirection2.isVisible = false
                        }

                        val timetableDetailBundle = Bundle()
                        timetableDetailBundle.putInt("route_id", routeId)
                        timetableDetailBundle.putString("short_name", lineNum)

                        TimetableDirectionToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
                            if (isChecked) {
                                when (checkedId) {
                                    R.id.TimetableDirection1 -> {
                                        animatedAlphaChange(
                                            0F, 1F, 0, TimetableLoadingIndicator
                                        )
                                        timetableDetailBundle.putString(
                                            "long_name", directions[0].direction
                                        )
                                        fetchTimetable(
                                            timetablesViewModel,
                                            routeId,
                                            directions[0].direction_id,
                                            idsbkSession,
                                            timetableDetailBundle
                                        )
                                    }

                                    R.id.TimetableDirection2 -> {
                                        animatedAlphaChange(
                                            0F, 1F, 0, TimetableLoadingIndicator
                                        )
                                        timetableDetailBundle.putString(
                                            "long_name", directions[1].direction
                                        )
                                        fetchTimetable(
                                            timetablesViewModel,
                                            routeId,
                                            directions[1].direction_id,
                                            idsbkSession,
                                            timetableDetailBundle
                                        )
                                    }

                                }
                            }
                        }
                        TimetableDirectionToggle.clearChecked()
                        TimetableDirectionToggle.check(R.id.TimetableDirection1)
                    } else {
                        TimetableError.isVisible = true
                    }
                }
            }
        }
    }


    private fun fetchTimetable(
        timetablesViewModel: TimetablesViewModel,
        routeId: Int,
        direction: Int,
        idsbkSession: Session,
        timetableDetailBundle: Bundle
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val timetable = timetablesViewModel.getTimetable(
                routeId, "departures", direction, getDate(), idsbkSession
            )
            val departures = timetable?.departures
            binding.apply {
                CoroutineScope(Dispatchers.Main).launch {
                    if (!departures.isNullOrEmpty()) {
                        val valueTo = (departures.size - 1).toFloat()
                        TimetableTimeSlider.valueFrom = 0F
                        TimetableTimeSlider.valueTo = if (valueTo > 0F) valueTo else 1F
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
                        if (tooLate) TimetableTimeSlider.value = (departures.size - 1).toFloat()
                        TimetableTimeSlider.setLabelFormatter { value ->
                            val departureTime = departures[value.toInt()].departure
                            return@setLabelFormatter "${departureTime / 60}:${
                                (departureTime % 60).toString().padStart(2, Char(48))
                            }"
                        }

                        val timetableAdapter =
                            TimetableAdapter(departures[TimetableTimeSlider.value.toInt()]) { stationId, stationName ->
                                timetableDetailBundle.putInt("direction", direction)
                                timetableDetailBundle.putInt("station_id", stationId)
                                timetableDetailBundle.putString("station_name", stationName)
                                findNavController().navigate(
                                    R.id.action_navigationTimetable_to_navigationTimetableDetail,
                                    timetableDetailBundle
                                )
                            }
                        TimetableStopsList.adapter = timetableAdapter
                        TimetableStopsList.layoutManager = LinearLayoutManager(context)
                        TimetableTimeSlider.addOnChangeListener { _, value, _ ->
                            if (value < departures.size) timetableAdapter.replaceList(departures[value.toInt()])
                        }
                        TimetableTimePlusButton.setOnClickListener {
                            val newValue = TimetableTimeSlider.value + 1F
                            if (newValue <= (departures.size - 1).toFloat()) TimetableTimeSlider.value =
                                newValue
                        }
                        TimetableTimeMinusButton.setOnClickListener {
                            val newValue = TimetableTimeSlider.value - 1F
                            if (newValue >= 0F) TimetableTimeSlider.value = newValue
                        }
                    } else if (departures == null) {
                        TimetableError.isVisible = true
                    } else {
                        TimetableNoDeparturesInfo.isVisible = true
                    }
                    animatedAlphaChange(1F, 0F, 100, TimetableLoadingIndicator)
                }
            }
        }
    }
}
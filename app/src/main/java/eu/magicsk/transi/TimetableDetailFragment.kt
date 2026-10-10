package eu.magicsk.transi

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import eu.magicsk.transi.databinding.FragmentTimetableDetailBinding
import eu.magicsk.transi.databinding.TimetableHourItemBinding
import eu.magicsk.transi.timetable.ARG_DATE
import eu.magicsk.transi.timetable.ARG_DIRECTION_ID
import eu.magicsk.transi.timetable.ARG_DIRECTION_NAME
import eu.magicsk.transi.timetable.ARG_OFFLINE
import eu.magicsk.transi.timetable.ARG_ROUTE_ID
import eu.magicsk.transi.timetable.ARG_SHORT_NAME
import eu.magicsk.transi.timetable.ARG_STATION_ID
import eu.magicsk.transi.timetable.ARG_STATION_NAME
import eu.magicsk.transi.timetable.ARG_STOP_CODE
import eu.magicsk.transi.timetable.groupByHour
import eu.magicsk.transi.timetable.leftClosedOfflineDatabase
import eu.magicsk.transi.timetable.showTimetableError
import eu.magicsk.transi.util.customizeLineText
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.TimetablesViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A stop's departures of one direction on the chosen day, one row per hour. */
class TimetableDetailFragment : Fragment(R.layout.fragment_timetable_detail) {
    private val viewModel: TimetablesViewModel by activityViewModels()
    private val mainViewModel: MainViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentTimetableDetailBinding.bind(view)
        val args = requireArguments()
        val shortName = args.getString(ARG_SHORT_NAME).orEmpty()
        customizeLineText(binding.TimetableDetailLine, shortName, requireContext(), resources)
        binding.TimetableDetailLine.contentDescription = getString(R.string.timetable_line_description, shortName)
        binding.TimetableDetailDirection.text = args.getString(ARG_DIRECTION_NAME)
        binding.TimetableDetailStop.text = args.getString(ARG_STATION_NAME)
        load(binding, args)
    }

    private fun load(binding: FragmentTimetableDetailBinding, args: Bundle) {
        if (leftClosedOfflineDatabase(args.getBoolean(ARG_OFFLINE), viewModel.isOfflineSource)) return
        binding.TimetableDetailLoading.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val minutes = viewModel.stopTimes(
                routeId = args.getInt(ARG_ROUTE_ID),
                shortName = args.getString(ARG_SHORT_NAME).orEmpty(),
                offline = args.getBoolean(ARG_OFFLINE),
                directionId = args.getInt(ARG_DIRECTION_ID),
                directionName = args.getString(ARG_DIRECTION_NAME).orEmpty(),
                date = LocalDate.ofEpochDay(args.getLong(ARG_DATE)),
                stationId = args.getInt(ARG_STATION_ID),
                stopCode = args.getString(ARG_STOP_CODE),
                session = mainViewModel.idsbkSession.value,
            )
            binding.TimetableDetailLoading.isVisible = false
            if (minutes == null) {
                showTimetableError(
                    R.string.timetable_error,
                    onRetry = { load(binding, args) },
                    onCancel = { findNavController().popBackStack() },
                )
                return@launch
            }
            binding.TimetableDetailHours.removeAllViews()
            groupByHour(minutes).forEach { (hour, times) ->
                val row = TimetableHourItemBinding.inflate(layoutInflater, binding.TimetableDetailHours, true)
                row.TimetableHourItemHour.text = hour
                row.TimetableHourItemMinutes.text = times.joinToString("   ")
            }
        }
    }
}

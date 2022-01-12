package eu.magicsk.transi

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import eu.magicsk.transi.data.models.SelectedTrip
import kotlinx.android.synthetic.main.fragment_main.*
import kotlinx.android.synthetic.main.fragment_plan.*
import java.util.*

class PlanFragment : Fragment(R.layout.fragment_plan) {
    private lateinit var navHostFragment: NavHostFragment
    private lateinit var mainFragment: MainFragment
    private var selectedTripCalendar = Calendar.getInstance()
    private var selectedTrip = SelectedTrip()

    fun getTrip(
        time: Long = selectedTrip.time,
        from: String = selectedTrip.from,
        to: String = selectedTrip.to,
        ad: Int = selectedTrip.ad
    ) {
        selectedTrip = SelectedTrip(time, from, to, ad)
        mainFragment.apply {
            activity?.progressBar_bg?.visibility = View.VISIBLE
            activity?.progressBar_ic?.visibility = View.VISIBLE
            if ((to == "" || from == "") || (to == "0" && from == "0")) {
                val errorAlertBuilder = AlertDialog.Builder(activity)
                errorAlertBuilder.setTitle(getString(R.string.ops))
                    .setPositiveButton("OK") { dialog, _ ->
                        dialog.cancel()
                    }
                val errorAlert = errorAlertBuilder.create()
                errorAlert.setMessage(getString(R.string.error400))
                errorAlert.show()
            } else {
                if ((to == "0" || from == "0") && actualLocation == null) {
                    waitingForLocation = true
                } else {
                    val lat = actualLocation!!.latitude
                    val long = actualLocation!!.longitude
                    tripViewModel.getTrip(
                        if (time == 0L) System.currentTimeMillis() else time,
                        if (from == "0") "c$lat,$long" else from,
                        if (to == "0") "c$lat,$long" else to,
                        ad
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        navHostFragment = activity?.supportFragmentManager?.findFragmentById(R.id.navHostFragment) as NavHostFragment
        mainFragment = navHostFragment.childFragmentManager.fragments[0] as MainFragment
    }

    @SuppressLint("RestrictedApi")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val navController = findNavController()
        val argId = requireArguments().getInt("selectedToStopId")
        if (argId > 0) {
            mainFragment.getStopById(argId)?.let{
                editTextTo.setText(it.name)
                getTrip(from = "0", to = it.value)
            }
        }

        if (mainFragment.nearestSwitching) {
            if (mainFragment.actualLocation == null) {
                positionPlanBtn.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_location_search, context?.theme)
                (positionPlanBtn.icon as AnimatedVectorDrawable).start()
            } else positionPlanBtn.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_my_location, context?.theme)
        } else {
            positionPlanBtn.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_location_disabled, context?.theme)
        }

        positionPlanBtn.setOnClickListener {
            mainFragment.nearestSwitching = !mainFragment.nearestSwitching
            if (mainFragment.nearestSwitching) {
                positionPlanBtn.icon =
                    ResourcesCompat.getDrawable(resources, R.drawable.ic_my_location, context?.theme)
                mainFragment.selected = mainFragment.stopList[0]
                activity?.MHDTableStopName?.text = mainFragment.selected.name
                mainFragment.tableAdapter.ioDisconnect()
                mainFragment.tableAdapter.ioConnect(mainFragment.selected.id)
            } else {
                positionPlanBtn.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_location_disabled, context?.theme)
            }
        }

        backBtn.setOnClickListener {
            if (navController.backStack.size > 2) navController.popBackStack()
            activity?.supportFragmentManager?.beginTransaction()?.apply {
                replace(R.id.search_barFL, mainFragment.searchFragment)
                commit()
            }
        }

        switchBtn.setOnClickListener {
            val temp = editTextFrom.text
            editTextFrom.text = editTextTo.text
            editTextTo.text = temp
            getTrip(from = selectedTrip.to, to = selectedTrip.from)
        }

        val timePickerListener = TimePickerDialog.OnTimeSetListener { _, hours, minutes ->
            selectedTripCalendar.set(
                selectedTripCalendar.get(Calendar.YEAR),
                selectedTripCalendar.get(Calendar.MONTH),
                selectedTripCalendar.get(Calendar.DAY_OF_MONTH),
                hours,
                minutes
            )
            getTrip(time = selectedTripCalendar.timeInMillis)
        }

        timeDateBtn.setOnClickListener {
            val datePickerDialog = DatePickerDialog(requireContext())
            val calendar = Calendar.getInstance()
            if (selectedTrip.time != 0L) {
                calendar.timeInMillis = selectedTrip.time
                datePickerDialog.updateDate(
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
                )
            }
            datePickerDialog.datePicker.minDate = System.currentTimeMillis() - 86400000
            datePickerDialog.show()
            datePickerDialog.setOnDateSetListener { _, year, month, day ->
                selectedTripCalendar.set(year, month, day)
                val timePickerDialog = TimePickerDialog(
                    requireContext(),
                    timePickerListener,
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    DateFormat.is24HourFormat(context)
                )
                timePickerDialog.show()
            }
        }

        adPlanBtn.setOnClickListener {
            val toastText: String
            if (selectedTrip.ad == 0) {
                selectedTrip.ad = 1
                toastText = "arrival"
            } else {
                selectedTrip.ad = 0
                toastText = "departure"
            }
            getTrip()
            Toast.makeText(context, "Set to time of $toastText.", Toast.LENGTH_SHORT).show()
        }

        val typeAheadBundle = Bundle()
        typeAheadBundle.clear()
        typeAheadBundle.putSerializable("stopsList", mainFragment.stopList)
        typeAheadBundle.putBoolean("directions", false)
        editTextFrom.setOnFocusChangeListener { _, b ->
            typeAheadBundle.putString("origin", "editTextFrom")
            if (b) {
                if (navController.backStack.size > 2) navController.popBackStack()
                navController.navigate(
                    R.id.action_mainFragment_to_typeAheadFragment,
                    typeAheadBundle,
                    null,
                )
            }
        }

        timeDateBtn.setOnLongClickListener {
            selectedTripCalendar = Calendar.getInstance()
            selectedTrip.time = System.currentTimeMillis()
            Toast.makeText(context, "Changed to current date and time.", Toast.LENGTH_SHORT).show()
            true
        }

        editTextTo.setOnFocusChangeListener { _, b ->
            typeAheadBundle.putString("origin", "editTextTo")
            if (b) {
                if (navController.backStack.size > 2) navController.popBackStack()
                navController.navigate(
                    R.id.action_mainFragment_to_typeAheadFragment,
                    typeAheadBundle,
                    null,
                )
            }
        }
    }
}
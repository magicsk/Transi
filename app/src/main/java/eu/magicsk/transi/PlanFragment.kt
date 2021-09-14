package eu.magicsk.transi

import android.annotation.SuppressLint
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import kotlinx.android.synthetic.main.fragment_main.*
import kotlinx.android.synthetic.main.fragment_plan.*

class PlanFragment : Fragment(R.layout.fragment_plan) {
    private var navHostFragment = activity?.supportFragmentManager?.findFragmentById(R.id.navHostFragment)
    private var mainFragment = navHostFragment?.childFragmentManager?.fragments?.get(0) as MainFragment?

    fun getTrip(
        time: Long = System.currentTimeMillis(),
        from: String = editTextFrom.text.toString(),
        to: String = editTextTo.text.toString()
    ) {
        mainFragment?.apply {
            activity?.progressBar_bg?.visibility = View.VISIBLE
            activity?.progressBar_ic?.visibility = View.VISIBLE
            if ((to == "Actual position" || from == "Actual position") && actualLocation == null) {
                mainFragment!!.waitingForLocation = true
            } else {
                val lat = actualLocation!!.latitude
                val long = actualLocation!!.longitude
                tripViewModel.getTrip(
                    time,
                    if (from == "Actual position") "c$lat,$long" else from,
                    if (to == "Actual position") "c$lat,$long" else to
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navHostFragment = activity?.supportFragmentManager?.findFragmentById(R.id.navHostFragment)
        mainFragment = navHostFragment?.childFragmentManager?.fragments?.get(0) as MainFragment?
    }

    @SuppressLint("RestrictedApi")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val navController = findNavController()
        mainFragment!!.let { mainFragment ->

            editTextTo.setText(mainFragment.getStopById(requireArguments().getInt("selectedToStopId")).name)
            getTrip()

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
                    val selected = mainFragment.stopList[0]
                    activity?.MHDTableStopName?.text = selected.name
                    mainFragment.tableAdapter.ioDisconnect()
                    mainFragment.tableAdapter.ioConnect(selected.id)
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
}
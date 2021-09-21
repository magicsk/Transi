package eu.magicsk.transi

import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import kotlinx.android.synthetic.main.fragment_main.*
import kotlinx.android.synthetic.main.fragment_search.*


class SearchFragment : Fragment(R.layout.fragment_search) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val navHostFragment = activity?.supportFragmentManager?.findFragmentById(R.id.navHostFragment)
        val mainFragment = navHostFragment?.childFragmentManager?.fragments?.get(0) as MainFragment

        if (mainFragment.nearestSwitching) {
            if (mainFragment.actualLocation == null) {
                positionBtn.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_location_search, context?.theme)
                (positionBtn.icon as AnimatedVectorDrawable).start()
            } else positionBtn.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_my_location, context?.theme)
        } else {
            positionBtn.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_location_disabled, context?.theme)
        }

        positionBtn.setOnClickListener {
            mainFragment.nearestSwitching = !mainFragment.nearestSwitching
            if (mainFragment.nearestSwitching) {
                positionBtn.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_my_location, context?.theme)
                mainFragment.selected = mainFragment.stopList[0]
                MHDTableStopName?.text = mainFragment.selected.name
                mainFragment.tableAdapter.ioDisconnect()
                mainFragment.tableAdapter.ioConnect(mainFragment.selected.id)
            } else {
                positionBtn.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_location_disabled, context?.theme)
            }
        }

        val typeAheadBundle = Bundle()
        typeAheadBundle.clear()
        typeAheadBundle.putSerializable("stopsList", mainFragment.stopList)
        typeAheadBundle.putBoolean("directions", true)
        editText.setOnFocusChangeListener { _, b ->
            typeAheadBundle.putString("origin", "editText")
            if (b) {
                findNavController().navigate(
                    R.id.action_mainFragment_to_typeAheadFragment,
                    typeAheadBundle,
                    null,
                )
            }
        }
    }
}
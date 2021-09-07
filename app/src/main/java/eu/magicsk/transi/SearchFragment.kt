package eu.magicsk.transi

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import eu.magicsk.transi.data.remote.responses.StopsJSON
import kotlinx.android.synthetic.main.fragment_search.*

class SearchFragment : Fragment(R.layout.fragment_search) {
    private var stopList: StopsJSON = StopsJSON()
    private val stopListBundle = Bundle()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stopList = requireArguments().getSerializable("stopsList") as StopsJSON
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        stopListBundle.clear()
        stopListBundle.putSerializable("stopsList", stopList)
        editText.setOnFocusChangeListener { view, b ->
            if (b) {
                findNavController().navigate(
                    R.id.action_mainFragment_to_typeAheadFragment,
                    stopListBundle,
                    null,
                )
            }
        }
    }
}
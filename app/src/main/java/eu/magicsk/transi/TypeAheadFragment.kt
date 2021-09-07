package eu.magicsk.transi

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import eu.magicsk.transi.adapters.TypeAheadAdapter
import eu.magicsk.transi.data.remote.responses.StopsJSON
import kotlinx.android.synthetic.main.fragment_search.*
import kotlinx.android.synthetic.main.fragment_type_ahead.*

class TypeAheadFragment : Fragment(R.layout.fragment_type_ahead) {

    private lateinit var typeAheadAdapter: TypeAheadAdapter
    private var stopList: StopsJSON = StopsJSON()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        val animation = TransitionInflater.from(requireContext()).inflateTransition(android.R.transition.move).setDuration(100)
//        sharedElementEnterTransition = animation
//        sharedElementReturnTransition = animation
        stopList = requireArguments().getSerializable("stopsList") as StopsJSON
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fun onListItemClick(pos: Int) {
            val navController = findNavController()
            navController.previousBackStackEntry?.savedStateHandle?.set(
                "selectedStopId",
                typeAheadAdapter.getItem(pos).id
            )
            activity?.editText?.setText(typeAheadAdapter.getItem(pos).name)

            val im: InputMethodManager? =
                activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
            im?.hideSoftInputFromWindow(view.windowToken, 0)
            navController.popBackStack()
        }

        fun onButtonItemClick(pos: Int) {
            val navController = findNavController()
            navController.previousBackStackEntry?.savedStateHandle?.set(
                "selectedToStopId",
                typeAheadAdapter.getItem(pos).id
            )
            activity?.editText?.clearFocus()
            val im: InputMethodManager? =
                activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
            im?.hideSoftInputFromWindow(view.windowToken, 0)
            navController.popBackStack()
        }

        typeAheadAdapter =
            TypeAheadAdapter(mutableListOf(), { position -> onListItemClick(position) }) { position -> onButtonItemClick(position) }
        typeAheadAdapter.addItems(stopList)
        StopList.adapter = typeAheadAdapter
        StopList.layoutManager = LinearLayoutManager(context)


        activity?.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(p0: Editable?) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {
                typeAheadAdapter.filter(s.toString())
            }
        })
//
//        editText.requestFocus()
//        val im: InputMethodManager? =
//            activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
//        im?.showSoftInput(editText, 0)
    }
}
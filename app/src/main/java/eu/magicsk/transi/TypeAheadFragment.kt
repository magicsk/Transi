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
import eu.magicsk.transi.data.remote.responses.StopsJSONItem
import kotlinx.android.synthetic.main.fragment_plan.*
import kotlinx.android.synthetic.main.fragment_search.*
import kotlinx.android.synthetic.main.fragment_type_ahead.*

class TypeAheadFragment : Fragment(R.layout.fragment_type_ahead) {

    private lateinit var typeAheadAdapter: TypeAheadAdapter
    private lateinit var origin: String
    private var showDirections = false
    private var stopsList: StopsJSON = StopsJSON()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stopsList = requireArguments().getSerializable("stopsList") as StopsJSON
        showDirections = requireArguments().getBoolean("directions")
        origin = requireArguments().getString("origin").toString()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val planFragment =
            try {
                activity?.supportFragmentManager?.findFragmentById(R.id.search_barFL) as PlanFragment?
            } catch (e: ClassCastException) {
                println("not PlanFragment")
                null
            }

        val fromMap = StopsJSONItem(
            0,
            "From map",
            "From map",
            "",
            "-1",
            "map",
            0,
            0.0,
            0.0,
            null
        )
        stopsList.remove(fromMap)
        stopsList.add(
            0, fromMap
        )

        if (origin != "editText") {
            stopsList.add(
                0, StopsJSONItem(
                    0,
                    "Actual position",
                    "Actual position",
                    "",
                    "0",
                    "location",
                    0,
                    0.0,
                    0.0,
                    null
                )
            )
        }

        fun onListItemClick(pos: Int) {
            val navController = findNavController()
            if (typeAheadAdapter.getItem(pos).type == "map") {
                val mapBundle = Bundle();
                mapBundle.putSerializable("stopsList", stopsList)
                mapBundle.putString("origin", origin)
                findNavController().navigate(
                    R.id.action_typeAheadFragment_to_mapFragment,
                    mapBundle,
                    null,
                )
                val im: InputMethodManager? = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
                im?.hideSoftInputFromWindow(view.windowToken, 0)
            } else {
                navController.previousBackStackEntry?.savedStateHandle?.apply {
                    remove<Int>("selectedToStopId")
                    set("selectedStopId", typeAheadAdapter.getItem(pos).id)
                    set("origin", origin)
                }
                val stop = typeAheadAdapter.getItem(pos)
                when (origin) {
                    "editText" -> activity?.editText?.setText(stop.name)
                    "editTextFrom" -> {
                        activity?.editTextFrom?.setText(stop.name)
                        planFragment?.getTrip(from = stop.value)
                    }
                    "editTextTo" -> {
                        activity?.editTextTo?.setText(stop.name)
                        planFragment?.getTrip(to = stop.value)
                    }
                }

                val im: InputMethodManager? = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
                im?.hideSoftInputFromWindow(view.windowToken, 0)
                navController.popBackStack()
            }
        }

        fun onButtonItemClick(pos: Int) {
            val navController = findNavController()
            navController.previousBackStackEntry?.savedStateHandle?.set(
                "selectedToStopId",
                typeAheadAdapter.getItem(pos).id
            )
            val im: InputMethodManager? =
                activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
            im?.hideSoftInputFromWindow(view.windowToken, 0)
            navController.popBackStack()
        }

        fun onButtonItemLongClick() {
            val navController = findNavController()
            navController.previousBackStackEntry?.savedStateHandle?.set(
                "selectedToStopId",
                0
            )
            val im: InputMethodManager? =
                activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
            im?.hideSoftInputFromWindow(view.windowToken, 0)
            navController.popBackStack()
        }

        typeAheadAdapter =
            TypeAheadAdapter(
                mutableListOf(), showDirections,
                { position -> onListItemClick(position) }, { position -> onButtonItemClick(position) }) { onButtonItemLongClick() }
        typeAheadAdapter.addItems(stopsList)
        StopList.adapter = typeAheadAdapter
        StopList.layoutManager = LinearLayoutManager(context)

        val eText = when (origin) {
            "editTextFrom" -> activity?.editTextFrom
            "editTextTo" -> activity?.editTextTo
            else -> activity?.editText
        }

        eText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(p0: Editable?) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {
                typeAheadAdapter.filter(s.toString())
            }
        })
    }
}
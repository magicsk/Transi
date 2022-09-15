package eu.magicsk.transi

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import eu.magicsk.transi.adapters.TypeAheadAdapter
import eu.magicsk.transi.data.remote.responses.Stops
import eu.magicsk.transi.databinding.FragmentTypeAheadBinding
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.TripPlannerViewModel

class TypeAheadFragment : Fragment() {
    private var _binding: FragmentTypeAheadBinding? = null
    private val binding get() = _binding!!
    private lateinit var typeAheadAdapter: TypeAheadAdapter
    private lateinit var origin: String
    private val mapFragment: MapFragment = MapFragment()
    private var showDirections = false
    private var stopsList: Stops = Stops()
    private val filterHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = FragmentTypeAheadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun hideVirtualInput(view: View) {
        val im: InputMethodManager? = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        im?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun onListItemClick(view: View, pos: Int) {
        val mainViewModel = ViewModelProvider(requireActivity()).get(MainViewModel::class.java)
        val tripPlannerViewModel = ViewModelProvider(requireActivity()).get(TripPlannerViewModel::class.java)
        if (typeAheadAdapter.getItem(pos).type == "map") {
            val mapBundle = Bundle()
            mapBundle.putSerializable("stopsList", stopsList)
            mapBundle.putString("origin", origin)
            mapFragment.arguments = mapBundle
            val id = if (origin == "editText") R.id.searchFragmentLayout else R.id.tripSearchFragmentLayout
            val name = if (origin == "editText") "typeAhead" else "tripTypeAhead"
            activity?.supportFragmentManager?.beginTransaction()?.apply {
                replace(id, mapFragment).addToBackStack(name).commit()
            }
        } else {
            val stop = typeAheadAdapter.getItem(pos)
            when (origin) {
                "editText" -> {
                    mainViewModel.setSelectedStop(stop)
                    activity?.supportFragmentManager?.popBackStack("typeAhead", 1)
                    hideVirtualInput(view)
                }
                "editTextFrom" -> {
                    tripPlannerViewModel.setSelectedFromStop(stop)
                    activity?.supportFragmentManager?.popBackStack("tripTypeAhead", 1)
                }
                "editTextTo" -> {
                    tripPlannerViewModel.setSelectedToStop(stop)
                    activity?.supportFragmentManager?.popBackStack("tripTypeAhead", 1)
                    hideVirtualInput(view)
                }
            }

        }
    }

    private fun onButtonItemClick(view: View, pos: Int) {
        val tripPlannerViewModel = ViewModelProvider(requireActivity()).get(TripPlannerViewModel::class.java)
        val stop = typeAheadAdapter.getItem(pos)
        activity?.supportFragmentManager?.popBackStack("typeAhead", 1)
        val navView = activity?.findViewById<BottomNavigationView>(R.id.navView)
        navView?.selectedItemId = R.id.navigationTripPlanner
        tripPlannerViewModel.setSelectedToStop(stop)
        hideVirtualInput(view)
    }

    private fun onButtonItemLongClick(view: View) {
        val navController = findNavController()
        navController.previousBackStackEntry?.savedStateHandle?.set(
            "selectedToStopId", 0
        )
        hideVirtualInput(view)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stopsList = requireArguments().getSerializable("stopsList") as Stops
        showDirections = requireArguments().getBoolean("directions")
        origin = requireArguments().getString("origin").toString()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.window?.statusBarColor = MaterialColors.getColor(view, R.attr.colorSurface)

        typeAheadAdapter = TypeAheadAdapter(mutableListOf(),
            showDirections,
            { position -> onListItemClick(view, position) },
            { position -> onButtonItemClick(view, position) }) { onButtonItemLongClick(view) }
        typeAheadAdapter.addItems(stopsList, origin != "editText")
        binding.StopList.adapter = typeAheadAdapter
        binding.StopList.layoutManager = LinearLayoutManager(context)

        val eText: EditText? = when (origin) {
            "editTextFrom" -> activity?.findViewById(R.id.editTextFrom)
            "editTextTo" -> activity?.findViewById(R.id.editTextTo)
            else -> activity?.findViewById(R.id.editText)
        }

        eText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(p0: Editable?) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {
                filterHandler.removeCallbacksAndMessages(null)
                filterHandler.postDelayed({
                    typeAheadAdapter.filter(s.toString())
                }, 200)
            }
        })
    }
}
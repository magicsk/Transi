package eu.magicsk.transi

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import eu.magicsk.transi.adapters.MHDTableAdapter
import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.data.remote.responses.StopsJSONItem
import eu.magicsk.transi.databinding.FragmentMainBinding
import eu.magicsk.transi.view_models.StopsListViewModel
import kotlinx.android.synthetic.main.fragment_main.*

@AndroidEntryPoint
class MainFragment : Fragment(R.layout.fragment_main) {
    private lateinit var tableAdapter: MHDTableAdapter
    private lateinit var selected: StopsJSONItem
    private val viewModel: StopsListViewModel by viewModels()
    private var stopList: StopsJSON = StopsJSON()

    private var _binding: FragmentMainBinding? = null
    private val binding: FragmentMainBinding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMainBinding.bind(view)

        viewModel.stops.observe(viewLifecycleOwner) { stops ->
            if (stops != null) {
                stopList.addAll(stops)
                MHDTableStopName.text = stopList[1].name
            }
        }

        findNavController().currentBackStackEntry?.savedStateHandle?.getLiveData<StopsJSONItem>("selectedStop")
            ?.observe(viewLifecycleOwner) {
                selected = it
                MHDTableStopName.text = it.name
            }

        val bundle = Bundle()
        bundle.putSerializable("stopsList", stopList)

        binding.editTextFake.setOnClickListener {
            findNavController().navigate(
                R.id.action_mainFragment_to_typeAheadFragment,
                bundle,
                null
            )
        }

        tableAdapter = MHDTableAdapter(mutableListOf())
        activity?.let { tableAdapter.ioConnect(it) }
        MHDTableList.adapter = tableAdapter
        MHDTableList.layoutManager = LinearLayoutManager(context)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
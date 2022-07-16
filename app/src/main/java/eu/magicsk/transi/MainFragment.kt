package eu.magicsk.transi

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.AnimatedVectorDrawable
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import eu.magicsk.transi.adapters.MHDTableAdapter
import eu.magicsk.transi.adapters.TableInfoAdapter
import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.data.remote.responses.StopsJSONItem
import eu.magicsk.transi.databinding.FragmentMainBinding
import eu.magicsk.transi.view_models.MainViewModel
import java.util.*

@AndroidEntryPoint
class MainFragment : Fragment() {
    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!
    private lateinit var sharedPreferences: SharedPreferences
    private val tableAdapter = MHDTableAdapter()
    private val tableInfoAdapter = TableInfoAdapter(mutableListOf())
    private var stopList = StopsJSON()
    private val typeAheadFragment = TypeAheadFragment()
    private var actualLocation: Location? = null
    private var nearestSwitching = true
    private var infoDismissed = false
    private val connectionHandler = Handler(Looper.getMainLooper())
    private var selected = StopsJSONItem(
        0, "Locating nearest stop…", "none", "/ba/zastavka/Hronsk%C3%A1/b68883", "g94", "bus", 394, 48.13585663, 17.20938683, null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        infoDismissed = savedInstanceState?.get("infoDismissed") as? Boolean ?: infoDismissed
        tableAdapter.dismissed = infoDismissed
        selected = savedInstanceState?.getSerializable("selectedStop") as? StopsJSONItem ?: selected
        nearestSwitching = savedInstanceState?.getBoolean("nearestSwitching") ?: nearestSwitching
        sharedPreferences = context?.getSharedPreferences("Transi", Context.MODE_PRIVATE)!!
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    override fun onPause() {
        super.onPause()
        connectionHandler.postDelayed({
            tableAdapter.ioDisconnect()
        }, 60000)
    }

    override fun onResume() {
        super.onResume()
        connectionHandler.removeCallbacksAndMessages(null)
        if (!tableAdapter.connected) {
            tableAdapter.ioConnect(selected.id)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mainViewModel = ViewModelProvider(requireActivity()).get(MainViewModel::class.java)

        activity?.apply {
            tableAdapter.ioObservers(this)
            val actualTime: Thread = object : Thread() {
                override fun run() {
                    try {
                        while (!this.isInterrupted) {
                            sleep(1000)
                            runOnUiThread {
                                val calendar = Calendar.getInstance()
                                activity?.findViewById<TextView>(R.id.MHDTableActualTime)?.text = getString(
                                    R.string.actualTime,
                                    calendar.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0'),
                                    calendar.get(Calendar.MINUTE).toString().padStart(2, '0'),
                                    calendar.get(Calendar.SECOND).toString().padStart(2, '0')
                                )
                            }
                        }
                    } catch (_: InterruptedException) {
                    }
                }
            }
            actualTime.start()
            tableAdapter.startUpdater(this)
        }

        mainViewModel.stopList.observe(viewLifecycleOwner) { stopsJSON ->
            stopList = stopsJSON
            tableAdapter.putStopList(stopList)
            val typeAheadBundle = Bundle()
            typeAheadBundle.clear()
            typeAheadBundle.putSerializable("stopsList", stopList)
            typeAheadBundle.putBoolean("directions", true)
            typeAheadBundle.putString("origin", "editText")
            typeAheadFragment.arguments = typeAheadBundle
            activity?.findViewById<LinearLayout>(R.id.LoadingOverlay)?.visibility = View.GONE
            binding.MHDTableListConnectInfo.visibility = View.GONE
        }
        mainViewModel.actualLocation.observe(viewLifecycleOwner) { location ->
            actualLocation = location
            if (location != null && stopList.size > 1 && nearestSwitching && selected != stopList[0]) {
                val id = selected.id
                selected = stopList[0]
                binding.MHDTableStopName?.text = selected.name
                binding.positionBtn?.icon = ResourcesCompat.getDrawable(
                    resources, R.drawable.ic_my_location, context?.theme
                )
                if (id != selected.id) {
                    tableAdapter.ioDisconnect()
                    tableAdapter.ioConnect(selected.id)
                }
            }
        }
        mainViewModel.selectedStop.observe(viewLifecycleOwner) { selectedStop ->
            activity?.window?.statusBarColor = MaterialColors.getColor(view, R.attr.colorMyBackground)
            selected = selectedStop
            nearestSwitching = false
            binding.editText.clearFocus()
            binding.editText.setText(selected.name)
            binding.MHDTableStopName.text = selected.name
            binding.MHDTableStopName.isSelected = true
            binding.positionBtn.icon = ResourcesCompat.getDrawable(
                resources, R.drawable.ic_location_disabled, context?.theme
            )
            tableAdapter.ioDisconnect()
            tableAdapter.ioConnect(selected.id)
        }

        if (nearestSwitching) {
            if (actualLocation == null) {
                binding.positionBtn.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_location_search, context?.theme)
                (binding.positionBtn.icon as AnimatedVectorDrawable).start()
            } else binding.positionBtn.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_my_location, context?.theme)
        } else {
            binding.positionBtn.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_location_disabled, context?.theme)
        }

        binding.positionBtn.setOnClickListener {
            if (actualLocation != null || stopList.size < 1) {
                nearestSwitching = !nearestSwitching
                if (nearestSwitching) {
                    binding.positionBtn.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_my_location, context?.theme)
                    selected = stopList[0]
                    binding.MHDTableStopName.text = selected.name
                    tableAdapter.ioDisconnect()
                    tableAdapter.ioConnect(selected.id)
                } else {
                    binding.positionBtn.icon =
                        ResourcesCompat.getDrawable(resources, R.drawable.ic_location_disabled, context?.theme)
                }
            } else if (actualLocation != null) {
                Toast.makeText(context, "Actual location not available.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Stop list not available.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.editText.setOnFocusChangeListener { _, b ->
            if (b) {
                binding.MHDTable.visibility = View.GONE
                binding.searchFragmentLayout.visibility = View.VISIBLE
                activity?.supportFragmentManager?.beginTransaction()?.apply {
                    replace(R.id.searchFragmentLayout, typeAheadFragment).addToBackStack("typeAhead").commit()
                }
            }
        }

        activity?.supportFragmentManager?.addOnBackStackChangedListener {
            val fragment = activity?.supportFragmentManager?.findFragmentById(R.id.searchFragmentLayout)
            if (fragment == null) {
                activity?.findViewById<EditText>(R.id.editText)?.clearFocus()
                activity?.findViewById<ConstraintLayout>(R.id.MHDTable)?.isVisible = true
                activity?.findViewById<FrameLayout>(R.id.searchFragmentLayout)?.isVisible = true
            }
        }

        if (selected.html != "none") binding.MHDTableStopName.text = selected.name
        val calendar = Calendar.getInstance()
        binding.MHDTableActualTime.text = context?.getString(
            R.string.actualTime,
            calendar.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0'),
            calendar.get(Calendar.MINUTE).toString().padStart(2, '0'),
            calendar.get(Calendar.SECOND).toString().padStart(2, '0')
        )
        binding.MHDTableList.adapter = tableAdapter
        binding.MHDTableList.layoutManager = LinearLayoutManager(context)
        binding.MHDTableList.itemAnimator?.changeDuration = 0
        binding.MHDTableInfoText.adapter = tableInfoAdapter
        binding.MHDTableInfoText.layoutManager = LinearLayoutManager(context)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        infoDismissed = tableAdapter.dismissed
        println("onSave")
        println(infoDismissed)
        outState.putBoolean("infoDismissed", infoDismissed)
        outState.putSerializable("selectedStop", selected)
        outState.putBoolean("nearestSwitching", nearestSwitching)
        super.onSaveInstanceState(outState)
    }
}

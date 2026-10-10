package eu.magicsk.transi

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.core.graphics.withClip
import androidx.core.os.BundleCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.androidpasswordstore.sublimefuzzy.Fuzzy
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import eu.magicsk.transi.adapters.StopItem
import eu.magicsk.transi.adapters.TypeAheadAdapter
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.Stops
import eu.magicsk.transi.databinding.FragmentTypeAheadBinding
import eu.magicsk.transi.util.actualLocationStop
import eu.magicsk.transi.util.favoriteStopIds
import eu.magicsk.transi.util.hasLocationPermission
import eu.magicsk.transi.util.readCachedStops
import eu.magicsk.transi.util.sortedByDistance
import eu.magicsk.transi.util.toggleFavoriteStop
import eu.magicsk.transi.util.unaccent
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.TripPlannerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stop search over the screen that opened it. Origins: "editText" (table), "editTextFrom"/"editTextTo" (trip planner)
 * and [ORIGIN_PICKER] (Settings default stop, inside [StopPickerActivity]).
 */
class TypeAheadFragment : Fragment() {
    private var _binding: FragmentTypeAheadBinding? = null
    private val binding get() = _binding!!
    private lateinit var typeAheadAdapter: TypeAheadAdapter
    private lateinit var origin: String
    private val mapFragment: MapFragment = MapFragment()
    private var showDirections = false
    private var stopsList: List<Stop> = emptyList()
    private var eText: EditText? = null
    private var listener: TextWatcher? = null
    private var query = ""
    private var filterJob: Job? = null
    private val mainViewModel: MainViewModel by activityViewModels()
    private val tripPlannerViewModel: TripPlannerViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTypeAheadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activity?.findViewById<View>(R.id.statusBarBackground)?.background = null
        eText?.removeTextChangedListener(listener)
        if (origin == "editTextFrom") {
            val fromStopName = tripPlannerViewModel.selectedFromStop.value?.name
            if (fromStopName != null && eText?.text.toString() != fromStopName) {
                eText?.setText(fromStopName)
            }
        }
        if (origin == "editTextTo") {
            val toStopName = tripPlannerViewModel.selectedToStop.value?.name
            if (toStopName != null && eText?.text.toString() != toStopName) {
                eText?.setText(toStopName)
            }
        }
        _binding = null
    }

    private fun hideVirtualInput(view: View) {
        val im: InputMethodManager? =
            activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        im?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun onListItemClick(view: View, stop: Stop) {
        if (stop.type == "map") {
            val mapBundle = Bundle()
            mapBundle.putSerializable("stopsList", Stops().apply { addAll(stopsList) })
            mapBundle.putString("origin", origin)
            mapFragment.arguments = mapBundle
            val id = if (origin == "editText") R.id.searchFragmentLayout else R.id.tripSearchFragmentLayout
            val name = if (origin == "editText") "typeAhead" else "tripTypeAhead"
            activity?.supportFragmentManager?.beginTransaction()?.apply {
                replace(id, mapFragment).addToBackStack(name).commit()
            }
            return
        }
        when (origin) {
            ORIGIN_PICKER -> activity?.apply {
                setResult(Activity.RESULT_OK, Intent().putExtra(StopPickerActivity.EXTRA_STOP_ID, stop.id))
                finish()
            }

            "editText" -> {
                mainViewModel.changeTableStop(stop.id)
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

    private fun onButtonItemClick(view: View, stop: Stop) {
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

    private fun onFavoriteToggled(stop: Stop) {
        toggleFavoriteStop(requireContext(), stop.id)
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stopsList = BundleCompat.getSerializable(requireArguments(), "stopsList", Stops::class.java).orEmpty()
        showDirections = requireArguments().getBoolean("directions")
        origin = requireArguments().getString("origin").toString()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Extend the overlay surface into the status bar strip while the search is open.
        activity?.findViewById<View>(R.id.statusBarBackground)?.setBackgroundColor(
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface)
        )

        typeAheadAdapter = TypeAheadAdapter(
            showDirections,
            { stop -> onListItemClick(view, stop) },
            { stop -> onButtonItemClick(view, stop) },
            { onButtonItemLongClick(view) },
            ::onFavoriteToggled,
        )
        binding.StopList.adapter = typeAheadAdapter
        binding.StopList.layoutManager = LinearLayoutManager(context)
        ItemTouchHelper(FavoriteSwipe()).attachToRecyclerView(binding.StopList)
        binding.StopList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideVirtualInput(recyclerView)
            }
        })

        eText = when (origin) {
            "editTextFrom" -> activity?.findViewById(R.id.editTextFrom)
            "editTextTo" -> activity?.findViewById(R.id.editTextTo)
            ORIGIN_PICKER -> activity?.findViewById(R.id.pickerSearch)
            else -> activity?.findViewById(R.id.editText)
        }
        // The table field still shows the stop name when the search opens, so it starts unfiltered.
        if (origin != "editText") query = eText?.text.toString().trim()
        listener = object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(p0: Editable?) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {
                query = s.toString().trim()
                refresh(debounce = true)
            }
        }
        eText?.addTextChangedListener(listener)

        if (origin == ORIGIN_PICKER && stopsList.isEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                val context = requireContext().applicationContext
                val location = lastKnownLocation()
                stopsList = withContext(Dispatchers.IO) {
                    readCachedStops(context)?.let { if (location == null) it else sortedByDistance(it, location) }
                }.orEmpty()
                refresh()
            }
        } else {
            refresh()
        }
    }

    private fun lastKnownLocation(): Location? {
        val context = context ?: return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }

    // Pinned first: Actual location while location is allowed (table and picker), the trip planner's own
    // Actual position (id 0), and From map.
    private fun pinnedStops(): List<Stop> {
        val context = requireContext()
        val fromMap = Stop(0, 0, getString(R.string.table_from_map), "", "map", 0, 0.0, 0.0, null)
        val actualLocation = actualLocationStop(context).takeIf { context.hasLocationPermission() }
        return when (origin) {
            "editText" -> listOfNotNull(actualLocation, fromMap)
            ORIGIN_PICKER -> listOfNotNull(actualLocation)
            else -> listOf(Stop(0, 0, getString(R.string.actual_position), "", "location", 0, 0.0, 0.0, null), fromMap)
        }
    }

    // Without a query: favourites, then the rest in distance order. With one: fuzzy matches, best first.
    private fun refresh(debounce: Boolean = false) {
        filterJob?.cancel()
        val term = query.normalized()
        val favorites = favoriteStopIds(requireContext()).toSet()
        val pinned = pinnedStops()
        val stops = stopsList
        filterJob = viewLifecycleOwner.lifecycleScope.launch {
            if (debounce) delay(200)
            val ordered = withContext(Dispatchers.Default) {
                if (term.isEmpty()) {
                    stops.filter { it.id in favorites } + stops.filter { it.id !in favorites }
                } else {
                    stops.mapNotNull { stop ->
                        val (matched, score) = Fuzzy.fuzzyMatch(term, stop.name.normalized())
                        if (matched) stop to score else null
                    }.sortedByDescending { it.second }.map { it.first }
                }
            }
            typeAheadAdapter.submitList((pinned + ordered).map { StopItem(it, it.id in favorites) })
        }
    }

    private fun String.normalized() = unaccent().lowercase().trim()

    /** Swipe a stop toward the start to add or remove it from the favourites. */
    private inner class FavoriteSwipe : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

        override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val position = viewHolder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION || typeAheadAdapter.getStop(position).id <= 0) return 0
            return super.getSwipeDirs(recyclerView, viewHolder)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            typeAheadAdapter.notifyItemChanged(position)
            onFavoriteToggled(typeAheadAdapter.getStop(position))
        }

        override fun onChildDraw(
            c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
        ) {
            val position = viewHolder.bindingAdapterPosition
            if (dX != 0f && position != RecyclerView.NO_POSITION) {
                val favorite = typeAheadAdapter.currentList[position].favorite
                val context = recyclerView.context
                val item = viewHolder.itemView
                val fromEnd = dX < 0
                val left = if (fromEnd) item.right + dX else item.left.toFloat()
                val right = if (fromEnd) item.right.toFloat() else item.left + dX
                val foreground = if (favorite) Color.WHITE else Color.BLACK
                paint.color = ContextCompat.getColor(context, if (favorite) R.color.table_red else R.color.table_yellow)
                val metrics = context.resources.displayMetrics
                val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, metrics).toInt()
                val margin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, metrics).toInt()
                val iconLeft = if (fromEnd) item.right - margin - size else item.left + margin
                val iconTop = item.top + (item.height - size) / 2
                c.withClip(left, item.top.toFloat(), right, item.bottom.toFloat()) {
                    drawColor(paint.color)
                    ContextCompat.getDrawable(context, if (favorite) R.drawable.ic_star_off else R.drawable.ic_star)?.mutate()?.let {
                        it.setTint(foreground)
                        it.setBounds(iconLeft, iconTop, iconLeft + size, iconTop + size)
                        it.draw(this)
                    }
                    paint.color = foreground
                    paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, metrics)
                    paint.textAlign = if (fromEnd) Paint.Align.RIGHT else Paint.Align.LEFT
                    val gap = margin / 2
                    val textX = if (fromEnd) (iconLeft - gap).toFloat() else (iconLeft + size + gap).toFloat()
                    val textY = item.top + item.height / 2f - (paint.descent() + paint.ascent()) / 2
                    drawText(getString(TypeAheadAdapter.favoriteLabel(favorite)), textX, textY, paint)
                }
            }
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    companion object {
        const val ORIGIN_PICKER = "picker"
    }
}

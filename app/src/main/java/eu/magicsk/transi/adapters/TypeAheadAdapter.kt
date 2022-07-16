package eu.magicsk.transi.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.R
import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.data.remote.responses.StopsJSONItem
import eu.magicsk.transi.databinding.StopsListItemBinding
import eu.magicsk.transi.util.unaccent

class TypeAheadAdapter(
    private var typeAheadItemList: MutableList<StopsJSONItem>,
    private val showDirections: Boolean,
    private val onItemClicked: (pos: Int) -> Unit,
    private val onButtonClicked: (pos: Int) -> Unit,
    private val onButtonLongClicked: () -> Unit
) : RecyclerView.Adapter<TypeAheadAdapter.TypeAheadViewHolder>() {
    class TypeAheadViewHolder(
        val binding: StopsListItemBinding,
        private val onItemClicked: (pos: Int) -> Unit,
        private val onButtonClicked: (pos: Int) -> Unit,
        private val onButtonLongClicked: () -> Unit
    ) :
        RecyclerView.ViewHolder(binding.root), View.OnClickListener {
        init {
            binding.root.setOnClickListener(this) // was itemView
            binding.directionBtn.setOnClickListener {
                onButtonClicked(adapterPosition)
            }
            binding.directionBtn.setOnLongClickListener {
                onButtonLongClicked()
                true
            }
        }

        override fun onClick(v: View?) {
            val pos = adapterPosition
            onItemClicked(pos)
        }
    }

    private var _binding: StopsListItemBinding? = null
    private val binding get() = _binding!!
    private val originalStopList = mutableListOf<StopsJSONItem>()
    private val fromMap = StopsJSONItem(
        0, "From map", "From map", "", "-1", "map", 0, 0.0, 0.0, null
    )
    private val actualPosition = StopsJSONItem(
        0, "Actual position", "Actual position", "", "0", "location", 0, 0.0, 0.0, null
    )

    fun filter(term: String) {
        var defaultsCount = 0
        val filtered = originalStopList.filter { it.name.unaccent().contains(term.unaccent(), ignoreCase = true) }
        val toRemove = mutableListOf<Int>()
        typeAheadItemList.forEachIndexed { index, item ->
            if (!filtered.contains(item) && item.id != 0) {
                toRemove.add(index)
            }
            if (item.id == 0) defaultsCount++
        }
        toRemove.forEachIndexed { i, it ->
            typeAheadItemList.removeAt(it-i)
            notifyItemRemoved(it-i)
        }
        filtered.forEachIndexed { index, item ->
            if (!typeAheadItemList.contains(item)) {
                typeAheadItemList.add(index+defaultsCount, item)
                notifyItemInserted(index+defaultsCount)
            }
        }
    }

    fun addItems(items: StopsJSON, ap: Boolean) {
        addDefaults(ap)
        originalStopList.addAll(items)
        typeAheadItemList.addAll(items)
        notifyItemRangeInserted(0, itemCount)
    }

    fun add(pos: Int, item: StopsJSONItem) {
        typeAheadItemList.add(pos, item)
        notifyItemChanged(pos)
    }

    fun remove(item: StopsJSONItem) {
        val index = typeAheadItemList.indexOf(item)
        typeAheadItemList.remove(item)
        notifyItemRemoved(index)
    }

    fun getItem(pos: Int): StopsJSONItem {
        return typeAheadItemList[pos]
    }

    private fun addDefaults(ap: Boolean) {
        typeAheadItemList.add(
            0, fromMap
        )
        if (ap) {
            typeAheadItemList.add(
                0, actualPosition
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TypeAheadViewHolder {
        _binding = StopsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TypeAheadViewHolder(binding, onItemClicked, onButtonClicked, onButtonLongClicked)
    }

    override fun onBindViewHolder(holder: TypeAheadViewHolder, position: Int) {
        val current = typeAheadItemList[position]
        holder.binding.apply {
            val resources = this.root.resources
            val context = this.root.context
            directionBtn.visibility = if (showDirections && current.type != "map") View.VISIBLE else View.GONE
            stopName.text = current.name
            stopName.isSelected = true
            when (current.type) {
                "location" -> {
                    stopIconDrawable.background =
                        ResourcesCompat.getDrawable(resources, R.drawable.ic_location_on, context?.theme)
                    stopIconBackground.backgroundTintList =
                        ResourcesCompat.getColorStateList(resources, R.color.blue_100, context?.theme)
                }

                "map" -> {
                    stopIconDrawable.background =
                        ResourcesCompat.getDrawable(resources, R.drawable.ic_map, context?.theme)
                    stopIconBackground.backgroundTintList =
                        ResourcesCompat.getColorStateList(resources, R.color.green_100, context?.theme)
                }

                "train" -> {
                    stopIconDrawable.background =
                        ResourcesCompat.getDrawable(resources, R.drawable.ic_train, context?.theme)
                    stopIconBackground.backgroundTintList =
                        ResourcesCompat.getColorStateList(resources, R.color.blue_100, context?.theme)
                }

                "tram" -> {
                    stopIconDrawable.background =
                        ResourcesCompat.getDrawable(resources, R.drawable.ic_tram, context?.theme)
                    stopIconBackground.backgroundTintList =
                        ResourcesCompat.getColorStateList(resources, R.color.yellow_100, context?.theme)
                }

                "regio_bus" -> {
                    stopIconDrawable.background =
                        ResourcesCompat.getDrawable(resources, R.drawable.ic_regiobus, context?.theme)
                    stopIconBackground.backgroundTintList =
                        ResourcesCompat.getColorStateList(resources, R.color.yellow_100, context?.theme)
                }

                else -> {
                    stopIconDrawable.background =
                        ResourcesCompat.getDrawable(resources, R.drawable.ic_bus, context?.theme)
                    stopIconBackground.backgroundTintList =
                        ResourcesCompat.getColorStateList(resources, R.color.red_100, context?.theme)
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return typeAheadItemList.size
    }

}
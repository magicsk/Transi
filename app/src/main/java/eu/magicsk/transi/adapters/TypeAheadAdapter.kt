package eu.magicsk.transi.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.R
import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.data.remote.responses.StopsJSONItem
import eu.magicsk.transi.util.unaccent
import kotlinx.android.synthetic.main.stops_list_item.view.*

class TypeAheadAdapter(
    private var typeAheadItemList: MutableList<StopsJSONItem>,
    private val showDirections: Boolean,
    private val onItemClicked: (pos: Int) -> Unit,
    private val onButtonClicked: (pos: Int) -> Unit
) : RecyclerView.Adapter<TypeAheadAdapter.TypeAheadViewHolder>() {
    class TypeAheadViewHolder(
        itemView: View,
        private val onItemClicked: (pos: Int) -> Unit,
        private val onButtonClicked: (pos: Int) -> Unit
    ) :
        RecyclerView.ViewHolder(itemView), View.OnClickListener {
        init {
            itemView.setOnClickListener(this)
            itemView.directionBtn.setOnClickListener {
                onButtonClicked(adapterPosition)
            }
        }

        override fun onClick(v: View?) {
            val pos = adapterPosition
            onItemClicked(pos)
        }
    }



    @SuppressLint("NotifyDataSetChanged")
    fun filter(term: String) {
        typeAheadItemList = typeAheadItemList.filter {
            it.name.unaccent().contains(term.unaccent(), ignoreCase = true)
        } as MutableList<StopsJSONItem>
        notifyDataSetChanged()
    }

    fun addItems(items: StopsJSON) {
        typeAheadItemList.clear()
        typeAheadItemList.addAll(items)
        notifyItemRangeChanged(0, typeAheadItemList.size)
    }

    fun getItem(pos: Int): StopsJSONItem {
        return typeAheadItemList[pos]
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TypeAheadViewHolder {
        val context = parent.context
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.stops_list_item, parent, false)
        return TypeAheadViewHolder(view, onItemClicked, onButtonClicked)
    }

    override fun onBindViewHolder(holder: TypeAheadViewHolder, position: Int) {
        val current = typeAheadItemList[position]
        holder.itemView.apply {
            directionBtn.visibility = if (showDirections) View.VISIBLE else View.GONE
            stopName.text = current.name
            when (current.type) {
                "location" -> {
                    stopIconDrawable.background = ResourcesCompat.getDrawable(resources,R.drawable.ic_location_on, context?.theme)
                    stopIconBackground.backgroundTintList =
                        ResourcesCompat.getColorStateList(resources,R.color.blue_100, context?.theme)
                }
                "train" -> {
                    stopIconDrawable.background = ResourcesCompat.getDrawable(resources,R.drawable.ic_train, context?.theme)
                    stopIconBackground.backgroundTintList =
                        ResourcesCompat.getColorStateList(resources,R.color.blue_100, context?.theme)
                }
                "tram" -> {
                    stopIconDrawable.background = ResourcesCompat.getDrawable(resources, R.drawable.ic_tram, context?.theme)
                    stopIconBackground.backgroundTintList =
                        ResourcesCompat.getColorStateList(resources, R.color.yellow_100, context?.theme)
                }
                else -> {
                    stopIconDrawable.background = ResourcesCompat.getDrawable(resources, R.drawable.ic_bus, context?.theme)
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
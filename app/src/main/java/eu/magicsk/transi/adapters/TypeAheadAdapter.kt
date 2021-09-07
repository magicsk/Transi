package eu.magicsk.transi.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.R
import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.data.remote.responses.StopsJSONItem
import eu.magicsk.transi.util.unaccent
import kotlinx.android.synthetic.main.stops_list_item.view.*

class TypeAheadAdapter(
    private var typeAheadItemList: MutableList<StopsJSONItem>,
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


    private val typeAheadOriginalList: MutableList<StopsJSONItem> = mutableListOf()

    @SuppressLint("NotifyDataSetChanged")
    fun filter(term: String) {
        typeAheadItemList = typeAheadOriginalList.filter {
            it.name.unaccent().contains(term.unaccent(), ignoreCase = true)
        } as MutableList<StopsJSONItem>
        notifyDataSetChanged()
    }

    fun addItems(items: StopsJSON) {
        typeAheadItemList.clear()
        typeAheadOriginalList.clear()
        typeAheadItemList.addAll(items)
        typeAheadOriginalList.addAll(items)
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

    @SuppressLint("UseCompatLoadingForDrawables", "UseCompatLoadingForColorStateLists")
    override fun onBindViewHolder(holder: TypeAheadViewHolder, position: Int) {
        val current = typeAheadItemList[position]
        holder.itemView.apply {
            stopName.text = current.name
            when (current.type) {
                "train" -> {
                    stopIconDrawable.background = resources.getDrawable(R.drawable.ic_train)
                    stopIconBackground.backgroundTintList =
                        resources.getColorStateList(R.color.blue_100)
                }
                "tram" -> {
                    stopIconDrawable.background = resources.getDrawable(R.drawable.ic_tram)
                    stopIconBackground.backgroundTintList =
                        resources.getColorStateList(R.color.yellow_100)
                }
                else -> {
                    stopIconDrawable.background = resources.getDrawable(R.drawable.ic_bus)
                    stopIconBackground.backgroundTintList =
                        resources.getColorStateList(R.color.red_100)
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return typeAheadItemList.size
    }

}
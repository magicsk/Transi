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

class TypeAheadAdapter (
    private var typeAheadItemList: MutableList<StopsJSONItem>,
    private val onItemClicked: (pos: Int) -> Unit
):  RecyclerView.Adapter<TypeAheadAdapter.TypeAheadViewHolder>() {
    class TypeAheadViewHolder(itemView: View, private val onItemClicked: (pos: Int) -> Unit) : RecyclerView.ViewHolder(itemView), View.OnClickListener {
        init {
            itemView.setOnClickListener(this)
        }

        override fun onClick(v: View?) {
            val pos = adapterPosition
            onItemClicked(pos)
        }
    }


    private val TypeAheadOriginalList: MutableList<StopsJSONItem> = mutableListOf()


    @SuppressLint("NotifyDataSetChanged")
    fun filter(term: String): MutableList<StopsJSONItem> {
        typeAheadItemList = TypeAheadOriginalList.filter {
            it.name.unaccent().contains(term.unaccent(), ignoreCase = true)
        } as MutableList<StopsJSONItem>
        notifyDataSetChanged()
        return typeAheadItemList
    }

    fun addItems(items: StopsJSON){
        typeAheadItemList.addAll(items)
        TypeAheadOriginalList.addAll(items)
        notifyItemRangeChanged(0, typeAheadItemList.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TypeAheadViewHolder {
        val context = parent.context
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.stops_list_item, parent, false)
        return TypeAheadViewHolder(view, onItemClicked)
//        return TypeAheadAdapter.TypeAheadViewHolder(
//            LayoutInflater.from(parent.context).inflate(
//                R.layout.stops_list_item,
//                parent,
//                false
//            )
//        )
    }

    override fun onBindViewHolder(holder: TypeAheadViewHolder, position: Int) {
        val current = typeAheadItemList[position]
        holder.itemView.apply {
            stopName.text = current.name
        }
    }

    override fun getItemCount(): Int {
        return typeAheadItemList.size
    }

}
package eu.magicsk.transi.adapters

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.browser.customtabs.CustomTabsIntent
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.R
import kotlinx.android.synthetic.main.table_info_list.view.*

class TableInfoAdapter(
    private val TableInfoList: MutableList<String>
) : RecyclerView.Adapter<TableInfoAdapter.TableInfoViewHolder>() {
    class TableInfoViewHolder(itemView: View) :
        RecyclerView.ViewHolder(itemView)

    fun removeAt(pos: Int) {
        TableInfoList.removeAt(pos)
        notifyItemRemoved(pos)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TableInfoViewHolder {
        val context = parent.context
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.table_info_list, parent, false)
        return TableInfoViewHolder(view)
    }

    override fun onBindViewHolder(holder: TableInfoViewHolder, position: Int) {
        val current = TableInfoList[position]
        holder.itemView.apply {
            MHDTableInfoTextView.text = current
            setOnClickListener {
                val customTabsIntent = CustomTabsIntent.Builder()
                customTabsIntent.build().launchUrl(context,Uri.parse("https://dpb.sk/sk/filter/zmeny-a-obmedzenia"))
            }
        }
    }

    override fun getItemCount(): Int {
        return TableInfoList.size
    }
}
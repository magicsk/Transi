package eu.magicsk.transi.adapters

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.browser.customtabs.CustomTabsIntent
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.databinding.TableInfoListBinding

class TableInfoAdapter(
    private val TableInfoList: MutableList<String>
) : RecyclerView.Adapter<TableInfoAdapter.TableInfoViewHolder>() {
    class TableInfoViewHolder(val binding: TableInfoListBinding) :
        RecyclerView.ViewHolder(binding.root)

    private var _binding: TableInfoListBinding? = null
    private val binding get() = _binding!!

    fun removeAt(pos: Int) {
        TableInfoList.removeAt(pos)
        notifyItemRemoved(pos)
    }

    fun add(info: String) {
        TableInfoList.clear()
        TableInfoList.add(info)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TableInfoViewHolder {
        _binding = TableInfoListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TableInfoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TableInfoViewHolder, position: Int) {
        val current = TableInfoList[position]
        holder.binding.root.apply {
            holder.binding.MHDTableInfoTextView.text = current
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
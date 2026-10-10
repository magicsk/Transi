package eu.magicsk.transi.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.databinding.TableInfoListBinding

/** Service information texts; tapping one opens the operator's list of changes. */
class TableInfoAdapter : ListAdapter<String, TableInfoAdapter.TableInfoViewHolder>(DIFF) {
    class TableInfoViewHolder(val binding: TableInfoListBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TableInfoViewHolder {
        val holder = TableInfoViewHolder(TableInfoListBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        holder.binding.root.setOnClickListener {
            CustomTabsIntent.Builder().build()
                .launchUrl(it.context, "https://dpb.sk/sk/filter/zmeny-a-obmedzenia".toUri())
        }
        return holder
    }

    override fun onBindViewHolder(holder: TableInfoViewHolder, position: Int) {
        holder.binding.MHDTableInfoTextView.text = getItem(position)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
            override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        }
    }
}

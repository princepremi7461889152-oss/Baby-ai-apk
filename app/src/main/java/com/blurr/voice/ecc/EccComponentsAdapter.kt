package com.blurr.voice.ecc

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blurr.voice.R

class EccComponentsAdapter(
    private var items: List<EccComponent>,
    private val onClick: (EccComponent) -> Unit
) : RecyclerView.Adapter<EccComponentsAdapter.ViewHolder>() {

    fun updateItems(newItems: List<EccComponent>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ecc_component, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvEccItemTitle)
        private val tvBadge: TextView = itemView.findViewById(R.id.tvEccItemBadge)
        private val tvDesc: TextView = itemView.findViewById(R.id.tvEccItemDescription)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvEccItemMeta)

        fun bind(item: EccComponent) {
            tvTitle.text = item.title
            tvBadge.text = item.type.name
            tvDesc.text = item.description
            val tagsStr = item.tags.take(3).joinToString(" • ")
            val pathInfo = item.sourcePath.ifBlank { item.category }
            tvMeta.text = if (tagsStr.isNotEmpty()) "${item.category} • $pathInfo • $tagsStr" else "${item.category} • $pathInfo"
        }
    }
}

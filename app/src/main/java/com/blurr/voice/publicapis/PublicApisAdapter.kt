package com.blurr.voice.publicapis

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blurr.voice.R

class PublicApisAdapter(
    private var items: List<PublicApiEntry>,
    private val onItemClick: (PublicApiEntry) -> Unit
) : RecyclerView.Adapter<PublicApisAdapter.ApiViewHolder>() {

    fun updateItems(newItems: List<PublicApiEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ApiViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_public_api, parent, false)
        return ApiViewHolder(view)
    }

    override fun onBindViewHolder(holder: ApiViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class ApiViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val apiName: TextView = itemView.findViewById(R.id.tvApiName)
        private val apiCategory: TextView = itemView.findViewById(R.id.tvApiCategory)
        private val apiDescription: TextView = itemView.findViewById(R.id.tvApiDescription)
        private val apiMeta: TextView = itemView.findViewById(R.id.tvApiMeta)

        fun bind(entry: PublicApiEntry) {
            apiName.text = entry.api
            apiCategory.text = entry.category
            apiDescription.text = entry.description
            val authLabel = if (entry.auth.equals("No", ignoreCase = true)) "No Auth" else "Auth: ${entry.auth}"
            val httpsLabel = if (entry.https) "HTTPS" else "HTTP"
            val corsLabel = "CORS: ${entry.cors}"
            apiMeta.text = "$authLabel  •  $httpsLabel  •  $corsLabel"
        }
    }
}

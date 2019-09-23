package com.github.harukawa.drivetext

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class entryAdapter(private val dataSet: Array<String>) :
    RecyclerView.Adapter<entryAdapter.ViewHolder> () {

    companion object {
        private val TAG = "entryAdapter"
    }

    class ViewHolder(v: View): RecyclerView.ViewHolder(v) {
        val textView: TextView = v.findViewById(R.id.textViewFile)
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(viewGroup.context)
                        .inflate(R.layout.file_item, viewGroup, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.textView.setText(dataSet[position])
    }

    override fun getItemCount() = dataSet.size
}
package nz.valenta.androidtvmqtt.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import nz.valenta.androidtvmqtt.databinding.ItemMessageBinding
import nz.valenta.androidtvmqtt.model.ReceivedMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter : ListAdapter<ReceivedMessage, MessageAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ReceivedMessage) {
            binding.tvTimestamp.text = formatTime(message.timestamp)
            binding.tvTopic.text = message.topic
            binding.tvTitle.text = message.title
            if (message.description.isNotBlank()) {
                binding.tvDescription.text = message.description
                binding.tvDescription.visibility = View.VISIBLE
            } else {
                binding.tvDescription.visibility = View.GONE
            }
        }

        private fun formatTime(timestamp: Long): String =
            SimpleDateFormat("HH:mm:ss  dd/MM/yyyy", Locale.getDefault()).format(Date(timestamp))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object DiffCallback : DiffUtil.ItemCallback<ReceivedMessage>() {
        override fun areItemsTheSame(oldItem: ReceivedMessage, newItem: ReceivedMessage) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ReceivedMessage, newItem: ReceivedMessage) =
            oldItem == newItem
    }
}

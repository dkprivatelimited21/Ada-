package com.ada.assistant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ada.assistant.R
import com.ada.assistant.data.models.ChatMessage
import com.ada.assistant.data.models.MessageRole
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    private val VIEW_TYPE_USER = 1
    private val VIEW_TYPE_ASSISTANT = 2

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).role == MessageRole.USER) VIEW_TYPE_USER else VIEW_TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val view = inflater.inflate(R.layout.item_message_user, parent, false)
            UserMessageViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_message_ada, parent, false)
            AdaMessageViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is UserMessageViewHolder -> holder.bind(message)
            is AdaMessageViewHolder -> holder.bind(message)
        }
    }

    class UserMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvContent: TextView = view.findViewById(R.id.tvMessageContent)
        private val tvTime: TextView = view.findViewById(R.id.tvMessageTime)

        fun bind(message: ChatMessage) {
            tvContent.text = message.content
            tvTime.text = formatTime(message.timestamp)
        }
    }

    class AdaMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvContent: TextView = view.findViewById(R.id.tvMessageContent)
        private val tvTime: TextView = view.findViewById(R.id.tvMessageTime)
        private val tvAgent: TextView? = view.findViewById(R.id.tvAgent)

        fun bind(message: ChatMessage) {
            tvContent.text = message.content
            tvTime.text = formatTime(message.timestamp)
            tvAgent?.let {
                if (!message.agentType.isNullOrBlank() && message.agentType != "system") {
                    it.text = message.agentType
                    it.visibility = View.VISIBLE
                } else {
                    it.visibility = View.GONE
                }
            }
        }
    }

    companion object {
        private val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun formatTime(timestamp: Long): String = sdf.format(Date(timestamp))

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(old: ChatMessage, new: ChatMessage) = old.id == new.id
            override fun areContentsTheSame(old: ChatMessage, new: ChatMessage) = old == new
        }
    }
}

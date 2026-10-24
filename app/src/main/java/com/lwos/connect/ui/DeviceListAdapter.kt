package com.lwos.connect.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lwos.connect.R
import com.lwos.connect.model.DiscoveredDevice

/**
 * Shows nearby LWOS devices, trusted or not. Tapping a trusted device
 * fires [onSendContinueOn]; tapping an untrusted one fires [onPair].
 */
class DeviceListAdapter(
    private val onSendContinueOn: (DiscoveredDevice) -> Unit,
    private val onPair: (DiscoveredDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {

    private val devices = mutableListOf<DiscoveredDevice>()

    fun submitList(newDevices: List<DiscoveredDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.deviceTitle)
        val subtitle: TextView = view.findViewById(R.id.deviceSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.title.text = device.deviceName.ifBlank { device.deviceId }
        holder.subtitle.text = buildString {
            append(device.deviceType.name.lowercase().replaceFirstChar { it.uppercase() })
            append(" · ")
            append(device.linkType.name)
            append(if (device.isTrusted) " · Trusted" else " · Not paired")
        }

        holder.itemView.setOnClickListener {
            if (device.isTrusted) onSendContinueOn(device) else onPair(device)
        }
    }

    override fun getItemCount(): Int = devices.size
}

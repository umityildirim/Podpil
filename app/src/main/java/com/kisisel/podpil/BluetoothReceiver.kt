package com.kisisel.podpil

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.IntentCompat

/**
 * Manifest'te kayıtlı: uygulama kapalıyken de AirPods bağlanınca/kopunca Android bizi uyandırır.
 * Bu yayınlar, Android'in arka plan yayın kısıtlamasından muaf tutulan Bluetooth yayınlarıdır.
 */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        if (!AirPodsDevice.isAirPods(device)) return
        PodRepository.init(context)

        when (intent.action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                val profile = if (intent.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                    BluetoothProfile.A2DP
                } else {
                    BluetoothProfile.HEADSET
                }
                when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                    BluetoothProfile.STATE_CONNECTED -> PodService.onProfileConnected(context, profile)
                    BluetoothProfile.STATE_DISCONNECTED -> PodService.onProfileDisconnected(context, profile)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.i(PodRepository.TAG, "AirPods bağlantısı koptu (ACL)")
                PodService.onDeviceDisconnected(context)
            }
        }
    }
}

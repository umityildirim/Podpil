package com.kisisel.podpil

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.ParcelUuid

/** Bağlanan Bluetooth cihazının AirPods olup olmadığını ve şu an bağlı olup olmadığını bulur. */
object AirPodsDevice {
    // AirPods'un yayınladığı Apple'a özgü hizmet kimlikleri (OpenPods'un kullandığı değerler;
    // senin AirPods'unda ilki telefonun Bluetooth kayıtlarında görüldü).
    private val APPLE_UUIDS = setOf(
        ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a"),
        ParcelUuid.fromString("2a72e02b-7b99-778f-014d-ad0b7221ec74"),
    )

    @SuppressLint("MissingPermission")
    fun isAirPods(device: BluetoothDevice): Boolean = try {
        device.uuids?.any { it in APPLE_UUIDS } == true ||
            device.name?.contains("AirPods", ignoreCase = true) == true
    } catch (e: SecurityException) {
        false // BLUETOOTH_CONNECT izni yok
    }

    /**
     * Uygulama açıldığında: AirPods zaten bağlıysa (ör. uygulama kurulmadan önce bağlanmışsa)
     * "bağlandı" yayını kaçmış olur; o yüzden şu anki bağlantıları bir kez sorarız.
     */
    @SuppressLint("MissingPermission")
    fun syncWithCurrentConnection(context: Context) {
        val app = context.applicationContext
        if (!Permissions.status(app).bluetoothPermission) return
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled } ?: return
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            adapter.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    try {
                        if (proxy.connectedDevices.any(::isAirPods)) PodService.onProfileConnected(app, p)
                    } finally {
                        adapter.closeProfileProxy(p, proxy)
                    }
                }

                override fun onServiceDisconnected(p: Int) = Unit
            }, profile)
        }
    }
}

package com.kisisel.podpil

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.bluetooth.BluetoothManager

/** Kurulum bölümündeki izinlerin anlık durumu. */
data class SetupStatus(
    val bluetoothPermission: Boolean,
    val bluetoothOn: Boolean,
    val notifications: Boolean,
    val overlay: Boolean,
    val batteryUnrestricted: Boolean,
)

object Permissions {
    val BLUETOOTH = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    // Sadece Android 13+ için istenir; 12-12L'de bildirim izni yoktur (bkz. status()).
    @SuppressLint("InlinedApi")
    const val NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

    fun status(context: Context) = SetupStatus(
        bluetoothPermission = BLUETOOTH.all { granted(context, it) },
        bluetoothOn = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true,
        notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, NOTIFICATIONS),
        overlay = Settings.canDrawOverlays(context),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName),
    )

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun packageUri(context: Context) = Uri.fromParts("package", context.packageName, null)

    fun overlaySettings(context: Context) =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context))

    // Play Store bu izni çoğu uygulamaya yasaklar; Pod Pil Play Store'a konmayacak ve arka planda
    // (AirPods bağlanınca) ön plan servisi başlatabilmek için bu muafiyet gerekli.
    @SuppressLint("BatteryLife")
    fun batteryOptimizationRequest(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))

    /** Kullanıcı izni "bir daha sorma" ile reddettiyse sistem penceresi açılmaz; uygulama ayarlarına göndeririz. */
    fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
}

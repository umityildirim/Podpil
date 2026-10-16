package com.kisisel.podpil

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sadece AirPods telefona bağlıyken çalışan "connectedDevice" tipli ön plan servisi.
 * Bağlantı gelince başlar ve taramayı açar; bağlantı (veya Bluetooth) kapanınca hemen kendini kapatır.
 */
class PodService : Service() {

    private lateinit var scanner: PodScanner
    private val scope = MainScope()
    private val profiles = mutableSetOf<Int>()
    private val popup by lazy { PodPopup(this) }

    private val bluetoothOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                Log.i(TAG, "Bluetooth kapandı, servis duruyor")
                shutdown()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        PodRepository.init(this)
        scanner = PodScanner(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(PodRepository.state.value?.battery),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        registerReceiver(bluetoothOffReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        PodRepository.setConnected(true)

        // Bildirimi sadece pil değerleri gerçekten değişince güncelle (RSSI/zaman değişimi sayılmaz).
        scope.launch {
            PodRepository.state.map { it?.battery }.distinctUntilChanged().collect { battery ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(battery))
            }
        }
        // Alt pop-up: bağlantıdan sonraki ilk TAZE değeri bekle (eski kayıtlı değeri gösterme).
        // Ölçümlerde ~5 sn sürüyor; 30 sn içinde gelmezse pop-up gösterme.
        val connectedAt = System.currentTimeMillis()
        scope.launch {
            val fresh = withTimeoutOrNull(POPUP_WAIT_MS) {
                PodRepository.state.first { it != null && it.updatedAt >= connectedAt }
            }
            if (fresh != null) popup.show() else Log.i(TAG, "Taze değer gelmedi, pop-up atlandı")
        }
        Log.i(TAG, "Servis başladı")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val profile = intent?.getIntExtra(EXTRA_PROFILE, -1) ?: -1
        when (intent?.action) {
            ACTION_PROFILE_CONNECTED -> {
                profiles += profile
                scanner.start()
            }
            ACTION_PROFILE_DISCONNECTED -> {
                profiles -= profile
                if (profiles.isEmpty()) shutdown()
            }
            ACTION_DEVICE_DISCONNECTED -> shutdown()
        }
        // Sistem servisi öldürürse kendiliğinden yeniden başlatmasın; bir sonraki bağlantı yayını başlatır.
        return START_NOT_STICKY
    }

    /**
     * "Çalışmıyor" bayrağını hemen indirir: aynı kopma için arkadan gelen diğer yayınlar
     * (ACL + A2DP + HFP neredeyse aynı anda gelir) servisi boşuna yeniden başlatmasın.
     */
    private fun shutdown() {
        running = false
        scanner.stop()
        stopSelf()
    }

    override fun onDestroy() {
        popup.removeNow()
        scanner.stop()
        scope.cancel()
        unregisterReceiver(bluetoothOffReceiver)
        PodRepository.setConnected(false)
        running = false
        Log.i(TAG, "Servis durdu")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "AirPods pil durumu", NotificationManager.IMPORTANCE_MIN).apply {
            description = "AirPods bağlıyken pil seviyesini gösteren kalıcı bildirim"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(battery: PodBattery?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (battery == null) "Pil bilgisi bekleniyor…" else PodPopup.batteryText(battery)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pods)
            .setContentTitle(battery?.let { AirPodsParser.modelName(it.model) } ?: "AirPods bağlı")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = PodRepository.TAG
        private const val CHANNEL_ID = "durum"
        private const val NOTIFICATION_ID = 1
        private const val POPUP_WAIT_MS = 30_000L
        private const val EXTRA_PROFILE = "profile"
        private const val ACTION_PROFILE_CONNECTED = "com.kisisel.podpil.PROFILE_CONNECTED"
        private const val ACTION_PROFILE_DISCONNECTED = "com.kisisel.podpil.PROFILE_DISCONNECTED"
        private const val ACTION_DEVICE_DISCONNECTED = "com.kisisel.podpil.DEVICE_DISCONNECTED"

        @Volatile
        var running = false
            private set

        fun onProfileConnected(context: Context, profile: Int) {
            val intent = Intent(context, PodService::class.java)
                .setAction(ACTION_PROFILE_CONNECTED).putExtra(EXTRA_PROFILE, profile)
            Log.i(TAG, "AirPods bağlandı (${profileName(profile)})")
            try {
                context.startForegroundService(intent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "Servis arka plandan başlatılamadı. Pil optimizasyonu muafiyeti verilmemiş olabilir.", e)
            }
        }

        fun onProfileDisconnected(context: Context, profile: Int) {
            Log.i(TAG, "AirPods profili koptu (${profileName(profile)})")
            sendIfRunning(context, Intent(context, PodService::class.java)
                .setAction(ACTION_PROFILE_DISCONNECTED).putExtra(EXTRA_PROFILE, profile))
        }

        fun onDeviceDisconnected(context: Context) =
            sendIfRunning(context, Intent(context, PodService::class.java).setAction(ACTION_DEVICE_DISCONNECTED))

        /** Servis zaten çalışmıyorsa sırf kapatmak için başlatmayız. */
        private fun sendIfRunning(context: Context, intent: Intent) {
            if (!running) return
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Servise komut gönderilemedi: ${e.message}")
            }
        }

        private fun profileName(profile: Int) = when (profile) {
            BluetoothProfile.A2DP -> "A2DP"
            BluetoothProfile.HEADSET -> "kulaklık/HFP"
            else -> "profil $profile"
        }
    }
}

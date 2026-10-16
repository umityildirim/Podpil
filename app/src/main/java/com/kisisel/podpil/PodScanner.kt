package com.kisisel.podpil

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Düşük güçlü BLE taraması. Sadece Apple Proximity Pairing reklamları donanım filtresinden geçer.
 * Yakındaki başkalarının AirPods'larını elemek için: RSSI >= -60 dBm ve son 10 sn'deki en güçlü sinyal.
 * Android 30 saniyede 5'ten fazla tarama başlatan uygulamayı cezalandırır; en fazla 4'e izin verip
 * fazlasını sınır açılana kadar erteleriz (bağlantı hızlı hızlı kopup gelirse diye).
 */
class PodScanner(private val context: Context) {

    private val latest = HashMap<String, ScanResult>()
    private var lastAppliedNanos = 0L
    private var callback: ScanCallback? = null
    private val startTimes = ArrayDeque<Long>()
    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { start() }

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (callback != null) return true
        handler.removeCallbacks(retry)
        if (!hasPermission()) {
            Log.w(TAG, "Tarama başlatılamadı: BLUETOOTH_SCAN izni yok")
            return false
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "Tarama başlatılamadı: Bluetooth kapalı")
            return false
        }

        val now = SystemClock.elapsedRealtime()
        while (startTimes.isNotEmpty() && now - startTimes.first() > START_WINDOW_MS) startTimes.removeFirst()
        if (startTimes.size >= MAX_STARTS_PER_WINDOW) {
            val wait = START_WINDOW_MS - (now - startTimes.first()) + 500
            Log.w(TAG, "Tarama sınırına yaklaşıldı, ${wait} ms sonra başlatılacak")
            handler.postDelayed(retry, wait)
            return false
        }

        val filter = ScanFilter.Builder()
            .setManufacturerData(
                AirPodsParser.APPLE_ID,
                byteArrayOf(AirPodsParser.TYPE_PROXIMITY.toByte(), AirPodsParser.PAYLOAD_LENGTH.toByte()),
                byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
            )
            .build()
        // Toplu (batch) tarama zorunlu: Galaxy S26 Ultra / One UI 9'da 0x07 paketleri anlık
        // taramaya hiç gelmiyor, sadece toplu taramaya geliyor (ölçüldü, 10.10.2026).
        // Ayrıca daha az pil harcar: Bluetooth yongası sonuçları biriktirir, işlemci 5 sn'de bir uyanır.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(REPORT_DELAY_MS)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) =
                results.sortedBy { it.timestampNanos }.forEach(::handle)
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Tarama hatası: $errorCode")
                callback = null
                PodRepository.setScanning(false)
            }
        }
        scanner.startScan(listOf(filter), settings, cb)
        startTimes.addLast(now)
        callback = cb
        PodRepository.setScanning(true)
        Log.i(TAG, "BLE tarama başladı (LOW_POWER, toplu ${REPORT_DELAY_MS} ms, 0x004C filtresi)")
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacks(retry)
        val cb = callback ?: return
        callback = null
        latest.clear()
        lastAppliedNanos = 0L
        PodRepository.setScanning(false)
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (e: Exception) {
            // Bluetooth kapatılmışsa stopScan hata verebilir; tarama zaten durmuştur.
            Log.w(TAG, "stopScan: ${e.message}")
        }
        Log.i(TAG, "BLE tarama durdu")
    }

    /**
     * İki kulaklık ayrı adreslerden yayın yapar ve toplu taramada paketler sırasız/tekrarlı gelebilir.
     * Kural: her adresin sadece en yeni paketini tut, son 10 sn'de sinyali en güçlü adresi izle
     * (yakındaki başkalarının AirPods'unu elemek için) ve zamanda asla geriye gitme.
     */
    private fun handle(result: ScanResult) {
        if (AirPodsParser.parse(manufacturerData(result)) == null) return
        val address = result.device.address
        val previous = latest[address]
        if (previous != null && previous.timestampNanos >= result.timestampNanos) return
        latest[address] = result
        latest.values.removeAll { result.timestampNanos - it.timestampNanos > RECENT_WINDOW_NS }

        val best = latest.values.maxBy { it.rssi }
        if (best.rssi < MIN_RSSI) {
            Log.d(TAG, "Zayıf sinyal yok sayıldı: ${best.rssi} dBm")
            return
        }
        if (best.timestampNanos <= lastAppliedNanos) return
        lastAppliedNanos = best.timestampNanos
        val data = manufacturerData(best) ?: return
        val battery = AirPodsParser.parse(data) ?: return
        PodRepository.update(battery, best.rssi, "..${best.device.address.takeLast(5)} ${AirPodsParser.toHex(data)}")
    }

    private fun manufacturerData(result: ScanResult): ByteArray? =
        result.scanRecord?.getManufacturerSpecificData(AirPodsParser.APPLE_ID)

    companion object {
        private const val TAG = PodRepository.TAG
        const val MIN_RSSI = -60
        private const val REPORT_DELAY_MS = 5_000L
        private const val RECENT_WINDOW_NS = 10_000_000_000L
        private const val START_WINDOW_MS = 30_000L
        private const val MAX_STARTS_PER_WINDOW = 4
    }
}

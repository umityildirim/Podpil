package com.kisisel.podpil

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Uygulamanın tek "gerçek" pil durumu. Ekran, servis ve widget buradan okur. */
object PodRepository {
    const val TAG = "PodPil"

    data class Snapshot(val battery: PodBattery, val rssi: Int, val updatedAt: Long)

    private val _state = MutableStateFlow<Snapshot?>(null)
    val state: StateFlow<Snapshot?> = _state.asStateFlow()

    /** AirPods telefona Bluetooth ile bağlı mı (servis çalışıyor mu). */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Son bilinen değerleri diskten yükler; uygulama yeniden açılınca/widget için. */
    fun init(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        val p = context.getSharedPreferences("son_degerler", Context.MODE_PRIVATE)
        prefs = p
        if (p.contains("updatedAt")) {
            _state.value = Snapshot(
                battery = PodBattery(
                    model = p.getInt("model", 0),
                    left = p.getInt("left", -1).takeIf { it >= 0 },
                    right = p.getInt("right", -1).takeIf { it >= 0 },
                    case = p.getInt("case", -1).takeIf { it >= 0 },
                    leftCharging = p.getBoolean("leftCharging", false),
                    rightCharging = p.getBoolean("rightCharging", false),
                    caseCharging = p.getBoolean("caseCharging", false),
                ),
                rssi = p.getInt("rssi", 0),
                updatedAt = p.getLong("updatedAt", 0),
            )
        }
    }

    fun update(packet: PodBattery, rssi: Int, rawHex: String) {
        val previous = _state.value?.battery
        // Kutunun dışındaki kulaklık kutunun pilini bilmez ve "15 = bilinmiyor" yollar; bir kulaklık da
        // diğerini göremeyebilir. Bilinmeyen değerde son bilineni tut, yoksa değer %50 ile — arasında gidip gelir.
        val battery = if (previous == null) packet else packet.copy(
            left = packet.left ?: previous.left,
            right = packet.right ?: previous.right,
            case = packet.case ?: previous.case,
            caseCharging = if (packet.case == null) previous.caseCharging else packet.caseCharging,
        )
        _state.value = Snapshot(battery, rssi, System.currentTimeMillis())
        if (battery != previous) {
            Log.i(TAG, "Yeni değer: sol=${battery.left} sağ=${battery.right} kutu=${battery.case} " +
                "şarj(L/R/K)=${battery.leftCharging}/${battery.rightCharging}/${battery.caseCharging} " +
                "model=${AirPodsParser.modelName(battery.model)} rssi=$rssi ham=$rawHex")
            save()
        }
    }

    fun setConnected(value: Boolean) {
        if (_connected.value == value) return
        _connected.value = value
        // Bağlantı kopunca "son görülme" zamanını kaydet (her pakette diske yazmıyoruz).
        if (!value) save() else updateWidget()
    }

    fun setScanning(value: Boolean) {
        _scanning.value = value
    }

    private fun save() {
        val s = _state.value ?: return
        prefs?.edit {
            putInt("model", s.battery.model)
            putInt("left", s.battery.left ?: -1)
            putInt("right", s.battery.right ?: -1)
            putInt("case", s.battery.case ?: -1)
            putBoolean("leftCharging", s.battery.leftCharging)
            putBoolean("rightCharging", s.battery.rightCharging)
            putBoolean("caseCharging", s.battery.caseCharging)
            putInt("rssi", s.rssi)
            putLong("updatedAt", s.updatedAt)
        }
        updateWidget()
    }

    /** Widget sadece burada yenilenir: pil değeri değişince ve bağlantı kurulup kopunca. */
    private fun updateWidget() {
        val context = appContext ?: return
        widgetScope.launch {
            runCatching { PodWidget().updateAll(context) }
                .onFailure { Log.w(TAG, "Widget güncellenemedi: ${it.message}") }
        }
    }
}

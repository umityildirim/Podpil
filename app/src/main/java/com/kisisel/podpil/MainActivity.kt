package com.kisisel.podpil

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                MainScreen()
            }
        }
    }
}

/** Kurulum bölümündeki bir satır: başlık, açıklama, tamam mı, eksikse ne yapılacak. */
private class SetupItem(val title: String, val subtitle: String, val ok: Boolean, val fix: () -> Unit)

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val snapshot by PodRepository.state.collectAsStateWithLifecycle()
    val connected by PodRepository.connected.collectAsStateWithLifecycle()
    val scanning by PodRepository.scanning.collectAsStateWithLifecycle()
    var setup by remember { mutableStateOf(Permissions.status(context)) }
    var bluetoothDenied by remember { mutableStateOf(false) }
    var notificationsDenied by remember { mutableStateOf(false) }
    var showSetupDetails by remember { mutableStateOf(false) }

    fun refresh() {
        setup = Permissions.status(context)
        // Tarama ekrana değil bağlantıya bağlı: ekran sadece AirPods zaten bağlıysa servisi başlatır.
        if (setup.bluetoothPermission && setup.bluetoothOn && !PodService.running) {
            AirPodsDevice.syncWithCurrentConnection(context)
        }
    }

    // Ayarlardan geri dönünce (ör. "diğer uygulamaların üzerinde göster" açıldıysa) durumu yenile.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        bluetoothDenied = result.values.any { !it }
        refresh()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        notificationsDenied = !ok
        refresh()
    }

    val setupItems = listOf(
        SetupItem("Bluetooth izni", "AirPods sinyallerini okumak için", setup.bluetoothPermission) {
            if (bluetoothDenied) context.startActivity(Permissions.appDetails(context))
            else bluetoothLauncher.launch(Permissions.BLUETOOTH)
        },
        SetupItem("Bluetooth açık", "Telefonun Bluetooth'u açık olmalı", setup.bluetoothOn) {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        },
        SetupItem("Bildirim izni", "Bağlıyken pil bildirimi için", setup.notifications) {
            if (notificationsDenied) context.startActivity(Permissions.appDetails(context))
            else notificationLauncher.launch(Permissions.NOTIFICATIONS)
        },
        SetupItem("Diğer uygulamaların üzerinde göster", "Alttan çıkan pil kartı için", setup.overlay) {
            context.startActivity(Permissions.overlaySettings(context))
        },
        SetupItem("Pil optimizasyonu muafiyeti", "Arka planda başlayabilmek için (zorunlu)", setup.batteryUnrestricted) {
            context.startActivity(Permissions.batteryOptimizationRequest(context))
        },
    )
    val missing = setupItems.filterNot { it.ok }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text("Pod Pil", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))

            // Pil kartı
            val s = snapshot
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(vertical = 24.dp, horizontal = 12.dp)) {
                    Text(
                        if (s == null) "AirPods" else AirPodsParser.modelName(s.battery.model),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(6.dp))
                    StatusChip(connected, scanning, s?.updatedAt, Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(24.dp))
                    // Bağlı değilken son bilinen değerler soluk gösterilir.
                    BatteryRow(s?.battery, Modifier.alpha(if (connected) 1f else 0.45f))
                    if (s == null) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "AirPods'u telefona bağla; değerler 20-30 sn içinde gelir.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Kurulum: her şey tamamsa tek satır; eksik varsa sadece eksikler.
            if (missing.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSetupDetails = !showSetupDetails }
                        .padding(vertical = 8.dp),
                ) {
                    Text("✓  Kurulum tamam", color = PodGreen, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (showSetupDetails) "Gizle" else "Ayrıntılar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showSetupDetails) setupItems.forEach { SetupRow(it) }
            } else {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Eksik ayarlar (${missing.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        missing.forEach { SetupRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(connected: Boolean, scanning: Boolean, updatedAt: Long?, modifier: Modifier = Modifier) {
    val text = when {
        connected && scanning && updatedAt != null -> "Bağlı · ${formatTime(updatedAt)}"
        connected -> "Bağlı · değer bekleniyor"
        updatedAt != null -> "Son görülme ${formatTime(updatedAt)}"
        else -> "Bağlı değil"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(if (connected) PodGreen else Color.Gray, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SetupRow(item: SetupItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (item.ok) "✓  " else "") + item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (!item.ok) {
                Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!item.ok) FilledTonalButton(onClick = item.fix) { Text("Aç") }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateTimeFormat = DateTimeFormatter.ofPattern("d MMM HH:mm")

/** Bugünse sadece saat, değilse gün + saat. */
private fun formatTime(millis: Long): String {
    val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return if (time.toLocalDate() == LocalDate.now(ZoneId.systemDefault())) timeFormat.format(time)
    else dateTimeFormat.format(time)
}

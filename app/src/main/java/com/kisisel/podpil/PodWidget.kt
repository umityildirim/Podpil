package com.kisisel.podpil

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.LocalSize
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.runtime.collectAsState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Ana ekran widget'ı: sol · sağ · kutu. Bağlı değilken son bilinen değerler soluk ve "son görülme" ile.
 * Kendiliğinden yenilenmez; PodRepository değer değişince/bağlantı değişince updateAll çağırır.
 */
class PodWidget : GlanceAppWidget() {

    // Gerçek boyutu bil: kilit ekranı gibi dar yerlerde ikon üstte, yazı altta dizilir.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        PodRepository.init(context)
        provideContent {
            val snapshot by PodRepository.state.collectAsState()
            val connected by PodRepository.connected.collectAsState()
            GlanceTheme { WidgetContent(snapshot, connected) }
        }
    }
}

class PodWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PodWidget()
}

// Gündüz / gece renkleri
private val Background = ColorProvider(day = Color(0xFFF2F2F7), night = Color(0xFF1C1C1E))
private val Foreground = ColorProvider(day = Color(0xFF111111), night = Color(0xFFFFFFFF))
private val ForegroundDim = ColorProvider(day = Color(0x66111111), night = Color(0x66FFFFFF))
private val Secondary = ColorProvider(day = Color(0x99111111), night = Color(0x99FFFFFF))
private val Track = ColorProvider(day = Color(0x1A111111), night = Color(0x26FFFFFF))
private val Green = ColorProvider(PodGreen)
private val GreenDim = ColorProvider(PodGreen.copy(alpha = 0.4f))
private val Red = ColorProvider(PodRed)
private val RedDim = ColorProvider(PodRed.copy(alpha = 0.4f))

@Composable
private fun WidgetContent(snapshot: PodRepository.Snapshot?, connected: Boolean) {
    val battery = snapshot?.battery
    val size = LocalSize.current
    // Öğe başına ~80 dp'den azsa (ör. kilit ekranındaki 193 dp genişlik) dikey diz.
    val compact = size.width < 240.dp
    val showStatus = size.height >= 90.dp
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Background)
            .cornerRadius(24.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WidgetItem(R.drawable.ic_pod, battery?.left, battery?.leftCharging == true, connected, compact, GlanceModifier.defaultWeight())
            WidgetItem(R.drawable.ic_pod_right, battery?.right, battery?.rightCharging == true, connected, compact, GlanceModifier.defaultWeight())
            WidgetItem(R.drawable.ic_case, battery?.case, battery?.caseCharging == true, connected, compact, GlanceModifier.defaultWeight())
        }
        if (showStatus) {
            Spacer(GlanceModifier.height(if (compact) 4.dp else 6.dp))
            Text(
                text = when {
                    snapshot == null -> "AirPods bekleniyor"
                    connected -> "Bağlı"
                    else -> "Son görülme ${formatSeen(snapshot.updatedAt)}"
                },
                style = TextStyle(color = if (connected) Green else Secondary, fontSize = 11.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun WidgetItem(
    icon: Int,
    percent: Int?,
    charging: Boolean,
    connected: Boolean,
    compact: Boolean,
    modifier: GlanceModifier,
) {
    val low = percent != null && percent <= 20
    val fg = if (connected) Foreground else ForegroundDim
    val iconView = @Composable {
        Image(
            provider = ImageProvider(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(fg),
            modifier = GlanceModifier.size(if (compact) 20.dp else 22.dp),
        )
    }
    val valueView = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (percent == null) "—" else "%$percent",
                style = TextStyle(
                    color = if (low) (if (connected) Red else RedDim) else fg,
                    fontSize = if (compact) 14.sp else 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            if (charging) {
                Image(
                    provider = ImageProvider(R.drawable.ic_bolt),
                    contentDescription = "Şarj oluyor",
                    colorFilter = ColorFilter.tint(if (connected) Green else GreenDim),
                    modifier = GlanceModifier.size(if (compact) 12.dp else 14.dp),
                )
            }
        }
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (compact) {
            iconView()
            Spacer(GlanceModifier.height(2.dp))
            valueView()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                iconView()
                Spacer(GlanceModifier.width(4.dp))
                valueView()
            }
        }
        Spacer(GlanceModifier.height(if (compact) 4.dp else 6.dp))
        LinearProgressIndicator(
            progress = (percent ?: 0) / 100f,
            modifier = GlanceModifier.width(if (compact) 40.dp else 52.dp).height(4.dp),
            color = when {
                low -> if (connected) Red else RedDim
                else -> if (connected) Green else GreenDim
            },
            backgroundColor = Track,
        )
    }
}

private val seenTime = DateTimeFormatter.ofPattern("HH:mm")
private val seenDateTime = DateTimeFormatter.ofPattern("d MMM HH:mm")

private fun formatSeen(millis: Long): String {
    val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return if (time.toLocalDate() == LocalDate.now(ZoneId.systemDefault())) seenTime.format(time)
    else seenDateTime.format(time)
}

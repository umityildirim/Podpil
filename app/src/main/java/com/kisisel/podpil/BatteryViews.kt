package com.kisisel.podpil

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val PodGreen = Color(0xFF34C759)
val PodRed = Color(0xFFFF3B30)

/** Sol kulaklık · sağ kulaklık · kutu: halka + ikon + yüzde. Ana ekran ve alt pop-up ortak kullanır. */
@Composable
fun BatteryRow(battery: PodBattery?, modifier: Modifier = Modifier, ringSize: Dp = 96.dp) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        BatteryItem("Sol", battery?.left, battery?.leftCharging == true, R.drawable.ic_pod, false, ringSize)
        BatteryItem("Sağ", battery?.right, battery?.rightCharging == true, R.drawable.ic_pod, true, ringSize)
        BatteryItem("Kutu", battery?.case, battery?.caseCharging == true, R.drawable.ic_case, false, ringSize)
    }
}

@Composable
private fun BatteryItem(
    label: String,
    percent: Int?,
    charging: Boolean,
    @DrawableRes icon: Int,
    mirror: Boolean,
    ringSize: Dp,
) {
    val progress by animateFloatAsState(
        targetValue = (percent ?: 0) / 100f,
        animationSpec = tween(durationMillis = 700),
        label = "pil",
    )
    val ringColor = when {
        percent == null -> Color.Transparent
        percent <= 20 -> PodRed
        else -> PodGreen
    }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val iconColor = MaterialTheme.colorScheme.onSurface

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(ringSize)) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.075f
                val style = Stroke(width = stroke, cap = StrokeCap.Round)
                val inset = stroke / 2
                val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(track, 0f, 360f, false, topLeft, arcSize, style = style)
                drawArc(ringColor, -90f, 360f * progress, false, topLeft, arcSize, style = style)
            }
            Image(
                painter = painterResource(icon),
                contentDescription = label,
                colorFilter = ColorFilter.tint(iconColor),
                modifier = Modifier
                    .size(ringSize * 0.42f)
                    .graphicsLayer { if (mirror) scaleX = -1f },
            )
            if (charging) {
                // Şimşek halkanın sağ üstünde küçük bir rozet olarak.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(ringSize * 0.3f),
                ) {
                    Canvas(Modifier.fillMaxSize()) { drawCircle(PodGreen) }
                    Image(
                        painter = painterResource(R.drawable.ic_bolt),
                        contentDescription = "Şarj oluyor",
                        colorFilter = ColorFilter.tint(Color.White),
                        modifier = Modifier.size(ringSize * 0.2f),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (percent == null) "—" else "%$percent",
            fontSize = (ringSize.value * 0.24f).sp,
            fontWeight = FontWeight.SemiBold,
            color = if (percent != null && percent <= 20) PodRed else MaterialTheme.colorScheme.onSurface,
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

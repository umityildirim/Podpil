package com.kisisel.podpil

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * AirPods bağlanınca ekranın altından kayarak çıkan Apple tarzı pil kartı (TYPE_APPLICATION_OVERLAY).
 * 6 sn sonra, aşağı kaydırınca veya dokununca kapanır. Overlay izni yoksa heads-up bildirim gösterir.
 */
class PodPopup(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var visible = MutableTransitionState(false)
    private val autoDismiss = Runnable { dismiss() }

    fun show() {
        // Overlay izni yoksa ya da telefon kilitliyse (Android overlay pencerelerini kilit ekranında
        // göstermez) kart yerine heads-up bildirim.
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!Settings.canDrawOverlays(context) || locked) {
            showHeadsUpNotification(if (locked) "kilit ekranı" else "overlay izni yok")
            return
        }
        if (view != null) return

        visible = MutableTransitionState(false) // her gösterimde yeni: giriş animasyonu baştan oynasın
        val overlayOwner = OverlayOwner().also { it.start() }
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(overlayOwner)
            setViewTreeSavedStateRegistryOwner(overlayOwner)
            setContent { PopupContent() }
        }
        // Kart gezinme çubuğunun hemen üstünde dursun; pencereyi ekranın en altına kadar uzatıp
        // gezinme çubuğu kadar boşluğu kendimiz bırakıyoruz ki kayma ekranın dibinden başlasın.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            fitInsetsTypes = 0
            title = "Pod Pil pop-up"
        }
        try {
            windowManager.addView(composeView, params)
        } catch (e: Exception) {
            Log.e(PodRepository.TAG, "Pop-up gösterilemedi, bildirime düşülüyor", e)
            overlayOwner.destroy()
            showHeadsUpNotification("overlay hatası")
            return
        }
        view = composeView
        owner = overlayOwner
        visible.targetState = true
        handler.postDelayed(autoDismiss, SHOW_MS)
        Log.i(PodRepository.TAG, "Pop-up gösterildi")
    }

    /** Kaydırarak kapanma animasyonunu başlatır; animasyon bitince pencere kaldırılır. */
    fun dismiss() {
        handler.removeCallbacks(autoDismiss)
        if (view == null) return
        visible.targetState = false
    }

    /** Animasyonsuz hemen kaldır (servis kapanırken). */
    fun removeNow() {
        handler.removeCallbacks(autoDismiss)
        val v = view ?: return
        view = null
        runCatching { windowManager.removeView(v) }
        owner?.destroy()
        owner = null
        visible.targetState = false
    }

    @Composable
    private fun PopupContent() {
        val snapshot by PodRepository.state.collectAsStateWithLifecycle()
        val density = LocalDensity.current
        val navBarPx = remember {
            windowManager.currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.navigationBars()).bottom
        }
        val dragOffset = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val dismissThresholdPx = with(density) { 80.dp.toPx() }

        // Kapanma animasyonu bitince pencereyi kaldır.
        LaunchedEffect(visible.currentState, visible.isIdle) {
            if (visible.isIdle && !visible.currentState && !visible.targetState) removeNow()
        }

        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            AnimatedVisibility(
                visibleState = visible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp + with(density) { navBarPx.toDp() }),
                ) {
                    Surface(
                        shape = RoundedCornerShape(36.dp),
                        color = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color.White,
                        shadowElevation = 12.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset { IntOffset(0, dragOffset.value.roundToInt()) }
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta ->
                                    scope.launch { dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f)) }
                                },
                                onDragStarted = { handler.removeCallbacks(autoDismiss) },
                                onDragStopped = {
                                    if (dragOffset.value > dismissThresholdPx) dismiss()
                                    else {
                                        dragOffset.animateTo(0f)
                                        handler.postDelayed(autoDismiss, SHOW_MS)
                                    }
                                },
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { dismiss() },
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(top = 10.dp, bottom = 28.dp),
                        ) {
                            // Tutma çubuğu: aşağı kaydırılabileceğini belli eder.
                            Box(
                                Modifier
                                    .size(width = 36.dp, height = 5.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), RoundedCornerShape(3.dp)),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                snapshot?.battery?.let { AirPodsParser.modelName(it.model) } ?: "AirPods",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(20.dp))
                            BatteryRow(snapshot?.battery, ringSize = 84.dp)
                        }
                    }
                }
            }
        }
    }

    private fun showHeadsUpNotification(reason: String) {
        val battery = PodRepository.state.value?.battery ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AirPods bağlandı", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Overlay izni yokken bağlantı anında çıkan pil bildirimi"
                setSound(null, null)
                enableVibration(false)
            },
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pods)
            .setContentTitle("${AirPodsParser.modelName(battery.model)} bağlandı")
            .setContentText(batteryText(battery))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(SHOW_MS)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC) // pil yüzdesi gizli bilgi değil; kilit ekranında da görünsün
            .build()
        nm.notify(NOTIFICATION_ID, notification)
        Log.i(PodRepository.TAG, "Heads-up bildirim gösterildi ($reason)")
    }

    companion object {
        const val SHOW_MS = 6_000L
        private const val CHANNEL_ID = "baglanti"
        private const val NOTIFICATION_ID = 2

        fun batteryText(battery: PodBattery): String {
            fun f(v: Int?, c: Boolean) = (if (v == null) "—" else "%$v") + if (c) "⚡" else ""
            return "Sol ${f(battery.left, battery.leftCharging)} · Sağ ${f(battery.right, battery.rightCharging)} · " +
                "Kutu ${f(battery.case, battery.caseCharging)}"
        }
    }
}

/** Activity dışındaki bir pencerede Compose çalıştırmak için gereken asgari yaşam döngüsü. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun start() {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

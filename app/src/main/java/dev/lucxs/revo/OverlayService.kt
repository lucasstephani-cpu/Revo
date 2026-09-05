package dev.lucxs.revo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Owns the overlay window and the sensors feeding it for as long as the
 * user has the feature turned on. Runs as a foreground service so Android
 * doesn't kill it the moment the screen goes idle or another app opens -
 * exactly the situation this app needs to survive (it exists specifically
 * for while the user is in some *other* app on a bus or in a car).
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var sensorManager: SensorManager
    private var overlayView: MotionOverlayView? = null
    private var motionFusion: MotionFusion? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            Prefs.CHAVE_SENSIBILIDADE -> motionFusion?.sensitivity =
                prefs.getInt(key, Prefs.PADRAO_SENSIBILIDADE) / 100f
            Prefs.CHAVE_OPACIDADE -> overlayView?.setOpacity(
                prefs.getInt(key, Prefs.PADRAO_OPACIDADE)
            )
            Prefs.CHAVE_QUANTIDADE_PONTOS -> overlayView?.setDotCount(
                prefs.getInt(key, Prefs.PADRAO_QUANTIDADE_PONTOS)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        startForegroundWithNotification()
        addOverlayView()
        startSensors()

        Prefs.arquivo(this).registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        Prefs.arquivo(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        motionFusion?.stop()
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        Prefs.setAtivo(this, false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addOverlayView() {
        val view = MotionOverlayView(this).apply {
            setOpacity(Prefs.opacidade(this@OverlayService))
            setDotCount(Prefs.quantidadePontos(this@OverlayService))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        windowManager.addView(view, params)
        overlayView = view
    }

    private fun startSensors() {
        val sensitivity = Prefs.sensibilidade(this) / 100f
        motionFusion = MotionFusion(sensorManager) { dx, dy ->
            overlayView?.updateMotion(dx, dy)
        }.apply {
            this.sensitivity = sensitivity
            start()
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        // The typed overload only matters from API 34 on, which is also the
        // first OS version that defines FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        // at all - older versions don't enforce a type match, so the plain
        // two-arg call is the correct (and only valid) one there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "revo_overlay"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}

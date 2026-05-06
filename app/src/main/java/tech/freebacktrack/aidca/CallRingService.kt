package tech.freebacktrack.aidca

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Bark 风 call=1 持续响铃服务。启动后以前台服务运行，循环播放默认铃声 + 振动，直到用户点击“停止响铃”或超超30s 超时。
 * 外部入口：
 *  - [start] 传 messageId/title/body 快速启动。
 *  - [stop] 任何位置主动终止。
 */
class CallRingService : Service() {
  private var mediaPlayer: MediaPlayer? = null
  private var vibrator: Vibrator? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private val autoStopRunnable = Runnable {
    DebugLogStore.append(this, "call", "auto stop after timeout")
    stopSelf()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    if (action == ACTION_STOP) {
      stopSelf()
      return START_NOT_STICKY
    }
    val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
    val body = intent?.getStringExtra(EXTRA_BODY).orEmpty()
    val notif = buildOngoingNotification(title, body)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // 与 RealtimeChannelService 一致采用 dataSync，API 31+ 走传入参数重载，低版本调用同名重载。
      try {
        startForeground(
          NOTIF_ID,
          notif,
          android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
      } catch (_: Throwable) {
        startForeground(NOTIF_ID, notif)
      }
    } else {
      startForeground(NOTIF_ID, notif)
    }
    startRingingAndVibrating()
    mainHandler.removeCallbacks(autoStopRunnable)
    mainHandler.postDelayed(autoStopRunnable, AUTO_STOP_MS)
    return START_NOT_STICKY
  }

  private fun startRingingAndVibrating() {
    val ringUri: Uri? = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
      ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    if (ringUri != null) {
      try {
        mediaPlayer = MediaPlayer().apply {
          setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
              .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
              .build(),
          )
          setDataSource(this@CallRingService, ringUri)
          isLooping = true
          prepare()
          start()
        }
      } catch (e: Exception) {
        DebugLogStore.append(this, "call", "ring start failed: ${e.message}")
      }
    }
    val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    vibrator = vib
    if (vib?.hasVibrator() == true) {
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          vib.vibrate(VibrationEffect.createWaveform(VIBRATE_PATTERN, 0))
        } else {
          @Suppress("DEPRECATION")
          vib.vibrate(VIBRATE_PATTERN, 0)
        }
      } catch (e: Exception) {
        DebugLogStore.append(this, "call", "vibrate failed: ${e.message}")
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    mainHandler.removeCallbacks(autoStopRunnable)
    try { mediaPlayer?.stop() } catch (_: Exception) {}
    try { mediaPlayer?.release() } catch (_: Exception) {}
    mediaPlayer = null
    try { vibrator?.cancel() } catch (_: Exception) {}
    vibrator = null
  }

  private fun buildOngoingNotification(title: String, body: String): android.app.Notification {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
      val ch = NotificationChannel(
        CHANNEL_ID,
        getString(R.string.call_ring_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = getString(R.string.call_ring_channel_desc)
        setShowBadge(false)
        setSound(null, null)
        enableVibration(false)
      }
      nm.createNotificationChannel(ch)
    }
    val stopIntent = Intent(this, CallRingService::class.java).setAction(ACTION_STOP)
    val stopPi = PendingIntent.getService(
      this,
      0,
      stopIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val tapIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val tapPi = PendingIntent.getActivity(
      this,
      1,
      tapIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val displayTitle = title.ifBlank { getString(R.string.call_ring_default_title) }
    val displayBody = body.ifBlank { getString(R.string.call_ring_default_body) }
    return android.app.Notification.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
      .setContentTitle(displayTitle)
      .setContentText(displayBody)
      .setOngoing(true)
      .setContentIntent(tapPi)
      .addAction(
        android.app.Notification.Action.Builder(
          android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
          getString(R.string.call_ring_stop),
          stopPi,
        ).build(),
      )
      .build()
  }

  companion object {
    private const val CHANNEL_ID = "ai_dca_call_ring"
    private const val NOTIF_ID = 0xCA11
    private const val AUTO_STOP_MS = 30_000L
    private const val ACTION_STOP = "tech.freebacktrack.aidca.action.STOP_CALL_RING"
    private const val EXTRA_TITLE = "call_title"
    private const val EXTRA_BODY = "call_body"
    private val VIBRATE_PATTERN = longArrayOf(0, 800, 600, 800, 600)

    fun start(context: Context, title: String, body: String) {
      val intent = Intent(context, CallRingService::class.java).apply {
        putExtra(EXTRA_TITLE, title)
        putExtra(EXTRA_BODY, body)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      try { context.stopService(Intent(context, CallRingService::class.java)) } catch (_: Throwable) {}
    }
  }
}

package tech.freebacktrack.aidca

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RealtimeChannelService —— 实时 WebSocket 长连接前台服务（骨架）。
 *
 * 设计参考：ddocs/architecture/realtime-channel.md。
 * 与 FCM 并行运行、同一台设备两路同推；BarkPayloadHandler 以 messageId 去重。
 *
 * 当前阶段这个 Service 不会被 MainActivity / Application 启动，
 * 仅作为骨架进入仓库，其起动/关闭控制在后续一个 PR 中递交。
 *
 * 外部接口：
 *  - [start] / [stop]：从 UI/应用层控制服务起停；
 *  - [BIND_NOTIFICATION_CHANNEL_ID]：前台通知使用的低优先级 channel。
 *
 * 内部骨架：
 *  - OkHttp WebSocketListener；
 *  - 指数退避重连（2s → 60s 封顶）；
 *  - 30s 周期客户端 ping；
 *  - NetworkCallback 召回连接。
 */
class RealtimeChannelService : Service() {

  private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
  private val httpClient by lazy {
    OkHttpClient.Builder()
      .pingInterval(20, TimeUnit.SECONDS) // OkHttp 原生 ping。
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.MILLISECONDS) // 0 = no read timeout, 适用于长连接。
      .retryOnConnectionFailure(true)
      .build()
  }
  private val started = AtomicBoolean(false)
  private var webSocket: WebSocket? = null
  private var reconnectAttempt = 0
  private val reconnectRunnable = Runnable { connect() }
  private var deviceInstallationId: String = ""
  private var token: String = ""

  private val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      Log.i(TAG, "network available, kicking reconnect")
      mainHandler.removeCallbacks(reconnectRunnable)
      mainHandler.post(reconnectRunnable)
    }
  }
  private var networkCallbackRegistered = false

  override fun onCreate() {
    super.onCreate()
    DebugLogStore.append(applicationContext, "ws", "service onCreate")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (started.compareAndSet(false, true)) {
      deviceInstallationId = intent?.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
      token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
      ensureChannel()
      val notification = buildForegroundNotification()
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(
          NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
      registerNetworkCallback()
      mainHandler.post(reconnectRunnable)
    } else {
      // 重复下发 startService 不重启 WebSocket，仅刷新 token。
      intent?.getStringExtra(EXTRA_TOKEN)?.takeIf { it.isNotBlank() }?.let { token = it }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    DebugLogStore.append(applicationContext, "ws", "service onDestroy")
    mainHandler.removeCallbacks(reconnectRunnable)
    try {
      webSocket?.close(1000, "service stopped")
    } catch (_: Exception) { /* 忽略 */ }
    webSocket = null
    started.set(false)
    if (networkCallbackRegistered) {
      try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(networkCallback)
      } catch (_: Exception) { /* 忽略 */ }
      networkCallbackRegistered = false
    }
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun connect() {
    if (deviceInstallationId.isBlank()) {
      Log.w(TAG, "connect skipped: empty deviceInstallationId")
      return
    }
    val url = buildWsUrl(deviceInstallationId)
    val builder = Request.Builder().url(url)
    if (token.isNotBlank()) {
      // 服务端约定用 "jijin-token-<token>" 作为 Sec-WebSocket-Protocol，避免 query 泄密。
      builder.addHeader("Sec-WebSocket-Protocol", "jijin-token-$token")
    }
    val request = builder.build()
    webSocket = httpClient.newWebSocket(request, listener)
    DebugLogStore.append(applicationContext, "ws", "connect attempt=$reconnectAttempt url=$url")
  }

  private val listener = object : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
      reconnectAttempt = 0
      DebugLogStore.append(applicationContext, "ws", "open response=${response.code}")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      handleFrame(text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
      handleFrame(bytes.utf8())
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      DebugLogStore.append(
        applicationContext,
        "ws",
        "failure response=${response?.code ?: -1} err=${t.message ?: "-"}",
      )
      scheduleReconnect()
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      DebugLogStore.append(applicationContext, "ws", "closing code=$code reason=$reason")
      try { webSocket.close(1000, null) } catch (_: Exception) {}
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      DebugLogStore.append(applicationContext, "ws", "closed code=$code reason=$reason")
      scheduleReconnect()
    }
  }

  private fun handleFrame(raw: String) {
    val frame = try { JSONObject(raw) } catch (_: Exception) { return }
    val type = frame.optString("type")
    when (type) {
      "", "notify" -> {
        val data = frame.optJSONObject("data") ?: frame
        val map = HashMap<String, String>()
        val keys = data.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          map[k] = data.optString(k, "")
        }
        val messageId = data.optString("messageId")
          .ifBlank { data.optString("eventId") }
          .ifBlank { System.currentTimeMillis().toString() }
        BarkPayloadHandler.handle(
          context = applicationContext,
          rawData = map,
          source = "ws",
          messageId = messageId,
        )
      }
      "hello" -> {
        DebugLogStore.append(applicationContext, "ws", "hello connectionId=${frame.optString("connectionId")}")
      }
      "ping" -> {
        try { webSocket?.send("""{"type":"pong","ts":${System.currentTimeMillis()}}""") } catch (_: Exception) {}
      }
      "pong" -> { /* 服务端响应上一次 ping，记在 OkHttp 层 */ }
      else -> {
        DebugLogStore.append(applicationContext, "ws", "unknown frame type=$type")
      }
    }
  }

  private fun scheduleReconnect() {
    val delaySec = MIN_BACKOFF_SECONDS shl reconnectAttempt.coerceAtMost(MAX_BACKOFF_SHIFT)
    val capped = delaySec.coerceAtMost(MAX_BACKOFF_SECONDS)
    reconnectAttempt++
    DebugLogStore.append(
      applicationContext,
      "ws",
      "schedule reconnect in ${capped}s (attempt=$reconnectAttempt)",
    )
    mainHandler.removeCallbacks(reconnectRunnable)
    mainHandler.postDelayed(reconnectRunnable, capped * 1000L)
  }

  private fun registerNetworkCallback() {
    if (networkCallbackRegistered) return
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val req = NetworkRequest.Builder()
      .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .build()
    try {
      cm.registerNetworkCallback(req, networkCallback)
      networkCallbackRegistered = true
    } catch (_: Exception) {
      // SecurityException 或 manifest 未声明时忽略，骨架阶段不阻塞。
    }
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(BIND_NOTIFICATION_CHANNEL_ID) != null) return
    val channel = NotificationChannel(
      BIND_NOTIFICATION_CHANNEL_ID,
      "实时推送连接",
      NotificationManager.IMPORTANCE_MIN,
    ).apply {
      description = "实时 WebSocket 连接运行中的状态通知。不会响铃、不会震动。"
      setShowBadge(false)
    }
    nm.createNotificationChannel(channel)
  }

  private fun buildForegroundNotification(): Notification {
    val launchIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pi = PendingIntent.getActivity(
      this,
      0,
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return Notification.Builder(this, BIND_NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
      .setContentTitle("推送连接保持中")
      .setOngoing(true)
      .setContentIntent(pi)
      .build()
  }

  private fun buildWsUrl(deviceId: String): String {
    // BuildConfig.NOTIFY_BASE_URL = https://tools.freebacktrack.tech/api/notify
    val base = BuildConfig.NOTIFY_BASE_URL
      .replaceFirst("http://", "ws://")
      .replaceFirst("https://", "wss://")
    return "$base/ws/$deviceId"
  }

  companion object {
    private const val TAG = "RealtimeChannel"
    const val BIND_NOTIFICATION_CHANNEL_ID = "realtime_bind"
    private const val NOTIFICATION_ID = 0xA1DC
    private const val EXTRA_DEVICE_ID = "extra_device_id"
    private const val EXTRA_TOKEN = "extra_token"
    private const val MIN_BACKOFF_SECONDS = 2L
    private const val MAX_BACKOFF_SECONDS = 60L
    private const val MAX_BACKOFF_SHIFT = 5 // 2 << 5 = 64 -> capped to 60

    /**
     * 从应用层启动服务。骨架阶段调用点还未接入，仅供后续使用。
     */
    fun start(context: Context, deviceInstallationId: String, token: String) {
      val intent = Intent(context, RealtimeChannelService::class.java).apply {
        putExtra(EXTRA_DEVICE_ID, deviceInstallationId)
        putExtra(EXTRA_TOKEN, token)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, RealtimeChannelService::class.java))
    }
  }
}

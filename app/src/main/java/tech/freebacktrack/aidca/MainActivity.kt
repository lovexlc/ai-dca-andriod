package tech.freebacktrack.aidca

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
  private lateinit var titleTextView: TextView
  private lateinit var statusBadgeTextView: TextView
  private lateinit var statusTitleTextView: TextView
  private lateinit var statusDetailTextView: TextView
  private lateinit var statusUpdatedAtTextView: TextView
  private lateinit var pairingCardView: LinearLayout
  private lateinit var pairingStatusTextView: TextView
  private lateinit var pairingCodeTextView: TextView
  private lateinit var pairingDetailTextView: TextView
  private lateinit var pairedClientContainerView: LinearLayout
  private lateinit var pairedClientTextView: TextView
  private lateinit var deviceNameTextView: TextView
  private lateinit var projectIdTextView: TextView
  private lateinit var packageNameTextView: TextView
  private lateinit var senderIdTextView: TextView
  private lateinit var deviceInstallationIdTextView: TextView
  private lateinit var copyDeviceInstallationIdButton: Button
  private lateinit var tokenTextView: TextView
  private lateinit var deliveryStatusTextView: TextView
  private lateinit var messageHistoryContainer: LinearLayout
  private lateinit var messageHistoryScrollView: ScrollView
  private lateinit var debugCardView: LinearLayout
  private lateinit var debugLogTextView: TextView
  private lateinit var copyDebugLogsButton: Button
  private lateinit var clearDebugLogsButton: Button
  private var titleTapCount = 0
  private var lastTitleTapAtMs = 0L

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    bindViews()
    setupMessageHistoryScroll()
    setupDebugToggle()
    setupDeviceIdentityActions()
    setupDebugActions()
    DebugLogStore.append(applicationContext, "ui", "MainActivity onCreate")

    val identity = RegistrationRepository.currentIdentity(this)
    renderSnapshot(RegistrationStateStore.read(this, identity))
    renderDeliveryReceipt()
    renderMessageHistory()
    renderDebugPanel()
    requestNotificationPermissionIfNeeded()

    startRegistration("app-launch")
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)

    if (requestCode == REQUEST_NOTIFICATIONS && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
      Toast.makeText(this, R.string.permission_denied_message, Toast.LENGTH_LONG).show()
      DebugLogStore.append(applicationContext, "permission", "POST_NOTIFICATIONS denied")
    } else if (requestCode == REQUEST_NOTIFICATIONS) {
      DebugLogStore.append(applicationContext, "permission", "POST_NOTIFICATIONS granted")
    }

    renderDebugPanel()
  }

  override fun onResume() {
    super.onResume()
    renderDeliveryReceipt()
    renderMessageHistory()
    renderDebugPanel()
  }

  private fun bindViews() {
    titleTextView = findViewById(R.id.titleTextView)
    statusBadgeTextView = findViewById(R.id.statusBadgeTextView)
    statusTitleTextView = findViewById(R.id.statusTitleTextView)
    statusDetailTextView = findViewById(R.id.statusDetailTextView)
    statusUpdatedAtTextView = findViewById(R.id.statusUpdatedAtTextView)
    pairingCardView = findViewById(R.id.pairingCardView)
    pairingStatusTextView = findViewById(R.id.pairingStatusTextView)
    pairingCodeTextView = findViewById(R.id.pairingCodeTextView)
    pairingDetailTextView = findViewById(R.id.pairingDetailTextView)
    pairedClientContainerView = findViewById(R.id.pairedClientContainerView)
    pairedClientTextView = findViewById(R.id.pairedClientTextView)
    deviceNameTextView = findViewById(R.id.deviceNameTextView)
    projectIdTextView = findViewById(R.id.projectIdTextView)
    packageNameTextView = findViewById(R.id.packageNameTextView)
    senderIdTextView = findViewById(R.id.senderIdTextView)
    deviceInstallationIdTextView = findViewById(R.id.deviceInstallationIdTextView)
    copyDeviceInstallationIdButton = findViewById(R.id.copyDeviceInstallationIdButton)
    tokenTextView = findViewById(R.id.tokenTextView)
    deliveryStatusTextView = findViewById(R.id.deliveryStatusTextView)
    messageHistoryContainer = findViewById(R.id.messageHistoryContainer)
    messageHistoryScrollView = findViewById(R.id.messageHistoryScrollView)
    debugCardView = findViewById(R.id.debugCardView)
    debugLogTextView = findViewById(R.id.debugLogTextView)
    copyDebugLogsButton = findViewById(R.id.copyDebugLogsButton)
    clearDebugLogsButton = findViewById(R.id.clearDebugLogsButton)
  }

  // 内层消息列表 ScrollView 与外层页面 ScrollView 嵌套，拉动时需要阻止父级拦截，否则手势会被外层截走。
  private fun setupMessageHistoryScroll() {
    messageHistoryScrollView.setOnTouchListener { view, _ ->
      view.parent?.requestDisallowInterceptTouchEvent(true)
      false
    }
  }

  private fun setupDeviceIdentityActions() {
    copyDeviceInstallationIdButton.setOnClickListener {
      val deviceInstallationId = DeviceInstallationStore.getOrCreate(applicationContext)
      val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
      clipboardManager.setPrimaryClip(ClipData.newPlainText("AI DCA Device Installation ID", deviceInstallationId))
      Toast.makeText(this, R.string.device_installation_id_copied, Toast.LENGTH_SHORT).show()
    }
  }

  private fun setupDebugToggle() {
    titleTextView.setOnClickListener {
      val nowMs = System.currentTimeMillis()

      if (nowMs - lastTitleTapAtMs > DEBUG_TAP_WINDOW_MS) {
        titleTapCount = 0
      }

      lastTitleTapAtMs = nowMs
      titleTapCount += 1
      val remaining = DEBUG_TAP_TARGET - titleTapCount

      if (remaining in 1..2) {
        Toast.makeText(this, getString(R.string.debug_mode_hint, remaining), Toast.LENGTH_SHORT).show()
      }

      if (remaining <= 0) {
        val enabled = DebugLogStore.toggleEnabled(applicationContext)
        titleTapCount = 0
        DebugLogStore.append(
          applicationContext,
          "ui",
          if (enabled) "Debug mode enabled from title taps" else "Debug mode disabled from title taps"
        )
        renderDebugPanel()
        Toast.makeText(
          this,
          if (enabled) R.string.debug_mode_enabled else R.string.debug_mode_disabled,
          Toast.LENGTH_SHORT
        ).show()
      }
    }
  }

  private fun setupDebugActions() {
    copyDebugLogsButton.setOnClickListener {
      val logText = DebugLogStore.readJoined(applicationContext).ifBlank {
        getString(R.string.debug_log_empty)
      }
      val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
      clipboardManager.setPrimaryClip(ClipData.newPlainText("AI DCA Debug Logs", logText))
      Toast.makeText(this, R.string.debug_logs_copied, Toast.LENGTH_SHORT).show()
    }

    clearDebugLogsButton.setOnClickListener {
      DebugLogStore.clear(applicationContext)
      renderDebugPanel()
      Toast.makeText(this, R.string.debug_logs_cleared, Toast.LENGTH_SHORT).show()
    }
  }

  private fun startRegistration(trigger: String) {
    DebugLogStore.append(applicationContext, "ui", "Starting registration, trigger=$trigger")
    val identity = RegistrationRepository.currentIdentity(this)
    val previousSnapshot = RegistrationStateStore.read(this, identity)
    renderSnapshot(
      previousSnapshot.copy(
        state = "idle",
        title = "正在自动注册",
        detail = "",
        updatedAt = "",
        deviceInstallationId = identity.deviceInstallationId,
        projectId = identity.projectId,
        packageName = identity.packageName,
        deviceName = identity.deviceName,
        senderId = identity.senderId,
        appId = identity.appId,
        trigger = trigger
      )
    )

    RegistrationRepository.refresh(this, trigger) { snapshot ->
      renderSnapshot(snapshot)
      renderDeliveryReceipt()
      renderMessageHistory()
      DebugLogStore.append(applicationContext, "ui", "Registration finished with state=${snapshot.state}")
      renderDebugPanel()
    }
  }

  private fun renderSnapshot(snapshot: RegistrationSnapshot) {
    val badge = when (snapshot.state) {
      "validated", "connected" -> BadgeStyle("已校验", R.drawable.status_badge_connected, R.color.emerald_600)
      "registered" -> BadgeStyle("已注册", R.drawable.status_badge_registered, R.color.amber_700)
      "error" -> BadgeStyle("失败", R.drawable.status_badge_error, R.color.red_600)
      else -> BadgeStyle(getString(R.string.status_idle), R.drawable.status_badge_idle, R.color.slate_700)
    }

    statusBadgeTextView.text = badge.label
    statusBadgeTextView.setBackgroundResource(badge.backgroundRes)
    statusBadgeTextView.setTextColor(getColor(badge.textColorRes))
    statusTitleTextView.text = snapshot.title
    statusDetailTextView.text = snapshot.detail
    statusDetailTextView.visibility = if (snapshot.detail.isBlank()) View.GONE else View.VISIBLE
    statusUpdatedAtTextView.text = if (snapshot.updatedAt.isBlank()) "尚未完成自动注册" else "最近更新: ${snapshot.updatedAt}"
    pairingCardView.visibility = View.VISIBLE
    pairingStatusTextView.text = when (snapshot.pairingStatus) {
      "issued" -> "前端配对码已生成"
      "error" -> "前端配对码生成失败"
      "paired" -> "当前设备已绑定浏览器"
      "unavailable" -> "前端配对码暂不可用"
      else -> "等待生成前端配对码"
    }
    pairingCodeTextView.visibility = if (snapshot.pairingStatus == "paired") View.GONE else View.VISIBLE
    pairingCodeTextView.text = if (snapshot.pairingCode.isBlank()) "--------" else snapshot.pairingCode
    pairingDetailTextView.text = snapshot.pairingDetail
    pairingDetailTextView.visibility = if (snapshot.pairingDetail.isBlank()) View.GONE else View.VISIBLE
    pairedClientTextView.text = snapshot.pairedClientSummary
    pairedClientContainerView.visibility = if (snapshot.pairedClientSummary.isBlank()) View.GONE else View.VISIBLE
    deviceNameTextView.text = snapshot.deviceName.ifBlank { "Android Device" }
    projectIdTextView.text = "Firebase Project: ${snapshot.projectId.ifBlank { "未读取到" }}"
    packageNameTextView.text = "包名: ${snapshot.packageName.ifBlank { "未读取到" }}"
    senderIdTextView.text = "Sender ID: ${snapshot.senderId.ifBlank { "未读取到" }}"
    deviceInstallationIdTextView.text = snapshot.deviceInstallationId.ifBlank { DeviceInstallationStore.getOrCreate(applicationContext) }
    tokenTextView.text = if (snapshot.tokenMasked.isBlank()) "FCM token 尚未可用" else "Token: ${snapshot.tokenMasked}"
  }

  private fun renderDeliveryReceipt() {
    val receipt = DeliveryReceiptStore.read(this)

    deliveryStatusTextView.text = if (receipt == null) {
      getString(R.string.delivery_status_empty)
    } else {
      val prefix = if (receipt.status == "display-error") {
        getString(R.string.delivery_status_error_prefix)
      } else {
        getString(R.string.delivery_status_received_prefix)
      }

      buildString {
        append(prefix)
        append(receipt.receivedAt)

        if (receipt.title.isNotBlank()) {
          append('\n')
          append("标题: ")
          append(receipt.title)
        }

        if (receipt.body.isNotBlank()) {
          append('\n')
          append("内容: ")
          append(receipt.body)
        }

        if (receipt.messageId.isNotBlank()) {
          append('\n')
          append("Message ID: ")
          append(receipt.messageId)
        }
      }
    }
  }

  private fun renderMessageHistory() {
    val records = prioritizeMessages(NotificationMessageStore.readAll(this))
    messageHistoryContainer.removeAllViews()

    if (records.isEmpty()) {
      val emptyView = TextView(this).apply {
        text = getString(R.string.message_history_empty)
        setTextColor(getColor(R.color.slate_500))
        textSize = 13f
        setLineSpacing(0f, 1.2f)
      }
      messageHistoryContainer.addView(emptyView)
      return
    }

    for (record in records.take(MAX_MESSAGE_RECORDS)) {
      val itemView = LayoutInflater.from(this).inflate(R.layout.item_message_record, messageHistoryContainer, false)
      val titleView = itemView.findViewById<TextView>(R.id.messageTitleTextView)
      val metaView = itemView.findViewById<TextView>(R.id.messageMetaTextView)
      val bodyView = itemView.findViewById<TextView>(R.id.messageBodyTextView)
      val detailView = itemView.findViewById<TextView>(R.id.messageDetailTextView)
      val openDetailButton = itemView.findViewById<Button>(R.id.openMessageDetailButton)

      titleView.text = record.title.ifBlank { getString(R.string.incoming_message_fallback_title) }
      metaView.text = buildString {
        append(record.receivedAt.ifBlank { "--" })
        if (record.strategyName.isNotBlank()) {
          append(" · ")
          append(record.strategyName)
        }
        if (record.symbol.isNotBlank()) {
          append(" · ")
          append(record.symbol)
        }
      }
      bodyView.text = record.body.ifBlank { "这条推送没有正文内容。" }
      detailView.text = buildString {
        if (record.triggerCondition.isNotBlank()) {
          append("触发条件: ")
          append(record.triggerCondition)
        }
        if (record.purchaseAmount.isNotBlank()) {
          if (isNotEmpty()) append('\n')
          append("购买金额: ")
          append(record.purchaseAmount)
        }
        if (record.messageId.isNotBlank()) {
          if (isNotEmpty()) append('\n')
          append("FCM Message ID: ")
          append(record.messageId)
        }
        when (record.notificationStatus) {
          "display-error" -> {
            if (isNotEmpty()) append('\n')
            append("通知展示失败: ")
            append(record.notificationError.ifBlank { "未知错误" })
          }
          "displayed" -> {
            if (isNotEmpty()) append('\n')
            append("通知栏已展示。")
          }
        }
        if (record.detailUrl.isNotBlank()) {
          if (isNotEmpty()) append('\n')
          append("更详细的策略说明请到网站查看。")
        }
      }

      if (record.detailUrl.isBlank()) {
        openDetailButton.visibility = View.GONE
      } else {
        openDetailButton.visibility = View.VISIBLE
        openDetailButton.setOnClickListener {
          startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(record.detailUrl)))
        }
      }

      messageHistoryContainer.addView(itemView)
    }
  }

  private fun prioritizeMessages(records: List<NotificationMessageRecord>): List<NotificationMessageRecord> {
    val targetEventId = intent.getStringExtra(EXTRA_EVENT_ID).orEmpty()
    val targetMessageId = intent.getStringExtra(EXTRA_MESSAGE_ID).orEmpty()

    if (targetEventId.isBlank() && targetMessageId.isBlank()) {
      return records
    }

    val highlighted = records.filter { record ->
      (targetEventId.isNotBlank() && record.eventId == targetEventId)
        || (targetMessageId.isNotBlank() && record.messageId == targetMessageId)
    }
    val remaining = records.filterNot { record ->
      highlighted.any { highlightedRecord -> highlightedRecord.localId == record.localId }
    }

    return highlighted + remaining
  }

  private fun renderDebugPanel() {
    val enabled = DebugLogStore.isEnabled(this)
    debugCardView.visibility = if (enabled) View.VISIBLE else View.GONE

    if (enabled) {
      debugLogTextView.text = DebugLogStore.readJoined(this).ifBlank {
        getString(R.string.debug_log_empty)
      }
    }
  }

  private fun requestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT < 33) {
      DebugLogStore.append(applicationContext, "permission", "POST_NOTIFICATIONS not required on this Android version")
      return
    }

    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
      DebugLogStore.append(applicationContext, "permission", "POST_NOTIFICATIONS already granted")
      return
    }

    DebugLogStore.append(applicationContext, "permission", "Requesting POST_NOTIFICATIONS")
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
  }

  private data class BadgeStyle(
    val label: String,
    val backgroundRes: Int,
    val textColorRes: Int
  )

  companion object {
    const val EXTRA_EVENT_ID = "extra_event_id"
    const val EXTRA_MESSAGE_ID = "extra_message_id"
    private const val REQUEST_NOTIFICATIONS = 1001
    private const val DEBUG_TAP_TARGET = 7
    private const val DEBUG_TAP_WINDOW_MS = 1800L
    private const val MAX_MESSAGE_RECORDS = 12
  }
}

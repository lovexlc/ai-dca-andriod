package tech.freebacktrack.aidca

import android.Manifest
import android.app.AlertDialog
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

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
  private lateinit var pairedClientItemsContainer: LinearLayout
  private lateinit var deviceNameTextView: TextView
  private lateinit var projectIdTextView: TextView
  private lateinit var packageNameTextView: TextView
  private lateinit var senderIdTextView: TextView
  private lateinit var deviceInstallationIdTextView: TextView
  private lateinit var copyDeviceInstallationIdButton: Button
  private lateinit var tokenTextView: TextView
  private lateinit var copyTokenButton: Button
  private lateinit var updateStatusTextView: TextView
  private lateinit var updateRowView: LinearLayout
  private lateinit var navServerButton: LinearLayout
  private lateinit var navHistoryButton: LinearLayout
  private lateinit var navSettingsButton: LinearLayout
  private lateinit var navServerUpdateDot: View
  private lateinit var serverSection: LinearLayout
  private lateinit var historySection: LinearLayout
  private lateinit var settingsSection: LinearLayout
  private lateinit var messageHistoryContainer: LinearLayout
  private lateinit var debugCardView: LinearLayout
  private lateinit var debugLogTextView: TextView
  private lateinit var copyDebugLogsButton: Button
  private lateinit var clearDebugLogsButton: Button
  private lateinit var clearMessageHistoryButton: ImageButton
  private lateinit var deviceInfoToggleHeader: LinearLayout
  private lateinit var deviceInfoToggleChevron: TextView
  private lateinit var deviceAdvancedContainer: LinearLayout
  // Bark 测试推送预览卡
  private lateinit var barkPreviewContainer: LinearLayout
  private lateinit var barkPreviewItemsContainer: LinearLayout
  // 历史 tab action bar
  private lateinit var historyFilterButton: ImageButton
  private lateinit var historySearchButton: ImageButton
  // 设置 tab 扩展
  private lateinit var settingsExtrasContainer: LinearLayout
  private lateinit var defaultArchiveRow: LinearLayout
  private lateinit var defaultArchiveSwitch: Switch
  private lateinit var encryptionKeyRow: LinearLayout
  private lateinit var encryptionKeyStatus: TextView
  private lateinit var ringtoneRow: LinearLayout
  private lateinit var ringtoneCurrentValue: TextView
  private var titleTapCount = 0
  private var lastTitleTapAtMs = 0L
  private val executor = Executors.newSingleThreadExecutor()
  private val mainHandler = Handler(Looper.getMainLooper())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    bindViews()
    applyWindowInsets()
    setupDebugToggle()
    setupDeviceIdentityActions()
    setupTokenActions()
    setupUpdateActions()
    setupBottomNavigation()
    checkForUpdatesSilently()
    setupDebugActions()
    setupDeviceAdvancedToggle()
    setupHistoryClearAction()
    setupHistoryActionBar()
    setupSettingsExtras()
    DebugLogStore.append(applicationContext, "ui", "MainActivity onCreate")

    val identity = RegistrationRepository.currentIdentity(this)
    renderSnapshot(RegistrationStateStore.read(this, identity))
    renderMessageHistory()
    renderDebugPanel()
    requestNotificationPermissionIfNeeded()
    requestDndAccessIfNeeded()

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
    pairedClientItemsContainer = findViewById(R.id.pairedClientItemsContainer)
    deviceNameTextView = findViewById(R.id.deviceNameTextView)
    projectIdTextView = findViewById(R.id.projectIdTextView)
    packageNameTextView = findViewById(R.id.packageNameTextView)
    senderIdTextView = findViewById(R.id.senderIdTextView)
    deviceInstallationIdTextView = findViewById(R.id.deviceInstallationIdTextView)
    copyDeviceInstallationIdButton = findViewById(R.id.copyDeviceInstallationIdButton)
    tokenTextView = findViewById(R.id.tokenTextView)
    copyTokenButton = findViewById(R.id.copyTokenButton)
    updateStatusTextView = findViewById(R.id.updateStatusTextView)
    updateRowView = findViewById(R.id.updateRowView)
    navServerButton = findViewById(R.id.navServerButton)
    navHistoryButton = findViewById(R.id.navHistoryButton)
    navSettingsButton = findViewById(R.id.navSettingsButton)
    navServerUpdateDot = findViewById(R.id.navServerUpdateDot)
    serverSection = findViewById(R.id.serverSection)
    historySection = findViewById(R.id.historySection)
    settingsSection = findViewById(R.id.settingsSection)
    messageHistoryContainer = findViewById(R.id.messageHistoryContainer)
    debugCardView = findViewById(R.id.debugCardView)
    debugLogTextView = findViewById(R.id.debugLogTextView)
    copyDebugLogsButton = findViewById(R.id.copyDebugLogsButton)
    clearDebugLogsButton = findViewById(R.id.clearDebugLogsButton)
    clearMessageHistoryButton = findViewById(R.id.clearMessageHistoryButton)
    deviceInfoToggleHeader = findViewById(R.id.deviceInfoToggleHeader)
    deviceInfoToggleChevron = findViewById(R.id.deviceInfoToggleChevron)
    deviceAdvancedContainer = findViewById(R.id.deviceAdvancedContainer)
    barkPreviewContainer = findViewById(R.id.barkPreviewContainer)
    barkPreviewItemsContainer = findViewById(R.id.barkPreviewItemsContainer)
    historyFilterButton = findViewById(R.id.historyFilterButton)
    historySearchButton = findViewById(R.id.historySearchButton)
    settingsExtrasContainer = findViewById(R.id.settingsExtrasContainer)
    defaultArchiveRow = findViewById(R.id.defaultArchiveRow)
    defaultArchiveSwitch = findViewById(R.id.defaultArchiveSwitch)
    encryptionKeyRow = findViewById(R.id.encryptionKeyRow)
    encryptionKeyStatus = findViewById(R.id.encryptionKeyStatus)
    ringtoneRow = findViewById(R.id.ringtoneRow)
    ringtoneCurrentValue = findViewById(R.id.ringtoneCurrentValue)
  }

  // targetSdk 35 强制 edge-to-edge 时，状态栏会覆盖内容；用 WindowInsets 显式吸收 systemBars
  // 顶部和底部高度，作为根 ScrollView 的 padding，避免顶部贴状态栏、底部被导航条遮住。
  private fun applyWindowInsets() {
    val rootScrollView = findViewById<ScrollView>(R.id.rootScrollView)
    rootScrollView.setOnApplyWindowInsetsListener { view, insets ->
      val top: Int
      val bottom: Int
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        top = bars.top
        bottom = bars.bottom
      } else {
        @Suppress("DEPRECATION")
        top = insets.systemWindowInsetTop
        @Suppress("DEPRECATION")
        bottom = insets.systemWindowInsetBottom
      }
      view.setPadding(view.paddingLeft, top, view.paddingRight, bottom)
      insets
    }
    rootScrollView.requestApplyInsets()
  }

  private fun setupDeviceIdentityActions() {
    copyDeviceInstallationIdButton.setOnClickListener {
      val deviceInstallationId = DeviceInstallationStore.getOrCreate(applicationContext)
      val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
      clipboardManager.setPrimaryClip(ClipData.newPlainText("基金通知 设备 ID", deviceInstallationId))
      Toast.makeText(this, R.string.device_installation_id_copied, Toast.LENGTH_SHORT).show()
    }
  }

  private fun setupTokenActions() {
    copyTokenButton.setOnClickListener {
      val tokenText = tokenTextView.text?.toString().orEmpty()
      val token = tokenText.removePrefix("Token:").trim()
      if (token.isBlank() || token.contains("尚未")) {
        Toast.makeText(this, "Token 尚不可用", Toast.LENGTH_SHORT).show()
        return@setOnClickListener
      }
      val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
      clipboardManager.setPrimaryClip(ClipData.newPlainText("基金通知 FCM Token", token))
      Toast.makeText(this, R.string.token_copied, Toast.LENGTH_SHORT).show()
    }
  }

  private fun setupUpdateActions() {
    // 参考 iOS 设置 / Telegram / Linear 的常见设计：进入设置页默认只显示当前版本，
    // 点整行才去请求 GitHub 检查，避免打开页面就走一次外网请求。
    updateStatusTextView.text = "v${BuildConfig.VERSION_NAME}"
    updateRowView.setOnClickListener {
      checkForUpdates()
    }
  }

  private fun checkForUpdates() {
    updateStatusTextView.text = "正在检查…"
    updateRowView.isClickable = false
    executor.execute {
      val releasesUrl = "https://github.com/lovexlc/ai-dca-andriod/releases/latest"
      val current = normalizeVersion(BuildConfig.VERSION_NAME)
      val result = fetchLatestVersion()
      val resultText: String
      var openUrl: String? = null
      var hasUpdate = false
      if (result == null) {
        resultText = "检查失败·点此重试"
        openUrl = releasesUrl
      } else {
        val (_, latest) = result
        if (latest.isBlank() || latest == current) {
          resultText = "v$current · 已是最新"
        } else {
          resultText = "发现新版本 v$latest"
          openUrl = releasesUrl
          hasUpdate = true
        }
      }

      mainHandler.post {
        updateStatusTextView.text = resultText
        updateRowView.isClickable = true
        navServerUpdateDot.visibility = if (hasUpdate) View.VISIBLE else View.GONE
        if (openUrl != null) {
          updateRowView.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(openUrl)))
          }
        } else {
          updateRowView.setOnClickListener {
            checkForUpdates()
          }
        }
      }
    }
  }

  // 启动时静默检查一次更新，仅用于点亮设备 Tab 的小红点，不修改设置页的版本文案。
  private fun checkForUpdatesSilently() {
    executor.execute {
      val current = normalizeVersion(BuildConfig.VERSION_NAME)
      val result = fetchLatestVersion() ?: return@execute
      val (_, latest) = result
      val hasUpdate = latest.isNotBlank() && latest != current
      mainHandler.post {
        navServerUpdateDot.visibility = if (hasUpdate) View.VISIBLE else View.GONE
      }
    }
  }

  // 把 release tag（如 "app-v1.0.38" / "v1.0.38" / "1.0.38"）归一化为纯数字版本 "1.0.38"。
  private fun normalizeVersion(raw: String): String {
    var s = raw.trim()
    if (s.startsWith("app-")) s = s.removePrefix("app-")
    if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
    return s
  }

  // 拉取 GitHub 最新 release，返回 (原始展示名, 归一化版本号)；失败返回 null。
  private fun fetchLatestVersion(): Pair<String, String>? {
    return try {
      val apiUrl = "https://api.github.com/repos/lovexlc/ai-dca-andriod/releases/latest"
      val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8000
        readTimeout = 8000
        setRequestProperty("accept", "application/vnd.github+json")
        setRequestProperty("user-agent", "ai-dca-android")
      }
      val body = conn.inputStream.bufferedReader().use { it.readText() }
      val json = JSONObject(body)
      val tag = json.optString("tag_name").trim()
      val name = json.optString("name").trim()
      val display = tag.ifBlank { name }
      if (display.isBlank()) null else Pair(display, normalizeVersion(display))
    } catch (_: Exception) {
      null
    }
  }

  private fun setupBottomNavigation() {
    fun selectServer() {
      serverSection.visibility = View.VISIBLE
      historySection.visibility = View.GONE
      settingsSection.visibility = View.GONE
      navServerButton.isSelected = true
      navHistoryButton.isSelected = false
      navSettingsButton.isSelected = false
    }

    fun selectHistory() {
      serverSection.visibility = View.GONE
      historySection.visibility = View.VISIBLE
      settingsSection.visibility = View.GONE
      navServerButton.isSelected = false
      navHistoryButton.isSelected = true
      navSettingsButton.isSelected = false
    }

    fun selectSettings() {
      serverSection.visibility = View.GONE
      historySection.visibility = View.GONE
      settingsSection.visibility = View.VISIBLE
      navServerButton.isSelected = false
      navHistoryButton.isSelected = false
      navSettingsButton.isSelected = true
    }

    navServerButton.setOnClickListener {
      selectServer()
      rootScrollView().post { rootScrollView().fullScroll(View.FOCUS_UP) }
    }

    navHistoryButton.setOnClickListener {
      selectHistory()
      rootScrollView().post { rootScrollView().fullScroll(View.FOCUS_UP) }
    }

    navSettingsButton.setOnClickListener {
      selectSettings()
      rootScrollView().post { rootScrollView().fullScroll(View.FOCUS_UP) }
    }

    // default
    selectServer()
  }


  private fun rootScrollView(): ScrollView {
    return findViewById(R.id.rootScrollView)
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
      clipboardManager.setPrimaryClip(ClipData.newPlainText("基金通知 调试日志", logText))
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
      renderMessageHistory()
      DebugLogStore.append(applicationContext, "ui", "Registration finished with state=${snapshot.state}")
      renderDebugPanel()
    }
  }

  private fun renderSnapshot(snapshot: RegistrationSnapshot) {
    val badge = when (snapshot.state) {
      "validated", "connected" -> BadgeStyle("已校验", R.drawable.status_badge_connected, R.color.text_on_hero)
      "registered" -> BadgeStyle("已注册", R.drawable.status_badge_registered, R.color.text_on_hero)
      "error" -> BadgeStyle("失败", R.drawable.status_badge_error, R.color.text_on_hero)
      else -> BadgeStyle(getString(R.string.status_idle), R.drawable.status_badge_idle, R.color.text_on_hero_muted)
    }

    statusBadgeTextView.text = badge.label
    statusBadgeTextView.setBackgroundResource(badge.backgroundRes)
    statusBadgeTextView.setTextColor(getColor(badge.textColorRes))
    statusTitleTextView.text = snapshot.title
    statusDetailTextView.text = snapshot.detail
    // 成功状态下徽章 + 标题已经表达了「服务端已连通」，不再显示「FCM http v1 已连通」之类的冗余 detail。
    val hideDetailOnSuccess = snapshot.state == "validated" || snapshot.state == "connected"
    statusDetailTextView.visibility =
      if (snapshot.detail.isBlank() || hideDetailOnSuccess) View.GONE else View.VISIBLE
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
    renderPairedClients(snapshot)
    deviceNameTextView.text = snapshot.deviceName.ifBlank { "Android Device" }
    projectIdTextView.text = "Firebase Project: ${snapshot.projectId.ifBlank { "未读取到" }}"
    packageNameTextView.text = "包名: ${snapshot.packageName.ifBlank { "未读取到" }}"
    senderIdTextView.text = "Sender ID: ${snapshot.senderId.ifBlank { "未读取到" }}"
    deviceInstallationIdTextView.text = snapshot.deviceInstallationId.ifBlank { DeviceInstallationStore.getOrCreate(applicationContext) }
    tokenTextView.text = if (snapshot.tokenMasked.isBlank()) "FCM token 尚未可用" else "Token: ${snapshot.tokenMasked}"
    renderBarkPreviewCards(snapshot.deviceInstallationId.ifBlank { DeviceInstallationStore.getOrCreate(applicationContext) })
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
      val headerRow = itemView.findViewById<LinearLayout>(R.id.messageHeaderRow)
      val expandChevron = itemView.findViewById<TextView>(R.id.messageExpandChevron)
      val detailContainer = itemView.findViewById<LinearLayout>(R.id.messageDetailContainer)
      val deleteButton = itemView.findViewById<ImageButton>(R.id.messageDeleteButton)
      deleteButton.setOnClickListener {
        AlertDialog.Builder(this)
          .setTitle(R.string.message_delete_confirm_title)
          .setNegativeButton(R.string.action_cancel, null)
          .setPositiveButton(R.string.action_confirm) { _, _ ->
            if (NotificationMessageStore.removeByLocalId(this, record.localId)) {
              Toast.makeText(this, R.string.message_deleted, Toast.LENGTH_SHORT).show()
              renderMessageHistory()
            }
          }
          .show()
      }
      detailContainer.visibility = View.GONE
      expandChevron.rotation = 0f
      headerRow.setOnClickListener {
        val expanded = detailContainer.visibility != View.VISIBLE
        detailContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        expandChevron.rotation = if (expanded) 90f else 0f
      }

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
      if (record.bodyMd.isNotBlank()) {
        bodyView.text = MarkdownRenderer.render(record.bodyMd)
        bodyView.movementMethod = android.text.method.LinkMovementMethod.getInstance()
      }
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

  private fun requestDndAccessIfNeeded() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      DebugLogStore.append(applicationContext, "permission", "DND policy access not required on this Android version")
      return
    }

    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    if (notificationManager == null) {
      DebugLogStore.append(applicationContext, "permission", "NotificationManager unavailable; skip DND prompt")
      return
    }

    if (notificationManager.isNotificationPolicyAccessGranted) {
      DebugLogStore.append(applicationContext, "permission", "DND policy access already granted")
      return
    }

    val prefs = getSharedPreferences("ai_dca_notify_state", Context.MODE_PRIVATE)
    val promptedKey = "dnd_bypass_prompted_v1"
    if (prefs.getBoolean(promptedKey, false)) {
      DebugLogStore.append(applicationContext, "permission", "DND prompt already shown previously; not reprompting")
      return
    }

    DebugLogStore.append(applicationContext, "permission", "Prompting user to grant DND policy access")

    AlertDialog.Builder(this)
      .setTitle(R.string.dnd_bypass_title)
      .setMessage(R.string.dnd_bypass_message)
      .setNegativeButton(R.string.dnd_bypass_skip) { _, _ ->
        prefs.edit().putBoolean(promptedKey, true).apply()
        DebugLogStore.append(applicationContext, "permission", "DND prompt skipped by user")
      }
      .setPositiveButton(R.string.dnd_bypass_open_settings) { _, _ ->
        prefs.edit().putBoolean(promptedKey, true).apply()
        openDndPolicyAccessSettings()
      }
      .setCancelable(false)
      .show()
  }

  private fun openDndPolicyAccessSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      Toast.makeText(this, R.string.dnd_bypass_unsupported, Toast.LENGTH_SHORT).show()
      return
    }

    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
      startActivity(intent)
      DebugLogStore.append(applicationContext, "permission", "Launched DND policy access settings")
    } catch (error: Exception) {
      val message = error.message.orEmpty()
      Toast.makeText(this, getString(R.string.dnd_bypass_route_failed, message), Toast.LENGTH_LONG).show()
      DebugLogStore.append(applicationContext, "permission", "Failed to open DND policy access settings: ${'$'}message")
    }
  }

  private fun setupDeviceAdvancedToggle() {
    fun applyState(expanded: Boolean) {
      deviceAdvancedContainer.visibility = if (expanded) View.VISIBLE else View.GONE
      deviceInfoToggleChevron.rotation = if (expanded) 90f else 0f
    }
    applyState(false)
    deviceInfoToggleHeader.setOnClickListener {
      val expanded = deviceAdvancedContainer.visibility != View.VISIBLE
      applyState(expanded)
    }
  }

  private fun setupHistoryClearAction() {
    clearMessageHistoryButton.setOnClickListener {
      AlertDialog.Builder(this)
        .setTitle(R.string.history_clear_confirm_title)
        .setMessage(R.string.history_clear_confirm_message)
        .setNegativeButton(R.string.action_cancel, null)
        .setPositiveButton(R.string.action_confirm) { _, _ ->
          NotificationMessageStore.clearAll(this)
          Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
          renderMessageHistory()
        }
        .show()
    }
  }

  // 历史 tab action bar：筛选 / 清空 / 搜索。
  // 筛选 + 搜索本轮只放出按钮架子，点击提示“功能开发中”；清空复用原 setupHistoryClearAction 逻辑。
  private fun setupHistoryActionBar() {
    val placeholder = View.OnClickListener {
      Toast.makeText(this, R.string.feature_in_progress, Toast.LENGTH_SHORT).show()
    }
    historyFilterButton.setOnClickListener(placeholder)
    historySearchButton.setOnClickListener(placeholder)
  }

  // 设置 tab 扩展：默认保存 / 加密密钥 / 推送铃声。
  private fun setupSettingsExtras() {
    val prefs = getSharedPreferences("ai_dca_notify_state", Context.MODE_PRIVATE)

    // 默认保存 (isArchive)
    val defaultArchive = prefs.getBoolean("default_archive", true)
    defaultArchiveSwitch.isChecked = defaultArchive
    defaultArchiveSwitch.setOnCheckedChangeListener { _, isChecked ->
      prefs.edit().putBoolean("default_archive", isChecked).apply()
    }
    // 点击整行也能切换
    defaultArchiveRow.setOnClickListener {
      defaultArchiveSwitch.toggle()
    }

    // 加密密钥
    fun refreshEncryptionKeyStatus() {
      val key = prefs.getString("encryption_key", "").orEmpty()
      encryptionKeyStatus.text = if (key.isBlank()) {
        getString(R.string.settings_encryption_key_status_unset)
      } else {
        getString(R.string.settings_encryption_key_status_set)
      }
    }
    refreshEncryptionKeyStatus()
    encryptionKeyRow.setOnClickListener {
      val edit = EditText(this).apply {
        setText(prefs.getString("encryption_key", "").orEmpty())
        setSingleLine(true)
      }
      AlertDialog.Builder(this)
        .setTitle(R.string.settings_encryption_key_dialog_title)
        .setMessage(R.string.settings_encryption_key_subtitle)
        .setView(edit)
        .setNegativeButton(R.string.action_cancel, null)
        .setPositiveButton(R.string.action_confirm) { _, _ ->
          val newKey = edit.text?.toString()?.trim().orEmpty()
          prefs.edit().putString("encryption_key", newKey).apply()
          val msgRes = if (newKey.isBlank()) R.string.settings_encryption_key_cleared else R.string.settings_encryption_key_set
          Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
          refreshEncryptionKeyStatus()
        }
        .show()
    }

    // 推送铃声
    fun listRawSounds(): List<String> = try {
      // 避免编译期直接引用 R.raw，res/raw/ 为空时 R.raw 嵌套类不会生成。
      // 运行期反射查找，找不到返回空列表。
      val rawClass = Class.forName("$packageName.R\$raw")
      rawClass.fields.map { it.name }.sorted()
    } catch (_: Throwable) {
      emptyList()
    }
    fun refreshRingtoneLabel() {
      val current = prefs.getString("selected_ringtone", "").orEmpty()
      ringtoneCurrentValue.text = if (current.isBlank()) {
        getString(R.string.settings_ringtone_subtitle_default)
      } else {
        current
      }
    }
    refreshRingtoneLabel()
    ringtoneRow.setOnClickListener {
      val raws = listRawSounds()
      val labels = mutableListOf(getString(R.string.settings_ringtone_subtitle_default))
      labels.addAll(raws)
      val current = prefs.getString("selected_ringtone", "").orEmpty()
      val checkedIndex = if (current.isBlank()) 0 else (raws.indexOf(current).let { if (it >= 0) it + 1 else 0 })
      AlertDialog.Builder(this)
        .setTitle(R.string.settings_ringtone_dialog_title)
        .setSingleChoiceItems(labels.toTypedArray(), checkedIndex) { dialog, which ->
          val picked = if (which == 0) "" else (raws.getOrNull(which - 1) ?: "")
          prefs.edit().putString("selected_ringtone", picked).apply()
          refreshRingtoneLabel()
          dialog.dismiss()
        }
        .setNegativeButton(R.string.action_cancel, null)
        .show()
    }
  }

  // 服务器 tab 推送 URL 测试预览卡。
  private fun renderBarkPreviewCards(deviceInstallationId: String) {
    val key = deviceInstallationId.trim()
    if (key.isBlank()) {
      barkPreviewContainer.visibility = View.GONE
      return
    }
    barkPreviewContainer.visibility = View.VISIBLE
    barkPreviewItemsContainer.removeAllViews()

    fun encodePath(s: String): String =
      URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    val base = BuildConfig.NOTIFY_BASE_URL.trimEnd('/')
    data class Item(val title: String, val url: String)
    val items = listOf(
      Item("推送内容", "$base/quick/$key/${encodePath("这是一条测试推送")}"),
      Item("推送标题 + 内容", "$base/quick/$key/${encodePath("推送标题")}/${encodePath("这是测试推送内容")}"),
      Item("推送铃声", "$base/quick/$key/${encodePath("推送铃声")}?sound=alarm"),
      Item("持续响铃", "$base/quick/$key/${encodePath("持续响铃")}?call=1"),
    )

    for (item in items) {
      val row = LayoutInflater.from(this).inflate(R.layout.item_bark_preview, barkPreviewItemsContainer, false)
      row.findViewById<TextView>(R.id.barkItemTitle).text = item.title
      row.findViewById<TextView>(R.id.barkItemUrl).text = item.url
      row.findViewById<TextView>(R.id.barkItemCopy).setOnClickListener {
        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText("推送 URL", item.url))
        Toast.makeText(this, R.string.bark_url_copied, Toast.LENGTH_SHORT).show()
      }
      row.findViewById<TextView>(R.id.barkItemSend).setOnClickListener {
        val targetUrl = item.url
        executor.execute {
          var conn: HttpURLConnection? = null
          try {
            conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
              requestMethod = "GET"
              connectTimeout = 5000
              readTimeout = 5000
              setRequestProperty("user-agent", "ai-dca-android")
            }
            val code = conn.responseCode
            mainHandler.post {
              Toast.makeText(this, getString(R.string.bark_send_success, code), Toast.LENGTH_SHORT).show()
            }
          } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            mainHandler.post {
              Toast.makeText(this, getString(R.string.bark_send_failed, msg), Toast.LENGTH_LONG).show()
            }
          } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
          }
        }
      }
      barkPreviewItemsContainer.addView(row)
    }
  }

  private fun renderPairedClients(snapshot: RegistrationSnapshot) {
    pairedClientItemsContainer.removeAllViews()
    data class PairedItem(
      val name: String,
      val displayId: String,
      val groupId: String,
      val clientId: String
    )
    val items = mutableListOf<PairedItem>()
    val rawJson = snapshot.pairedClientsJson
    if (rawJson.isNotBlank()) {
      try {
        val arr = JSONArray(rawJson)
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          val name = obj.optString("clientName").ifBlank { "未命名浏览器" }
          val groupId = obj.optString("groupId")
          val clientId = obj.optString("clientId")
          if (groupId.isBlank() && clientId.isBlank()) continue
          val displayId = groupId.ifBlank { clientId }
          items += PairedItem(name, displayId, groupId, clientId)
        }
      } catch (_: Exception) {
        // Malformed JSON; fall through to empty list.
      }
    }

    if (items.isEmpty()) {
      pairedClientContainerView.visibility =
        if (snapshot.pairedClientSummary.isBlank()) View.GONE else View.VISIBLE
      return
    }

    pairedClientContainerView.visibility = View.VISIBLE
    for (item in items) {
      val row = LayoutInflater.from(this).inflate(R.layout.item_paired_client, pairedClientItemsContainer, false)
      row.findViewById<TextView>(R.id.pairedClientNameTextView).text = item.name
      row.findViewById<TextView>(R.id.pairedClientIdTextView).text = item.displayId
      row.findViewById<Button>(R.id.unpairClientButton).setOnClickListener {
        AlertDialog.Builder(this)
          .setTitle(R.string.unpair_confirm_title)
          .setMessage(R.string.unpair_confirm_message)
          .setNegativeButton(R.string.action_cancel, null)
          .setPositiveButton(R.string.action_confirm) { _, _ ->
            RegistrationRepository.unpairBrowser(this, item.groupId, item.clientId) { ok, errorMessage ->
              if (ok) {
                Toast.makeText(this, R.string.unpair_success, Toast.LENGTH_SHORT).show()
                startRegistration("after-unpair")
              } else {
                Toast.makeText(this, getString(R.string.unpair_failed, errorMessage), Toast.LENGTH_LONG).show()
              }
            }
          }
          .show()
      }
      pairedClientItemsContainer.addView(row)
    }
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

package tech.freebacktrack.aidca

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object RegistrationRepository {
  private val executor = Executors.newSingleThreadExecutor()
  private val mainHandler = Handler(Looper.getMainLooper())

  fun currentIdentity(context: Context): AppIdentity {
    val firebaseApp = resolveFirebaseApp(context)
    val options = firebaseApp?.options

    return AppIdentity(
      deviceInstallationId = DeviceInstallationStore.getOrCreate(context),
      projectId = options?.projectId.orEmpty(),
      packageName = context.packageName.orEmpty(),
      deviceName = buildDeviceName(),
      senderId = options?.gcmSenderId.orEmpty(),
      appId = options?.applicationId.orEmpty()
    )
  }

  fun refresh(context: Context, trigger: String, callback: (RegistrationSnapshot) -> Unit) {
    val appContext = context.applicationContext
    DebugLogStore.append(appContext, "register", "Refresh requested, trigger=$trigger")
    executor.execute {
      val snapshot = runHeartbeat(appContext, trigger)
      mainHandler.post {
        callback(snapshot)
      }
    }
  }

  fun syncFromService(context: Context, token: String, trigger: String) {
    val appContext = context.applicationContext
    DebugLogStore.append(appContext, "register", "Sync requested from service, trigger=$trigger token=${maskToken(token)}")
    executor.execute {
      safeRegisterCurrentToken(appContext, token, trigger)
    }
  }

  fun runHeartbeat(context: Context, trigger: String = "heartbeat"): RegistrationSnapshot {
    return safeRegisterCurrentToken(context.applicationContext, null, trigger)
  }

  private fun safeRegisterCurrentToken(
    context: Context,
    explicitToken: String?,
    trigger: String
  ): RegistrationSnapshot {
    return try {
      registerCurrentToken(context, explicitToken, trigger)
    } catch (error: Exception) {
      DebugLogStore.append(
        context,
        "register",
        "Registration failed during $trigger: ${error.message ?: "未知错误"}"
      )
      val identity = currentIdentity(context)
      errorSnapshot(
        context = context,
        identity = identity,
        trigger = trigger,
        title = "自动注册失败",
        detail = describeUnexpectedFailure(error),
        tokenMasked = maskToken(explicitToken.orEmpty())
      )
    }
  }

  private fun registerCurrentToken(context: Context, explicitToken: String?, trigger: String): RegistrationSnapshot {
    val identity = currentIdentity(context)
    val firebaseApp = resolveFirebaseApp(context)
    val previousSnapshot = RegistrationStateStore.read(context, identity)
    DebugLogStore.append(
      context,
      "register",
      "registerCurrentToken started, trigger=$trigger package=${identity.packageName.ifBlank { "-" }} explicitToken=${if (explicitToken.isNullOrBlank()) "no" else "yes"}"
    )

    if (firebaseApp == null) {
      DebugLogStore.append(context, "register", "Firebase app missing, aborting registration")
      return errorSnapshot(
        context = context,
        identity = identity,
        trigger = trigger,
        title = "缺少 Firebase 配置",
        detail = "应用还没有接入 Firebase。请把 google-services.json 放到 app/ 后重新构建安装。"
      )
    }

    val token = try {
      explicitToken?.trim().orEmpty().ifBlank {
        fetchTokenBlocking(context)
      }
    } catch (error: Exception) {
      DebugLogStore.append(context, "firebase", "Token fetch failed during $trigger: ${error.message ?: "未知错误"}")
      return errorSnapshot(
        context = context,
        identity = identity,
        trigger = trigger,
        title = "获取 FCM token 失败",
        detail = describeTokenFetchFailure(error)
      )
    }

    DebugLogStore.append(context, "register", "Using token ${maskToken(token)}")

    if (token.isBlank()) {
      DebugLogStore.append(context, "register", "Firebase returned empty token")
      return errorSnapshot(
        context = context,
        identity = identity,
        trigger = trigger,
        title = "获取 FCM token 失败",
        detail = "Firebase 没有返回可用的 registration token，请稍后再试。"
      )
    }

    return try {
      DebugLogStore.append(context, "register", "Submitting token to ${BuildConfig.NOTIFY_BASE_URL}/gcm/register")
      val registerPayload = JSONObject()
        .put("deviceInstallationId", identity.deviceInstallationId)
        .put("projectId", identity.projectId)
        .put("packageName", identity.packageName)
        .put("deviceName", identity.deviceName)
        .put("senderId", identity.senderId)
        .put("appId", identity.appId)
        .put("token", token)

      if (previousSnapshot.registrationId.isNotBlank()) {
        registerPayload.put("registrationId", previousSnapshot.registrationId)
      }

      val registerResponse = postJson("${BuildConfig.NOTIFY_BASE_URL}/gcm/register", registerPayload)
      val registerRegistration = registerResponse.body.optJSONObject("registration")
      val registrationId = registerResponse.body
        .optJSONObject("registration")
        ?.optString("id")
        .orEmpty()
        .ifBlank { previousSnapshot.registrationId }
      val registeredPairCount = registerResponse.body
        .optJSONObject("registration")
        ?.optInt("pairedClientCount")
        ?: 0
      var pairedClientSummary = readPairedClientSummary(registerRegistration)
      val registeredCount = registerResponse.body.optJSONObject("setup")?.optInt("gcmRegistrationCount") ?: 0
      DebugLogStore.append(
        context,
        "register",
        "Register API success, registrationCount=$registeredCount registrationId=${registrationId.ifBlank { "-" }} pairedClientCount=$registeredPairCount"
      )

      var pairedClientCount = registeredPairCount
      val connectionState = try {
        DebugLogStore.append(context, "register", "Running validateOnly check against ${BuildConfig.NOTIFY_BASE_URL}/gcm/check")
        val checkPayload = JSONObject()
          .put("deviceInstallationId", identity.deviceInstallationId)
          .put("projectId", identity.projectId)
          .put("packageName", identity.packageName)
          .put("token", token)

        if (registrationId.isNotBlank()) {
          checkPayload.put("registrationId", registrationId)
        }

        val checkResponse = postJson("${BuildConfig.NOTIFY_BASE_URL}/gcm/check", checkPayload)
        val checkRegistration = checkResponse.body.optJSONObject("registration")
        pairedClientCount = checkResponse.body
          .optJSONObject("registration")
          ?.optInt("pairedClientCount")
          ?: pairedClientCount
        pairedClientSummary = readPairedClientSummary(checkRegistration).ifBlank { pairedClientSummary }
        val detail = checkResponse.body
          .optJSONObject("result")
          ?.optString("detail")
          .orEmpty()
          .ifBlank { "服务端检查通过" }
        DebugLogStore.append(context, "register", "ValidateOnly check passed")
        ConnectionState(
          state = "validated",
          title = "服务端已连通",
          detail = detail
        )
      } catch (checkError: Exception) {
        DebugLogStore.append(
          context,
          "register",
          "ValidateOnly check failed: ${checkError.message ?: "未知错误"}"
        )
        ConnectionState(
          state = "registered",
          title = "设备已注册",
          detail = "服务端检查失败: ${checkError.message ?: "未知错误"}"
        )
      }

      val pairingState = if (pairedClientCount > 0) {
        DebugLogStore.append(
          context,
          "pairing",
          "Registration already paired, hide pairing card. pairedClientCount=$pairedClientCount"
        )
        PairingState(
          code = "",
          expiresAt = "",
          status = "paired",
          detail = "当前设备已完成前端配对。",
          pairedClientSummary = pairedClientSummary.ifBlank { previousSnapshot.pairedClientSummary }
        )
      } else {
        try {
          DebugLogStore.append(
            context,
            "pairing",
            "Requesting pairing code for registrationId=${registrationId.ifBlank { "-" }}"
          )
          requestPairingCode(context, identity, registrationId, token)
        } catch (pairingError: Exception) {
          DebugLogStore.append(
            context,
            "pairing",
            "Pairing code request failed: ${pairingError.message ?: "未知错误"}"
          )
          PairingState(
            code = "",
            expiresAt = "",
            status = "error",
            detail = pairingError.message ?: "配对码生成失败",
            pairedClientSummary = ""
          )
        }
      }

      val resolvedPairedClientSummary = if (pairingState.status == "paired") {
        pairingState.pairedClientSummary.ifBlank { previousSnapshot.pairedClientSummary }
      } else {
        ""
      }

      val snapshot = RegistrationSnapshot(
        state = connectionState.state,
        title = connectionState.title,
        detail = connectionState.detail,
        updatedAt = nowLabel(),
        deviceInstallationId = identity.deviceInstallationId,
        tokenMasked = maskToken(token),
        projectId = identity.projectId,
        packageName = identity.packageName,
        deviceName = identity.deviceName,
        senderId = identity.senderId,
        appId = identity.appId,
        trigger = trigger,
        registrationId = registrationId,
        pairingCode = pairingState.code,
        pairingCodeExpiresAt = pairingState.expiresAt,
        pairingStatus = pairingState.status,
        pairingDetail = pairingState.detail,
        pairedClientSummary = resolvedPairedClientSummary
      )
      RegistrationStateStore.write(context, snapshot)
      snapshot
    } catch (error: Exception) {
      DebugLogStore.append(context, "register", "Registration failed: ${error.message ?: "未知错误"}")
      errorSnapshot(
        context = context,
        identity = identity,
        trigger = trigger,
        title = "自动注册失败",
        detail = error.message ?: "注册通知设备时发生未知错误。",
        tokenMasked = maskToken(token)
      )
    }
  }

  private fun requestPairingCode(
    context: Context,
    identity: AppIdentity,
    registrationId: String,
    token: String
  ): PairingState {
    val payload = JSONObject()
      .put("deviceInstallationId", identity.deviceInstallationId)
      .put("token", token)

    if (registrationId.isNotBlank()) {
      payload.put("registrationId", registrationId)
    }

    val response = postJson("${BuildConfig.NOTIFY_BASE_URL}/gcm/pairing-key", payload)
    val pairingPayload = response.body.optJSONObject("pairing")
    val code = pairingPayload?.optString("code").orEmpty()
    val expiresAt = pairingPayload?.optString("expiresAt").orEmpty()

    return if (code.isBlank()) {
      DebugLogStore.append(context, "pairing", "Pairing key response had no code")
      PairingState(
        code = "",
        expiresAt = expiresAt,
        status = "unavailable",
        detail = "",
        pairedClientSummary = ""
      )
    } else {
      DebugLogStore.append(context, "pairing", "Pairing code issued: $code expiresAt=${expiresAt.ifBlank { "-" }}")
      PairingState(
        code = code,
        expiresAt = expiresAt,
        status = "issued",
        detail = if (expiresAt.isBlank()) "" else "有效期至 ${formatIsoLabel(expiresAt)}",
        pairedClientSummary = ""
      )
    }
  }

  private fun fetchTokenBlocking(context: Context): String {
    val latch = CountDownLatch(1)
    val tokenRef = AtomicReference("")
    val errorRef = AtomicReference<Exception?>(null)

    DebugLogStore.append(context, "firebase", "Requesting FCM token from FirebaseMessaging")
    FirebaseMessaging.getInstance().token
      .addOnSuccessListener { token ->
        tokenRef.set(token.orEmpty())
        DebugLogStore.append(context, "firebase", "FCM token received: ${maskToken(token.orEmpty())}")
        latch.countDown()
      }
      .addOnFailureListener { error ->
        errorRef.set(Exception(error))
        DebugLogStore.append(context, "firebase", "FCM token request failed: ${error.message ?: "未知错误"}")
        latch.countDown()
      }

    val completed = latch.await(20, TimeUnit.SECONDS)

    if (!completed) {
      DebugLogStore.append(context, "firebase", "Timed out while waiting for FCM token")
      throw IllegalStateException("等待 Firebase 返回 token 超时。")
    }

    val capturedError = errorRef.get()

    if (capturedError != null) {
      DebugLogStore.append(context, "firebase", "Throwing captured token error")
      throw capturedError
    }

    return tokenRef.get().orEmpty()
  }

  private fun resolveFirebaseApp(context: Context): FirebaseApp? {
    val existing = FirebaseApp.getApps(context).firstOrNull()

    if (existing != null) {
      DebugLogStore.append(context, "firebase", "Reusing existing Firebase app")
      return existing
    }

    DebugLogStore.append(context, "firebase", "Initializing Firebase app on demand")
    return FirebaseApp.initializeApp(context)
  }

  private fun postJson(url: String, payload: JSONObject): HttpResponse {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 15000
      readTimeout = 15000
      doInput = true
      doOutput = true
      setRequestProperty("Content-Type", "application/json; charset=utf-8")
      setRequestProperty("Accept", "application/json")
    }

    try {
      connection.outputStream.use { output ->
        output.write(payload.toString().toByteArray(Charsets.UTF_8))
      }

      val code = connection.responseCode
      val body = readStream(
        if (code in 200..299) connection.inputStream else connection.errorStream
      )
      val jsonBody = if (body.isBlank()) JSONObject() else JSONObject(body)

      if (code !in 200..299) {
        throw IllegalStateException(jsonBody.optString("error").ifBlank { "服务端返回状态 $code" })
      }

      return HttpResponse(code, jsonBody)
    } finally {
      connection.disconnect()
    }
  }

  private fun readStream(stream: InputStream?): String {
    if (stream == null) {
      return ""
    }

    return BufferedReader(InputStreamReader(stream)).use { reader ->
      buildString {
        while (true) {
          val line = reader.readLine() ?: break
          append(line)
        }
      }
    }
  }

  private fun errorSnapshot(
    context: Context,
    identity: AppIdentity,
    trigger: String,
    title: String,
    detail: String,
    tokenMasked: String = ""
  ): RegistrationSnapshot {
    val previousSnapshot = RegistrationStateStore.read(context, identity)
    val snapshot = RegistrationSnapshot(
      state = "error",
      title = title,
      detail = detail,
      updatedAt = nowLabel(),
      deviceInstallationId = identity.deviceInstallationId,
      tokenMasked = tokenMasked.ifBlank { previousSnapshot.tokenMasked },
      projectId = identity.projectId,
      packageName = identity.packageName,
      deviceName = identity.deviceName,
      senderId = identity.senderId,
      appId = identity.appId,
      trigger = trigger,
      registrationId = previousSnapshot.registrationId,
      pairingCode = previousSnapshot.pairingCode,
      pairingCodeExpiresAt = previousSnapshot.pairingCodeExpiresAt,
      pairingStatus = previousSnapshot.pairingStatus,
      pairingDetail = previousSnapshot.pairingDetail,
      pairedClientSummary = previousSnapshot.pairedClientSummary
    )
    RegistrationStateStore.write(context, snapshot)
    return snapshot
  }

  private fun readPairedClientSummary(registrationPayload: JSONObject?): String {
    val pairedClients = registrationPayload?.optJSONArray("pairedClients") ?: return ""
    val summaries = mutableListOf<String>()

    for (index in 0 until pairedClients.length()) {
      val client = pairedClients.optJSONObject(index) ?: continue
      val clientId = client.optString("clientId").trim()
      val clientName = client.optString("clientName").trim()
      val summary = buildString {
        if (clientName.isNotBlank()) {
          append(clientName)
        }
        if (clientId.isNotBlank()) {
          if (isNotEmpty()) {
            append("\n")
          }
          append(clientId)
        }
      }.trim()

      if (summary.isNotBlank()) {
        summaries += summary
      }
    }

    return summaries.joinToString("\n\n")
  }

  private fun describeTokenFetchFailure(error: Exception): String {
    val errorSummary = errorChainSummary(error)
    val hint = when {
      errorSummary.contains("MISSING_INSTANCEID_SERVICE", ignoreCase = true) ->
        "当前设备缺少可用的 Google Play 服务，FCM 无法签发 token。"
      errorSummary.contains("SERVICE_NOT_AVAILABLE", ignoreCase = true) ->
        "Firebase 当前不可达。请检查设备网络以及 Google Play 服务状态。"
      errorSummary.contains("FIS_AUTH_ERROR", ignoreCase = true) ->
        "Firebase Installations 鉴权失败。请确认 google-services.json 与当前应用包名一致。"
      else -> ""
    }

    return buildString {
      append("Firebase 没有返回可用的 registration token。")
      if (errorSummary.isNotBlank()) {
        append(" 原因: ")
        append(errorSummary)
        append("。")
      }
      if (hint.isNotBlank()) {
        append(" ")
        append(hint)
      }
    }
  }

  private fun describeUnexpectedFailure(error: Exception): String {
    val errorSummary = errorChainSummary(error)
    return if (errorSummary.isBlank()) {
      "自动注册通知设备时发生未知错误。"
    } else {
      "自动注册通知设备时发生错误: $errorSummary。"
    }
  }

  private fun errorChainSummary(error: Throwable): String {
    return generateSequence(error) { it.cause }
      .mapNotNull { cause ->
        cause.message
          ?.trim()
          ?.takeIf { it.isNotEmpty() }
          ?: cause.javaClass.simpleName.takeIf { it.isNotEmpty() }
      }
      .distinct()
      .joinToString(" -> ")
  }

  private fun buildDeviceName(): String {
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()

    return listOf(manufacturer, model)
      .filter { it.isNotBlank() }
      .joinToString(" ")
      .ifBlank { "Android Device" }
  }

  private fun maskToken(token: String): String {
    return if (token.length <= 14) token else "${token.take(8)}...${token.takeLast(6)}"
  }

  private fun nowLabel(): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
  }

  private fun formatIsoLabel(value: String): String {
    return value.replace('T', ' ').replace("Z", "").take(19)
  }

  private data class HttpResponse(
    val code: Int,
    val body: JSONObject
  )

  private data class ConnectionState(
    val state: String,
    val title: String,
    val detail: String
  )

  private data class PairingState(
    val code: String,
    val expiresAt: String,
    val status: String,
    val detail: String,
    val pairedClientSummary: String
  )
}

package tech.freebacktrack.aidca

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
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
  private const val TAG = "RegistrationRepository"
  private val executor = Executors.newSingleThreadExecutor()
  private val mainHandler = Handler(Looper.getMainLooper())

  fun currentIdentity(context: Context): AppIdentity {
    val firebaseApp = resolveFirebaseApp(context)
    val options = firebaseApp?.options

    return AppIdentity(
      projectId = options?.projectId.orEmpty(),
      packageName = context.packageName.orEmpty(),
      deviceName = buildDeviceName(),
      senderId = options?.gcmSenderId.orEmpty(),
      appId = options?.applicationId.orEmpty()
    )
  }

  fun refresh(context: Context, trigger: String, callback: (RegistrationSnapshot) -> Unit) {
    val appContext = context.applicationContext
    executor.execute {
      val snapshot = safeRegisterCurrentToken(appContext, null, trigger)
      mainHandler.post {
        callback(snapshot)
      }
    }
  }

  fun syncFromService(context: Context, token: String, trigger: String) {
    val appContext = context.applicationContext
    executor.execute {
      safeRegisterCurrentToken(appContext, token, trigger)
    }
  }

  private fun safeRegisterCurrentToken(
    context: Context,
    explicitToken: String?,
    trigger: String
  ): RegistrationSnapshot {
    return try {
      registerCurrentToken(context, explicitToken, trigger)
    } catch (error: Exception) {
      Log.e(TAG, "Registration failed during $trigger", error)
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

    if (firebaseApp == null) {
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
        fetchTokenBlocking()
      }
    } catch (error: Exception) {
      Log.e(TAG, "Failed to fetch FCM token during $trigger", error)
      return errorSnapshot(
        context = context,
        identity = identity,
        trigger = trigger,
        title = "获取 FCM token 失败",
        detail = describeTokenFetchFailure(error)
      )
    }

    if (token.isBlank()) {
      return errorSnapshot(
        context = context,
        identity = identity,
        trigger = trigger,
        title = "获取 FCM token 失败",
        detail = "Firebase 没有返回可用的 registration token，请稍后再试。"
      )
    }

    return try {
      val registerPayload = JSONObject()
        .put("projectId", identity.projectId)
        .put("packageName", identity.packageName)
        .put("deviceName", identity.deviceName)
        .put("senderId", identity.senderId)
        .put("appId", identity.appId)
        .put("token", token)
      val registerResponse = postJson("${BuildConfig.NOTIFY_BASE_URL}/gcm/register", registerPayload)
      val registeredCount = registerResponse.body.optJSONObject("setup")?.optInt("gcmRegistrationCount") ?: 0

      try {
        val checkPayload = JSONObject()
          .put("projectId", identity.projectId)
          .put("packageName", identity.packageName)
          .put("token", token)
        val checkResponse = postJson("${BuildConfig.NOTIFY_BASE_URL}/gcm/check", checkPayload)
        val detail = checkResponse.body
          .optJSONObject("result")
          ?.optString("detail")
          .orEmpty()
          .ifBlank { "FCM 凭证和当前 Android token 已通过服务端检查。" }
        val snapshot = RegistrationSnapshot(
          state = "connected",
          title = "Android 通知已连接",
          detail = detail,
          updatedAt = nowLabel(),
          tokenMasked = maskToken(token),
          projectId = identity.projectId,
          packageName = identity.packageName,
          deviceName = identity.deviceName,
          senderId = identity.senderId,
          appId = identity.appId,
          trigger = trigger
        )
        RegistrationStateStore.write(context, snapshot)
        snapshot
      } catch (checkError: Exception) {
        val snapshot = RegistrationSnapshot(
          state = "registered",
          title = "设备已注册",
          detail = "token 已提交到服务端（当前共登记 $registeredCount 台设备），但服务端连接检查失败: ${checkError.message ?: "未知错误"}",
          updatedAt = nowLabel(),
          tokenMasked = maskToken(token),
          projectId = identity.projectId,
          packageName = identity.packageName,
          deviceName = identity.deviceName,
          senderId = identity.senderId,
          appId = identity.appId,
          trigger = trigger
        )
        RegistrationStateStore.write(context, snapshot)
        snapshot
      }
    } catch (error: Exception) {
      val snapshot = RegistrationSnapshot(
        state = "error",
        title = "自动注册失败",
        detail = error.message ?: "注册通知设备时发生未知错误。",
        updatedAt = nowLabel(),
        tokenMasked = maskToken(token),
        projectId = identity.projectId,
        packageName = identity.packageName,
        deviceName = identity.deviceName,
        senderId = identity.senderId,
        appId = identity.appId,
        trigger = trigger
      )
      RegistrationStateStore.write(context, snapshot)
      snapshot
    }
  }

  private fun fetchTokenBlocking(): String {
    val latch = CountDownLatch(1)
    val tokenRef = AtomicReference("")
    val errorRef = AtomicReference<Throwable?>(null)

    FirebaseMessaging.getInstance().token
      .addOnSuccessListener { token ->
        tokenRef.set(token.orEmpty())
        latch.countDown()
      }
      .addOnFailureListener { error ->
        errorRef.set(error)
        latch.countDown()
      }

    val completed = latch.await(20, TimeUnit.SECONDS)

    if (!completed) {
      throw IllegalStateException("等待 Firebase 返回 token 超时。")
    }

    val capturedError = errorRef.get()

    if (capturedError != null) {
      throw capturedError.asException()
    }

    return tokenRef.get().orEmpty()
  }

  private fun resolveFirebaseApp(context: Context): FirebaseApp? {
    val existing = FirebaseApp.getApps(context).firstOrNull()

    if (existing != null) {
      return existing
    }

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
    val snapshot = RegistrationSnapshot(
      state = "error",
      title = title,
      detail = detail,
      updatedAt = nowLabel(),
      tokenMasked = tokenMasked,
      projectId = identity.projectId,
      packageName = identity.packageName,
      deviceName = identity.deviceName,
      senderId = identity.senderId,
      appId = identity.appId,
      trigger = trigger
    )
    RegistrationStateStore.write(context, snapshot)
    return snapshot
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

  private fun Throwable.asException(): Exception {
    return this as? Exception ?: Exception(message ?: javaClass.simpleName, this)
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

  private data class HttpResponse(
    val code: Int,
    val body: JSONObject
  )
}

package tech.freebacktrack.aidca

import android.content.Context
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
import java.util.TimeZone
import java.util.concurrent.Executors

/** Reports client-side delivery ACKs back to the notify Worker.
 *
 * FCM itself only tells the Worker that Firebase accepted the message. This reporter is the
 * Android-side callback that confirms app-level stages: received / displayed / opened / deduped / failed.
 */
object DeliveryAckReporter {
  private val executor = Executors.newSingleThreadExecutor()
  private val allowedStages = setOf("received", "displayed", "opened", "deduped", "failed")

  fun report(
    context: Context,
    source: String,
    stage: String,
    eventId: String,
    messageId: String,
    detail: String = ""
  ) {
    val appContext = context.applicationContext
    val normalizedStage = stage.trim().lowercase(Locale.US).let { if (it in allowedStages) it else "received" }
    val normalizedSource = source.trim().lowercase(Locale.US).ifBlank { "unknown" }
    val resolvedMessageId = messageId.trim().ifBlank { eventId.trim() }
    val resolvedEventId = eventId.trim().ifBlank { resolvedMessageId }
    if (resolvedMessageId.isBlank()) return

    executor.execute {
      try {
        FirebaseMessaging.getInstance().token
          .addOnSuccessListener { token ->
            executor.execute {
              postAck(appContext, token.orEmpty(), normalizedSource, normalizedStage, resolvedEventId, resolvedMessageId, detail)
            }
          }
          .addOnFailureListener { error ->
            DebugLogStore.append(
              appContext,
              "ack",
              "token fetch failed stage=$normalizedStage messageId=$resolvedMessageId err=${error.message ?: "-"}"
            )
          }
      } catch (error: Exception) {
        DebugLogStore.append(
          appContext,
          "ack",
          "schedule failed stage=$normalizedStage messageId=$resolvedMessageId err=${error.message ?: "-"}"
        )
      }
    }
  }

  private fun postAck(
    context: Context,
    token: String,
    source: String,
    stage: String,
    eventId: String,
    messageId: String,
    detail: String
  ) {
    if (token.isBlank()) {
      DebugLogStore.append(context, "ack", "skip ack stage=$stage messageId=$messageId: empty token")
      return
    }
    val payload = JSONObject()
      .put("deviceInstallationId", DeviceInstallationStore.getOrCreate(context))
      .put("token", token)
      .put("source", source)
      .put("stage", stage)
      .put("eventId", eventId)
      .put("messageId", messageId)
      .put("ts", nowIso())
    if (detail.isNotBlank()) payload.put("detail", detail.take(500))

    val url = "${ServerRegistry.currentApiBase(context)}/ack"
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 10000
      readTimeout = 10000
      doInput = true
      doOutput = true
      setRequestProperty("Content-Type", "application/json; charset=utf-8")
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", "jijin-notify-android")
    }

    try {
      connection.outputStream.use { output ->
        output.write(payload.toString().toByteArray(Charsets.UTF_8))
      }
      val code = connection.responseCode
      val body = readStream(if (code in 200..299) connection.inputStream else connection.errorStream)
      if (code in 200..299) {
        DebugLogStore.append(context, "ack", "ack ok stage=$stage source=$source messageId=$messageId")
      } else {
        DebugLogStore.append(context, "ack", "ack failed code=$code stage=$stage messageId=$messageId body=${body.take(120)}")
      }
    } catch (error: Exception) {
      DebugLogStore.append(context, "ack", "ack error stage=$stage messageId=$messageId err=${error.message ?: "-"}")
    } finally {
      connection.disconnect()
    }
  }

  private fun readStream(stream: InputStream?): String {
    if (stream == null) return ""
    return BufferedReader(InputStreamReader(stream)).use { reader ->
      buildString {
        while (true) {
          val line = reader.readLine() ?: break
          append(line)
        }
      }
    }
  }

  private fun nowIso(): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date())
  }
}

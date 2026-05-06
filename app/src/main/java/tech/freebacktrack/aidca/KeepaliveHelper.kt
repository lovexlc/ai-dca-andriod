package tech.freebacktrack.aidca

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 国产 ROM 后台保活引导：
 * - isOnBatteryWhitelist: 是否已在电池优化白名单。
 * - requestBatteryWhitelist: 拉起系统忽略电池优化对话框。
 * - openAutostartSettings: 按 OEM 跳到自启动 / 后台运行管理；都失败时回退到应用详情页。
 */
object KeepaliveHelper {
  fun isOnBatteryWhitelist(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
  }

  @SuppressLint("BatteryLife")
  fun requestBatteryWhitelist(context: Context): Boolean {
    return try {
      val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      true
    } catch (_: ActivityNotFoundException) {
      runCatching {
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallback)
      }.isSuccess
    } catch (_: Exception) {
      false
    }
  }

  /**
   * 跳到 OEM 自启动管理。返回是否成功跳到了厂商专用入口；失败时调用方自行回退。
   */
  fun openAutostartSettings(context: Context): Boolean {
    val brand = (Build.BRAND ?: "").lowercase()
    val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
    val intents = candidateIntentsFor(brand, manufacturer)
    for (component in intents) {
      val intent = Intent().apply {
        this.component = component
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      val resolved = context.packageManager.resolveActivity(intent, 0) != null
      if (resolved) {
        return runCatching { context.startActivity(intent) }.isSuccess
      }
    }
    return false
  }

  /**
   * 兜底：打开本应用的详情页（用户可在详情页里手动找到自启动 / 省电策略入口）。
   */
  fun openAppDetailsSettings(context: Context): Boolean {
    return runCatching {
      val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }.isSuccess
  }

  private fun candidateIntentsFor(brand: String, manufacturer: String): List<ComponentName> {
    val key = listOf(brand, manufacturer).joinToString(" ")
    return when {
      key.contains("xiaomi") || key.contains("redmi") || key.contains("poco") -> listOf(
        ComponentName(
          "com.miui.securitycenter",
          "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
      )
      key.contains("huawei") || key.contains("honor") -> listOf(
        ComponentName(
          "com.huawei.systemmanager",
          "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        ComponentName(
          "com.huawei.systemmanager",
          "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        ),
      )
      key.contains("oppo") || key.contains("realme") || key.contains("oneplus") -> listOf(
        ComponentName(
          "com.coloros.safecenter",
          "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        ),
        ComponentName(
          "com.coloros.safecenter",
          "com.coloros.safecenter.startupapp.StartupAppListActivity",
        ),
        ComponentName(
          "com.oppo.safe",
          "com.oppo.safe.permission.startup.StartupAppListActivity",
        ),
      )
      key.contains("vivo") || key.contains("iqoo") -> listOf(
        ComponentName(
          "com.iqoo.secure",
          "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        ),
        ComponentName(
          "com.vivo.permissionmanager",
          "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        ),
      )
      key.contains("samsung") -> listOf(
        ComponentName(
          "com.samsung.android.lool",
          "com.samsung.android.sm.ui.battery.BatteryActivity",
        ),
      )
      key.contains("meizu") -> listOf(
        ComponentName(
          "com.meizu.safe",
          "com.meizu.safe.security.SHOW_APPSEC",
        ),
      )
      else -> emptyList()
    }
  }
}

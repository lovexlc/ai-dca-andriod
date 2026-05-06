import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("com.google.gms.google-services")
}

val keystoreProperties = Properties().apply {
  val file = rootProject.file("keystore.properties")
  if (file.exists()) {
    file.inputStream().use(::load)
  }
}

fun propertyValue(name: String, envName: String = name): String? {
  return keystoreProperties.getProperty(name)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: providers.gradleProperty(name).orNull
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
    ?: providers.environmentVariable(envName).orNull
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
}

val configuredVersionCode = propertyValue("androidVersionCode", "ANDROID_VERSION_CODE")
  ?.toIntOrNull()
  ?: 1
val configuredVersionName = propertyValue("androidVersionName", "ANDROID_VERSION_NAME")
  ?: "1.0.0"
val releaseKeystoreFile = rootProject.file(propertyValue("storeFile") ?: "release.jks")
val releaseStorePassword = propertyValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = propertyValue("keyAlias", "ANDROID_KEY_ALIAS")
val releaseKeyPassword = propertyValue("keyPassword", "ANDROID_KEY_PASSWORD")
val hasReleaseSigning = releaseKeystoreFile.exists() &&
  !releaseStorePassword.isNullOrBlank() &&
  !releaseKeyAlias.isNullOrBlank() &&
  !releaseKeyPassword.isNullOrBlank()

android {
  namespace = "tech.freebacktrack.aidca"
  compileSdk = 35

  defaultConfig {
    applicationId = "tech.freebacktrack.aidca"
    minSdk = 26
    targetSdk = 35
    versionCode = configuredVersionCode
    versionName = configuredVersionName

    buildConfigField("String", "NOTIFY_BASE_URL", "\"https://tools.freebacktrack.tech/api/notify\"")
  }

  buildFeatures {
    buildConfig = true
  }

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = releaseKeystoreFile
        storePassword = requireNotNull(releaseStorePassword)
        keyAlias = requireNotNull(releaseKeyAlias)
        keyPassword = requireNotNull(releaseKeyPassword)
      }
    }
  }

  buildTypes {
    release {
      if (hasReleaseSigning) {
        signingConfig = signingConfigs.getByName("release")
      }
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
  }
}

dependencies {
  implementation("androidx.work:work-runtime-ktx:2.10.2")
  implementation(platform("com.google.firebase:firebase-bom:34.7.0"))
  implementation("com.google.firebase:firebase-messaging")
  // OkHttp 提供 WebSocket 客户端，给 RealtimeChannelService（实时长连接通道）使用。
  // 4.12.x 是当前 OkHttp 4 系列稳定线，支持 minSdk 21+，与现有 firebase-messaging 共存无冲突。
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

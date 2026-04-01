# AI DCA Android App

这个仓库提供一个最小 Android 原生 app。

目标很直接:

- 用户安装 app 后，不需要手动复制 token
- app 首次启动时自动获取 FCM registration token
- app 自动把当前设备注册到 `https://tools.freebacktrack.tech/api/notify/gcm/register`
- app 继续调用 `https://tools.freebacktrack.tech/api/notify/gcm/check` 做服务端连通性检查
- app 自动调用 `https://tools.freebacktrack.tech/api/notify/gcm/pairing-key` 生成前端配对码
- 当 Firebase token 轮换时，`FirebaseMessagingService` 会自动重新注册
- app 会把最近一次真实收到的 FCM 消息记录到本地页面，方便区分“validateOnly 校验通过”和“手机真的收到了推送”
- 调试模式下可以查看应用内日志，排查 HyperOS 后台限制和推送到达链路

## 你需要准备

1. 一个 Firebase Android App
2. 对应的 `google-services.json`
3. JDK 17
4. Android Studio / Android SDK

把 `google-services.json` 放到:

```text
app/google-services.json
```

这个文件已经在仓库 `.gitignore` 里忽略，不会被提交。

## 构建

```bash
./gradlew assembleDebug
```

首次成功构建后，debug apk 通常在:

```text
app/build/outputs/apk/debug/app-debug.apk
```

如果要生成可覆盖安装的固定签名 APK:

1. 把 `release.jks` 放在仓库根目录
2. 在仓库根目录新建 `keystore.properties`
3. 填入以下字段

```properties
storeFile=release.jks
storePassword=你的 keystore 密码
keyAlias=你的 alias
keyPassword=你的 key 密码
```

然后执行:

```bash
./gradlew assembleRelease
```

签名后的 release APK 通常在:

```text
app/build/outputs/apk/release/app-release.apk
```

如果要使用 GitHub Actions 生成固定签名 release APK，需要在仓库 Secrets 里配置:

```text
GOOGLE_SERVICES_JSON
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

其中 `ANDROID_KEYSTORE_BASE64` 可以这样生成:

```bash
base64 -w 0 release.jks
```

## 行为说明

- 普通用户只需要安装 app 并打开一次
- 想要覆盖更新，必须保持相同包名、相同签名，并且新包的 `versionCode` 不能低于已安装版本
- GitHub Actions 构建时会自动使用 `github.run_number` 作为 `versionCode`，便于后续覆盖更新
- app 会自动展示当前注册状态、失败原因、设备信息和 token 摘要
- app 会自动展示当前设备的 8 位前端配对码；把它填到前端 Android 页签，就能把设备绑定到那个浏览器
- 页面里的“已校验”只表示服务端凭证、包名和 token 可以通过 FCM `validateOnly` 校验，不代表真实消息已经从 FCM 下发到手机
- 如果服务端还没配置 `FIREBASE_SERVICE_ACCOUNT_JSON`，app 仍然会先完成“设备注册”，但会把“服务端连接检查失败”的原因显示出来
- 当前仓库的服务端还没有把正式的 FCM 下发接入到交易提醒周期里；这个 app 先解决“自动注册”和“免手工 token”问题

## 参考

- Firebase Android 设置: https://firebase.google.com/docs/android/setup
- Firebase Cloud Messaging Android 客户端: https://firebase.google.com/docs/cloud-messaging/android/client
- Android 13 通知权限: https://developer.android.com/develop/ui/views/notifications/notification-permission

## 社区支持

感谢 [LinuxDo](https://linux.do/) 各位佬的支持。欢迎加入 LinuxDo，这里有技术交流、AI 前沿资讯和实战经验分享。

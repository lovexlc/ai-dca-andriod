# AI DCA Android Notify

一个最小 Android 原生 app。

目标只有一件事: 用户安装后自动完成通知设备注册，不再让用户手动复制 token。

现在这套 app 会:

- 首次启动时自动获取 FCM registration token
- 自动把设备注册到 `https://tools.freebacktrack.tech/api/notify/gcm/register`
- 自动调用 `https://tools.freebacktrack.tech/api/notify/gcm/check` 做服务端连通性检查
- 自动调用 `https://tools.freebacktrack.tech/api/notify/gcm/pairing-key` 生成前端配对码
- 在 token 轮换时通过 `FirebaseMessagingService` 自动重注册
- 在 app 内展示当前注册状态、失败原因、设备信息、token 摘要和前端配对码

## 本地构建

你需要准备:

1. 一个 Firebase Android App
2. 对应的 `google-services.json`
3. JDK 17
4. Android Studio / Android SDK

把 `google-services.json` 放到:

```text
app/google-services.json
```

这个文件已经被 `.gitignore` 忽略，不会提交。

构建命令:

```bash
./gradlew assembleDebug
```

生成的 APK 通常在:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

仓库内已经启用一个现成的 Actions 工作流:

```text
.github/workflows/build-debug-apk.yml
```

它会做两类事:

- 普通 `push`、`pull_request`、手动触发: 编译 debug APK，并上传 artifact
- `v*` tag: 编译 debug APK，创建 GitHub Release，并把 APK 挂到 release assets

在 GitHub 仓库里先加一个 Actions secret:

- `GOOGLE_SERVICES_JSON`

内容就是完整的 `google-services.json` 文件文本。

工作流会在构建前自动把这个 secret 写回 `app/google-services.json`。

触发 release 的例子:

```bash
git tag v0.1.0
git push origin v0.1.0
```

推上去后，Actions 会自动创建同名 release，并上传 `ai-dca-android-notify-v0.1.0.apk`。

说明:

- 模板副本仍保留在 `workflow-templates/build-debug-apk.yml`
- 实际生效的工作流文件在 `.github/workflows/build-debug-apk.yml`

## 行为说明

生成的前端配对码是一次性的短时验证码，不是长期 API key。流程是：

1. Android app 自动注册设备并向 Worker 申请一个 8 位配对码
2. 用户在 Web 前端的 Android 页签输入这个配对码
3. Worker 把当前 Android 设备与那个浏览器的 `clientId` 保存成配对关系

这样用户不需要手动复制 FCM token，也不需要在 app 里自己生成或保存 API key。

- 普通用户只需要安装 app 并打开一次
- 如果服务端还没配置 `FIREBASE_SERVICE_ACCOUNT_JSON`，app 仍然会先完成“设备注册”，但会在状态页明确显示“服务端连接检查失败”的原因
- 当前后端还没有把正式 FCM 下发接入到交易提醒周期；这个仓库先解决“自动注册”和“免手工 token”问题

## 参考

- Firebase Android 设置: https://firebase.google.com/docs/android/setup
- Firebase Cloud Messaging Android 客户端: https://firebase.google.com/docs/cloud-messaging/android/client
- Android 13 通知权限: https://developer.android.com/develop/ui/views/notifications/notification-permission

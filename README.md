# AI DCA Android Notify

一个最小 Android 原生 app。

目标只有一件事: 用户安装后自动完成通知设备注册，不再让用户手动复制 token。

现在这套 app 会:

- 首次启动时自动获取 FCM registration token
- 自动把设备注册到 `https://tools.freebacktrack.tech/api/notify/gcm/register`
- 自动调用 `https://tools.freebacktrack.tech/api/notify/gcm/check` 做服务端连通性检查
- 在 token 轮换时通过 `FirebaseMessagingService` 自动重注册
- 在 app 内展示当前注册状态、失败原因、设备信息和 token 摘要

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

仓库内已经带了一个现成的 Actions 工作流模板:

```text
workflow-templates/build-debug-apk.yml
```

把它移动到:

```text
.github/workflows/build-debug-apk.yml
```

之后它会做两类事:

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

- 我这边创建仓库用的现有 PAT 没有 `workflow` scope，所以 GitHub 不允许我直接把文件推到 `.github/workflows/`
- 模板内容已经完整放进仓库；你在 GitHub Web 或本地移动到目标路径后就能直接用

## 行为说明

- 普通用户只需要安装 app 并打开一次
- 如果服务端还没配置 `FIREBASE_SERVICE_ACCOUNT_JSON`，app 仍然会先完成“设备注册”，但会在状态页明确显示“服务端连接检查失败”的原因
- 当前后端还没有把正式 FCM 下发接入到交易提醒周期；这个仓库先解决“自动注册”和“免手工 token”问题

## 参考

- Firebase Android 设置: https://firebase.google.com/docs/android/setup
- Firebase Cloud Messaging Android 客户端: https://firebase.google.com/docs/cloud-messaging/android/client
- Android 13 通知权限: https://developer.android.com/develop/ui/views/notifications/notification-permission

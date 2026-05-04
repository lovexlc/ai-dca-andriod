# AGENTS.md — ai-dca-andriod

本文件给自动化代理（Codex / Claude / Cursor 等）读取。请务必在改动版本号、改 release 命名、改 workflow 之前先看完。

## 项目身份

- 仓库名：`lovexlc/ai-dca-andriod`（注意 `andriod` 是历史拼写，不要修正）。
- 应用展示名：**基金通知**（`app/src/main/res/values/strings.xml` 的 `app_name`）。
- Java 包名 / `applicationId`：`tech.freebacktrack.aidca`（**绝对不要改**，改了会与已发布 APK 升级冲突）。
- Gradle 项目名：`ai-dca-android`（`settings.gradle.kts`）。
- 推送服务通信端：Cloudflare Worker 仓库 `lovexlc/ai-dca`，`workers/notify`，API Base `https://tools.freebacktrack.tech/api/notify`。

## 版本号规则（重要）

版本号格式：`MAJOR.MINOR.PATCH`，由两部分拼接：

```
versionName = ${APP_VERSION_BASE}.${GITHUB_RUN_NUMBER}
              └── 人手动维护 ──┘   └── CI 自动递增 ─┘
```

- **`APP_VERSION_BASE`**（=`MAJOR.MINOR`）写死在 `.github/workflows/build-debug-apk.yml` 的「Compute App Version」步骤里，当前值 `"1.0"`。
- **`PATCH`** 直接取 `GITHUB_RUN_NUMBER`，每次 push 到 `main` 自动递增，不需要也不应该手动改。
- 也就是说：每次合到 main，patch 自动 +1，例如 `1.0.36 → 1.0.37 → …`。

### 什么时候改 base、什么时候不改

| 场景 | 改 `APP_VERSION_BASE` 吗？ | 例子 |
| --- | --- | --- |
| Bug 修复、文案微调、UI 小改、依赖升级、内部重构 | **不改**，让 patch 自动增长 | `1.0.41 → 1.0.42` |
| 新增/调整一个用户能感知的功能（如新设置项、新通知类型） | **改 minor**：`1.0` → `1.1`，patch 从下一次 run number 开始 | `1.0.50 → 1.1.51` |
| 重大重构、不向后兼容的改动、UI/交互大翻新、协议升级 | **改 major**：`1.0` → `2.0` | `1.x.y → 2.0.N` |

改动只需要修改 workflow 里这一行：

```yaml
APP_VERSION_BASE="1.0"   # ← 只改这里
```

本地构建（不经过 CI）默认 versionName 为 `1.0.0`，由 `app/build.gradle.kts` 的 `configuredVersionName ?: "1.0.0"` 提供，无需手动改。

### 注入链路

CI → Gradle 通过环境变量 `ANDROID_VERSION_NAME`：

1. workflow 计算出 `APP_VERSION` 后，写入 `GITHUB_ENV`：
   ```bash
   echo "ANDROID_VERSION_NAME=${APP_VERSION}" >> "${GITHUB_ENV}"
   ```
2. `app/build.gradle.kts` 里 `propertyValue("androidVersionName", "ANDROID_VERSION_NAME")` 读到该值，赋给 `versionName`。
3. 同一份 `APP_VERSION` 也用于 release 标题、tag、APK 文件名。

不要绕过这条链路、不要在 gradle 里硬编码 versionName。

## Release 命名规则

main 分支 push 触发的自动 release：

- **Title**：`基金通知 v${APP_VERSION}`，例如 `基金通知 v1.0.36`
- **Tag**：`app-v${APP_VERSION}`，例如 `app-v1.0.36`
  - 故意不用 `v*` 前缀，避免触发 workflow 中 `tags: ["v*"]` 的二次构建循环。
- **APK 文件名**：`jijin-notify-v${APP_VERSION}-${SHORT_SHA}.apk`
- **Notes 文件**：`release-notes-v${APP_VERSION}.md`，自动包含 commit 区间的 changelog。

手动打 `v*` tag 触发的 release（保留逻辑，目前少用）：

- Title 与 tag 一致（如 `v1.2.0`）。
- APK：`ai-dca-android-notify-${tag}-debug.apk` / `-release.apk`。

## 旧 release 清理策略

用户偏好「只保留最新一个 release」。当出现以下任一情形时，主动删除旧的 release 与 tag：

- 切换命名方案（如把 `main-build-*` 全清掉，迁移到 `app-v*`）。
- 用户明确要求「移除除 latest 外的其他 tag」。

清理命令模板：

```bash
export GH_TOKEN=$(awk -F'[:@]' '/github.com/{print $3; exit}' ~/.git-credentials)
gh release list --repo lovexlc/ai-dca-andriod --limit 100
gh release delete <tag> --repo lovexlc/ai-dca-andriod --cleanup-tag --yes
```

不要主动删除 latest（GH 标记为 `Latest` 的那个）。

## 不要随便动的清单

以下值如果改动，会破坏既有用户体验或升级链路：

- `applicationId` / `namespace`：`tech.freebacktrack.aidca`
- 通知 channel id：`ai_dca_messages`
- SharedPreferences 名：`ai_dca_notify_state`
- WorkManager 名：`ai-dca-heartbeat`
- BuildConfig `NOTIFY_BASE_URL`：`https://tools.freebacktrack.tech/api/notify`
- FCM data key：`body_md`（worker → app 双方约定，详见下文 Markdown 推送链路）

## Markdown 推送链路

通知正文支持 Markdown，链路：worker → FCM `data` → Android 渲染。

- worker 在 `notification.body_md` 字段里产出 markdown 字符串。
- `workers/notify/src/evaluator.js` 的 `deliverNotification` 把它放进 FCM `data.body_md`，所有事件分支（test / plan / dca / switch / holdings）都会带上。
- Android `NotifyMessagingService` 读 `message.data["body_md"]`，交给 `MarkdownRenderer.render(...)` 渲染成 `Spannable`，用于通知 BigText 展开 + 历史详情页。
- `MarkdownRenderer` 支持子集：`**bold**`、`*italic*` / `_italic_`、`~~strike~~`、`` `code` ``、`# / ## / ###` 标题、`- /  *` 无序列表、`1.` 有序列表、`[text](url)` 链接。识别不到的语法按纯文本保留，不丢字符。
- 历史消息持久化字段：`NotificationMessageRecord.bodyMd`（JSON key `bodyMd`）。

如果新增推送类型，记得在 worker 端的 builder 里同步产出 `body_md`，否则 Android 会回退到纯文本 `body`。

## 提交 / 推送约定

- 直接在 `main` 上提交即可（个人项目，无需 PR 流程）。
- commit message 用 conventional commits 风格：`feat(android):` / `fix(notify):` / `chore(release):` 等。
- 推送前不需要本地跑 `./gradlew assembleDebug`，CI 会跑；但改 gradle / workflow 时可以用 `node --check` 校验 worker JS、用 `grep` 自检关键字段。
- GitHub 凭据从 `~/.git-credentials` 取：
  ```bash
  export GH_TOKEN=$(awk -F'[:@]' '/github.com/{print $3; exit}' ~/.git-credentials)
  ```

## 给自动化代理的判断指引

当用户说「发个新版本 / 出包 / push 一下」时：

1. **不要手动改 `versionName`、`versionCode`、release tag**。直接 `git commit && git push origin main`，CI 会自动算 patch 号、出 release、上传 APK。
2. 当用户说「这是个大更新 / 跨版本 / minor 升一级」时，去 `.github/workflows/build-debug-apk.yml` 改 `APP_VERSION_BASE`，再 push。
3. 当用户说「清掉旧 release」「只留最新」时，按上面的「旧 release 清理策略」执行。
4. 不确定当前应该用哪个 base 时，看一下 `git log` 里最近的「feat / breaking」commit 数量，或者直接问用户。

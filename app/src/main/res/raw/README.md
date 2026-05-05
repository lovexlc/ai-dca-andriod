# res/raw/ - 推送铃声资源

把 `.mp3` (或 `.wav` / `.ogg`) 文件直接拖入此目录,Android 编译时会自动把它注册成 raw 资源。

## 命名约定

- 文件名只能用 **小写字母 + 数字 + 下划线** (Android 资源命名限制)
- 文件名不带扩展名就是对外暴露的 `sound` 标识
- 例: `app/src/main/res/raw/minuet.mp3` → URL `?sound=minuet`

## 调用方式

通过 Bark 风格 URL 传 `?sound=<name>`:

```
https://tools.freebacktrack.tech/api/notify/bark/<deviceInstallationId>/推送内容?sound=minuet
```

或在 App 「设置」tab → 「推送铃声」中选择默认铃声(还未实现 per-channel 默认应用,选择只是记录用户偏好,实际生效仍以 URL 参数为准)。

## 落地行为

- `NotifyMessagingService.ensureChannel` 会用 `resources.getIdentifier(soundKey, "raw", packageName)` 解析 raw 资源
- 命中: `setSound(android.resource://<pkg>/<resId>, AudioAttributes)`,无论 level/call 如何均生效
- 未命中且 level=critical 或 call=1: 回退到系统默认 alarm uri
- 未命中且为普通 level: 不显式 setSound,使用 channel 默认

## 注意

- 通知 channel ID 包含 sound 名,所以同名不同文件会复用同一个 channel
- 一旦某个 channel 已经创建,后续再改 setSound 无效(系统不会更新已有 channel)。如果要强制切换,需要 uninstall 重装或换 channel ID(在 sound 名上加版本号)
- 添加文件后必须重新编译 (CI 自动) 才会随包发布

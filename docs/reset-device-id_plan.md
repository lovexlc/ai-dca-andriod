# 重置设备唯一 ID + 换 KV 绑定

## 目标
Android app 设备 tab 的设备信息卡 → 「复制」按钮改为「重置」。点击后：
1. 用户确认弹窗。
2. 本地生成新 `deviceInstallationId`（`android-<uuid>`）。
3. 通知 Worker 把 KV `settings.gcmRegistrations` 里旧 id 那条记录原地改名（保留 token / pairedClients / projectId 等绑定关系），删掉可能撞名的新 id 孤儿条目。
4. Worker 成功后，本地 SharedPreferences 写入新 id，再跑一次注册心跳让 Worker 拿到最新 updatedAt。
5. 失败回滚：本地不改 id，提示用户。

## Worker (ai-dca/workers/notify)

### `POST /api/notify/gcm/reset-device-id`
- Body: `{ oldDeviceInstallationId, newDeviceInstallationId, token }`
- 鉴权: `findGcmRegistration(settings, { deviceInstallationId: oldId, token })` + 验证 `reg.token === token`（仿 `handleGcmUnpairFromDevice`）。
- 防撞: 若 `newDeviceInstallationId` 已存在另一条记录，认为是无效孤儿，从 list 里 filter 掉。
- 主操作: spread 旧 reg → 覆盖 `id` / `deviceInstallationId` 为新值，刷新 `updatedAt`；其它字段（token / pairedClients / pairingCode* / projectId / ...）原样保留。
- 注意: WsHub 是 Durable Object，命名空间 = deviceInstallationId。旧 DO 不显式销毁，自然冷却；新 id 的 DO 在首次连接时按需创建。
- 输出: `{ ok, registration: buildPublicGcmRegistration(...) }`。
- 路由: 在 `fetch` handler 现有 `handleGcmRegister` / `handleGcmCheck` 路由附近加 `if POST /api/notify/gcm/reset-device-id`。

## Android (ai-dca-andriod)

### `DeviceInstallationStore`
- 新增 `replace(context, newId)`：直接写入 SharedPreferences `device_installation_id` 为给定 newId。
- 新增 `generateRandomId()`: 始终返回 `android-${UUID.randomUUID()}`，不读 ANDROID_ID（用户重置就是想换 id）。

### `RegistrationRepository.resetDeviceInstallationId(context, callback)`
- executor.execute:
  1. `oldId = DeviceInstallationStore.getOrCreate(ctx)`
  2. `newId = DeviceInstallationStore.generateRandomId()`
  3. `token = fetchTokenBlocking(ctx)`
  4. POST `${NOTIFY_BASE_URL}/gcm/reset-device-id` `{ oldDeviceInstallationId, newDeviceInstallationId, token }`
  5. 成功后 `DeviceInstallationStore.replace(ctx, newId)`
  6. 再跑 `safeRegisterCurrentToken(ctx, token, "reset-device-id")` 让 Worker 拿到最新心跳
  7. mainHandler.post { callback(true, "", snapshot) }
- 失败: 不改本地 id，callback(false, msg, currentSnapshot)。

### MainActivity
- `setupDeviceIdentityActions()`：现有按钮（id 保留 `copyDeviceInstallationIdButton` 避免 layout/findViewById 全改）改为弹 AlertDialog 确认 → 调用 `RegistrationRepository.resetDeviceInstallationId(...)`。
- 期间禁用按钮 + 显示「正在重置…」Toast。
- 成功回调里调用 `renderSnapshot(snapshot)` 刷新 UI 显示新 id。

### strings.xml
- 新增:
  - `action_reset_device_id` = 「重置」
  - `device_installation_id_reset_confirm_title` = 「重置设备唯一 ID？」
  - `device_installation_id_reset_confirm_message` = 「会生成一个新的唯一 ID，浏览器绑定/历史推送规则自动续到新 ID。」
  - `device_installation_id_reset_in_progress` = 「正在重置…」
  - `device_installation_id_reset_success` = 「已重置」
  - `device_installation_id_reset_failed_prefix` = 「重置失败：%1$s」
- 保留 `action_copy_device_id` / `device_installation_id_copied` 防止其它地方引用，但 layout 不再用到。

### layout/activity_main.xml
- 第 586 行 `android:text="@string/action_copy"` 改为 `@string/action_reset_device_id`。

## 验证
- 编译走 GitHub Actions（不本地 build）
- 后端走 ai-dca worker deploy Action
- 手动验证: 装新版 app → 点重置 → 看 toast 成功 → 设备页面 deviceInstallationIdTextView 已变 → 网页 Notify 页绑定关系仍在 (pairedClients 没丢)

## 提交
- ai-dca: `feat(notify): add gcm/reset-device-id endpoint for rebinding KV registration`
- ai-dca-andriod: `feat(android): replace copy with reset on device id + rebind worker KV`

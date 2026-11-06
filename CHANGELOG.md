# Changelog / 更新记录

All notable changes to this project are recorded here, newest first. Each released version also has an APK and a fuller write-up under `snapshots/v<version>/`.

本项目的重要变更都记在这里，新的在上面。每个已发布的版本在 `snapshots/v<版本>/` 下另有 APK 与更详细的说明。

Format follows [Keep a Changelog](https://keepachangelog.com/); version numbers follow `0.x` while the app is pre-1.0.

格式参考 Keep a Changelog；1.0 之前版本号保持 `0.x`。

---

## [Unreleased] / 未发布

### Changed / 变更

- **Removed a machine-specific path from `gradle.properties`.** The file used to pin `android.aapt2FromMavenOverride` to an absolute path on the development machine (`<local>/AndroidDev/sdk/...`). That override is genuinely needed there — without it a clean build dies on `stableIds.txt: failed to open: 数据无效 (13)` — but committed to the repository it becomes a broken reference for anyone else, and on a different layout it could even point at the wrong binary. The line now lives in the developer's **user-level** `gradle.properties`, and the README explains how to apply the same workaround locally. Project-level `gradle.properties` is back to generic settings only.
  **从 `gradle.properties` 去掉了一条本机绝对路径。** 该文件原先把 `android.aapt2FromMavenOverride` 钉死在本机的绝对路径上。这条覆盖项在本机确实必需——没有它 clean 构建会挂在 `stableIds.txt: failed to open: 数据无效 (13)`——但提交进仓库后，对其他人就是一个失效引用，换一种目录布局还可能指向错误的二进制。现在这条配置放在开发者的**用户级** `gradle.properties` 里，README 说明了如何在本地自行配置。项目级 `gradle.properties` 恢复为只放通用配置。
- Verified with a full `clean` + `assembleDebug`: BUILD SUCCESSFUL, and the resulting APK is content-identical to the v0.31 archive (108 entries, all CRC32 and total compressed size equal). No version bump, no new snapshot.
  已用完整的 `clean` + `assembleDebug` 验证：BUILD SUCCESSFUL，产物与 v0.31 存档内容一致（108 个条目，全部 CRC32 与压缩后总计相等）。未升版本号，未重做快照。

---

## [0.31] — 2026-11-03

### Changed / 变更

- **The minimum-brightness floor is a raw control value now (0–255), not a percentage.** The old label was wrong: the value written to `Settings.System.SCREEN_BRIGHTNESS` is a control word, and the system slider is a perceptual scale (`position ≈ 100 × √(value / 255)`), so a control value of 64 sits at about half the bar, not at 25%. The status row now shows the control value plus an estimated bar reading ("about N"), with no percent sign.
  **最低亮度下限改为原始控制值（0–255），不再是百分比。** 原来的标法是错的：写进设置项的是控制字，而系统亮度条是感知刻度，控制值 64 只走到条的一半，不是 25%。状态行现在显示控制值 + 估算的亮度条读数（标注「约」），不再带百分号。
- Input validation widened from 0–100 to 0–255; hint and toasts reworded accordingly.
  输入校验从 0–100 放宽到 0–255；提示语与 Toast 同步改写。

### Added / 新增

- Automatic migration of the stored floor: the old percentage key is converted to the equivalent control value on first read (`percent / 100 × 255`), so an existing setting does not suddenly become dimmer or brighter. Verified on a real device: `8` (%) → `20`.
  已有下限自动迁移：旧百分比键在首次读取时换算为等价控制值（`百分比 / 100 × 255`），不会突然变暗或变亮。真机实测 `8`（%）→ `20`。
- `BrightnessGuard.estimateBarPercent()` — converts a control value to an estimated slider position. Display only; never used for the threshold decision.
  `BrightnessGuard.estimateBarPercent()`：把控制值换算成估算的亮度条位置。仅用于显示，不参与判定。

### Fixed / 修复

- Removed two methods that nothing called (`isAutoBrightnessOn`, `readCurrentPercent`) and an unused string resource.
  删除两个无人调用的方法和一个失效的资源。

### Docs / 文档

- Measured slider mapping table, panel nits curve, and the HBM transition constant (`0.37486267`) added to the README.
  README 增加亮度条映射实测表、面板尼特曲线、HBM 切换点常数。
- Documented the interaction with auto-brightness: any manual brightness write activates Android's short-term model, which pauses auto-brightness for up to 5 minutes. Deliberately **not** resetting it — see the README for why resetting would cause a per-second fight.
  记录了与自动亮度的交互：任何手动写亮度都会触发 Android 短期亮度模型，自动亮度暂停最多 5 分钟。**刻意不重置**——README 里写了为什么重置会导致每秒震荡。

---

## [0.3] — 2026-11-02

### Added / 新增

- **Bilingual UI.** All user-visible text extracted into resources: `values/strings.xml` (English, default) and `values-zh-rCN/strings.xml` (Simplified Chinese only). Traditional Chinese and every other locale fall back to English.
  **中英双语界面。** 全部文案抽到资源里：`values/strings.xml`（英文，默认）与 `values-zh-rCN/strings.xml`（仅简体中文）。繁体中文与其他语言一律回落英文。
- Date and time formats follow the language via resource patterns rather than a locale `if`: `2026年11月2日 星期一` / `下午 12:13:46` versus `2026-11-02 Monday` / `12:13:46 PM`.
  日期时间格式通过资源串随后语言切换，不是用 if 判断：中文与英文各一套。

### Changed / 变更

- `versionCode` 3, `versionName` `0.3.0`. No functional change in this release.
  `versionCode` 3、`versionName` `0.3.0`。本版功能逻辑零改动。

---

## [0.2] — 2026-11-02

### Changed / 变更

- **Dropped `am task lock` (LockTask).** It works at shell level and blocks Back / Home / gestures / status bar — but LockTask internally calls `lockNow()`, so it locks the screen along with the task. On a device with a password lock, `wm dismiss-keyguard` cannot undo it, and every break ended on the lock screen.
  **放弃 `am task lock`（LockTask）。** 它在 shell 身份下确实能拦住返回、Home、手势与状态栏，但它内部会调用 `lockNow()`，锁任务的同时锁屏。有密码锁的设备无法自动解锁，每次休息都停在锁屏。
- Replaced with **full-screen overlay + re-attach on foreground change**: the overlay re-mounts itself whenever the foreground app changes, so it stays on top for the whole break. Cost: the original app gets pushed to the background.
  改为**全屏浮层 + 前台变化即重新置顶**：前台一变就把浮层重新挂到最上层，整个休息期间压在上面。代价是原应用被切到后台。
- Overlay duration 20 s → 25 s; screen kept awake with a time-limited `SCREEN_BRIGHT_WAKE_LOCK` because `FLAG_KEEP_SCREEN_ON` does not work on accessibility overlays.
  浮窗时长 20 → 25 秒；用限时 `SCREEN_BRIGHT_WAKE_LOCK` 保持亮屏，因为 `FLAG_KEEP_SCREEN_ON` 对无障碍浮层无效。
- Project renamed: ScreenBreak → Eye strain relief, package `com.morgangz.screenbreak` → `com.eyestrainrelief`.
  项目改名：ScreenBreak → Eye strain relief，包名同步更换。

### Added / 新增

- **Call protection** — an incoming call dismisses the overlay immediately (falls back to matching the call-UI package name when the phone-state permission is missing).
  **来电保护**：来电立即撤掉浮层；无电话状态权限时降级为匹配来电界面包名。
- **Gaze sampling** — every 30 s; two consecutive "no one there" verdicts freeze the timer.
  **注视采样**：每 30 秒一次，连续两次判定无人注视则冻结计时。
- **Minimum-brightness guard** — pulls brightness back up when it drops below the configured floor.
  **最低亮度守卫**：低于设定下限时自动拉回。
- **Clock on the overlay** — date line plus a 12-hour time line with seconds.
  **浮窗时钟**：日期一行 + 12 小时制时间一行，精确到秒。
- **End alert via the system notification sound and a double short vibration, without posting any notification.**
  **结束提示改用系统通知铃声 + 双短震，不产生任何通知对象。**
- Removed the bundled audio; nothing is shipped in `res/raw/` any more.
  删除了自制音频，`res/raw/` 不再打包任何声音文件。

### Fixed / 修复

- Accessibility service watchdog: checks whether the timing service is alive on the back of accessibility events (30 s throttle) and restarts it unconditionally when missing. The earlier "is the singleton null" check was unreliable — after `am stopservice` the reference was not cleared, so the watchdog thought the service was alive.
  无障碍看门狗：借无障碍事件检查计时服务存活（30 秒节流），发现缺失就无条件重启。原先用「单例是否为 null」判断不可靠——`am stopservice` 后引用没有清空，看门狗误判其存活。
- Media pause/resume goes through `cmd media_session dispatch` and checks `list-sessions` before resuming, so it no longer wakes a stale player widget on the home screen.
  媒体暂停/恢复走 `cmd media_session dispatch`，恢复前先 `list-sessions`，不再把桌面上遗留的播放器小部件唤醒。

### Stability / 稳定性

- Doze whitelist written on start-up (`cmd deviceidle whitelist +<pkg>`), persisted by the system.
  启动时写入 Doze 白名单，由系统持久化。
- `stopWithTask=false` so clearing the task does not stop the timing service.
  `stopWithTask=false`，清任务不停止计时服务。
- Hidden from the recents list via `setExcludeFromRecents`.
  通过 `setExcludeFromRecents` 从最近任务隐藏。

---

## [0.1] — 2026-10-29

Initial working skeleton: screen-on accumulation, full-screen break overlay with countdown, emergency exit by pressing volume-down five times. The overlay relied on `am task lock` at this point — see 0.2 for why that was dropped.

最初的可用骨架：亮屏累计、全屏休息浮层与倒计时、音量下键连按 5 次紧急退出。此时浮层依赖 `am task lock`——放弃原因见 0.2。

---

## Known limitations / 已知限制

Carried across versions; see `README.md` for the current list and `snapshots/v<version>/VERSION.md` for the state at each release.

各版本通用，当前清单见 `README.md`，每个版本当时的状态见 `snapshots/v<版本>/VERSION.md`。

1. Full-screen navigation gestures cannot be intercepted — the system consumes them above every app window.
   全面屏手势无法拦截——系统在所有应用窗口之上消费手势。
2. Silent and DND modes still vibrate (sound follows the system policy, vibration does not).
   静音与勿扰仍会震动（声音遵循系统策略，震动不跟随）。
3. Gaze detection is presence-level ("is a face nearby"), not eye tracking.
   注视检测是「人脸在场」级别，不是眼动追踪。
4. Shizuku does not survive a reboot in wireless-debugging mode.
   Shizuku 在无线调试模式下重启后不运行。
5. Auto-brightness pauses for up to 5 minutes after any manual brightness change — Android's own short-term model behaviour, not a defect.
   手动改过亮度后自动亮度暂停最多 5 分钟——Android 自身的短期亮度模型行为，不是缺陷。

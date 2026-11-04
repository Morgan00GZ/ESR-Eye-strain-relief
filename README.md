# ESR — Eye Strain Relief

Built on the 20-20-20 rule: every 20 minutes, look 20 feet (6 m) away for 20 seconds to relax the ciliary muscle. ESR enforces it with a full-screen break overlay, gaze detection and a minimum-brightness guard. No root required.

基于 20-20-20 法则：每看屏幕 20 分钟，看 20 英尺（6 米）外至少 20 秒，让睫状肌松弛。用全屏浮窗强制执行，支持注视检测与最低亮度保护。无需 root。

---

## What it does / 它做什么

ESR accumulates screen-on time. Once the total reaches a configurable threshold (default 20 minutes), it covers the entire screen with a rest overlay and a countdown. The overlay sits above the status bar and navigation bar, so it cannot be dismissed by pulling down quick settings or swiping back. When the countdown ends, the overlay disappears and the timer restarts from zero.

ESR 累计亮屏时长。达到可配置阈值（默认 20 分钟）后，用一个全屏休息浮窗盖住界面并开始倒计时。浮窗层级高于状态栏与导航栏，下拉通知栏、侧滑返回都无法绕过。倒计时结束浮窗自动消失，计时从零重新开始。

---

## Features / 功能

| Feature | 功能 | Notes / 说明 |
|---|---|---|
| Screen-on accumulation | 亮屏累计 | Counts only while the screen is on. Brief screen-off (< 20 s) keeps the total; longer resets it. 只统计亮屏时间；短暂熄屏（< 20 秒）保留累计，更久则清零 |
| Full-screen overlay | 全屏浮窗 | Covers status bar, navigation bar and display cutout. 覆盖状态栏、导航栏与挖孔区域 |
| Countdown 25 s (adjustable) | 倒计时 25 秒（可调） | Large digits, plus date and 12-hour time with seconds. 大号数字，另有日期与精确到秒的 12 小时制时间 |
| Pause media | 暂停活动 | Pauses the active media session so audio/video does not keep running behind the overlay. 暂停当前播放的媒体，避免浮窗后面还在放音视频 |
| Gaze detection | 注视检测 | Samples every 30 s; two consecutive "no gaze" results freeze the timer. 每 30 秒采样一次，连续 2 次判定无人注视则冻结计时 |
| Minimum brightness guard | 最低亮度保护 | Pulls screen brightness back up when it drops below a configurable floor. 亮度低于设定下限时自动拉回 |
| End alert | 结束提示 | Plays the system notification sound and vibrates twice — **without posting any notification**. 播放系统通知铃声并双短震，**不产生任何通知对象** |
| Emergency exit | 紧急脱出 | Press volume down 5 times while the overlay is shown. 浮窗期间连按音量下键 5 次 |
| Call protection | 来电保护 | Overlay is dismissed immediately when a call starts ringing. 响铃时立即撤掉浮窗 |
| Hidden from recents | 不显示在最近任务 | Keeps the launcher's task switcher clean. 保持最近任务列表干净 |

---

## How it works / 实现路径

### 1. Timing model / 计时模型

The timer stores **timestamps**, not a running counter. Every query settles the elapsed interval since the last settle point, so a delayed or dropped callback never causes drift. It is evaluated lazily: no periodic wake-up, no polling.

计时器存的是**时间戳**而不是累加器。每次查询时把「上次结算点到现在」的区间结算进总量，因此任何一次回调延迟或丢失都不会造成累积误差。取值是惰性的：没有周期任务，没有轮询。

- `SessionTimer.settle()` — settle elapsed time into `screenMs` / 结算区间
- `SessionTimer.onScreenOn/Off()` — screen state transitions / 屏幕状态切换
- Screen-off duration ≥ 20 s resets the total, otherwise it is preserved / 熄屏达到 20 秒清零，否则保留

### 2. Overlay window / 浮窗窗口

Uses `TYPE_ACCESSIBILITY_OVERLAY`, which is the only window type available to a normal app that can draw above the status bar. Added via `WindowManager.addView()` from the accessibility service.

使用 `TYPE_ACCESSIBILITY_OVERLAY`——这是普通应用能拿到的、层级高于状态栏的唯一窗口类型。由无障碍服务通过 `WindowManager.addView()` 添加。

- `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS` — extend under system bars / 铺满到系统栏下方
- `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` — cover the notch area / 覆盖挖孔区域
- The container is `clickable` so touches are consumed instead of reaching the app below / 容器设为可点击，触摸被消费而不会穿透到下层应用

### 3. Why not `am task lock` / 为什么不用系统级锁定

The first implementation used `am task lock` (LockTask) via Shizuku. It blocked **everything** — back, home, gestures, status bar. But on HyperOS it also called `WindowManagerService.lockNow()` internally, locking the screen together with the task. On a device with a password, `wm dismiss-keyguard` cannot undo that, so every break ended on the lock screen.

最初的实现通过 Shizuku 执行 `am task lock`（LockTask）。它确实能拦住一切——返回、Home、手势、状态栏。但在 HyperOS 上它内部会连带调用 `WindowManagerService.lockNow()`，把屏幕一起锁上。有密码的设备 `wm dismiss-keyguard` 无法解除，于是每次休息结束都停在锁屏。

Also tried and rejected / 其他试过并放弃的方案:

| Attempt | Result |
|---|---|
| `am task focus <id>` | Prints "Setting focus to task", UI does not move / 打印成功但界面不动 |
| `am start --activity-reorder-to-front` | Blocked by HyperOS background-launch restriction, shell is not exempt / 被 HyperOS 后台弹出限制拦截，shell 也不例外 |
| `wm dismiss-keyguard` | No effect once the keyguard is showing / 锁屏后无效 |

**Current approach / 现方案**: don't lock, don't pull the app back. Cover the screen with the overlay, and whenever the foreground window changes, re-attach the overlay (`removeViewImmediate` + `addView`) so it stays on top. The user can still leave the app, but sees and touches only the overlay for the full 25 seconds.

**不锁定、不拉回**。用浮窗盖住屏幕，监听前台窗口变化，一旦切换就重新挂载浮窗（`removeViewImmediate` + `addView`）把它压回最上层。用户仍能离开应用，但这 25 秒内看到、碰到的都只有浮窗。

### 4. Gaze detection / 注视检测

Xiaomi devices expose `attention` (`android.attention.IAttentionManager`), implemented by `com.xiaomi.aon`. It can be triggered from shell:

小米设备提供 `attention` 服务（`android.attention.IAttentionManager`），实现者是 `com.xiaomi.aon`，可从 shell 触发：

```
cmd attention call checkAttention            # dispatch a check / 派发一次检测
cmd attention getLastTestCallbackCode        # read the result / 读取结果
dumpsys attention                            # cached history / 历史缓存
```

Return codes measured on a real device / 真机实测返回码:

| Code | Meaning | Measured |
|---|---|---|
| `1` | User is looking at the screen / 正在注视 | Returning `1` while staring at the phone |
| `3` | Not looking / 未注视 | Consistently `3` after moving the face away |

Each check takes about **5 seconds**. Firing faster than every 5 s returns `3` because the new request overwrites the unfinished one — so 5–6 s is the practical floor. ESR samples every **30 s** to keep power use reasonable.

单次检测约 **5 秒**。触发间隔短于 5 秒会返回 `3`（新请求覆盖未完成的旧请求），因此 5–6 秒是可用下限。ESR 实际每 **30 秒**采样一次，以控制功耗。

**Important limitation / 重要限制**: this detects *whether a face is present*, not *where the eyes look*. Sitting at a desk looking at a monitor with the phone beside you still reports `1`. Do not treat it as eye tracking.

**重要限制**：它判断的是「人脸是否在附近」，不是「视线落在哪里」。坐在桌前看显示器、手机放在旁边，同样会返回 `1`。不要把它当成眼动追踪。

### 5. Brightness guard / 亮度守卫

A `ContentObserver` watches `Settings.System.SCREEN_BRIGHTNESS`. When the value drops below the configured floor, it is written back. Event-driven, no polling.

用 `ContentObserver` 监听 `Settings.System.SCREEN_BRIGHTNESS`，低于设定下限时写回。事件驱动，无轮询。

- Preferred path: `Settings.System.putInt()` with `WRITE_SETTINGS` granted / 优先直接写，需 `WRITE_SETTINGS`
- Fallback: `settings put system screen_brightness N` through Shizuku / 退化路径：经 Shizuku 执行命令
- Idempotent — the write triggers the observer again, but the value is already at the floor / 幂等：写回会再次触发监听，但此时已达标直接返回

**The floor is a raw control value, not a percentage / 下限是原始控制值，不是百分比**

`SCREEN_BRIGHTNESS` ranges from 0 to 255 on the tested device (`config_screenBrightnessSettingMaximum = 255`, minimum `10`), but the system slider is **not** linear in that value. Measured on the real device (HyperOS 4) by pixel-measuring the slider:

| Control value | 20 | 40 | 64 | 100 | 128 | 200 |
|---|---|---|---|---|---|---|
| Slider position | 32% | 40% | 49% | 60% | 69% | 84% |

This fits `position ≈ 100 × √(value / 255)`. So a control value of 64 sits at about half of the slider, not at 25%. The panel curve is even steeper: `dumpsys display` reports 0.037 → 59.8 nits, 0.5 → 800 nits, 1.0 → 3500 nits, with 3500 only reachable through HBM in bright ambient light.

Because of this, the UI stores and shows the **control value** and displays an estimated slider position next to it, rather than pretending it is a percentage. The estimate uses the fitted curve above and is labelled "about" — on stock Android, where the slider is linear, it will read high.

`SCREEN_BRIGHTNESS` 在实测机型上取值 0–255（`config_screenBrightnessSettingMaximum = 255`，最小 `10`），但系统亮度条与这个值**不成线性**。在真机上对亮度条做像素级实测：

| 控制值 | 20 | 40 | 64 | 100 | 128 | 200 |
|---|---|---|---|---|---|---|
| 滑块位置 | 32% | 40% | 49% | 60% | 69% | 84% |

拟合为 `位置 ≈ 100 × √(值 / 255)`。也就是说控制值 64 大约在亮度条一半的位置，而不是 25%。面板本身的曲线更陡：`dumpsys display` 显示 0.037 → 59.8 尼特、0.5 → 800 尼特、1.0 → 3500 尼特，其中 3500 只有在强光下的 HBM 才可达。

因此界面存的和显示的都是**控制值**，旁边附一个估算的亮度条位置，不宣称它是百分比。估算用的是上面这条拟合曲线，标注为「约」；在原生 Android（线性刻度）上读数会偏高。

**Known interaction with auto-brightness / 与自动亮度的已知交互**

When auto-brightness is on, pulling the floor back is itself a manual brightness change, and Android responds by activating a **short-term model**: the auto curve is anchored at the current ambient lux and stops tracking ambient light. Measured on the tested device:

| Field | Value |
|---|---|
| `mWasShortTermModelActive` | `true` after any manual write |
| `shortTermModelTimeout` | `300000` ms (5 minutes) |
| `shortTermModelLower/UpperLuxMultiplier` | `0.6` |
| `mAmbientLux` / `mLastObservedLux` | still updating (sensor is fine) |
| `BrightnessEvent` log | no new entries while the model is active |

So during those 5 minutes the screen stops adapting to ambient light; toggling the auto-brightness switch resets the model via `resetShortTermModel()` and restores normal tracking. This is **Android's own behaviour**, not a defect of this app — dragging the slider manually produces exactly the same effect.

Deliberately **not** resetting the model after each pull-back: the auto algorithm wants a lower value than the floor, so resetting would let both sides fight every second (light debounce is 1000 ms, ramp 0.30/s), producing a continuous brightness oscillation plus a `settings` write per cycle. Letting the model lock is stable, and during the lock the screen never dims below the floor — which is the intended outcome anyway.

自动亮度开启时，把亮度拉回本身就是一次「手动改亮度」，系统会因此启用**短期亮度模型**：把自动亮度曲线锚定在当前环境光上，不再跟随环境变化。在实测机型上量到的状态：

| 字段 | 值 |
|---|---|
| `mWasShortTermModelActive` | 任何手动写入后变为 `true` |
| `shortTermModelTimeout` | `300000` 毫秒（5 分钟） |
| `shortTermModelLower/UpperLuxMultiplier` | `0.6` |
| `mAmbientLux` / `mLastObservedLux` | 仍在正常刷新（传感器没坏） |
| `BrightnessEvent` 日志 | 模型生效期间不再产生新记录 |

也就是说这 5 分钟内屏幕不再随环境光变化；切换一次自动亮度开关会调用 `resetShortTermModel()`，立刻恢复正常跟随。这是 **Android 自身的行为**，不是本应用的缺陷——手动拖一次亮度条会得到完全相同的效果。

这里刻意**不**在每次拉回后重置模型：自动亮度算法想要的值低于下限，一旦重置，两边就会每秒较劲一次（变暗防抖 1000 毫秒，ramp 0.30/秒），导致亮度持续来回浮动，并且每轮还要写一次 `settings`。让模型锁住是稳定的，而且锁定期间屏幕根本不会暗过下限——恰好就是想要的结果。

### 6. End alert / 结束提示

Deliberately **not** a notification. Playing `RingtoneManager` + `Vibrator` directly keeps the notification shade and history clean.

刻意**不走通知**。直接播 `RingtoneManager` 默认通知音并调用 `Vibrator`，通知栏与通知历史都不会留下痕迹。

| Phone state | Sound | Vibration |
|---|---|---|
| Normal / 正常 | ✓ | ✓ |
| Silent / 静音 | ✗ | ✓ |
| Vibrate / 振动 | ✗ | ✓ |
| Do Not Disturb / 勿扰 | ✗ | ✓ |

Vibration uses `VibrationEffect.DEFAULT_AMPLITUDE`, so it automatically follows the system's notification vibration intensity.

震动使用 `VibrationEffect.DEFAULT_AMPLITUDE`，自动跟随系统的通知震动强度。

### 7. Keeping alive / 保活

| Layer | Mechanism |
|---|---|
| Foreground service | `specialUse` type, `START_STICKY`, `stopWithTask=false` |
| Watchdog | The accessibility service calls `startService` when foreground events arrive (throttled to once per 30 s) |
| Doze whitelist | Written once at startup via Shizuku / Shizuku 在启动时写入 |
| Boot | `BOOT_COMPLETED` + accessibility service re-binding by the system |

The accessibility service is the real anchor: it is owned by the system framework, restarted automatically after boot, and almost never killed by the memory reaper.

无障碍服务是真正的锚点：它由系统框架持有，开机自动重启，几乎不会被内存回收杀掉。

---

## Permissions / 权限

| Permission | Purpose / 用途 | Required | How to grant / 授予方式 |
|---|---|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | Draw the overlay, filter keys, detect calls and window changes / 绘制浮窗、过滤按键、检测来电与窗口变化 | **Yes** | Settings → Accessibility → ESR |
| `POST_NOTIFICATIONS` | Foreground service notification / 前台服务通知 | Recommended | Runtime prompt / 运行时弹窗 |
| `READ_PHONE_STATE` | Detect an incoming call to dismiss the overlay / 检测来电以撤掉浮窗 | Recommended | Runtime prompt / 运行时弹窗 |
| `WRITE_SETTINGS` | Write screen brightness directly / 直接写亮度 | Optional | Special access → Modify system settings |
| `VIBRATE` | End alert vibration / 结束提示震动 | Auto | Granted at install / 安装时自动授予 |
| `FOREGROUND_SERVICE` + `_SPECIAL_USE` | Run the timer as a foreground service / 前台服务计时 | Auto | Manifest only |
| `WAKE_LOCK` | Keep the screen on during the overlay / 浮窗期间保持亮屏 | Auto | Manifest only |
| `RECEIVE_BOOT_COMPLETED` | Restart after reboot / 开机自启 | Auto | Manifest only |

Without `WRITE_SETTINGS`, the brightness guard falls back to Shizuku. Without Shizuku both the brightness guard and media pause degrade or stop working.

没有 `WRITE_SETTINGS` 时亮度守卫走 Shizuku；Shizuku 也不可用时，亮度守卫与媒体暂停会降级或失效。

---

## System dependencies / 依赖的系统能力

| Capability | Used for | Availability |
|---|---|---|
| `AccessibilityService` | Overlay window, key filtering, window events, call UI detection / 浮窗、按键过滤、窗口事件、来电识别 | Android 4.0+ |
| `Shizuku` (optional) | `media_session` commands, brightness fallback, Doze whitelist / 媒体会话命令、亮度兜底、白名单 | Requires manual setup / 需手动部署 |
| Xiaomi `attention` service (optional) | Gaze sampling / 注视采样 | Xiaomi devices only, private API / 仅小米设备，私有接口 |

**Note / 说明**: the gaze feature depends on a vendor-private service. On non-Xiaomi devices it returns `null` and the app silently falls back to pure screen-on timing.

注视检测依赖厂商私有服务。非小米设备上会返回 `null`，应用静默回退到纯亮屏计时。

---

## Requirements / 环境要求

| Item | Value |
|---|---|
| minSdk | 26 (Android 8.0) |
| compileSdk / targetSdk | 35 |
| Language | Kotlin |
| Tested device | Xiaomi 25098PN5AC, Android 17 (HyperOS 4.0) |
| Tested emulator | Pixel 7 AVD, API 35 |

---

## Build / 构建

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gradle 8.9 / JDK 17 / Android SDK 35 are what the project was developed against.

开发环境为 Gradle 8.9 / JDK 17 / Android SDK 35。

---

## Project structure / 代码结构

```
app/src/main/java/com/eyestrainrelief/
├─ access/GuardAccessibilityService.kt   Accessibility service: overlay host, key filter, watchdog,
│                                        call listener, overlay re-attach on window change
│                                        无障碍服务：浮窗宿主、按键过滤、看门狗、来电监听、窗口变化重置顶
├─ overlay/BreakOverlay.kt               The full-screen overlay view and its countdown
│                                        全屏浮窗视图与倒计时逻辑
├─ service/MonitorService.kt             Foreground service: timing, scheduling, notifications
│                                        前台服务：计时、调度、通知
├─ core/SessionTimer.kt                  Timestamp-based timing state machine / 基于时间戳的计时状态机
├─ core/Prefs.kt                         Settings storage / 参数存储
├─ core/Timebase.kt                      Monotonic clock helper / 单调时钟封装
├─ attention/AttentionProbe.kt           Xiaomi attention service probe / 小米注视服务探测
├─ brightness/BrightnessGuard.kt         ContentObserver-based brightness floor / 亮度下限守卫
├─ media/MediaPauseHelper.kt             Media session pause/resume / 媒体暂停与恢复
├─ audio/BreakNotifier.kt                Ringtone + vibration end alert / 铃声与震动提示
├─ shell/ShizukuShell.kt                 Shell execution through Shizuku / 通过 Shizuku 执行命令
└─ ui/MainActivity.kt                    Settings screen / 设置界面

app/src/main/res/
├─ values/strings.xml                    English (default) / 英文（默认）
└─ values-zh-rCN/strings.xml              Simplified Chinese / 简体中文
```

---

## Rebuilding notes / 复现要点

Things that cost the most time and are easy to get wrong.

最费时间、也最容易踩错的地方。

1. **`FLAG_KEEP_SCREEN_ON` does not work on accessibility overlays.** Verified on a real device: `mStayOn` stays `false` and the window attributes carry no keep-screen-on flag. Use a `WakeLock` with a timeout instead.
   **`FLAG_KEEP_SCREEN_ON` 对无障碍浮窗无效**。真机验证 `mStayOn` 恒为 false，窗口属性里也没有该标志。必须改用带超时的 `WakeLock`。

2. **`dumpsys activity` cannot be trusted on some ROMs.** `topResumedActivity` reported a stale app while the actual foreground was the launcher. Use screenshots or `TYPE_WINDOW_STATE_CHANGED` events instead.
   **某些 ROM 上 `dumpsys activity` 不可信**。`topResumedActivity` 报的是过期的应用，实际前台是桌面。改用截图或 `TYPE_WINDOW_STATE_CHANGED` 事件判断。

3. **`adb shell input keyevent` bypasses accessibility key filtering.** Injected events skip the filter layer, so you cannot verify key interception that way — it must be tested by hand.
   **`adb shell input keyevent` 会绕过无障碍按键过滤**。注入的事件不经过过滤层，因此无法用它验证按键拦截，必须手动测试。

4. **`uiautomator dump` fails if the UI refreshes every second.** The accessibility service never reports idle. Either reduce the refresh rate or read coordinates from screenshots.
   **界面每秒刷新时 `uiautomator dump` 会失败**。无障碍服务一直不进入 idle 状态。要么降低刷新频率，要么从截图估算坐标。

5. **Overlay z-order is decided by attach order.** To raise the overlay above a newly launched app, `removeView` then `addView` again; `updateViewLayout` does not change stacking.
   **浮窗层级由添加顺序决定**。要让浮窗盖住刚启动的应用，必须 `removeView` 再 `addView`；`updateViewLayout` 不会改变层叠顺序。

6. **Changing the package name resets every permission.** Same package + same signature = a normal upgrade where permissions survive. Renaming the package makes it a brand-new app: accessibility, runtime permissions and appops all have to be granted again.
   **改包名会让所有权限重置**。同包名同签名是普通升级，权限保留；改包名则等于全新应用，无障碍、运行时权限、appops 都要重新授予。

---

## Known limitations / 已知限制

1. **Full-screen gestures cannot be intercepted.** The system handles gesture navigation above every app window. When the user swipes away, the original app goes to the background — the overlay stays but the app does not.
   **全面屏手势无法拦截**。系统在所有应用窗口之上处理手势导航。用户滑走后原应用进入后台，浮窗仍在，但应用已经不在前台。
2. **Gaze detection is presence-level, not eye tracking.** See above.
   **注视检测是「人脸在场」级别，不是眼动追踪**。见上文。
3. **Silent and DND still vibrate.** By design — sound follows the system policy, vibration does not.
   **静音与勿扰仍会震动**。这是设计选择：声音遵循系统策略，震动不跟随。
4. **Shizuku does not survive a reboot** in wireless-debugging mode. Media pause and the brightness fallback degrade until it is restarted manually.
   **Shizuku 在无线调试模式下重启后不运行**。媒体暂停与亮度兜底会降级，直到手动重启服务。

---

## Historical notes / 历史记录

`docs/archive/` keeps the development exploration records from 2026-10-29 / 10-30, including the abandoned LockTask approach and the measurements that led to dropping it. It has its own README explaining what each file is and why the approach failed. Where they disagree with this README, this README is authoritative.

`docs/archive/` 保存 2026-10-29 / 10-30 的开发探索记录，包含已放弃的 LockTask 方案以及导致放弃的实测数据。该目录自带一份 README 说明每个文件是什么、方案为什么失败。与本文档冲突之处，以本文档为准。

---

## License

MIT — see [`LICENSE`](LICENSE).

MIT，见 [`LICENSE`](LICENSE)。

## Version history / 版本记录

See [`CHANGELOG.md`](CHANGELOG.md) for what changed in each release. Release APKs live on the [Releases](../../releases) page; the local `snapshots/v<version>/` folder keeps a copy plus a fuller write-up of each version, but it is git-ignored and not part of the repository.

每个版本改了什么见 [`CHANGELOG.md`](CHANGELOG.md)。发布用的 APK 在 [Releases](../../releases) 页面；本地 `snapshots/v<版本>/` 另存一份 APK 与更详细的说明，但该目录被 git 忽略，不属于仓库内容。

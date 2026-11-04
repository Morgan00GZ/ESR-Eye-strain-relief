# Archive / 历史存档

Records from the first two days of the project (2026-10-29 / 10-30), when the plan was to keep the user inside the current app with `am task lock` (LockTask). That approach was dropped — see "Why LockTask was abandoned" below. The files are kept because the measurements in them are what justified the current design, and because they document paths that do not work, so nobody has to rediscover them.

这里存放项目头两天（2026-10-29 / 10-30）的记录，当时的方案是用 `am task lock`（LockTask）把用户锁在当前应用里。该方案已废弃（原因见下）。保留这些文件是因为：里面的实测数据正是当前设计的依据，同时记录了走不通的路径，避免以后重复验证。

| File / 文件 | What it records / 记录内容 |
|---|---|
| `P0-系统级拦截实测.md` | LockTask works at shell level on Android 15 AVD: blocks Back / Home / gesture back / gesture home / status bar. No root, no Device Owner. / LockTask 在 Android 15 模拟器上以 shell 身份可用，能拦住返回、Home、手势返回与上滑、状态栏下拉，无需 root 与 Device Owner |
| `P0-验收记录.md` | Emulator acceptance run for the P0 skeleton. / P0 骨架在模拟器上的验收记录 |
| `P1-真机验证记录.md` | The same LockTask validation on the physical device (25098PN5AC / HyperOS 4), plus Shizuku integration and the watchdog design. / 同一套 LockTask 验证在真机（25098PN5AC / HyperOS 4）上的结果，含 Shizuku 集成与看门狗设计 |

## Why LockTask was abandoned / 为什么放弃 LockTask

`am task lock` internally calls `WindowManagerService.lockNow(null)` — it locks the screen along with the task. On a device with a password lock, `wm dismiss-keyguard` cannot undo it, so the device always ended up on the lock screen after every break. Verified on the physical device.

`am task lock` 内部会调用 `WindowManagerService.lockNow(null)`，**锁任务的同时把屏幕锁了**。有密码锁的设备 `wm dismiss-keyguard` 无法解除，于是每次休息结束设备都停在锁屏。已在真机验证。

The current implementation instead uses a full-screen accessibility overlay plus re-attaching the overlay whenever the foreground app changes. See the main `README.md` for how it works now.

现在的实现改为「全屏无障碍浮层 + 前台变化就把浮层重新挂到顶层」，具体见主 `README.md`。

## Not to be used as a reference / 不要当现行规范

Where these files disagree with `README.md`, the README is authoritative. In particular, the P1 record describes a `TaskLockController` and a 35-second watchdog alarm that no longer exist in the code.

这些文件与 `README.md` 冲突处，以 README 为准。尤其 P1 记录里提到的 `TaskLockController` 和 35 秒看门狗闹钟，代码里已经不存在了。

